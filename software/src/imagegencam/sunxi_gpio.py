"""GPIO backends for the Orange Pi Zero 2 port.

Two backends behind one small interface:

- ``gpiod``: libgpiod v2 character device (kernel 5.10+; modern Orange Pi /
  Armbian images). Supports internal pull-up bias natively.
- ``sysfs``: /sys/class/gpio (works on the legacy 4.9 BSP kernel shipped with
  the official "Orange Pi Focal" image). sysfs cannot set bias, so pull-ups
  are written directly to the H616 pin-controller registers via /dev/mem
  (root required) — or fit external 10k resistors to 3.3V.

Line numbering is the sunxi convention on both backends:
``port_index * 32 + pin`` (PA=0, PB=1, PC=2, ... PH=7, PI=8), e.g. PC9 = 73.

Select with ``GPIO_BACKEND=auto|gpiod|sysfs`` (default auto: try gpiod, fall
back to sysfs).
"""

from __future__ import annotations

import logging
import mmap
import os
import time


logger = logging.getLogger(__name__)

H616_PIO_BASE = 0x0300B000
_BANK_STRIDE = 0x24
_PULL_REG_OFFSET = 0x1C
_PULL_UP = 0b01

_SYSFS_ROOT = "/sys/class/gpio"


def pull_register(line: int) -> tuple[int, int]:
    """Return (register byte offset from PIO base, bit shift) for a line's
    2-bit pull-control field in the H616 pin controller."""
    bank, pin = divmod(line, 32)
    register = bank * _BANK_STRIDE + _PULL_REG_OFFSET + 4 * (pin // 16)
    shift = 2 * (pin % 16)
    return register, shift


def set_pullups(lines: list[int]) -> bool:
    """Enable internal pull-ups by writing the H616 PIO registers via /dev/mem.

    Returns True on success. Requires root; on failure the caller should fall
    back to external pull-up resistors.
    """
    if os.environ.get("SUNXI_SET_PULLUPS", "1").strip().lower() in {"0", "false", "no", "off"}:
        return False
    try:
        fd = os.open("/dev/mem", os.O_RDWR | os.O_SYNC)
    except OSError as exc:
        logger.warning(
            "Cannot open /dev/mem to set pull-ups (%s). Run as root, or fit "
            "external 10k pull-up resistors from each button pin to 3.3V.",
            exc,
        )
        return False
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
        logger.warning("Failed to write H616 pull-up registers: %s", exc)
        return False
    finally:
        os.close(fd)
    logger.info("Enabled internal pull-ups on lines %s", lines)
    return True


class _SysfsLines:
    """Shared sysfs implementation with persistent value-file descriptors."""

    def __init__(self, lines: list[int], direction: str) -> None:
        self._base = int(os.environ.get("GPIO_SYSFS_BASE", "0"))
        self._fds: dict[int, int] = {}
        self._exported: list[int] = []
        for line in lines:
            number = self._base + line
            gpio_dir = f"{_SYSFS_ROOT}/gpio{number}"
            if not os.path.isdir(gpio_dir):
                with open(f"{_SYSFS_ROOT}/export", "w") as export:
                    export.write(str(number))
                self._exported.append(number)
                # The direction file can take a moment to appear after export.
                for _ in range(50):
                    if os.path.isdir(gpio_dir):
                        break
                    time.sleep(0.01)
            with open(f"{gpio_dir}/direction", "w") as direction_file:
                direction_file.write(direction)
            flags = os.O_RDONLY if direction == "in" else os.O_WRONLY
            self._fds[line] = os.open(f"{gpio_dir}/value", flags)

    def release(self) -> None:
        for fd in self._fds.values():
            try:
                os.close(fd)
            except OSError:
                pass
        self._fds = {}
        for number in self._exported:
            try:
                with open(f"{_SYSFS_ROOT}/unexport", "w") as unexport:
                    unexport.write(str(number))
            except OSError:
                pass
        self._exported = []


class SysfsOutputs(_SysfsLines):
    def __init__(self, lines: list[int]) -> None:
        super().__init__(lines, "low")

    def set(self, line: int, high: bool) -> None:
        os.pwrite(self._fds[line], b"1" if high else b"0", 0)


class SysfsInputs(_SysfsLines):
    def __init__(self, lines: list[int], pull_up: bool = True) -> None:
        if pull_up:
            set_pullups(lines)
        super().__init__(lines, "in")

    def is_high(self, line: int) -> bool:
        return os.pread(self._fds[line], 1, 0) == b"1"


class GpiodOutputs:
    def __init__(self, lines: list[int], consumer: str) -> None:
        import gpiod
        from gpiod.line import Direction, Value

        self._Value = Value
        self._request = gpiod.request_lines(
            os.environ.get("GPIO_CHIP", "/dev/gpiochip0"),
            consumer=consumer,
            config={
                tuple(lines): gpiod.LineSettings(
                    direction=Direction.OUTPUT, output_value=Value.INACTIVE
                )
            },
        )

    def set(self, line: int, high: bool) -> None:
        self._request.set_value(line, self._Value.ACTIVE if high else self._Value.INACTIVE)

    def release(self) -> None:
        self._request.release()


class GpiodInputs:
    def __init__(self, lines: list[int], consumer: str, pull_up: bool = True) -> None:
        import gpiod
        from gpiod.line import Bias, Direction, Value

        self._Value = Value
        settings = gpiod.LineSettings(
            direction=Direction.INPUT,
            bias=Bias.PULL_UP if pull_up else Bias.AS_IS,
        )
        self._request = gpiod.request_lines(
            os.environ.get("GPIO_CHIP", "/dev/gpiochip0"),
            consumer=consumer,
            config={tuple(lines): settings},
        )

    def is_high(self, line: int) -> bool:
        return self._request.get_value(line) == self._Value.ACTIVE

    def release(self) -> None:
        self._request.release()


def _backend_order() -> list[str]:
    choice = os.environ.get("GPIO_BACKEND", "auto").strip().lower()
    if choice in {"gpiod", "sysfs"}:
        return [choice]
    return ["gpiod", "sysfs"]


def request_outputs(lines: list[int], consumer: str = "imagegencam"):
    last_error: Exception | None = None
    for backend in _backend_order():
        try:
            if backend == "gpiod":
                return GpiodOutputs(lines, consumer)
            return SysfsOutputs(lines)
        except (ImportError, OSError, NotImplementedError) as exc:
            logger.info("GPIO output backend %s unavailable: %s", backend, exc)
            last_error = exc
    raise RuntimeError(f"No usable GPIO backend for outputs {lines}: {last_error}")


def request_inputs(lines: list[int], consumer: str = "imagegencam", pull_up: bool = True):
    last_error: Exception | None = None
    for backend in _backend_order():
        try:
            if backend == "gpiod":
                return GpiodInputs(lines, consumer, pull_up=pull_up)
            return SysfsInputs(lines, pull_up=pull_up)
        except (ImportError, OSError, NotImplementedError) as exc:
            logger.info("GPIO input backend %s unavailable: %s", backend, exc)
            last_error = exc
    raise RuntimeError(f"No usable GPIO backend for inputs {lines}: {last_error}")
