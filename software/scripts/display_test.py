#!/usr/bin/env python3
"""First-boot display check for the ST7796 panel.

Run over SSH after wiring the panel (see HARDWARE.md):

    .venv/bin/python3 scripts/display_test.py

Shows color bars plus labeled corner markers so you can verify RGB order,
orientation, and inversion, then blinks the backlight. Adjust DISPLAY_INVERT /
DISPLAY_ROTATION_180 in .env if colors or orientation look wrong.
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from imagegencam.st7796 import ST7796  # noqa: E402


def build_test_image(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(image)

    bars = [
        ("RED", (255, 0, 0)),
        ("GREEN", (0, 255, 0)),
        ("BLUE", (0, 0, 255)),
        ("WHITE", (255, 255, 255)),
        ("BLACK", (0, 0, 0)),
        ("YELLOW", (255, 255, 0)),
    ]
    bar_width = width // len(bars)
    for index, (label, color) in enumerate(bars):
        x0 = index * bar_width
        draw.rectangle((x0, 0, x0 + bar_width - 1, height - 1), fill=color)
        text_fill = (0, 0, 0) if color != (0, 0, 0) else (255, 255, 255)
        draw.text((x0 + 6, height // 2), label, fill=text_fill)

    draw.text((6, 6), "TOP-LEFT", fill=(255, 255, 255))
    draw.text((width - 80, height - 20), "BOTTOM-RIGHT", fill=(255, 255, 255))
    return image


def main() -> int:
    panel = ST7796()
    print(f"Panel: {panel.controller} {panel.width}x{panel.height} "
          f"(DISPLAY_CONTROLLER to change)")
    panel.open()
    panel.set_backlight(True)
    panel.show(build_test_image(panel.width, panel.height))
    print("Color bars drawn: RED GREEN BLUE WHITE BLACK YELLOW, left to right.")
    print("Check: labels readable (orientation), colors correct (RGB order/inversion).")
    print("Blinking backlight 3x...")
    for _ in range(3):
        time.sleep(0.7)
        panel.set_backlight(False)
        time.sleep(0.3)
        panel.set_backlight(True)
    print("Done. Panel stays on; Ctrl+C exits.")
    try:
        time.sleep(30)
    except KeyboardInterrupt:
        pass
    panel.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
