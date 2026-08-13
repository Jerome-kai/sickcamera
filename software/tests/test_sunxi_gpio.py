from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from imagegencam import sunxi_gpio


class BackendFallbackTests(unittest.TestCase):
    def test_auto_falls_back_to_sysfs_when_gpiod_line_out_of_range(self) -> None:
        # Legacy 4.9 kernel: gpiod opens a chip whose line offsets don't match
        # the sunxi numbering and raises ValueError("line offset of out range").
        # auto mode must swallow that and use the sysfs backend instead.
        sentinel = object()
        with mock.patch.dict(os.environ, {"GPIO_BACKEND": "auto"}):
            with mock.patch.object(
                sunxi_gpio, "GpiodOutputs", side_effect=ValueError("line offset of out range")
            ):
                with mock.patch.object(
                    sunxi_gpio, "SysfsOutputs", return_value=sentinel
                ) as sysfs:
                    result = sunxi_gpio.request_outputs([74, 71, 79], consumer="test")
        self.assertIs(result, sentinel)
        sysfs.assert_called_once()

    def test_auto_falls_back_for_inputs_too(self) -> None:
        sentinel = object()
        with mock.patch.dict(os.environ, {"GPIO_BACKEND": "auto"}):
            with mock.patch.object(
                sunxi_gpio, "GpiodInputs", side_effect=ValueError("line offset of out range")
            ):
                with mock.patch.object(
                    sunxi_gpio, "SysfsInputs", return_value=sentinel
                ) as sysfs:
                    result = sunxi_gpio.request_inputs([73, 70], consumer="test")
        self.assertIs(result, sentinel)
        sysfs.assert_called_once()

    def test_explicit_sysfs_skips_gpiod_entirely(self) -> None:
        sentinel = object()
        with mock.patch.dict(os.environ, {"GPIO_BACKEND": "sysfs"}):
            with mock.patch.object(sunxi_gpio, "GpiodOutputs") as gpiod:
                with mock.patch.object(sunxi_gpio, "SysfsOutputs", return_value=sentinel):
                    result = sunxi_gpio.request_outputs([74], consumer="test")
        self.assertIs(result, sentinel)
        gpiod.assert_not_called()


class SysfsExportRaceTests(unittest.TestCase):
    """The gpio files exist as soon as export returns, but udev owns them.

    Until udev applies the `gpio` group they are root-only, so a service
    starting at boot -- when udev has a queue -- can open them a moment too
    early and get EACCES. That used to abort startup.
    """

    def _fake_sysfs(self, tmp: str, denials: int) -> dict:
        root = Path(tmp)
        state = {
            "denials": denials,
            "direction_attempts": 0,
            "value_attempts": 0,
            "unexported": [],
        }
        exported: set[str] = set()

        def isdir(path: str) -> bool:
            return Path(path).name in exported or Path(path).name == "gpio"

        def fake_open(path, mode="r", *args, **kwargs):
            name = Path(path).name
            if name == "export":
                return _ExportFile(exported)
            if name == "unexport":
                return _UnexportFile(exported, state)
            if name == "direction":
                state["direction_attempts"] += 1
                if state["denials"] > 0:
                    state["denials"] -= 1
                    raise PermissionError(13, "Permission denied")
                return _NullFile()
            raise AssertionError(f"unexpected open {path}")

        def fake_os_open(path, flags):
            state["value_attempts"] += 1
            return 4242

        state["isdir"] = isdir
        state["open"] = fake_open
        state["os_open"] = fake_os_open
        state["root"] = root
        return state

    def _run(self, state) -> None:
        with mock.patch("builtins.open", state["open"]):
            with mock.patch.object(sunxi_gpio.os.path, "isdir", state["isdir"]):
                with mock.patch.object(sunxi_gpio.os, "open", state["os_open"]):
                    with mock.patch.object(sunxi_gpio, "set_pullups", return_value=True):
                        state["result"] = sunxi_gpio.SysfsInputs([73], pull_up=False)

    def test_retries_through_a_transient_permission_denied(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            state = self._fake_sysfs(tmp, denials=3)

            self._run(state)

            self.assertEqual(state["direction_attempts"], 4)
            self.assertEqual(state["value_attempts"], 1)

    def test_opens_straight_away_when_udev_already_ran(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            state = self._fake_sysfs(tmp, denials=0)

            self._run(state)

            self.assertEqual(state["direction_attempts"], 1)

    def test_gives_up_once_the_window_closes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            state = self._fake_sysfs(tmp, denials=10_000)

            with mock.patch.dict(os.environ, {"GPIO_SYSFS_READY_TIMEOUT_SECONDS": "0.05"}):
                with self.assertRaises(OSError):
                    self._run(state)

            # Giving up must hand the line back, or the next start inherits a
            # half-claimed pin.
            self.assertEqual(state["unexported"], ["73"])


class _ExportFile:
    def __init__(self, exported: set[str]) -> None:
        self._exported = exported

    def write(self, value: str) -> None:
        self._exported.add(f"gpio{value.strip()}")

    def __enter__(self):
        return self

    def __exit__(self, *args) -> None:
        return None


class _UnexportFile:
    def __init__(self, exported: set[str], state: dict) -> None:
        self._exported = exported
        self._state = state

    def write(self, value: str) -> None:
        self._state["unexported"].append(value.strip())
        self._exported.discard(f"gpio{value.strip()}")

    def __enter__(self):
        return self

    def __exit__(self, *args) -> None:
        return None


class _NullFile:
    def write(self, value: str) -> None:
        return None

    def __enter__(self):
        return self

    def __exit__(self, *args) -> None:
        return None


if __name__ == "__main__":
    unittest.main()
