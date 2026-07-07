#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps


WIDTH = 320
HEIGHT = 240
PROJECT_ROOT = Path(__file__).resolve().parents[1]
BOOT_IMAGE_PATH = PROJECT_ROOT / "assets" / "boot-screen.png"
FONT_PATH = PROJECT_ROOT / "assets" / "fonts" / "Orbitron-Regular.ttf"


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    try:
        return ImageFont.truetype(str(FONT_PATH), size=size)
    except OSError:
        return ImageFont.load_default()


def build_fallback_screen() -> Image.Image:
    screen = Image.new("RGB", (WIDTH, HEIGHT), (244, 246, 248))
    draw = ImageDraw.Draw(screen)
    title_font = load_font(28)
    body_font = load_font(16)
    draw.text((18, 80), "ImageGenCam v2.0", font=title_font, fill=(18, 18, 18))
    draw.text((18, 118), "Booting up...", font=body_font, fill=(48, 48, 48))
    return screen


def load_boot_image() -> Image.Image:
    if not BOOT_IMAGE_PATH.exists():
        return build_fallback_screen()
    try:
        with Image.open(BOOT_IMAGE_PATH) as source:
            return ImageOps.fit(
                source.convert("RGB"),
                (WIDTH, HEIGHT),
                method=Image.Resampling.LANCZOS,
            )
    except Exception:
        return build_fallback_screen()


def main() -> int:
    sys.path.insert(0, str(PROJECT_ROOT / "src"))
    if os.environ.get("IMAGEGENCAM_HW", "opi").strip().lower() == "pi":
        import displayhatmini
    else:
        from imagegencam import opi_hw as displayhatmini

    image = load_boot_image()
    buffer = Image.new("RGB", (WIDTH, HEIGHT))
    buffer.paste(image)
    display = displayhatmini.DisplayHATMini(buffer, backlight_pwm=True)
    display.set_backlight(1.0)
    display.set_led(0.0, 0.0, 0.0)
    display.display()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"boot splash failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
