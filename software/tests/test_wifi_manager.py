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


def _completed(stdout: str = "", returncode: int = 0, stderr: str = "") -> subprocess.CompletedProcess:
    return subprocess.CompletedProcess(
        args=["nmcli"], returncode=returncode, stdout=stdout, stderr=stderr
    )


class _FakeNmcli:
    """Routes nmcli invocations to canned output.

    nmcli's terse output is the module's whole input, and it varies by version
    and by what is in range, so the parsing is worth pinning against realistic
    lines rather than a live daemon.
    """

    def __init__(
        self,
        *,
        wifi_list: str = "",
        wifi_list_returncode: int = 0,
        saved: str = "",
        active: str = "",
        current: str = "",
    ) -> None:
        self.wifi_list = wifi_list
        self.wifi_list_returncode = wifi_list_returncode
        self.saved = saved
        self.active = active
        self.current = current
        self.privileged_calls: list[list[str]] = []
        self.plain_calls: list[list[str]] = []

    def _dispatch(self, args: list[str]) -> subprocess.CompletedProcess:
        if "rescan" in args and "dev" in args:
            return _completed()
        if "connection" in args and "show" in args:
            return _completed(self.active if "--active" in args else self.saved)
        fields = args[args.index("-f") + 1] if "-f" in args else ""
        if "list" in args:
            return _completed(self.wifi_list, returncode=self.wifi_list_returncode)
        if fields.startswith("active,ssid") and "dev" in args:
            return _completed(self.current)
        return _completed()

    def privileged(self, args: list[str], **kwargs) -> subprocess.CompletedProcess:
        self.privileged_calls.append(args)
        return self._dispatch(args)

    def plain(self, args: list[str], **kwargs) -> subprocess.CompletedProcess:
        self.plain_calls.append(args)
        result = self._dispatch(args)
        if "list" in args:
            # The unprivileged fallback is only reached when the privileged
            # call failed, and it succeeds where that one did not.
            return _completed(self.wifi_list)
        return result

    def install(self, test: unittest.TestCase) -> "_FakeNmcli":
        for name, target in (
            ("_run_privileged_nmcli", self.privileged),
            ("_run_nmcli", self.plain),
        ):
            patcher = patch.object(wifi_manager, name, side_effect=target)
            patcher.start()
            test.addCleanup(patcher.stop)
        return self


class CurrentNetworkTests(unittest.TestCase):
    def test_the_active_ssid_is_read_from_the_device_list(self) -> None:
        fake = _FakeNmcli(current="no:Neighbour\nyes:Studio WiFi\n").install(self)

        self.assertEqual(NetworkManagerWifi().current_ssid(), "Studio WiFi")
        self.assertTrue(fake.plain_calls)

    def test_an_ssid_containing_a_colon_survives_parsing(self) -> None:
        _FakeNmcli(current="yes:Studio\\:WiFi\n").install(self)

        self.assertEqual(NetworkManagerWifi().current_ssid(), "Studio:WiFi")

    def test_no_active_network_reads_as_unknown(self) -> None:
        _FakeNmcli(current="no:Neighbour\n").install(self)

        self.assertEqual(NetworkManagerWifi().current_ssid(), "Unknown")

    def test_an_active_row_with_a_blank_ssid_is_not_reported(self) -> None:
        # A hidden network broadcasts no SSID; reporting "" as the network name
        # would put an empty label on the diagnostics screen.
        _FakeNmcli(current="yes:\n").install(self)

        self.assertEqual(NetworkManagerWifi().current_ssid(), "Unknown")

    def test_the_active_wireless_profile_is_named(self) -> None:
        _FakeNmcli(
            active="Wired connection 1:802-3-ethernet:eth0\nHome WiFi:802-11-wireless:wlan0\n"
        ).install(self)

        self.assertEqual(NetworkManagerWifi().active_connection_name(), "Home WiFi")

    def test_a_wireless_profile_with_no_device_is_not_active(self) -> None:
        _FakeNmcli(active="Home WiFi:802-11-wireless:\n").install(self)

        self.assertIsNone(NetworkManagerWifi().active_connection_name())


class SavedNetworkTests(unittest.TestCase):
    def test_only_wireless_profiles_are_listed(self) -> None:
        _FakeNmcli(
            saved=(
                "Wired connection 1:802-3-ethernet:yes:eth0\n"
                "Home WiFi:802-11-wireless:yes:wlan0\n"
                "Cafe:802-11-wireless:no:\n"
            ),
            active="Home WiFi:802-11-wireless:wlan0\n",
        ).install(self)

        networks = NetworkManagerWifi().list_saved_networks()

        self.assertEqual([network.ssid for network in networks], ["Home WiFi", "Cafe"])
        self.assertTrue(networks[0].active)
        self.assertFalse(networks[1].active)
        self.assertTrue(all(network.saved for network in networks))

    def test_a_duplicated_profile_name_is_listed_once(self) -> None:
        _FakeNmcli(saved="Cafe:802-11-wireless:yes:\nCafe:802-11-wireless:yes:\n").install(self)

        self.assertEqual(len(NetworkManagerWifi().list_saved_networks()), 1)


class ScanTests(unittest.TestCase):
    def test_networks_in_range_are_parsed(self) -> None:
        _FakeNmcli(wifi_list="yes:Studio WiFi:88:WPA2\nno:Cafe:54:WPA1 WPA2\n").install(self)

        networks = NetworkManagerWifi().scan_networks()

        self.assertEqual([network.ssid for network in networks], ["Studio WiFi", "Cafe"])
        self.assertTrue(networks[0].active)
        self.assertEqual(networks[0].signal, 88)
        self.assertTrue(networks[1].secure)

    def test_an_open_network_is_marked_insecure(self) -> None:
        # An empty security column is what tells the UI to skip the password
        # keyboard; reading it as secure would strand the user on a keyboard.
        _FakeNmcli(wifi_list="no:Airport Free:40:\n").install(self)

        self.assertFalse(NetworkManagerWifi().scan_networks()[0].secure)

    def test_a_hidden_network_without_an_ssid_is_skipped(self) -> None:
        _FakeNmcli(wifi_list="no::62:WPA2\nno:Cafe:54:WPA2\n").install(self)

        self.assertEqual([n.ssid for n in NetworkManagerWifi().scan_networks()], ["Cafe"])

    def test_an_unreadable_signal_is_left_unknown(self) -> None:
        _FakeNmcli(wifi_list="no:Cafe:--:WPA2\n").install(self)

        self.assertIsNone(NetworkManagerWifi().scan_networks()[0].signal)

    def test_a_short_row_is_ignored_rather_than_crashing_the_scan(self) -> None:
        _FakeNmcli(wifi_list="garbage\nno:Cafe:54:WPA2\n").install(self)

        self.assertEqual([n.ssid for n in NetworkManagerWifi().scan_networks()], ["Cafe"])

    def test_one_ssid_seen_on_two_bands_keeps_the_stronger_reading(self) -> None:
        # A dual-band router shows up twice; listing it twice would make the
        # on-device picker look duplicated.
        _FakeNmcli(wifi_list="no:Studio:41:WPA2\nno:Studio:79:WPA2\n").install(self)

        networks = NetworkManagerWifi().scan_networks()

        self.assertEqual(len(networks), 1)
        self.assertEqual(networks[0].signal, 79)

    def test_a_scanned_network_is_matched_against_the_saved_profiles(self) -> None:
        _FakeNmcli(
            wifi_list="no:Home WiFi:70:WPA2\n",
            saved="Home WiFi:802-11-wireless:yes:\n",
        ).install(self)

        networks = NetworkManagerWifi().scan_networks()

        self.assertEqual(len(networks), 1)
        self.assertTrue(networks[0].saved)
        self.assertEqual(networks[0].connection_name, "Home WiFi")

    def test_a_profile_whose_name_differs_from_the_ssid_is_listed_separately(self) -> None:
        # Saved profiles are matched to scan results by name, and NetworkManager
        # names a second profile for the same network "Home WiFi 1". The scanned
        # network therefore reads as unsaved and the profile is offered on its
        # own line -- the picker shows both.
        _FakeNmcli(
            wifi_list="no:Home WiFi:70:WPA2\n",
            saved="Home WiFi 1:802-11-wireless:yes:wlan0\n",
        ).install(self)

        networks = {network.ssid: network for network in NetworkManagerWifi().scan_networks()}

        self.assertEqual(sorted(networks), ["Home WiFi", "Home WiFi 1"])
        self.assertFalse(networks["Home WiFi"].saved)
        self.assertTrue(networks["Home WiFi 1"].saved)

    def test_a_saved_network_out_of_range_is_still_offered(self) -> None:
        # Otherwise a network the camera knows disappears from the picker the
        # moment it is out of range, and cannot be selected to move back.
        _FakeNmcli(
            wifi_list="no:Cafe:54:WPA2\n",
            saved="Home WiFi:802-11-wireless:yes:\n",
        ).install(self)

        networks = NetworkManagerWifi().scan_networks()

        self.assertEqual([n.ssid for n in networks], ["Home WiFi", "Cafe"])
        self.assertIsNone(networks[0].signal)

    def test_the_picker_is_ordered_active_then_saved_then_by_signal(self) -> None:
        _FakeNmcli(
            wifi_list=(
                "no:Weak:20:WPA2\n"
                "no:Strong:90:WPA2\n"
                "no:Known:35:WPA2\n"
                "yes:Connected:44:WPA2\n"
            ),
            saved="Known:802-11-wireless:yes:\n",
        ).install(self)

        order = [network.ssid for network in NetworkManagerWifi().scan_networks()]

        self.assertEqual(order, ["Connected", "Known", "Strong", "Weak"])

    def test_the_scan_falls_back_to_an_unprivileged_listing(self) -> None:
        # Without passwordless sudo the privileged call fails; the picker must
        # still fill from the plain nmcli listing instead of coming up empty.
        fake = _FakeNmcli(wifi_list="no:Cafe:54:WPA2\n", wifi_list_returncode=1).install(self)

        networks = NetworkManagerWifi().scan_networks()

        self.assertEqual([n.ssid for n in networks], ["Cafe"])
        self.assertTrue(any("list" in args for args in fake.plain_calls))


class ConnectTests(unittest.TestCase):
    def test_joining_a_new_network_passes_the_password(self) -> None:
        calls: list[list[str]] = []

        def record(args, **kwargs):
            calls.append(args)
            return _completed()

        with patch.object(wifi_manager, "_run_privileged_nmcli", side_effect=record):
            NetworkManagerWifi().connect_new("Cafe", "hunter22")

        self.assertEqual(calls, [["device", "wifi", "connect", "Cafe", "password", "hunter22"]])

    def test_joining_an_open_network_sends_no_password_argument(self) -> None:
        # A trailing empty "password" argument makes nmcli reject the command.
        calls: list[list[str]] = []

        def record(args, **kwargs):
            calls.append(args)
            return _completed()

        with patch.object(wifi_manager, "_run_privileged_nmcli", side_effect=record):
            NetworkManagerWifi().connect_new("Airport Free")

        self.assertEqual(calls, [["device", "wifi", "connect", "Airport Free"]])

    def test_reconnecting_uses_the_saved_profile_name(self) -> None:
        calls: list[list[str]] = []

        def record(args, **kwargs):
            calls.append(args)
            return _completed()

        network = WifiNetwork(
            ssid="Home WiFi", saved=True, active=False, secure=True,
            connection_name="Home WiFi 1",
        )
        with patch.object(wifi_manager, "_run_privileged_nmcli", side_effect=record):
            NetworkManagerWifi().connect_saved(network)

        self.assertEqual(calls, [["connection", "up", "id", "Home WiFi 1"]])


class PrivilegeEscalationTests(unittest.TestCase):
    """The camera ships a narrow sudoers rule, but a hand-built image may not
    have one -- so a sudo prompt has to degrade to a plain call, not hang."""

    def test_a_sudo_password_prompt_falls_back_to_plain_nmcli(self) -> None:
        commands: list[list[str]] = []

        def fake_run(command, **kwargs):
            commands.append(command)
            if command[0] == "sudo":
                return _completed(returncode=1, stderr="sudo: a password is required")
            return _completed("ok")

        with patch.object(wifi_manager.subprocess, "run", side_effect=fake_run):
            result = wifi_manager._run_privileged_nmcli(["connection", "show"])

        self.assertEqual(result.stdout, "ok")
        self.assertEqual(commands[0][:3], ["sudo", "-n", "nmcli"])
        self.assertEqual(commands[1][0], "nmcli")

    def test_another_kind_of_failure_is_returned_as_is(self) -> None:
        # Retrying unprivileged would only produce the same error twice.
        commands: list[list[str]] = []

        def fake_run(command, **kwargs):
            commands.append(command)
            return _completed(returncode=4, stderr="Error: unknown connection")

        with patch.object(wifi_manager.subprocess, "run", side_effect=fake_run):
            result = wifi_manager._run_privileged_nmcli(["connection", "up", "id", "Gone"])

        self.assertEqual(result.returncode, 4)
        self.assertEqual(len(commands), 1)

    def test_a_successful_privileged_call_is_not_repeated(self) -> None:
        commands: list[list[str]] = []

        def fake_run(command, **kwargs):
            commands.append(command)
            return _completed("done")

        with patch.object(wifi_manager.subprocess, "run", side_effect=fake_run):
            wifi_manager._run_privileged_nmcli(["connection", "show"])

        self.assertEqual(len(commands), 1)


class RollbackTests(unittest.TestCase):
    def test_no_previous_connection_means_nothing_to_roll_back_to(self) -> None:
        self.assertIsNone(NetworkManagerWifi().schedule_rollback(None))

    def test_confirming_writes_the_keep_file_that_cancels_the_rollback(self) -> None:
        import tempfile
        from pathlib import Path

        from imagegencam.wifi_manager import WifiRollback

        with tempfile.TemporaryDirectory() as tmp:
            keep_file = Path(tmp) / "nested" / "keep-abc"
            rollback = WifiRollback(
                keep_file=keep_file, previous_connection="Home WiFi", expires_at=0.0
            )

            NetworkManagerWifi().confirm_rollback(rollback)

            self.assertTrue(keep_file.exists())

    def test_confirming_nothing_is_harmless(self) -> None:
        NetworkManagerWifi().confirm_rollback(None)



if __name__ == "__main__":
    unittest.main()
