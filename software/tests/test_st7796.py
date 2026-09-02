from __future__ import annotations

import sys
import types
import unittest
from unittest import mock

from PIL import Image

from imagegencam import st7796 as st7796_module
from imagegencam.st7796 import ST7796


class _FakeSpiDev:
    def __init__(self) -> None:
        self.opened: tuple[int, int] | None = None
        self.max_speed_hz: int | None = None
        self.mode: int | None = None
        self.writes: list[list[int]] = []
        self.frames: list[bytes] = []
        self.closed = False

    def open(self, bus: int, device: int) -> None:
        self.opened = (bus, device)

    def writebytes(self, values) -> None:
        self.writes.append(list(values))

    def writebytes2(self, payload) -> None:
        self.frames.append(bytes(payload))

    def close(self) -> None:
        self.closed = True


class _FakeLines:
    def __init__(self) -> None:
        self.transitions: list[tuple[int, bool]] = []
        self.released = False

    def set(self, pin: int, high: bool) -> None:
        self.transitions.append((pin, bool(high)))

    def release(self) -> None:
        self.released = True


class _PanelTestCase(unittest.TestCase):
    """Drives the real driver against a fake spidev and fake GPIO lines.

    The panel is the one part a contributor cannot test by looking at it: a
    wrong init byte or a mis-set window shows up as a blank or shifted screen
    on the bench, with nothing in the logs.
    """

    def setUp(self) -> None:
        self.spi = _FakeSpiDev()
        self.lines = _FakeLines()
        fake_spidev = types.ModuleType("spidev")
        fake_spidev.SpiDev = lambda: self.spi
        patches = [
            mock.patch.dict(sys.modules, {"spidev": fake_spidev}),
            mock.patch.object(st7796_module.sunxi_gpio, "request_outputs", self._request_outputs),
            # The init sequences wait out panel timings; nothing here needs them.
            mock.patch("time.sleep"),
        ]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)

    def _request_outputs(self, pins, consumer: str = "") -> _FakeLines:
        self.requested_pins = list(pins)
        self.consumer = consumer
        return self.lines

    def commands(self) -> list[int]:
        """The command bytes, which _cmd always writes as a single-byte write
        with DC low."""
        return [write[0] for write in self.spi.writes if len(write) == 1]


class PanelOpenTests(_PanelTestCase):
    def test_opening_configures_the_spi_device(self) -> None:
        panel = ST7796(spi_bus=1, spi_dev=0, speed_hz=32_000_000)

        panel.open()

        self.assertEqual(self.spi.opened, (1, 0))
        self.assertEqual(self.spi.max_speed_hz, 32_000_000)
        self.assertEqual(self.spi.mode, 0)

    def test_opening_requests_the_three_control_lines(self) -> None:
        panel = ST7796(dc_pin=74, rst_pin=71, backlight_pin=79)

        panel.open()

        self.assertEqual(self.requested_pins, [74, 71, 79])
        self.assertEqual(self.consumer, "imagegencam-display")

    def test_the_panel_is_pulsed_through_reset(self) -> None:
        # A missing low pulse leaves the controller in whatever state the last
        # boot left it in, which shows as a panel that never wakes.
        panel = ST7796(rst_pin=71)

        panel.open()

        reset_edges = [high for pin, high in self.lines.transitions if pin == 71]
        self.assertEqual(reset_edges[:3], [True, False, True])

    def test_the_st7796_init_ends_by_turning_the_display_on(self) -> None:
        panel = ST7796(controller="st7796")

        panel.open()

        commands = self.commands()
        self.assertEqual(commands[0], 0x01)  # SWRESET
        self.assertIn(0x11, commands)  # SLPOUT
        self.assertEqual(commands[-1], 0x29)  # DISPON

    def test_the_ili9341_init_runs_its_own_sequence(self) -> None:
        panel = ST7796(controller="ili9341")

        panel.open()

        commands = self.commands()
        self.assertIn(0xCB, commands)  # power control A, ILI9341 only
        self.assertEqual(commands[-1], 0x29)

    def test_the_pixel_format_is_set_to_rgb565(self) -> None:
        # The blitter always sends 16-bit words; a panel left in 18-bit mode
        # renders them as noise.
        for controller in ("st7796", "ili9341"):
            with self.subTest(controller=controller):
                self.setUp()
                ST7796(controller=controller).open()

                colmod = self._data_for_command(0x3A)
                self.assertEqual(colmod, b"\x55")

    def _data_for_command(self, command: int) -> bytes:
        for index, write in enumerate(self.spi.writes):
            if write == [command] and index + 1 < len(self.spi.writes):
                following = self.spi.writes[index + 1]
                if len(following) > 1 or len(following) == 1 and following[0] > 0x00:
                    return bytes(following)
        return b""


class OrientationTests(_PanelTestCase):
    def test_the_default_orientation_byte_is_sent(self) -> None:
        ST7796(rotate_180=False).open()

        self.assertEqual(self._madctl_value(), 0xE8)

    def test_a_180_degree_mount_flips_the_orientation_byte(self) -> None:
        ST7796(rotate_180=True).open()

        self.assertEqual(self._madctl_value(), 0x28)

    def test_inversion_is_off_by_default_and_on_when_asked(self) -> None:
        ST7796(invert=False).open()
        self.assertIn(0x20, self.commands())  # INVOFF

        self.setUp()
        ST7796(invert=True).open()
        self.assertIn(0x21, self.commands())  # INVON

    def _madctl_value(self) -> int:
        for index, write in enumerate(self.spi.writes):
            if write == [0x36]:
                return self.spi.writes[index + 1][0]
        raise AssertionError("MADCTL was never sent")


class BlitTests(_PanelTestCase):
    def test_the_write_window_covers_the_whole_panel(self) -> None:
        # An off-by-one here shifts or tears every frame on the device.
        panel = ST7796(controller="ili9341")
        panel.open()
        self.spi.writes.clear()

        panel.show(Image.new("RGB", (panel.width, panel.height)))

        column = self._data_after(0x2A)
        row = self._data_after(0x2B)
        self.assertEqual(column, b"\x00\x00" + (panel.width - 1).to_bytes(2, "big"))
        self.assertEqual(row, b"\x00\x00" + (panel.height - 1).to_bytes(2, "big"))

    def test_an_off_size_image_is_resized_to_the_panel(self) -> None:
        panel = ST7796(controller="ili9341")
        panel.open()

        panel.show(Image.new("RGB", (100, 80)))

        self.assertEqual(len(self.spi.frames[-1]), panel.width * panel.height * 2)

    def test_the_data_line_is_high_for_the_pixel_payload(self) -> None:
        panel = ST7796(controller="ili9341", dc_pin=74)
        panel.open()
        self.lines.transitions.clear()

        panel.show(Image.new("RGB", (panel.width, panel.height)))

        self.assertEqual(self.lines.transitions[-1], (74, True))

    def _data_after(self, command: int) -> bytes:
        for index, write in enumerate(self.spi.writes):
            if write == [command]:
                return bytes(self.spi.writes[index + 1])
        raise AssertionError(f"command {command:#x} was never sent")


class BacklightAndCloseTests(_PanelTestCase):
    def test_the_backlight_line_follows_the_requested_state(self) -> None:
        panel = ST7796(backlight_pin=79)
        panel.open()
        self.lines.transitions.clear()

        panel.set_backlight(True)
        panel.set_backlight(False)

        self.assertEqual(self.lines.transitions, [(79, True), (79, False)])

    def test_closing_releases_the_spi_device_and_the_gpio_lines(self) -> None:
        panel = ST7796()
        panel.open()

        panel.close()

        self.assertTrue(self.spi.closed)
        self.assertTrue(self.lines.released)

    def test_closing_twice_is_harmless(self) -> None:
        # close() runs from the shutdown path, which also runs after a failed
        # start -- so it has to tolerate being called on a half-open panel.
        panel = ST7796()
        panel.open()

        panel.close()
        panel.close()

        self.assertIsNone(panel.spi)

    def test_closing_a_panel_that_never_opened_is_harmless(self) -> None:
        ST7796().close()

    def test_a_device_that_fails_to_close_still_releases_the_gpio_lines(self) -> None:
        # Otherwise a restart cannot re-request the lines and the display
        # stays dark until the next reboot.
        panel = ST7796()
        panel.open()
        self.spi.close = mock.Mock(side_effect=OSError("device is gone"))

        panel.close()

        self.assertTrue(self.lines.released)
        self.assertIsNone(panel._lines)


class PanelSizeTests(unittest.TestCase):
    def test_an_unknown_controller_falls_back_to_the_default_panel(self) -> None:
        panel = ST7796(controller="nonsense")

        self.assertEqual(panel.controller, "st7796")
        self.assertEqual((panel.width, panel.height), (480, 320))


if __name__ == "__main__":
    unittest.main()
