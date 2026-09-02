from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from threading import Lock
from unittest import mock

from imagegencam.config import SettingsStore
from imagegencam.controller import TUTORIAL_PAGES, ImageGenCamController


class _FakeState:
    def __init__(self) -> None:
        self.mode = "preview"
        self.status_message = ""


class _TutorialStub:
    """Controller surface for the tutorial flow, exercised unbound (the real
    controller opens the camera and display in __init__)."""

    def __init__(self, settings_path: Path) -> None:
        self.settings_store = SettingsStore(settings_path)
        self.state = _FakeState()
        self.state_lock = Lock()
        self.last_drawn_mode = "preview"
        self.tutorial_index = 0
        self.tutorial_seen = False
        self.exited_to_preview = 0

    _enter_tutorial = ImageGenCamController._enter_tutorial
    _advance_tutorial = ImageGenCamController._advance_tutorial
    _finish_tutorial = ImageGenCamController._finish_tutorial
    _mark_tutorial_seen = ImageGenCamController._mark_tutorial_seen

    def _exit_to_preview(self) -> None:
        self.exited_to_preview += 1
        self.state.mode = "preview"


class TutorialTests(unittest.TestCase):
    def _stub(self, tmp: str) -> _TutorialStub:
        return _TutorialStub(Path(tmp) / "settings.json")

    def test_entering_shows_the_first_page(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stub = self._stub(tmp)

            stub._enter_tutorial()

            self.assertEqual(stub.state.mode, "tutorial")
            self.assertEqual(stub.tutorial_index, 0)

    def test_stepping_past_the_last_page_finishes_and_persists(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stub = self._stub(tmp)
            stub._enter_tutorial()

            for _ in range(len(TUTORIAL_PAGES)):
                stub._advance_tutorial(1)

            self.assertEqual(stub.state.mode, "preview")
            self.assertTrue(stub.tutorial_seen)
            # The flag survives a reload, so the tutorial shows exactly once.
            self.assertEqual(stub.settings_store.load()["tutorial_seen"], 1)

    def test_stepping_back_never_leaves_the_first_page(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stub = self._stub(tmp)
            stub._enter_tutorial()

            stub._advance_tutorial(-1)

            self.assertEqual(stub.tutorial_index, 0)
            self.assertEqual(stub.state.mode, "tutorial")

    def test_skipping_counts_as_seen(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stub = self._stub(tmp)
            stub._enter_tutorial()

            stub._finish_tutorial()

            self.assertTrue(stub.tutorial_seen)
            self.assertEqual(stub.settings_store.load()["tutorial_seen"], 1)

    def test_rerunning_does_not_rewrite_settings(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stub = self._stub(tmp)
            stub._enter_tutorial()
            stub._finish_tutorial()
            settings_path = Path(tmp) / "settings.json"
            stamp = settings_path.stat().st_mtime_ns

            stub._enter_tutorial()
            stub._finish_tutorial()

            self.assertEqual(settings_path.stat().st_mtime_ns, stamp)


class _FakeDisplay:
    def __init__(self) -> None:
        self.backlight_calls: list[float] = []

    def set_backlight(self, value: float) -> None:
        self.backlight_calls.append(value)


class _SleepStub:
    def __init__(self) -> None:
        self.state = _FakeState()
        self.state_lock = Lock()
        self.display = _FakeDisplay()
        self.last_drawn_mode = "preview"
        self.sleep_after_seconds = 300.0
        self.sleep_active = False
        self.wifi_connecting = False
        self.last_user_activity = 0.0
        self.capture_last_frame_at: float | None = 10.0
        self.stale_frame_timeout_seconds = 2.0
        self.exited_to_preview = 0
        self.setup_portal_active = False

    _should_sleep = ImageGenCamController._should_sleep
    _enter_sleep = ImageGenCamController._enter_sleep
    _wake_from_sleep = ImageGenCamController._wake_from_sleep
    _check_stale_camera = ImageGenCamController._check_stale_camera

    def _exit_to_preview(self) -> None:
        self.exited_to_preview += 1
        self.state.mode = "preview"

    def _fail_fast_camera_restart(self, message: str) -> None:
        raise AssertionError(f"fail-fast fired during sleep: {message}")


class SleepTests(unittest.TestCase):
    def test_sleeps_only_after_the_idle_window_from_preview(self) -> None:
        stub = _SleepStub()

        self.assertFalse(stub._should_sleep(299.0, "preview", 0))
        self.assertTrue(stub._should_sleep(300.0, "preview", 0))
        self.assertFalse(stub._should_sleep(300.0, "album", 0))
        self.assertFalse(stub._should_sleep(300.0, "preview", 2))

    def test_disabled_by_zero(self) -> None:
        stub = _SleepStub()
        stub.sleep_after_seconds = 0.0

        self.assertFalse(stub._should_sleep(10_000.0, "preview", 0))

    def test_never_sleeps_mid_wifi_connect(self) -> None:
        stub = _SleepStub()
        stub.wifi_connecting = True

        self.assertFalse(stub._should_sleep(300.0, "preview", 0))

    def test_entering_sleep_turns_the_backlight_off(self) -> None:
        stub = _SleepStub()

        stub._enter_sleep()

        self.assertTrue(stub.sleep_active)
        self.assertEqual(stub.state.mode, "sleep")
        self.assertEqual(stub.display.backlight_calls, [0])

    def test_waking_restores_backlight_and_preview(self) -> None:
        stub = _SleepStub()
        stub._enter_sleep()

        stub._wake_from_sleep()

        self.assertFalse(stub.sleep_active)
        self.assertEqual(stub.display.backlight_calls, [0, 1])
        self.assertEqual(stub.exited_to_preview, 1)

    def test_stale_camera_watchdog_holds_fire_while_asleep(self) -> None:
        # Frames stop on purpose during sleep; the watchdog calling
        # _fail_fast_camera_restart here would os._exit the whole service.
        stub = _SleepStub()
        stub._enter_sleep()
        stub.capture_last_frame_at = 10.0

        stub._check_stale_camera(10_000.0)

    def test_a_background_mode_change_does_not_strand_the_camera(self) -> None:
        # The setup-portal watchdog and the web Wi-Fi connect both write
        # state.mode from other threads with no idea the camera is asleep.
        # Waking must key off sleep_active, or the backlight stays off forever.
        stub = _SleepStub()
        stub._enter_sleep()

        stub.state.mode = "wifi_setup"  # background thread, mid-nap

        self.assertTrue(stub.sleep_active)
        stub._wake_from_sleep()
        self.assertFalse(stub.sleep_active)
        self.assertEqual(stub.display.backlight_calls, [0, 1])

    def test_waking_keeps_the_setup_screen_when_the_hotspot_came_up(self) -> None:
        stub = _SleepStub()
        stub._enter_sleep()
        stub.setup_portal_active = True

        stub._wake_from_sleep()

        # Join instructions beat the viewfinder here.
        self.assertEqual(stub.state.mode, "wifi_setup")
        self.assertEqual(stub.exited_to_preview, 0)

    def test_waking_resets_the_stale_frame_clock(self) -> None:
        stub = _SleepStub()
        stub._enter_sleep()
        stub.capture_last_frame_at = 10.0

        stub._wake_from_sleep()

        # The first stale check after waking must not see the hours-old
        # timestamp from before the nap.
        self.assertGreater(stub.capture_last_frame_at, 10.0)


class RedrawBudgetTests(unittest.TestCase):
    """The preview rate is now configurable. Bad input must fall back rather
    than turn the render loop into a busy spin."""

    def test_an_unset_variable_uses_the_default(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("PREVIEW_TARGET_FPS", None)
            self.assertAlmostEqual(
                ImageGenCamController._redraw_interval("PREVIEW_TARGET_FPS", 1 / 8), 1 / 8
            )

    def test_a_target_becomes_its_interval(self) -> None:
        with mock.patch.dict(os.environ, {"PREVIEW_TARGET_FPS": "20"}):
            self.assertAlmostEqual(
                ImageGenCamController._redraw_interval("PREVIEW_TARGET_FPS", 1 / 8), 1 / 20
            )

    def test_junk_and_zero_fall_back_instead_of_spinning(self) -> None:
        for value in ("0", "-5", "abc", ""):
            with self.subTest(value=value):
                with mock.patch.dict(os.environ, {"PREVIEW_TARGET_FPS": value}):
                    self.assertAlmostEqual(
                        ImageGenCamController._redraw_interval("PREVIEW_TARGET_FPS", 1 / 8),
                        1 / 8,
                    )

    def test_an_absurd_target_is_clamped(self) -> None:
        with mock.patch.dict(os.environ, {"PREVIEW_TARGET_FPS": "100000"}):
            interval = ImageGenCamController._redraw_interval("PREVIEW_TARGET_FPS", 1 / 8)
        self.assertAlmostEqual(interval, 1 / 60)
        self.assertGreater(interval, 0)


class _ForgetStub:
    def __init__(self, network, forget_result_code: int = 0) -> None:
        from imagegencam.wifi_manager import WifiNetwork  # noqa: F401

        self.wifi_selected_network = network
        self.wifi_connect_message = ""
        self.entered_menu: list[bool] = []
        self.forgotten: list[object] = []
        self._forget_result_code = forget_result_code
        self.wifi_manager = self

    _forget_selected_wifi_network = ImageGenCamController._forget_selected_wifi_network
    _return_to_wifi_menu = ImageGenCamController._return_to_wifi_menu
    _wifi_detail_options = ImageGenCamController._wifi_detail_options

    def forget(self, network):
        import subprocess

        self.forgotten.append(network)
        return subprocess.CompletedProcess(
            args=["nmcli"],
            returncode=self._forget_result_code,
            stdout="",
            stderr="" if self._forget_result_code == 0 else "Error: unknown connection",
        )

    def _enter_wifi_menu(self, *, rescan: bool = False) -> None:
        # Mirrors the real _enter_wifi_menu, which ALWAYS overwrites
        # wifi_connect_message with its own scan status. A no-op stub here hid
        # a real bug: the "Forgot <ssid>" confirmation was being wiped before
        # the owner ever saw it.
        self.entered_menu.append(rescan)
        self.wifi_connect_message = "Scan complete" if rescan else ""


class ForgetOptionTests(unittest.TestCase):
    def _network(self, *, saved: bool, active: bool = False, secure: bool = True):
        from imagegencam.wifi_manager import WifiNetwork

        return WifiNetwork(ssid="Home", saved=saved, active=active, secure=secure)

    def test_saved_networks_offer_forget(self) -> None:
        stub = _ForgetStub(self._network(saved=True))
        self.assertIn("Forget", stub._wifi_detail_options())

    def test_the_active_network_offers_forget_too(self) -> None:
        stub = _ForgetStub(self._network(saved=True, active=True))
        self.assertIn("Forget", stub._wifi_detail_options())

    def test_unsaved_networks_do_not(self) -> None:
        stub = _ForgetStub(self._network(saved=False))
        self.assertNotIn("Forget", stub._wifi_detail_options())

    def test_forgetting_deletes_and_rescans(self) -> None:
        stub = _ForgetStub(self._network(saved=True))

        stub._forget_selected_wifi_network()

        self.assertEqual(len(stub.forgotten), 1)
        self.assertIsNone(stub.wifi_selected_network)
        self.assertEqual(stub.entered_menu, [True])
        self.assertIn("Forgot", stub.wifi_connect_message)

    def test_a_failed_delete_is_reported_not_hidden(self) -> None:
        stub = _ForgetStub(self._network(saved=True), forget_result_code=1)

        stub._forget_selected_wifi_network()

        self.assertIn("Forget failed", stub.wifi_connect_message)

    def test_the_outcome_survives_the_rescan_that_follows(self) -> None:
        # Regression: _enter_wifi_menu(rescan=True) sets "Scan complete", so
        # the confirmation has to be reapplied after it, not before.
        stub = _ForgetStub(self._network(saved=True))

        stub._forget_selected_wifi_network()

        self.assertEqual(stub.wifi_connect_message, "Forgot Home")
        self.assertNotEqual(stub.wifi_connect_message, "Scan complete")

    def test_an_exception_message_also_survives(self) -> None:
        stub = _ForgetStub(self._network(saved=True))
        stub.forget = lambda network: (_ for _ in ()).throw(OSError("nmcli gone"))

        stub._forget_selected_wifi_network()

        self.assertIn("nmcli gone", stub.wifi_connect_message)

    def test_an_unsaved_selection_is_refused(self) -> None:
        stub = _ForgetStub(self._network(saved=False))

        stub._forget_selected_wifi_network()

        self.assertEqual(stub.forgotten, [])


if __name__ == "__main__":
    unittest.main()
