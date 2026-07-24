from __future__ import annotations

import os
import unittest
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


if __name__ == "__main__":
    unittest.main()
