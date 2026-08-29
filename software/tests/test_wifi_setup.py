from __future__ import annotations

import subprocess
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from imagegencam import wifi_setup
from imagegencam.wifi_setup import AP_CONNECTION_NAME, SetupAccessPoint, current_ipv4


def _completed(stdout: str = "", returncode: int = 0, stderr: str = "") -> subprocess.CompletedProcess[str]:
    return subprocess.CompletedProcess(args=["nmcli"], returncode=returncode, stdout=stdout, stderr=stderr)


class SetupAccessPointTests(unittest.TestCase):
    def setUp(self) -> None:
        self.access_point = SetupAccessPoint(
            ifname="wlan0", ssid="TestCam", password="takeaphoto"
        )

    def test_is_online_ignores_the_setup_hotspot(self) -> None:
        active = f"{AP_CONNECTION_NAME}:802-11-wireless\n"
        with patch.object(wifi_setup, "_run_privileged_nmcli", return_value=_completed(active)):
            self.assertFalse(self.access_point.is_online())
            self.assertTrue(self.access_point.is_active())

    def test_is_online_true_for_a_real_network(self) -> None:
        active = "Home WiFi:802-11-wireless\nWired connection 1:802-3-ethernet\n"
        with patch.object(wifi_setup, "_run_privileged_nmcli", return_value=_completed(active)):
            self.assertTrue(self.access_point.is_online())
            self.assertFalse(self.access_point.is_active())

    def test_start_issues_the_hotspot_command(self) -> None:
        calls: list[list[str]] = []

        def fake_nmcli(args, **kwargs):
            calls.append(args)
            if args[:2] == ["-t", "-f"]:
                return _completed("")
            return _completed("")

        with patch.object(wifi_setup, "_run_privileged_nmcli", side_effect=fake_nmcli):
            self.assertTrue(self.access_point.start())

        hotspot = [call for call in calls if "hotspot" in call]
        self.assertEqual(
            hotspot[0],
            [
                "device",
                "wifi",
                "hotspot",
                "ifname",
                "wlan0",
                "con-name",
                AP_CONNECTION_NAME,
                "ssid",
                "TestCam",
                "password",
                "takeaphoto",
            ],
        )

    def test_start_reports_failure(self) -> None:
        def fake_nmcli(args, **kwargs):
            if args[:2] == ["-t", "-f"]:
                return _completed("")
            return _completed(returncode=1, stderr="Error: no suitable device")

        with patch.object(wifi_setup, "_run_privileged_nmcli", side_effect=fake_nmcli):
            self.assertFalse(self.access_point.start())

    def test_stop_is_a_noop_when_the_hotspot_is_down(self) -> None:
        calls: list[list[str]] = []

        def fake_nmcli(args, **kwargs):
            calls.append(args)
            return _completed("Home WiFi:802-11-wireless\n")

        with patch.object(wifi_setup, "_run_privileged_nmcli", side_effect=fake_nmcli):
            self.access_point.stop()

        self.assertEqual([call for call in calls if "down" in call], [])

    def test_info_exposes_the_join_instructions(self) -> None:
        with patch.object(wifi_setup, "_run_privileged_nmcli", return_value=_completed("")):
            info = self.access_point.info()
        self.assertEqual(info.ssid, "TestCam")
        self.assertEqual(info.password, "takeaphoto")
        self.assertEqual(info.url, "http://10.42.0.1")
        self.assertFalse(info.active)

    def test_short_env_passwords_fall_back_to_the_device_password(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            password_file = Path(tmp) / "ap_password"
            password_file.write_text("devicepass\n", encoding="utf-8")
            with patch.dict("os.environ", {"WIFI_SETUP_AP_PASSWORD": "short"}):
                with patch.object(wifi_setup, "DEFAULT_PASSWORD_FILE", password_file):
                    self.assertEqual(SetupAccessPoint(ssid="x").password, "devicepass")


class DevicePasswordTests(unittest.TestCase):
    """Each camera gets its own hotspot password -- nothing in the repo works."""

    def test_generates_and_persists_on_first_use(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            password_file = Path(tmp) / "ap_password"

            first = wifi_setup._device_ap_password(password_file)
            second = wifi_setup._device_ap_password(password_file)

            self.assertEqual(first, second)
            self.assertGreaterEqual(len(first), wifi_setup.MIN_AP_PASSWORD_LENGTH)
            self.assertEqual(password_file.read_text(encoding="utf-8").strip(), first)

    def test_every_character_is_typable_off_the_display(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            generated = wifi_setup._device_ap_password(Path(tmp) / "ap_password")
        for character in generated:
            self.assertIn(character, wifi_setup._PASSWORD_ALPHABET)
        # The ambiguous-at-3.5-inches characters must never appear.
        self.assertFalse(set("il1o0O") & set(generated))

    def test_two_cameras_get_different_passwords(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            one = wifi_setup._device_ap_password(Path(tmp) / "camera-one")
            two = wifi_setup._device_ap_password(Path(tmp) / "camera-two")
        self.assertNotEqual(one, two)

    def test_a_stored_password_that_is_too_short_is_replaced(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            password_file = Path(tmp) / "ap_password"
            password_file.write_text("tiny\n", encoding="utf-8")

            generated = wifi_setup._device_ap_password(password_file)

            self.assertGreaterEqual(len(generated), wifi_setup.MIN_AP_PASSWORD_LENGTH)
            self.assertEqual(password_file.read_text(encoding="utf-8").strip(), generated)

    def test_env_override_still_wins(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict("os.environ", {"WIFI_SETUP_AP_PASSWORD": "myownpassword"}):
                self.assertEqual(
                    wifi_setup._env_ap_password(Path(tmp) / "ap_password"),
                    "myownpassword",
                )

    def test_an_unwritable_location_still_yields_a_usable_password(self) -> None:
        generated = wifi_setup._device_ap_password(
            Path("/proc/does-not-exist/ap_password")
        )
        self.assertGreaterEqual(len(generated), wifi_setup.MIN_AP_PASSWORD_LENGTH)


class WebConnectValidationTests(unittest.TestCase):
    """The SSID reaches a root nmcli argv, and the web UI has no auth."""

    def setUp(self) -> None:
        from imagegencam.controller import ImageGenCamController

        self.connect = ImageGenCamController.begin_wifi_connect_from_web
        self.stub = object()

    def _reject(self, ssid: str) -> dict:
        return self.connect(self.stub, ssid)

    def test_rejects_an_option_like_ssid(self) -> None:
        self.assertFalse(self._reject("--help")["ok"])
        self.assertFalse(self._reject("-x")["ok"])

    def test_rejects_control_characters(self) -> None:
        self.assertFalse(self._reject("home\nwifi")["ok"])

    def test_rejects_an_over_length_ssid(self) -> None:
        self.assertFalse(self._reject("a" * 33)["ok"])

    def test_rejects_an_empty_ssid(self) -> None:
        self.assertFalse(self._reject("   ")["ok"])


class _FakeAccessPoint:
    def __init__(self, online_after: int = 0) -> None:
        self.online_after = online_after
        self.online_checks = 0
        self.started = 0
        self.stopped = 0
        self.active = True

    def is_online(self) -> bool:
        self.online_checks += 1
        return self.online_checks > self.online_after

    def is_active(self) -> bool:
        return self.active

    def start(self) -> bool:
        self.started += 1
        self.active = True
        return True

    def stop(self) -> None:
        self.stopped += 1
        self.active = False


class _RetryStub:
    """Stand-in for the controller so the retry cycle runs without hardware."""

    def __init__(self, access_point: _FakeAccessPoint) -> None:
        from threading import Lock

        self.running = True
        self.setup_access_point = access_point
        self.setup_portal_active = True
        self.setup_portal_message = ""
        self.setup_portal_networks = []
        self.setup_rejoin_seconds = 0.2
        self.setup_retry_seconds = 0.1
        self.setup_portal_last_activity = 0.0
        self.wifi_connecting = False
        self.last_drawn_mode = None
        self.state_lock = Lock()
        self.state = types.SimpleNamespace(mode="preview", status_message="")
        self.wifi_manager = types.SimpleNamespace(
            scan_networks=lambda: [], list_saved_networks=lambda: []
        )

    def _get_wifi_ssid(self) -> str:
        return "Home WiFi"

    def close_setup_portal(self):
        from imagegencam.controller import ImageGenCamController

        return ImageGenCamController.close_setup_portal(self)

    def open_setup_portal(self, *, rescan: bool = True):
        from imagegencam.controller import ImageGenCamController

        return ImageGenCamController.open_setup_portal(self, rescan=rescan)


class SetupPortalRetryTests(unittest.TestCase):
    def setUp(self) -> None:
        from imagegencam.controller import ImageGenCamController

        self.retry = ImageGenCamController._retry_known_networks

    def test_a_recovered_network_keeps_the_hotspot_down(self) -> None:
        access_point = _FakeAccessPoint(online_after=0)
        stub = _RetryStub(access_point)

        self.assertTrue(self.retry(stub))

        self.assertEqual(access_point.stopped, 1)
        self.assertEqual(access_point.started, 0)
        self.assertFalse(stub.setup_portal_active)
        self.assertIn("Home WiFi", stub.setup_portal_message)

    def test_the_hotspot_returns_when_nothing_is_reachable(self) -> None:
        # Never reports online, so the rejoin window must expire.
        access_point = _FakeAccessPoint(online_after=10_000)
        stub = _RetryStub(access_point)

        self.assertFalse(self.retry(stub))

        self.assertEqual(access_point.stopped, 1)
        self.assertEqual(access_point.started, 1)
        self.assertTrue(stub.setup_portal_active)

    def test_the_retry_window_refreshes_the_offered_networks(self) -> None:
        access_point = _FakeAccessPoint(online_after=10_000)
        stub = _RetryStub(access_point)
        stub.wifi_manager.scan_networks = lambda: ["Home WiFi"]

        self.retry(stub)

        self.assertEqual(stub.setup_portal_networks, ["Home WiFi"])

    def test_a_failed_scan_does_not_abort_the_retry(self) -> None:
        access_point = _FakeAccessPoint(online_after=10_000)
        stub = _RetryStub(access_point)

        def boom():
            raise OSError("nmcli exploded")

        stub.wifi_manager.scan_networks = boom

        self.assertFalse(self.retry(stub))
        self.assertTrue(stub.setup_portal_active)


class SetupPortalActivityTests(unittest.TestCase):
    def test_activity_defers_the_next_retry(self) -> None:
        from imagegencam.controller import ImageGenCamController

        stub = _RetryStub(_FakeAccessPoint())
        self.assertEqual(stub.setup_portal_last_activity, 0.0)

        ImageGenCamController.note_setup_portal_activity(stub)

        self.assertGreater(stub.setup_portal_last_activity, 0.0)


class CurrentAddressTests(unittest.TestCase):
    def test_current_ipv4_strips_the_prefix_length(self) -> None:
        stdout = "IP4.ADDRESS[1]:192.168.1.24/24\n"
        with patch.object(wifi_setup, "_run_privileged_nmcli", return_value=_completed(stdout)):
            self.assertEqual(current_ipv4("wlan0"), "192.168.1.24")

    def test_current_ipv4_returns_none_without_a_lease(self) -> None:
        with patch.object(wifi_setup, "_run_privileged_nmcli", return_value=_completed("")):
            self.assertIsNone(current_ipv4("wlan0"))


if __name__ == "__main__":
    unittest.main()
