#!/usr/bin/env python3
"""Hot shoe flash trigger check (MOC3021 on PC11 — see HARDWARE.md).

Run over SSH with a flash mounted in the shoe (or a multimeter in continuity
mode across the center contact and frame):

    sudo .venv/bin/python3 scripts/hotshoe_test.py [pulses]

Fires the trigger once per second for `pulses` shots (default 5). A xenon
flash should pop on every pulse; on a meter the shoe contacts read closed
for HOTSHOE_PULSE_MS each time. If nothing happens, check the 330R resistor
and MOC3021 pin 1/2 orientation — see the MOC3023 fallback note in
HARDWARE.md if the LED current is marginal.
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from imagegencam.opi_hw import HotShoe  # noqa: E402


def main() -> int:
    pulses = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    shoe = HotShoe()
    print(f"Hot shoe on line {shoe.pin}, pulse {shoe.pulse_seconds * 1000:.0f} ms.")
    try:
        for shot in range(1, pulses + 1):
            print(f"Fire {shot}/{pulses}")
            shoe.fire()
            time.sleep(max(1.0, shoe.pulse_seconds + 0.2))
    except KeyboardInterrupt:
        pass
    finally:
        shoe.close()
    print("Done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
