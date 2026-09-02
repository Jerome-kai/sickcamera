from __future__ import annotations

import unittest
from queue import Queue
from threading import Lock
from unittest import mock

from imagegencam.controller import ImageGenCamController


class _FakeState:
    def __init__(self, mode: str) -> None:
        self.mode = mode
        self.status_message = ""


class _FakeAccessPoint:
    def __init__(self, active: bool) -> None:
        self.active = active

    def is_active(self) -> bool:
        return self.active


class _RoutingStub:
    """Controller surface for _handle_event and nothing else.

    Every transition method is swapped for a recorder, so a test can state a
    routing rule -- "in the album, the shutter compares rather than shoots" --
    without opening the camera, the display, or the OpenAI client. The routing
    itself is the real implementation: that is what is under test.

    A wrong transition here strands the camera on a screen with no way back,
    which is why this dispatcher is worth pinning event by event.
    """

    ACTIONS = (
        "_advance_tutorial",
        "_finish_tutorial",
        "_queue_magic_prompt_from_current_frame",
        "_enqueue_magic_generation_from_current_frame",
        "_enqueue_generation_from_current_frame",
        "_toggle_album_compare",
        "_enter_wifi_menu",
        "_select_wifi_detail_option",
        "_keyboard_submit",
        "_exit_to_preview",
        "_stop_magic_mode",
        "_enter_album",
        "_select_prompt_from_picker",
        "_enter_album_download",
        "_select_wifi_network",
        "_keyboard_add_char",
        "_confirm_wifi_connection",
        "_rollback_wifi_now",
        "_enter_diagnostics",
        "_keyboard_backspace_or_exit",
        "_enter_prompt_picker",
        "_scroll_prompt_picker",
        "_scroll_album",
        "_scroll_wifi_menu",
        "_scroll_wifi_detail",
        "_keyboard_scroll",
        "_enter_tutorial",
        "_wake_from_sleep",
    )

    _handle_event = ImageGenCamController._handle_event
    _maybe_trigger_diagnostics = ImageGenCamController._maybe_trigger_diagnostics

    def __init__(
        self,
        mode: str = "preview",
        *,
        magic_mode_active: bool = False,
        sleep_active: bool = False,
        hotspot_active: bool = False,
        wifi_connecting: bool = False,
    ) -> None:
        self.state = _FakeState(mode)
        self.state_lock = Lock()
        self.last_drawn_mode = mode
        self.sleep_active = sleep_active
        self.magic_mode_active = magic_mode_active
        self.wifi_connecting = wifi_connecting
        self.setup_access_point = _FakeAccessPoint(hotspot_active)
        self.diagnostics_tap_times: list[float] = []
        self.calls: list[tuple[str, tuple, dict]] = []
        for name in self.ACTIONS:
            setattr(self, name, self._recorder(name))

    def _recorder(self, name: str):
        def record(*args, **kwargs) -> None:
            self.calls.append((name, args, kwargs))

        return record

    def get_status_snapshot(self) -> dict[str, str]:
        return {"mode": self.state.mode}

    @property
    def actions(self) -> list[str]:
        return [call[0] for call in self.calls]

    def send(self, event: str) -> "_RoutingStub":
        self._handle_event(event)
        return self


EVERY_EVENT = ("shutter", "magic_shutter", "ui_up", "ui_down", "ui_album", "ui_prompt")


class ShutterRoutingTests(unittest.TestCase):
    def test_the_shutter_starts_a_generation_from_preview(self) -> None:
        stub = _RoutingStub("preview").send("shutter")

        self.assertEqual(stub.actions, ["_enqueue_generation_from_current_frame"])

    def test_the_shutter_still_shoots_from_the_capture_confirmation(self) -> None:
        # capture_feedback is a transient screen; holding down the shutter
        # through it has to keep taking photos, not drop the press.
        stub = _RoutingStub("capture_feedback").send("shutter")

        self.assertEqual(stub.actions, ["_enqueue_generation_from_current_frame"])

    def test_magic_mode_sends_the_shutter_down_the_magic_pipeline(self) -> None:
        stub = _RoutingStub("preview", magic_mode_active=True).send("shutter")

        self.assertEqual(stub.actions, ["_enqueue_magic_generation_from_current_frame"])

    def test_the_shutter_compares_instead_of_shooting_in_the_album(self) -> None:
        stub = _RoutingStub("album").send("shutter")

        self.assertEqual(stub.actions, ["_toggle_album_compare"])

    def test_the_shutter_rescans_the_wifi_menu(self) -> None:
        stub = _RoutingStub("wifi_menu").send("shutter")

        self.assertEqual(stub.actions, ["_enter_wifi_menu"])
        self.assertEqual(stub.calls[0][2], {"rescan": True})

    def test_the_shutter_submits_the_wifi_password(self) -> None:
        stub = _RoutingStub("wifi_keyboard").send("shutter")

        self.assertEqual(stub.actions, ["_keyboard_submit"])

    def test_the_setup_screen_can_be_dismissed_once_a_network_exists(self) -> None:
        stub = _RoutingStub("wifi_setup").send("shutter")

        self.assertEqual(stub.actions, ["_exit_to_preview"])

    def test_the_setup_screen_stays_while_the_hotspot_is_the_only_way_in(self) -> None:
        # Dismissing here would hide the join instructions with no network to
        # go back to, leaving the camera unreachable.
        stub = _RoutingStub("wifi_setup", hotspot_active=True).send("shutter")

        self.assertEqual(stub.actions, [])

    def test_the_setup_screen_stays_while_a_connection_is_being_applied(self) -> None:
        stub = _RoutingStub("wifi_setup", wifi_connecting=True).send("shutter")

        self.assertEqual(stub.actions, [])


class MagicShutterRoutingTests(unittest.TestCase):
    def test_the_magic_shutter_plans_a_prompt_from_preview(self) -> None:
        stub = _RoutingStub("preview").send("magic_shutter")

        self.assertEqual(stub.actions, ["_queue_magic_prompt_from_current_frame"])

    def test_the_magic_shutter_plans_from_the_album_only_while_magic_is_on(self) -> None:
        off = _RoutingStub("album").send("magic_shutter")
        on = _RoutingStub("album", magic_mode_active=True).send("magic_shutter")

        self.assertEqual(off.actions, [])
        self.assertEqual(on.actions, ["_queue_magic_prompt_from_current_frame"])

    def test_the_magic_shutter_is_inert_on_the_wifi_screens(self) -> None:
        for mode in ("wifi_menu", "wifi_detail", "wifi_keyboard", "wifi_confirm"):
            with self.subTest(mode=mode):
                self.assertEqual(_RoutingStub(mode).send("magic_shutter").actions, [])


class SleepRoutingTests(unittest.TestCase):
    def test_a_press_while_asleep_is_spent_on_waking(self) -> None:
        # The press that wakes the camera must not also fire a shutter or
        # navigate: the user cannot see what they are pressing.
        for event in EVERY_EVENT:
            with self.subTest(event=event):
                stub = _RoutingStub("preview", sleep_active=True).send(event)

                self.assertEqual(stub.actions, ["_wake_from_sleep"])

    def test_waking_is_keyed_on_the_sleep_flag_not_the_mode(self) -> None:
        # Background threads (the portal watchdog, a web Wi-Fi connect) write
        # state.mode without knowing the camera is asleep. Trusting mode here
        # once left the backlight off with no way back short of a power cycle.
        stub = _RoutingStub("wifi_setup", sleep_active=True).send("ui_album")

        self.assertEqual(stub.actions, ["_wake_from_sleep"])


class TutorialRoutingTests(unittest.TestCase):
    def test_the_tutorial_pages_forward_on_shutter_prompt_and_down(self) -> None:
        for event in ("shutter", "ui_prompt", "ui_down"):
            with self.subTest(event=event):
                stub = _RoutingStub("tutorial").send(event)

                self.assertEqual(stub.actions, ["_advance_tutorial"])
                self.assertEqual(stub.calls[0][1], (1,))

    def test_the_tutorial_pages_back_on_up(self) -> None:
        stub = _RoutingStub("tutorial").send("ui_up")

        self.assertEqual(stub.calls, [("_advance_tutorial", (-1,), {})])

    def test_the_album_button_skips_the_tutorial(self) -> None:
        stub = _RoutingStub("tutorial").send("ui_album")

        self.assertEqual(stub.actions, ["_finish_tutorial"])

    def test_the_tutorial_swallows_the_magic_shutter(self) -> None:
        # Every event returns inside the tutorial branch; none may leak into
        # the capture pipeline before the user has finished onboarding.
        stub = _RoutingStub("tutorial").send("magic_shutter")

        self.assertEqual(stub.actions, [])


class PromptButtonRoutingTests(unittest.TestCase):
    EXPECTED = {
        "prompt_picker": "_select_prompt_from_picker",
        "album": "_enter_album_download",
        "diagnostics": "_enter_wifi_menu",
        "diagnostics_detail": "_enter_wifi_menu",
        "wifi_menu": "_select_wifi_network",
        "wifi_detail": "_select_wifi_detail_option",
        "wifi_keyboard": "_keyboard_add_char",
        "wifi_confirm": "_confirm_wifi_connection",
        "preview": "_enter_prompt_picker",
    }

    def test_each_mode_routes_the_prompt_button(self) -> None:
        for mode, expected in self.EXPECTED.items():
            with self.subTest(mode=mode):
                self.assertEqual(_RoutingStub(mode).send("ui_prompt").actions, [expected])

    def test_the_prompt_button_is_ignored_mid_connect_and_mid_download(self) -> None:
        for mode in ("wifi_connecting", "album_download"):
            with self.subTest(mode=mode):
                self.assertEqual(_RoutingStub(mode).send("ui_prompt").actions, [])

    def test_the_prompt_button_leaves_magic_mode_from_preview(self) -> None:
        stub = _RoutingStub("preview", magic_mode_active=True).send("ui_prompt")

        self.assertEqual(stub.actions, ["_stop_magic_mode"])


class AlbumButtonRoutingTests(unittest.TestCase):
    EXPECTED = {
        "album_download": "_enter_album",
        "prompt_picker": "_exit_to_preview",
        "diagnostics": "_exit_to_preview",
        "diagnostics_detail": "_exit_to_preview",
        "wifi_menu": "_enter_diagnostics",
        "wifi_detail": "_enter_wifi_menu",
        "wifi_keyboard": "_keyboard_backspace_or_exit",
        "wifi_confirm": "_rollback_wifi_now",
        "album": "_exit_to_preview",
        "preview": "_enter_album",
    }

    def test_each_mode_routes_the_album_button(self) -> None:
        for mode, expected in self.EXPECTED.items():
            with self.subTest(mode=mode):
                self.assertEqual(_RoutingStub(mode).send("ui_album").actions, [expected])

    def test_the_album_button_is_ignored_mid_connect(self) -> None:
        self.assertEqual(_RoutingStub("wifi_connecting").send("ui_album").actions, [])

    def test_the_album_button_still_opens_the_album_in_magic_mode(self) -> None:
        stub = _RoutingStub("preview", magic_mode_active=True).send("ui_album")

        self.assertEqual(stub.actions, ["_enter_album"])


class ScrollRoutingTests(unittest.TestCase):
    SCROLLERS = {
        "prompt_picker": "_scroll_prompt_picker",
        "album": "_scroll_album",
        "wifi_menu": "_scroll_wifi_menu",
        "wifi_detail": "_scroll_wifi_detail",
        "wifi_keyboard": "_keyboard_scroll",
    }

    def test_up_scrolls_backwards_in_every_list(self) -> None:
        for mode, expected in self.SCROLLERS.items():
            with self.subTest(mode=mode):
                stub = _RoutingStub(mode).send("ui_up")

                self.assertEqual(stub.calls, [(expected, (-1,), {})])

    def test_down_scrolls_forwards_in_every_list(self) -> None:
        for mode, expected in self.SCROLLERS.items():
            with self.subTest(mode=mode):
                stub = _RoutingStub(mode).send("ui_down")

                self.assertEqual(stub.calls, [(expected, (1,), {})])

    def test_up_opens_the_diagnostics_detail_page(self) -> None:
        stub = _RoutingStub("diagnostics").send("ui_up")

        self.assertEqual(stub.state.mode, "diagnostics_detail")
        # Cleared so the next loop repaints instead of reusing the old frame.
        self.assertIsNone(stub.last_drawn_mode)

    def test_down_from_diagnostics_replays_the_tutorial(self) -> None:
        stub = _RoutingStub("diagnostics").send("ui_down")

        self.assertEqual(stub.actions, ["_enter_tutorial"])

    def test_scrolling_is_ignored_on_screens_without_a_list(self) -> None:
        for mode in ("preview", "wifi_connecting", "wifi_confirm", "album_download"):
            with self.subTest(mode=mode):
                self.assertEqual(_RoutingStub(mode).send("ui_down").actions, [])


class CaptureFeedbackRoutingTests(unittest.TestCase):
    def test_navigation_is_swallowed_while_the_capture_confirmation_shows(self) -> None:
        for event in ("ui_up", "ui_down", "ui_album", "ui_prompt"):
            with self.subTest(event=event):
                self.assertEqual(_RoutingStub("capture_feedback").send(event).actions, [])


class DiagnosticsGestureTests(unittest.TestCase):
    """The diagnostics screen is opened by a hidden triple-tap, so the timing
    window is the feature -- an accidental double-tap must not open it."""

    def _tap(self, stub: _RoutingStub, times: list[float]) -> None:
        with mock.patch("imagegencam.controller.time") as fake_time:
            for stamp in times:
                fake_time.monotonic.return_value = stamp
                stub.send("ui_up")

    def test_three_quick_taps_open_diagnostics(self) -> None:
        stub = _RoutingStub("preview")

        self._tap(stub, [10.0, 10.3, 10.6])

        self.assertEqual(stub.actions, ["_enter_diagnostics"])

    def test_two_taps_are_not_enough(self) -> None:
        stub = _RoutingStub("preview")

        self._tap(stub, [10.0, 10.3])

        self.assertEqual(stub.actions, [])

    def test_taps_spread_past_the_window_never_accumulate(self) -> None:
        stub = _RoutingStub("preview")

        self._tap(stub, [10.0, 11.5, 13.0, 14.5])

        self.assertEqual(stub.actions, [])

    def test_the_counter_resets_after_it_fires(self) -> None:
        stub = _RoutingStub("preview")

        self._tap(stub, [10.0, 10.3, 10.6, 10.9])

        # The fourth tap starts a fresh gesture rather than re-firing.
        self.assertEqual(stub.actions, ["_enter_diagnostics"])
        self.assertEqual(len(stub.diagnostics_tap_times), 1)

    def test_the_gesture_only_works_from_preview(self) -> None:
        stub = _RoutingStub("album")

        self._tap(stub, [10.0, 10.3, 10.6])

        self.assertNotIn("_enter_diagnostics", stub.actions)


class _VirtualButtonStub:
    """press_virtual_button is the web remote's entry point into the same
    queue the physical switches feed."""

    press_virtual_button = ImageGenCamController.press_virtual_button
    _queue_ui_event = ImageGenCamController._queue_ui_event
    _queue_shutter_event = ImageGenCamController._queue_shutter_event

    def __init__(self, *, magic_mode_enabled: bool = True) -> None:
        self.event_queue: Queue = Queue()
        self.magic_mode_enabled = magic_mode_enabled
        self.last_user_activity = 0.0
        self.last_ui_press_time = 0.0
        self.last_shutter_event_times: dict[str, float] = {}

    def drain(self) -> list[str]:
        events = []
        while not self.event_queue.empty():
            events.append(self.event_queue.get_nowait())
        return events


class VirtualButtonTests(unittest.TestCase):
    def test_every_real_button_can_be_pressed_remotely(self) -> None:
        stub = _VirtualButtonStub()

        with mock.patch("imagegencam.controller.time") as fake_time:
            for index, name in enumerate(EVERY_EVENT):
                # Spaced past the debounce so each press is its own event.
                fake_time.monotonic.return_value = 100.0 + index
                self.assertTrue(stub.press_virtual_button(name), name)

        self.assertEqual(sorted(stub.drain()), sorted(EVERY_EVENT))

    def test_an_unknown_button_is_refused_and_queues_nothing(self) -> None:
        stub = _VirtualButtonStub()

        self.assertFalse(stub.press_virtual_button("nonsense"))
        self.assertEqual(stub.drain(), [])

    def test_names_are_normalised_before_matching(self) -> None:
        stub = _VirtualButtonStub()

        with mock.patch("imagegencam.controller.time") as fake_time:
            fake_time.monotonic.return_value = 100.0
            self.assertTrue(stub.press_virtual_button("  SHUTTER "))

        self.assertEqual(stub.drain(), ["shutter"])

    def test_the_magic_shutter_is_refused_when_magic_mode_is_off(self) -> None:
        stub = _VirtualButtonStub(magic_mode_enabled=False)

        self.assertFalse(stub.press_virtual_button("magic_shutter"))
        self.assertEqual(stub.drain(), [])

    def test_a_double_press_inside_the_debounce_is_dropped(self) -> None:
        stub = _VirtualButtonStub()

        with mock.patch("imagegencam.controller.time") as fake_time:
            fake_time.monotonic.return_value = 100.0
            stub.press_virtual_button("ui_up")
            fake_time.monotonic.return_value = 100.05
            stub.press_virtual_button("ui_up")

        self.assertEqual(stub.drain(), ["ui_up"])

    def test_the_two_shutters_debounce_independently(self) -> None:
        # They share a timestamp map keyed by event name; keying it on one
        # clock would make a magic press swallow the plain one next to it.
        stub = _VirtualButtonStub()

        with mock.patch("imagegencam.controller.time") as fake_time:
            fake_time.monotonic.return_value = 100.0
            stub.press_virtual_button("shutter")
            stub.press_virtual_button("magic_shutter")

        self.assertEqual(stub.drain(), ["shutter", "magic_shutter"])

    def test_a_remote_press_counts_as_user_activity(self) -> None:
        # Otherwise the camera would fall asleep under a user who is only
        # driving it from their phone.
        stub = _VirtualButtonStub()

        with mock.patch("imagegencam.controller.time") as fake_time:
            fake_time.monotonic.return_value = 250.0
            stub.press_virtual_button("ui_album")

        self.assertEqual(stub.last_user_activity, 250.0)


if __name__ == "__main__":
    unittest.main()
