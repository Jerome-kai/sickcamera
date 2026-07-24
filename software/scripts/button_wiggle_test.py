#!/usr/bin/env python3
"""Loose-connection hunter for the five buttons.

Run over SSH, then wiggle each wire and solder joint:

    sudo .venv/bin/python3 scripts/button_wiggle_test.py

Shows a live one-line dashboard of all five lines plus a change counter per
button. Reading it:

- [.] = released, [#] = pressed. A button should sit at [.] untouched and
  flip to [#] only while held.
- The count after each name is total transitions. Press-and-release adds 2.
  If a count climbs while you wiggle a WIRE without pressing, that wire or
  joint is flaky — you found the fault.
- A button stuck at [#] is shorted to ground or wired across an internally
  joined leg pair (use the diagonal legs).
- A button that never flips is open circuit: wrong header pin, cold joint,
  or broken jumper.

Ctrl+C exits and prints a summary.
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from imagegencam.config import load_env_file  # noqa: E402

load_env_file(PROJECT_ROOT / ".env")

from imagegencam import sunxi_gpio  # noqa: E402
from imagegencam.opi_hw import DisplayHATMini  # noqa: E402


def main() -> int:
    buttons = {
        "SHUTTER": DisplayHATMini.BUTTON_SHUTTER,
        "UP": DisplayHATMini.BUTTON_B,
        "DOWN": DisplayHATMini.BUTTON_A,
        "ALBUM": DisplayHATMini.BUTTON_X,
        "PROMPT": DisplayHATMini.BUTTON_Y,
    }
    lines = sunxi_gpio.request_inputs(list(buttons.values()), consumer="button-wiggle", pull_up=True)
    print("Wiggle each wire/joint. [.]=released [#]=pressed; counts climb on every change.")
    print("A count that climbs while you wiggle (not press) = flaky connection. Ctrl+C exits.\n")

    state = {name: not lines.is_high(pin) for name, pin in buttons.items()}
    counts = {name: 0 for name in buttons}
    try:
        while True:
            changed = False
            for name, pin in buttons.items():
                pressed = not lines.is_high(pin)
                if pressed != state[name]:
                    state[name] = pressed
                    counts[name] += 1
                    changed = True
            dashboard = "  ".join(
                f"{name}[{'#' if state[name] else '.'}]:{counts[name]}" for name in buttons
            )
            sys.stdout.write("\r" + dashboard + "   ")
            sys.stdout.flush()
            # Poll fast so even a brief flicker from a loose wire registers.
            time.sleep(0.002 if changed else 0.01)
    except KeyboardInterrupt:
        pass
    finally:
        release = getattr(lines, "release", None)
        if release:
            release()

    print("\n\nSummary (transitions per button — press+release = 2):")
    for name, pin in buttons.items():
        note = ""
        if counts[name] == 0:
            note = "  <- never changed: open circuit / wrong pin?"
        elif counts[name] > 40:
            note = "  <- very chatty: flaky wire or bounce-prone joint"
        print(f"  line {pin:3d}  {name}: {counts[name]}{note}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
