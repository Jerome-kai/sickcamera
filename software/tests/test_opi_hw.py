from __future__ import annotations

import unittest

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


if __name__ == "__main__":
    unittest.main()
