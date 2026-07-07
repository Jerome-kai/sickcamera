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


class ImportSurfaceTests(unittest.TestCase):
    def test_opi_hw_importable_without_hardware_libraries(self) -> None:
        # gpiod/spidev/cv2 must only be required at device-open time so the
        # test suite runs on machines without the hardware stack.
        from imagegencam import opi_hw

        self.assertTrue(hasattr(opi_hw.DisplayHATMini, "BUTTON_SHUTTER"))
        self.assertIs(opi_hw.Picamera2, opi_hw.UsbCamera)


if __name__ == "__main__":
    unittest.main()
