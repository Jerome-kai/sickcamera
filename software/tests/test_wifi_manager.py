from __future__ import annotations

import subprocess
import unittest
from unittest.mock import patch

from imagegencam import wifi_manager
from imagegencam.wifi_manager import NetworkManagerWifi, WifiNetwork, _split_nmcli_line


class WifiManagerTests(unittest.TestCase):
    def test_split_nmcli_line_handles_escaped_colons(self) -> None:
        self.assertEqual(
            _split_nmcli_line(r"yes:Studio\:WiFi:88:WPA2"),
            ["yes", "Studio:WiFi", "88", "WPA2"],
        )

    def test_split_nmcli_line_preserves_empty_fields(self) -> None:
        self.assertEqual(
            _split_nmcli_line("no:Open Network:42:"),
            ["no", "Open Network", "42", ""],
        )


class ForgetNetworkTests(unittest.TestCase):
    def test_forget_deletes_the_saved_profile_by_connection_name(self) -> None:
        calls: list[list[str]] = []

        def fake_nmcli(args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args=["nmcli"], returncode=0, stdout="", stderr="")

        network = WifiNetwork(
            ssid="Home WiFi", saved=True, active=False, secure=True,
            connection_name="Home WiFi 1",
        )
        with patch.object(wifi_manager, "_run_privileged_nmcli", side_effect=fake_nmcli):
            result = NetworkManagerWifi().forget(network)

        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, [["connection", "delete", "id", "Home WiFi 1"]])

    def test_forget_falls_back_to_the_ssid_without_a_profile_name(self) -> None:
        calls: list[list[str]] = []

        def fake_nmcli(args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args=["nmcli"], returncode=0, stdout="", stderr="")

        network = WifiNetwork(ssid="Cafe", saved=True, active=False, secure=True)
        with patch.object(wifi_manager, "_run_privileged_nmcli", side_effect=fake_nmcli):
            NetworkManagerWifi().forget(network)

        self.assertEqual(calls, [["connection", "delete", "id", "Cafe"]])


if __name__ == "__main__":
    unittest.main()
