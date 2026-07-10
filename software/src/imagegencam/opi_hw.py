"""Orange Pi Zero 2 hardware layer.

Presents the same class surface the controller uses from the Raspberry Pi
stack — a ``DisplayHATMini``-compatible display/button object and a
``Picamera2``-compatible USB camera — so ``controller.py`` only needs an
import selector to run on either board.

Wiring (see HARDWARE.md): ST7796 480x320 panel on SPI1, five mechanical
switches on port-C lines with internal pull-ups (active-low to ground).
"""

from __future__ import annotations

import os
import time
from threading import Timer

from PIL import Image

from . import sunxi_gpio
from .st7796 import ST7796


def _env_int(name: str, default: int) -> int:
    return int(os.environ.get(name, str(default)))


class DisplayHATMini:
    """ST7796 + GPIO buttons behind the Pimoroni DisplayHATMini interface."""

    BUTTON_A = _env_int("BUTTON_UI_DOWN_PIN", 70)  # PC6, header pin 11
    BUTTON_B = _env_int("BUTTON_UI_UP_PIN", 69)  # PC5, header pin 13
    BUTTON_X = _env_int("BUTTON_UI_ALBUM_PIN", 72)  # PC8, header pin 15
    BUTTON_Y = _env_int("BUTTON_UI_PROMPT_PIN", 78)  # PC14, header pin 18
    BUTTON_SHUTTER = _env_int("BUTTON_SHUTTER_PIN", 73)  # PC9, header pin 7

    def __init__(self, buffer: Image.Image, backlight_pwm: bool = True) -> None:
        self._buffer = buffer
        self._fit_mode = os.environ.get("DISPLAY_FIT", "pillarbox").strip().lower()
        self._panel = ST7796()
        self._canvas = Image.new("RGB", (self._panel.width, self._panel.height))
        self._panel.open()

        button_pins = [
            self.BUTTON_A,
            self.BUTTON_B,
            self.BUTTON_X,
            self.BUTTON_Y,
            self.BUTTON_SHUTTER,
        ]
        self._buttons = sunxi_gpio.request_inputs(
            button_pins, consumer="imagegencam-buttons", pull_up=True
        )

    def display(self) -> None:
        panel_width, panel_height = self._panel.width, self._panel.height
        if self._buffer.size == (panel_width, panel_height):
            # Pixel-perfect panel (e.g. 320x240 ILI9341): no scaling needed.
            frame = self._buffer
        elif self._fit_mode == "stretch":
            frame = self._buffer.resize((panel_width, panel_height), Image.Resampling.BILINEAR)
        else:
            # Pillarbox: 4:3 buffer scaled to panel height, centered on black.
            scaled_width = panel_height * self._buffer.width // self._buffer.height
            scaled = self._buffer.resize((scaled_width, panel_height), Image.Resampling.BILINEAR)
            self._canvas.paste(scaled, ((panel_width - scaled_width) // 2, 0))
            frame = self._canvas
        self._panel.show(frame)

    def read_button(self, pin: int) -> bool:
        # Switches pull the line to ground when pressed.
        return not self._buttons.is_high(pin)

    def on_button_pressed(self, callback) -> None:
        # Force the controller onto its existing polling fallback path.
        raise RuntimeError("GPIO edge callbacks not supported on this board; using polling")

    def set_backlight(self, value: float) -> None:
        self._panel.set_backlight(value > 0)

    def set_led(self, r: float, g: float, b: float) -> None:
        # No RGB LED on this build.
        pass


class HotShoe:
    """Flash trigger: hot-shoe sync contact switched by a MOC3021 opto-triac.

    GPIO --330R--> pin 1 (LED anode), pin 2 -> GND. Output side: pin 6 -> shoe
    center contact, pin 4 -> shoe metal frame. The triac is polarity-agnostic
    and isolates the board from the flash's sync voltage (old flashes can put
    100V+ on the shoe). Once triggered it latches until the flash's own sync
    current stops, so the xenon burst fires at the leading edge of the pulse.
    """

    def __init__(self) -> None:
        self.pin = _env_int("HOTSHOE_PIN", 75)  # PC11, header pin 12
        self.pulse_seconds = max(1, _env_int("HOTSHOE_PULSE_MS", 200)) / 1000.0
        self._outputs = sunxi_gpio.request_outputs([self.pin], consumer="imagegencam-hotshoe")
        self._off_timer: Timer | None = None

    def fire(self) -> None:
        """Assert the trigger now; release it after the pulse in the background.

        The long default pulse (200 ms) is deliberate: a xenon flash latches
        the triac and fires instantly, while LED "flashes" that only light
        while the contact is closed stay on across the next camera frame.
        """
        if self._off_timer is not None:
            self._off_timer.cancel()
        self._outputs.set(self.pin, True)
        self._off_timer = Timer(self.pulse_seconds, self._outputs.set, args=(self.pin, False))
        self._off_timer.daemon = True
        self._off_timer.start()

    def close(self) -> None:
        if self._off_timer is not None:
            self._off_timer.cancel()
            self._off_timer = None
        try:
            self._outputs.set(self.pin, False)
        finally:
            release = getattr(self._outputs, "release", None)
            if release:
                release()


class UsbCamera:
    """V4L2 USB camera behind the small Picamera2 surface the controller uses."""

    def __init__(self) -> None:
        self._device_index = _env_int("CAMERA_DEVICE", 0)
        self._size = (480, 360)
        self._frame_rate = 10.0
        self._capture = None
        self._last_frame_at = 0.0

    def create_preview_configuration(self, main=None, controls=None, buffer_count=2) -> dict:
        return {"main": main or {}, "controls": controls or {}}

    def configure(self, config: dict) -> None:
        size = config.get("main", {}).get("size")
        if size:
            self._size = (int(size[0]), int(size[1]))
        frame_rate = config.get("controls", {}).get("FrameRate")
        if frame_rate:
            self._frame_rate = float(frame_rate)

    def start(self) -> None:
        import cv2

        self._cv2 = cv2
        capture = cv2.VideoCapture(self._device_index, cv2.CAP_V4L2)
        if not capture.isOpened():
            raise RuntimeError(f"USB camera /dev/video{self._device_index} failed to open")
        capture.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
        capture.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        capture.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        capture.set(cv2.CAP_PROP_FPS, self._frame_rate)
        capture.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        ok, _ = capture.read()
        if not ok:
            capture.release()
            raise RuntimeError(f"USB camera /dev/video{self._device_index} returned no frames")
        self._capture = capture

    def capture_array(self, name: str = "main"):
        # Throttle to the configured frame rate; cameras that ignore
        # CAP_PROP_FPS would otherwise spin this loop at full USB speed.
        min_interval = 1.0 / self._frame_rate if self._frame_rate > 0 else 0.0
        elapsed = time.monotonic() - self._last_frame_at
        if elapsed < min_interval:
            time.sleep(min_interval - elapsed)

        frame = None
        for _ in range(3):
            ok, frame = self._capture.read()
            if ok and frame is not None:
                break
        else:
            raise RuntimeError("USB camera read failed")
        self._last_frame_at = time.monotonic()

        if (frame.shape[1], frame.shape[0]) != self._size:
            frame = self._cv2.resize(frame, self._size, interpolation=self._cv2.INTER_AREA)
        # BGR, same channel order picamera2 delivers for "RGB888"; the
        # controller's CAMERA_SWAP_RED_BLUE handling applies unchanged.
        return frame

    def stop(self) -> None:
        if self._capture is not None:
            self._capture.release()
            self._capture = None


# Alias so the controller's `Picamera2` import site works unmodified.
Picamera2 = UsbCamera
