#!/usr/bin/env python3
"""Enable the H616 internal pull-ups on the button lines, as root.

This is the ONLY part of the boot sequence that needs root: the pull-up bits
live in the pin-controller registers, reachable only through /dev/mem. Keeping
it in its own tiny script means the boot splash -- which needs nothing but the
spi and gpio groups -- can run as the unprivileged service user instead.

install_service.sh copies this to /usr/local/lib/imagegencam/ owned by root, so
root never executes a file the service user can write. Stdlib only, so it runs
under the system interpreter and needs no virtualenv.
"""

from __future__ import annotations

import mmap
import os
import sys

H616_PIO_BASE = 0x0300B000
_BANK_STRIDE = 0x24
_PULL_REG_OFFSET = 0x1C
_PULL_UP = 0b01

DEFAULT_LINES = (73, 70, 69, 72, 78)  # shutter, down, up, album, prompt


def pull_register(line: int) -> tuple[int, int]:
    bank, pin = divmod(line, 32)
    register = bank * _BANK_STRIDE + _PULL_REG_OFFSET + 4 * (pin // 16)
    return register, 2 * (pin % 16)


def button_lines() -> list[int]:
    names = (
        "BUTTON_SHUTTER_PIN",
        "BUTTON_UI_DOWN_PIN",
        "BUTTON_UI_UP_PIN",
        "BUTTON_UI_ALBUM_PIN",
        "BUTTON_UI_PROMPT_PIN",
    )
    lines = []
    for name, fallback in zip(names, DEFAULT_LINES):
        try:
            lines.append(int(os.environ.get(name, str(fallback))))
        except ValueError:
            lines.append(fallback)
    return lines


def main() -> int:
    if os.environ.get("SUNXI_SET_PULLUPS", "1").strip().lower() in {"0", "false", "no", "off"}:
        return 0
    lines = button_lines()
    try:
        fd = os.open("/dev/mem", os.O_RDWR | os.O_SYNC)
    except OSError as exc:
        # Not fatal: external 10k pull-ups to 3.3V do the same job in hardware.
        print(f"pull-ups skipped, cannot open /dev/mem: {exc}", file=sys.stderr)
        return 0
    try:
        mem = mmap.mmap(fd, mmap.PAGESIZE, offset=H616_PIO_BASE)
        try:
            for line in lines:
                register, shift = pull_register(line)
                value = int.from_bytes(mem[register : register + 4], "little")
                value = (value & ~(0b11 << shift)) | (_PULL_UP << shift)
                mem[register : register + 4] = value.to_bytes(4, "little")
        finally:
            mem.close()
    except (OSError, ValueError) as exc:
        print(f"pull-ups failed: {exc}", file=sys.stderr)
        return 0
    finally:
        os.close(fd)
    print(f"enabled internal pull-ups on lines {lines}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
