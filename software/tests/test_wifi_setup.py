from __future__ import annotations

import subprocess
import unittest
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

    def test_short_passwords_fall_back_to_the_default(self) -> None:
        with patch.dict("os.environ", {"WIFI_SETUP_AP_PASSWORD": "short"}):
            self.assertEqual(SetupAccessPoint(ssid="x").password, "takeaphoto")


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
