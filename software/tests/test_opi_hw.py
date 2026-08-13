from __future__ import annotations

import os
import sys
import time
import types
import unittest
from unittest import mock

from PIL import Image

from imagegencam.st7796 import HEIGHT, ST7796, WIDTH


class RGB565ConversionTests(unittest.TestCase):
    def test_primary_colors_convert_to_expected_words(self) -> None:
        image = Image.new("RGB", (2, 2))
        image.putpixel((0, 0), (255, 0, 0))  # red -> 0xF800
        image.putpixel((1, 0), (0, 255, 0))  # green -> 0x07E0
        image.putpixel((0, 1), (0, 0, 255))  # blue -> 0x001F
        image.putpixel((1, 1), (255, 255, 255))  # white -> 0xFFFF

        buf = ST7796.to_rgb565_bytes(image)

        self.assertEqual(len(buf), 2 * 2 * 2)
        # Big-endian words, row-major order.
        self.assertEqual(buf[0:2], b"\xf8\x00")
        self.assertEqual(buf[2:4], b"\x07\xe0")
        self.assertEqual(buf[4:6], b"\x00\x1f")
        self.assertEqual(buf[6:8], b"\xff\xff")

    def test_full_frame_byte_count(self) -> None:
        image = Image.new("RGB", (WIDTH, HEIGHT))
        self.assertEqual(len(ST7796.to_rgb565_bytes(image)), WIDTH * HEIGHT * 2)


class ShowBlitTests(unittest.TestCase):
    def test_show_sends_full_frame_in_one_writebytes2_call(self) -> None:
        # writebytes2 chunks to the kernel bufsiz internally; a Python-side
        # slice loop costs one ioctl round-trip per 4 KB and halves the frame
        # rate on the H616, so show() must hand over the whole buffer at once.
        panel = ST7796(controller="ili9341")
        panel.spi = mock.Mock()
        panel._lines = mock.Mock()

        panel.show(Image.new("RGB", (panel.width, panel.height)))

        self.assertEqual(panel.spi.writebytes2.call_count, 1)
        (payload,) = panel.spi.writebytes2.call_args[0]
        self.assertEqual(len(payload), panel.width * panel.height * 2)


class PanelControllerTests(unittest.TestCase):
    def test_default_is_st7796_480x320(self) -> None:
        panel = ST7796()
        self.assertEqual(panel.controller, "st7796")
        self.assertEqual((panel.width, panel.height), (WIDTH, HEIGHT))

    def test_ili9341_is_320x240(self) -> None:
        panel = ST7796(controller="ili9341")
        self.assertEqual((panel.width, panel.height), (320, 240))
        with mock.patch.dict(os.environ, {"DISPLAY_CONTROLLER": "ili9341"}):
            self.assertEqual(ST7796().controller, "ili9341")

    def test_unknown_controller_falls_back_to_st7796(self) -> None:
        self.assertEqual(ST7796(controller="nonsense").controller, "st7796")


class SunxiPullRegisterTests(unittest.TestCase):
    def test_pull_register_math(self) -> None:
        from imagegencam.sunxi_gpio import pull_register

        # PC9 (line 73): bank C (2) at 0x48, PULL0 at +0x1C, pin 9 -> bits 18-19.
        self.assertEqual(pull_register(73), (0x64, 18))
        # PC15 (line 79): still PULL0, bits 30-31.
        self.assertEqual(pull_register(79), (0x64, 30))
        # PC16 (line 80) would roll into PULL1.
        self.assertEqual(pull_register(80), (0x68, 0))
        # PH7 (line 231): bank H (7) at 0xFC, PULL0 at 0x118, bits 14-15.
        self.assertEqual(pull_register(231), (0x118, 14))


class ImportSurfaceTests(unittest.TestCase):
    def test_opi_hw_importable_without_hardware_libraries(self) -> None:
        # gpiod/spidev/cv2 must only be required at device-open time so the
        # test suite runs on machines without the hardware stack.
        from imagegencam import opi_hw

        self.assertTrue(hasattr(opi_hw.DisplayHATMini, "BUTTON_SHUTTER"))
        self.assertIs(opi_hw.Picamera2, opi_hw.UsbCamera)


class _FakeCapture:
    """Minimal cv2.VideoCapture stand-in."""

    def __init__(self, opened: bool, frames: bool = True) -> None:
        self._opened = opened
        self._frames = frames
        self.released = False

    def isOpened(self) -> bool:
        return self._opened

    def set(self, *args) -> None:
        return None

    def read(self):
        return (self._frames, object() if self._frames else None)

    def release(self) -> None:
        self.released = True


def _fake_cv2(captures: list) -> types.ModuleType:
    module = types.ModuleType("cv2")
    module.CAP_V4L2 = 200
    for name in ("CAP_PROP_FOURCC", "CAP_PROP_FRAME_WIDTH", "CAP_PROP_FRAME_HEIGHT",
                 "CAP_PROP_FPS", "CAP_PROP_BUFFERSIZE"):
        setattr(module, name, 0)
    module.VideoWriter_fourcc = lambda *args: 0
    module.VideoCapture = lambda index, api=None: captures.pop(0)
    return module


class CameraStartRetryTests(unittest.TestCase):
    """A camera that is still enumerating must not kill the service."""

    def _start(self, captures: list, timeout: str = "5"):
        from imagegencam.opi_hw import UsbCamera

        camera = UsbCamera()
        module = _fake_cv2(captures)
        with mock.patch.dict(sys.modules, {"cv2": module}), \
                mock.patch.dict(os.environ, {"CAMERA_OPEN_TIMEOUT_SECONDS": timeout}), \
                mock.patch("imagegencam.opi_hw.time.sleep", lambda _seconds: None):
            camera.start()
        return camera

    def test_waits_for_a_device_that_appears_late(self) -> None:
        late = _FakeCapture(opened=True)
        camera = self._start([_FakeCapture(opened=False), _FakeCapture(opened=False), late])

        self.assertIs(camera._capture, late)

    def test_retries_a_device_that_opens_but_yields_no_frames(self) -> None:
        good = _FakeCapture(opened=True)
        camera = self._start([_FakeCapture(opened=True, frames=False), good])

        self.assertIs(camera._capture, good)

    def test_gives_up_once_the_window_closes(self) -> None:
        from imagegencam.opi_hw import UsbCamera

        camera = UsbCamera()
        # Always-failing device, with a window that has already expired.
        module = _fake_cv2([_FakeCapture(opened=False) for _ in range(10)])
        with mock.patch.dict(sys.modules, {"cv2": module}), \
                mock.patch.dict(os.environ, {"CAMERA_OPEN_TIMEOUT_SECONDS": "0"}), \
                mock.patch("imagegencam.opi_hw.time.sleep", lambda _seconds: None):
            with self.assertRaises(RuntimeError) as raised:
                camera.start()

        self.assertIn("failed to open", str(raised.exception))

    def test_failed_attempts_release_their_handle(self) -> None:
        first = _FakeCapture(opened=False)
        self._start([first, _FakeCapture(opened=True)])

        self.assertTrue(first.released)


class _FakeOutputs:
    def __init__(self) -> None:
        self.calls: list[tuple[int, bool]] = []
        self.released = False

    def set(self, line: int, high: bool) -> None:
        self.calls.append((line, high))

    def release(self) -> None:
        self.released = True


class HotShoeTests(unittest.TestCase):
    def _make(self, fake: _FakeOutputs, env: dict[str, str]):
        from imagegencam import opi_hw

        with mock.patch.dict(os.environ, env):
            with mock.patch.object(opi_hw.sunxi_gpio, "request_outputs", return_value=fake):
                return opi_hw.HotShoe()

    def test_fire_pulses_configured_pin_high_then_low(self) -> None:
        fake = _FakeOutputs()
        shoe = self._make(fake, {"HOTSHOE_PIN": "75", "HOTSHOE_PULSE_MS": "1"})

        shoe.fire()
        deadline = time.monotonic() + 1.0
        while len(fake.calls) < 2 and time.monotonic() < deadline:
            time.sleep(0.005)

        self.assertEqual(fake.calls[0], (75, True))
        self.assertEqual(fake.calls[1], (75, False))

    def test_close_drives_low_and_releases(self) -> None:
        fake = _FakeOutputs()
        shoe = self._make(fake, {"HOTSHOE_PULSE_MS": "60000"})

        shoe.fire()
        shoe.close()

        self.assertEqual(fake.calls[-1], (75, False))
        self.assertTrue(fake.released)


if __name__ == "__main__":
    unittest.main()
