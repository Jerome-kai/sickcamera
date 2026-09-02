#!/usr/bin/env python3
"""Measure what the SPI display can actually sustain, and where the limit is.

Reports three numbers per clock rate: the theoretical bit time, the measured
frame time, and the gap between them. A large gap means transfer overhead
(raise the spidev bufsiz); a small gap means you are at the wire limit (raise
the clock, or accept the rate).

Run with the service stopped, or the two fight over the SPI bus and GPIO:

    sudo systemctl stop imagegencam
    ./scripts/display_benchmark.py
    sudo systemctl start imagegencam
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from PIL import Image  # noqa: E402

from imagegencam.config import load_env_file  # noqa: E402

load_env_file(PROJECT_ROOT / ".env")

from imagegencam.st7796 import ST7796  # noqa: E402

FRAMES = 30
CLOCKS = [40_000_000, 50_000_000, 62_500_000]


def read_bufsiz() -> int | None:
    for path in (
        "/sys/module/spidev/parameters/bufsiz",
        "/sys/module/spi_bcm2835/parameters/bufsiz",
    ):
        try:
            return int(Path(path).read_text().strip())
        except (OSError, ValueError):
            continue
    return None


def build_frames(width: int, height: int) -> list[Image.Image]:
    """Two very different frames, alternated, so nothing can cache a no-op."""
    a = Image.new("RGB", (width, height))
    a.putdata([((x * 5) % 256, (x * 11) % 256, (x * 17) % 256) for x in range(width * height)])
    b = Image.new("RGB", (width, height))
    b.putdata([((x * 3) % 256, 255 - (x * 7) % 256, (x * 23) % 256) for x in range(width * height)])
    return [a, b]


def main() -> int:
    bufsiz = read_bufsiz()
    print(f"spidev bufsiz : {bufsiz if bufsiz else 'unknown'} bytes")
    if bufsiz:
        print(f"                -> {(-(-307200 // bufsiz))} transfers per 480x320 frame")
    print(f"DISPLAY_SPI_HZ: {os.environ.get('DISPLAY_SPI_HZ', '40000000')} (from .env)")
    print()

    requested = [int(value) for value in sys.argv[1:]] or CLOCKS
    print(f"{'clock':>10}  {'theory':>8}  {'measured':>9}  {'overhead':>9}  {'fps':>6}")
    print("-" * 50)

    for clock in requested:
        panel = ST7796(speed_hz=clock)
        try:
            panel.open()
            frames = build_frames(panel.width, panel.height)
            payload = panel.width * panel.height * 2
            theory_ms = payload * 8 / clock * 1000

            panel.show(frames[0])  # warm up
            started = time.perf_counter()
            for index in range(FRAMES):
                panel.show(frames[index % 2])
            measured_ms = (time.perf_counter() - started) / FRAMES * 1000

            overhead = measured_ms - theory_ms
            print(
                f"{clock/1e6:>8.1f}M  {theory_ms:>7.1f}ms  {measured_ms:>8.1f}ms  "
                f"{overhead:>8.1f}ms  {1000/measured_ms:>5.1f}"
            )
        except Exception as exc:
            print(f"{clock/1e6:>8.1f}M  failed: {exc}")
        finally:
            try:
                panel.close()
            except Exception:
                pass

    print()
    print("Reading the overhead column:")
    print("  small (<10ms)  -> you are at the wire limit; raise the clock for more")
    print("  large (>20ms)  -> transfer overhead; raise spidev bufsiz first")
    print("                    (see HARDWARE.md 'Preview frame rate')")
    print()
    print("Torn, speckled or colour-shifted output at a higher clock means the")
    print("wiring cannot carry it. Step back down -- shorter, twisted SPI leads")
    print("or a proper PCB are what buy you the higher rates.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
