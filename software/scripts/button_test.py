#!/usr/bin/env python3
"""First-boot button check for the five switches.

Run over SSH after wiring the buttons (see HARDWARE.md):

    sudo .venv/bin/python3 scripts/button_test.py

Polls all five lines and prints a line every time a button goes down or up,
so you can press each switch and confirm the wiring. A button that reads
PRESSED before you touch it is shorted or missing its pull-up; one that
never fires is on the wrong line or has a cold joint. Ctrl+C exits.
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from imagegencam import sunxi_gpio  # noqa: E402
from imagegencam.opi_hw import DisplayHATMini  # noqa: E402


def main() -> int:
    buttons = {
        "SHUTTER (MX)": DisplayHATMini.BUTTON_SHUTTER,
        "UP (big top)": DisplayHATMini.BUTTON_B,
        "DOWN (big bottom)": DisplayHATMini.BUTTON_A,
        "ALBUM (small left)": DisplayHATMini.BUTTON_X,
        "PROMPT (small right)": DisplayHATMini.BUTTON_Y,
    }
    lines = sunxi_gpio.request_inputs(list(buttons.values()), consumer="button-test", pull_up=True)
    print("Watching buttons (active-low). Press each one; Ctrl+C exits.")
    for name, pin in buttons.items():
        state = "PRESSED (check wiring!)" if not lines.is_high(pin) else "released"
        print(f"  line {pin:3d}  {name}: {state}")

    was_pressed = {pin: not lines.is_high(pin) for pin in buttons.values()}
    try:
        while True:
            for name, pin in buttons.items():
                pressed = not lines.is_high(pin)
                if pressed != was_pressed[pin]:
                    was_pressed[pin] = pressed
                    print(f"line {pin:3d}  {name}: {'DOWN' if pressed else 'up'}")
            time.sleep(0.02)
    except KeyboardInterrupt:
        pass
    finally:
        release = getattr(lines, "release", None)
        if release:
            release()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
