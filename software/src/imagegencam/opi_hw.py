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

from PIL import Image

from .st7796 import ST7796, WIDTH as PANEL_WIDTH, HEIGHT as PANEL_HEIGHT


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
        import gpiod
        from gpiod.line import Bias, Direction, Value

        self._Value = Value
        self._buffer = buffer
        self._fit_mode = os.environ.get("DISPLAY_FIT", "pillarbox").strip().lower()
        self._canvas = Image.new("RGB", (PANEL_WIDTH, PANEL_HEIGHT))
        self._panel = ST7796()
        self._panel.open()

        button_pins = (
            self.BUTTON_A,
            self.BUTTON_B,
            self.BUTTON_X,
            self.BUTTON_Y,
            self.BUTTON_SHUTTER,
        )
        self._buttons = gpiod.request_lines(
            os.environ.get("GPIO_CHIP", "/dev/gpiochip0"),
            consumer="imagegencam-buttons",
            config={
                button_pins: gpiod.LineSettings(direction=Direction.INPUT, bias=Bias.PULL_UP)
            },
        )

    def display(self) -> None:
        if self._fit_mode == "stretch":
            frame = self._buffer.resize((PANEL_WIDTH, PANEL_HEIGHT), Image.Resampling.BILINEAR)
        else:
            # Pillarbox: 4:3 buffer scaled to panel height, centered on black.
            scaled_width = PANEL_HEIGHT * self._buffer.width // self._buffer.height
            scaled = self._buffer.resize((scaled_width, PANEL_HEIGHT), Image.Resampling.BILINEAR)
            self._canvas.paste(scaled, ((PANEL_WIDTH - scaled_width) // 2, 0))
            frame = self._canvas
        self._panel.show(frame)

    def read_button(self, pin: int) -> bool:
        # Switches pull the line to ground when pressed.
        return self._buttons.get_value(pin) == self._Value.INACTIVE

    def on_button_pressed(self, callback) -> None:
        # Force the controller onto its existing polling fallback path.
        raise RuntimeError("GPIO edge callbacks not supported on this board; using polling")

    def set_backlight(self, value: float) -> None:
        self._panel.set_backlight(value > 0)

    def set_led(self, r: float, g: float, b: float) -> None:
        # No RGB LED on this build.
        pass


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
