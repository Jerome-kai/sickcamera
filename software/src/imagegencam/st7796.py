"""Minimal SPI TFT panel driver for the Orange Pi Zero 2 port.

Drives an RGB565 TFT over spidev with DC/RST/backlight on libgpiod lines.
Hardware chip-select is handled by the SPI controller (SPI1 CS0), so only
three GPIO lines are requested here.

Two panel controllers are supported, selected with ``DISPLAY_CONTROLLER``:

- ``st7796`` (default): 3.2" ST7796S/U modules, 480x320 landscape.
- ``ili9341``: 2.8" 240x320 modules (e.g. "2.8 TFT SPI 240X320" with
  capacitive touch), 320x240 landscape — a pixel-perfect match for the
  app's 320x240 UI. Touch and SD pins on those modules are not used.
"""

from __future__ import annotations

import os
import time

import numpy as np

from . import sunxi_gpio


# Default (ST7796) landscape panel size; kept as module constants for
# backwards compatibility. Prefer the instance's width/height.
WIDTH = 480
HEIGHT = 320

_PANEL_SIZES = {
    "st7796": (480, 320),
    "ili9341": (320, 240),
}

_SPIDEV_CHUNK_BYTES = 4096  # spidev default bufsiz; chunk RAMWR data to stay under it


def _env_int(name: str, default: int) -> int:
    return int(os.environ.get(name, str(default)))


def _env_flag(name: str, default: str = "0") -> bool:
    return os.environ.get(name, default).strip().lower() not in {"", "0", "false", "no", "off"}


class ST7796:
    """Full-frame PIL image blitter for ST7796 / ILI9341 panels."""

    def __init__(
        self,
        spi_bus: int | None = None,
        spi_dev: int | None = None,
        speed_hz: int | None = None,
        dc_pin: int | None = None,
        rst_pin: int | None = None,
        backlight_pin: int | None = None,
        gpio_chip: str | None = None,
        invert: bool | None = None,
        rotate_180: bool | None = None,
        controller: str | None = None,
    ) -> None:
        chosen = (controller or os.environ.get("DISPLAY_CONTROLLER", "st7796")).strip().lower()
        self.controller = chosen if chosen in _PANEL_SIZES else "st7796"
        self.width, self.height = _PANEL_SIZES[self.controller]
        self.spi_bus = spi_bus if spi_bus is not None else _env_int("DISPLAY_SPI_BUS", 1)
        # Legacy 4.9 image exposes /dev/spidev1.1; modern images use spidev1.0 (DEV=0).
        self.spi_dev = spi_dev if spi_dev is not None else _env_int("DISPLAY_SPI_DEV", 1)
        self.speed_hz = speed_hz if speed_hz is not None else _env_int("DISPLAY_SPI_HZ", 40_000_000)
        self.dc_pin = dc_pin if dc_pin is not None else _env_int("DISPLAY_DC_PIN", 74)  # PC10
        self.rst_pin = rst_pin if rst_pin is not None else _env_int("DISPLAY_RST_PIN", 71)  # PC7
        self.backlight_pin = (
            backlight_pin if backlight_pin is not None else _env_int("DISPLAY_BACKLIGHT_PIN", 79)  # PC15
        )
        self.gpio_chip = gpio_chip or os.environ.get("GPIO_CHIP", "/dev/gpiochip0")
        self.invert = invert if invert is not None else _env_flag("DISPLAY_INVERT")
        self.rotate_180 = rotate_180 if rotate_180 is not None else _env_flag("DISPLAY_ROTATION_180")
        self.spi = None
        self._lines = None

    def open(self) -> None:
        import spidev

        self._lines = sunxi_gpio.request_outputs(
            [self.dc_pin, self.rst_pin, self.backlight_pin],
            consumer="imagegencam-display",
        )
        self.spi = spidev.SpiDev()
        self.spi.open(self.spi_bus, self.spi_dev)
        self.spi.max_speed_hz = self.speed_hz
        self.spi.mode = 0
        self._reset()
        if self.controller == "ili9341":
            self._init_ili9341()
        else:
            self._init_st7796()

    def _set_line(self, pin: int, high: bool) -> None:
        self._lines.set(pin, high)

    def _reset(self) -> None:
        self._set_line(self.rst_pin, True)
        time.sleep(0.01)
        self._set_line(self.rst_pin, False)
        time.sleep(0.01)
        self._set_line(self.rst_pin, True)
        time.sleep(0.12)

    def _cmd(self, command: int, data: bytes = b"") -> None:
        self._set_line(self.dc_pin, False)
        self.spi.writebytes([command])
        if data:
            self._set_line(self.dc_pin, True)
            self.spi.writebytes(list(data))

    def _madctl(self) -> bytes:
        # MV|BGR landscape; MY|MX added for 180deg mounts. Same values work
        # for both controllers (both default to BGR panel wiring).
        return b"\x28" if self.rotate_180 else b"\xe8"

    def _init_st7796(self) -> None:
        self._cmd(0x01)  # SWRESET
        time.sleep(0.12)
        self._cmd(0x11)  # SLPOUT
        time.sleep(0.12)
        self._cmd(0xF0, b"\xc3")  # CSCON unlock part 1
        self._cmd(0xF0, b"\x96")  # CSCON unlock part 2
        self._cmd(0x36, self._madctl())
        self._cmd(0x3A, b"\x55")  # COLMOD: 16-bit RGB565
        self._cmd(0xB4, b"\x01")  # display inversion control
        self._cmd(0xC1, b"\x06")  # power control 2
        self._cmd(0xC2, b"\xa7")  # power control 3
        self._cmd(0xC5, b"\x18")  # VCOM control
        self._cmd(0xE8, b"\x40\x8a\x00\x00\x29\x19\xa5\x33")  # display output ctrl adjust
        self._cmd(
            0xE0,
            b"\xf0\x09\x0b\x06\x04\x15\x2f\x54\x42\x3c\x17\x14\x18\x1b",
        )  # positive gamma
        self._cmd(
            0xE1,
            b"\xf0\x09\x0b\x06\x04\x03\x2d\x43\x42\x3b\x16\x14\x17\x1b",
        )  # negative gamma
        self._cmd(0xF0, b"\x3c")  # CSCON lock part 1
        self._cmd(0xF0, b"\x69")  # CSCON lock part 2
        self._cmd(0x21 if self.invert else 0x20)  # INVON / INVOFF
        self._cmd(0x29)  # DISPON
        time.sleep(0.02)

    def _init_ili9341(self) -> None:
        self._cmd(0x01)  # SWRESET
        time.sleep(0.15)
        self._cmd(0xCB, b"\x39\x2c\x00\x34\x02")  # power control A
        self._cmd(0xCF, b"\x00\xc1\x30")  # power control B
        self._cmd(0xE8, b"\x85\x00\x78")  # driver timing control A
        self._cmd(0xEA, b"\x00\x00")  # driver timing control B
        self._cmd(0xED, b"\x64\x03\x12\x81")  # power on sequence control
        self._cmd(0xF7, b"\x20")  # pump ratio control
        self._cmd(0xC0, b"\x23")  # power control 1
        self._cmd(0xC1, b"\x10")  # power control 2
        self._cmd(0xC5, b"\x3e\x28")  # VCOM control 1
        self._cmd(0xC7, b"\x86")  # VCOM control 2
        self._cmd(0x36, self._madctl())
        self._cmd(0x3A, b"\x55")  # COLMOD: 16-bit RGB565
        self._cmd(0xB1, b"\x00\x18")  # frame rate control
        self._cmd(0xB6, b"\x08\x82\x27")  # display function control
        self._cmd(0xF2, b"\x00")  # disable 3-gamma
        self._cmd(0x26, b"\x01")  # gamma curve 1
        self._cmd(
            0xE0,
            b"\x0f\x31\x2b\x0c\x0e\x08\x4e\xf1\x37\x07\x10\x03\x0e\x09\x00",
        )  # positive gamma
        self._cmd(
            0xE1,
            b"\x00\x0e\x14\x03\x11\x07\x31\xc1\x48\x08\x0f\x0c\x31\x36\x0f",
        )  # negative gamma
        self._cmd(0x11)  # SLPOUT
        time.sleep(0.12)
        self._cmd(0x21 if self.invert else 0x20)  # INVON / INVOFF
        self._cmd(0x29)  # DISPON
        time.sleep(0.02)

    @staticmethod
    def to_rgb565_bytes(image) -> bytes:
        """Convert a PIL RGB image to big-endian RGB565 bytes."""
        arr = np.asarray(image.convert("RGB"), dtype=np.uint16)
        rgb565 = ((arr[..., 0] & 0xF8) << 8) | ((arr[..., 1] & 0xFC) << 3) | (arr[..., 2] >> 3)
        return rgb565.byteswap().tobytes()

    def show(self, image) -> None:
        if image.size != (self.width, self.height):
            image = image.resize((self.width, self.height))
        buf = self.to_rgb565_bytes(image)
        self._cmd(0x2A, b"\x00\x00" + (self.width - 1).to_bytes(2, "big"))  # CASET
        self._cmd(0x2B, b"\x00\x00" + (self.height - 1).to_bytes(2, "big"))  # RASET
        self._cmd(0x2C)  # RAMWR
        self._set_line(self.dc_pin, True)
        for offset in range(0, len(buf), _SPIDEV_CHUNK_BYTES):
            self.spi.writebytes2(buf[offset : offset + _SPIDEV_CHUNK_BYTES])

    def set_backlight(self, on: bool) -> None:
        self._set_line(self.backlight_pin, bool(on))

    def close(self) -> None:
        if self.spi is not None:
            try:
                self.spi.close()
            except Exception:
                pass
            self.spi = None
        if self._lines is not None:
            try:
                self._lines.release()
            except Exception:
                pass
            self._lines = None
