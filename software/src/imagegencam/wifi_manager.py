from __future__ import annotations

"""Small, defensive wrapper around NetworkManager Wi-Fi commands.

The camera is often headless, so Wi-Fi changes must be reversible. This module
never deletes saved connections. New networks are added as normal
NetworkManager profiles, and every connection attempt can schedule a rollback to
the previously active profile.
"""

import os
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from uuid import uuid4


ROLLBACK_DIR = Path("/tmp/imagegencam-wifi")
WIFI_IFNAME = os.environ.get("WIFI_INTERFACE", "wlan0").strip() or "wlan0"


@dataclass(frozen=True)
class WifiNetwork:
    ssid: str
    saved: bool
    active: bool
    secure: bool
    signal: int | None = None
    connection_name: str | None = None


@dataclass(frozen=True)
class WifiRollback:
    keep_file: Path
    previous_connection: str | None
    expires_at: float


def _run_nmcli(args: list[str], *, timeout: float = 8.0) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["nmcli", *args],
        capture_output=True,
        text=True,
        check=False,
        timeout=timeout,
    )


def _run_privileged_nmcli(
    args: list[str],
    *,
    timeout: float = 8.0,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["sudo", "-n", "nmcli", *args],
        capture_output=True,
        text=True,
        check=False,
        timeout=timeout,
    )
    if result.returncode != 0 and "a password is required" in result.stderr.lower():
        return subprocess.run(
            ["nmcli", *args],
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout,
        )
    return result


def _split_nmcli_line(line: str) -> list[str]:
    parts: list[str] = []
    current: list[str] = []
    escaped = False
    for char in line.rstrip("\n"):
        if escaped:
            current.append(char)
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == ":":
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    parts.append("".join(current))
    return parts


class NetworkManagerWifi:
    def current_ssid(self) -> str:
        result = _run_nmcli(["-t", "-f", "active,ssid", "dev", "wifi"], timeout=3.0)
        for line in result.stdout.splitlines():
            parts = _split_nmcli_line(line)
            if len(parts) >= 2 and parts[0] == "yes" and parts[1]:
                return parts[1]
        return "Unknown"

    def active_connection_name(self) -> str | None:
        result = _run_nmcli(["-t", "-f", "NAME,TYPE,DEVICE", "connection", "show", "--active"], timeout=3.0)
        for line in result.stdout.splitlines():
            parts = _split_nmcli_line(line)
            if len(parts) >= 3 and parts[1] == "802-11-wireless" and parts[2]:
                return parts[0]
        return None

    def list_saved_networks(self) -> list[WifiNetwork]:
        active_connection = self.active_connection_name()
        result = _run_nmcli(
            ["-t", "-f", "NAME,TYPE,AUTOCONNECT,DEVICE", "connection", "show"],
            timeout=4.0,
        )
        networks: list[WifiNetwork] = []
        seen: set[str] = set()
        for line in result.stdout.splitlines():
            parts = _split_nmcli_line(line)
            if len(parts) < 4 or parts[1] != "802-11-wireless":
                continue
            name = parts[0].strip()
            if not name or name in seen:
                continue
            seen.add(name)
            networks.append(
                WifiNetwork(
                    ssid=name,
                    saved=True,
                    active=name == active_connection or bool(parts[3]),
                    secure=True,
                    connection_name=name,
                )
            )
        return networks

    def scan_networks(self) -> list[WifiNetwork]:
        _run_privileged_nmcli(["dev", "wifi", "rescan", "ifname", WIFI_IFNAME], timeout=12.0)
        result = _run_privileged_nmcli(
            [
                "-t",
                "-f",
                "active,ssid,signal,security",
                "dev",
                "wifi",
                "list",
                "ifname",
                WIFI_IFNAME,
                "--rescan",
                "no",
            ],
            timeout=12.0,
        )
        if result.returncode != 0:
            result = _run_nmcli(
                ["-t", "-f", "active,ssid,signal,security", "dev", "wifi", "list", "--rescan", "no"],
                timeout=8.0,
            )
        saved_by_name = {network.ssid: network for network in self.list_saved_networks()}
        best_by_ssid: dict[str, WifiNetwork] = {}
        for line in result.stdout.splitlines():
            parts = _split_nmcli_line(line)
            if len(parts) < 4:
                continue
            active, ssid, signal_text, security = parts[:4]
            ssid = ssid.strip()
            if not ssid:
                continue
            try:
                signal = int(signal_text)
            except ValueError:
                signal = None
            saved = saved_by_name.get(ssid)
            candidate = WifiNetwork(
                ssid=ssid,
                saved=saved is not None,
                active=active == "yes" or bool(saved and saved.active),
                secure=bool(security.strip()),
                signal=signal,
                connection_name=saved.connection_name if saved else None,
            )
            current = best_by_ssid.get(ssid)
            if current is None or (candidate.signal or 0) > (current.signal or 0):
                best_by_ssid[ssid] = candidate
        saved_only = [network for network in saved_by_name.values() if network.ssid not in best_by_ssid]
        return sorted(
            [*best_by_ssid.values(), *saved_only],
            key=lambda network: (
                not network.active,
                not network.saved,
                -(network.signal or 0),
                network.ssid.lower(),
            ),
        )

    def schedule_rollback(self, previous_connection: str | None, seconds: int = 120) -> WifiRollback | None:
        if not previous_connection:
            return None
        ROLLBACK_DIR.mkdir(parents=True, exist_ok=True)
        keep_file = ROLLBACK_DIR / f"keep-{uuid4().hex}"
        env = os.environ.copy()
        env.update(
            {
                "WIFI_KEEP_FILE": str(keep_file),
                "WIFI_OLD_CONNECTION": previous_connection,
                "WIFI_ROLLBACK_SECONDS": str(max(15, seconds)),
            }
        )
        subprocess.Popen(
            [
                "bash",
                "-c",
                (
                    'sleep "$WIFI_ROLLBACK_SECONDS"; '
                    'if [ ! -f "$WIFI_KEEP_FILE" ]; then '
                    'sudo -n nmcli connection up id "$WIFI_OLD_CONNECTION" >/dev/null 2>&1 || '
                    'nmcli connection up id "$WIFI_OLD_CONNECTION" >/dev/null 2>&1 || true; '
                    "fi; "
                    'rm -f "$WIFI_KEEP_FILE"'
                ),
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=env,
            start_new_session=True,
        )
        return WifiRollback(
            keep_file=keep_file,
            previous_connection=previous_connection,
            expires_at=time.monotonic() + seconds,
        )

    def confirm_rollback(self, rollback: WifiRollback | None) -> None:
        if rollback is None:
            return
        rollback.keep_file.parent.mkdir(parents=True, exist_ok=True)
        rollback.keep_file.write_text("keep\n", encoding="utf-8")

    def connect_saved(self, network: WifiNetwork) -> subprocess.CompletedProcess[str]:
        connection_name = network.connection_name or network.ssid
        return _run_privileged_nmcli(["connection", "up", "id", connection_name], timeout=25.0)

    def connect_new(self, ssid: str, password: str = "") -> subprocess.CompletedProcess[str]:
        args = ["device", "wifi", "connect", ssid]
        if password:
            args.extend(["password", password])
        return _run_privileged_nmcli(args, timeout=35.0)
