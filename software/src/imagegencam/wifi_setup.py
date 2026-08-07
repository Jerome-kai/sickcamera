from __future__ import annotations

"""Setup hotspot so a brand-new camera can be joined to Wi-Fi without a terminal.

The camera's web UI is the natural place to pick a network, but it is only
reachable once the camera is already online -- a chicken-and-egg problem for
anyone unboxing the camera somewhere its saved networks do not exist.

This module closes that loop. When the camera comes up and finds no network, it
publishes its own WPA2 access point and prints the join instructions on the
built-in display. The owner joins that hotspot from a phone, opens the same web
UI, and picks a real network from a list. Nothing here needs the physical
buttons, which is what makes it usable as a gift.

Only one radio is available, so the hotspot and a normal connection are mutually
exclusive: joining a network always tears the hotspot down first, and a failed
attempt brings it back so the camera never becomes unreachable.
"""

import logging
import os
import socket
import subprocess
from dataclasses import dataclass

from .wifi_manager import WIFI_IFNAME, _run_privileged_nmcli, _split_nmcli_line

logger = logging.getLogger(__name__)

AP_CONNECTION_NAME = "imagegencam-setup"
# NetworkManager hands the shared-mode gateway this address; the phone that
# joins the hotspot reaches the camera's web UI there.
AP_GATEWAY_ADDRESS = "10.42.0.1"
# WPA2 refuses anything shorter, and a short memorable phrase beats a random
# string nobody can type off a 3.5" screen.
MIN_AP_PASSWORD_LENGTH = 8


def _env_ap_ssid() -> str:
    configured = os.environ.get("WIFI_SETUP_AP_SSID", "").strip()
    if configured:
        return configured
    # Suffix keeps two cameras on the same table distinguishable.
    hostname = socket.gethostname().strip() or "camera"
    return f"ImageGenCam-{hostname[-4:]}" if len(hostname) > 4 else "ImageGenCam-Setup"


def _env_ap_password() -> str:
    configured = os.environ.get("WIFI_SETUP_AP_PASSWORD", "").strip()
    if len(configured) >= MIN_AP_PASSWORD_LENGTH:
        return configured
    if configured:
        logger.warning(
            "WIFI_SETUP_AP_PASSWORD is shorter than %d characters; using the default",
            MIN_AP_PASSWORD_LENGTH,
        )
    return "takeaphoto"


@dataclass(frozen=True)
class AccessPointInfo:
    ssid: str
    password: str
    url: str
    active: bool


class SetupAccessPoint:
    """Starts and stops the fallback hotspot via NetworkManager."""

    def __init__(
        self,
        *,
        ifname: str | None = None,
        ssid: str | None = None,
        password: str | None = None,
        connection_name: str = AP_CONNECTION_NAME,
    ) -> None:
        self.ifname = ifname or WIFI_IFNAME
        self.ssid = ssid or _env_ap_ssid()
        self.password = password or _env_ap_password()
        self.connection_name = connection_name

    # -- inspection ----------------------------------------------------

    def _active_wireless_connections(self) -> list[str]:
        try:
            result = _run_privileged_nmcli(
                ["-t", "-f", "NAME,TYPE", "connection", "show", "--active"],
                timeout=5.0,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            logger.warning("Could not list active connections: %s", exc)
            return []
        names: list[str] = []
        for line in result.stdout.splitlines():
            parts = _split_nmcli_line(line)
            if len(parts) >= 2 and parts[1] == "802-11-wireless" and parts[0]:
                names.append(parts[0])
        return names

    def is_active(self) -> bool:
        """True when the setup hotspot itself is up."""
        return self.connection_name in self._active_wireless_connections()

    def is_online(self) -> bool:
        """True when joined to a real network -- the hotspot does not count."""
        return any(
            name != self.connection_name for name in self._active_wireless_connections()
        )

    def info(self) -> AccessPointInfo:
        return AccessPointInfo(
            ssid=self.ssid,
            password=self.password,
            url=f"http://{AP_GATEWAY_ADDRESS}",
            active=self.is_active(),
        )

    # -- lifecycle -----------------------------------------------------

    def start(self) -> bool:
        if self.is_active():
            return True
        logger.info("Starting setup hotspot %r on %s", self.ssid, self.ifname)
        try:
            result = _run_privileged_nmcli(
                [
                    "device",
                    "wifi",
                    "hotspot",
                    "ifname",
                    self.ifname,
                    "con-name",
                    self.connection_name,
                    "ssid",
                    self.ssid,
                    "password",
                    self.password,
                ],
                timeout=30.0,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            logger.error("Setup hotspot failed to start: %s", exc)
            return False
        if result.returncode != 0:
            logger.error("Setup hotspot failed to start: %s", result.stderr.strip())
            return False
        return True

    def stop(self) -> None:
        if not self.is_active():
            return
        logger.info("Stopping setup hotspot %r", self.ssid)
        try:
            _run_privileged_nmcli(
                ["connection", "down", "id", self.connection_name], timeout=15.0
            )
        except (OSError, subprocess.SubprocessError) as exc:
            logger.warning("Setup hotspot failed to stop: %s", exc)


def current_ipv4(ifname: str | None = None) -> str | None:
    """Address the camera is reachable at, or None when it has no lease."""
    interface = ifname or WIFI_IFNAME
    try:
        result = _run_privileged_nmcli(
            ["-t", "-f", "IP4.ADDRESS", "device", "show", interface], timeout=5.0
        )
    except (OSError, subprocess.SubprocessError) as exc:
        logger.warning("Could not read the interface address: %s", exc)
        return None
    for line in result.stdout.splitlines():
        parts = _split_nmcli_line(line)
        if len(parts) >= 2 and parts[1].strip():
            # Field looks like "IP4.ADDRESS[1]:192.168.1.24/24".
            return parts[1].strip().split("/", 1)[0]
    return None
