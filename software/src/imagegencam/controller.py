from __future__ import annotations

"""Physical camera controller.

This module owns the hardware loop: camera frames, Display HAT Mini drawing,
button events, PiSugar shutter events, battery display, and background job
coordination. Pure storage/network helpers live in smaller modules so this file
can stay focused on device state.
"""

import json
import logging
import math
import os
import signal
import shutil
import socket
import subprocess
import time
from dataclasses import dataclass, field
from datetime import datetime
from io import BytesIO
from pathlib import Path
from queue import Empty, Queue
from threading import Lock, Thread
from urllib.parse import quote, unquote

from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageOps
import qrcode

from .config import MagicHistoryStore, PromptStore, SettingsStore
from .job_store import PersistentJobStore
from .openai_client import OpenAIImageEditor, OpenAIImageError, OpenAIMagicPromptPlanner
from .wifi_manager import NetworkManagerWifi, WifiNetwork, WifiRollback


WIDTH = 320
HEIGHT = 240
SIDE_CONTROL_TOP_Y = 64
SIDE_CONTROL_BOTTOM_Y = HEIGHT - 98
PREVIEW_REDRAW_INTERVAL_SECONDS = 1.0 / 8.0
MENU_REDRAW_INTERVAL_SECONDS = 1.0 / 8.0
ALBUM_REDRAW_INTERVAL_SECONDS = 1.0 / 8.0
BATTERY_REFRESH_INTERVAL_SECONDS = 20.0
PISUGAR_POWER_BUTTON_POLL_INTERVAL_SECONDS = 0.02
PISUGAR_POWER_BUTTON_MAX_SHUTTER_PRESS_SECONDS = 0.6
QUEUE_RETRY_BASE_SECONDS = 15.0
QUEUE_RETRY_MAX_SECONDS = 15.0 * 60.0
FONT_PATH = str(Path(__file__).resolve().parents[2] / "assets" / "fonts" / "Orbitron-Regular.ttf")
MONO_FONT_PATH = FONT_PATH
SHUTTER_EVENT_DIR = Path("/tmp/imagegencam-shutter-events")
WIFI_KEYBOARD_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.!?#@$%&*/: "
logger = logging.getLogger(__name__)


@dataclass
class RuntimeState:
    mode: str = "preview"
    status_message: str = "Live camera preview"
    last_button: str | None = None
    last_error: str | None = None
    last_capture_path: str | None = None
    last_generated_path: str | None = None
    prompts: dict[str, str] = field(default_factory=dict)
    prompt_titles: dict[str, str] = field(default_factory=dict)
    current_prompt_button: str = "prompt-1"
    preview_calibration: dict[str, int] = field(default_factory=dict)
    capture_fps: str = "-"
    display_fps: str = "-"
    pending_jobs: int = 0
    ready_images: int = 0
    magic_mode_active: bool = False
    magic_prompt_title: str | None = None
    magic_prompt_ready: bool = False


@dataclass
class GenerationJob:
    prompt_button: str
    prompt_title: str
    prompt_body: str
    capture_path: Path
    generated_path: Path
    reference_paths: tuple[Path, ...] = ()
    magic_history_id: str | None = None


@dataclass
class CaptureRequest:
    prompt_button: str
    prompt_title: str
    prompt_body: str
    source_image: Image.Image
    reference_paths: tuple[Path, ...] = ()
    magic_history_id: str | None = None


@dataclass
class MagicSeedRequest:
    source_image: Image.Image


@dataclass
class MagicPromptState:
    history_id: str
    title: str
    body: str
    reference_capture_path: Path


class ImageGenCamController:
    def __init__(
        self,
        project_root: Path,
        prompt_store: PromptStore,
        magic_history_store: MagicHistoryStore,
        settings_store: SettingsStore,
        generation_job_store: PersistentJobStore,
        image_editor: OpenAIImageEditor,
        magic_prompt_planner: OpenAIMagicPromptPlanner,
        preview_size: tuple[int, int],
        frame_rate: int,
        generation_input_size: tuple[int, int],
    ) -> None:
        self.project_root = project_root
        self.prompt_store = prompt_store
        self.magic_history_store = magic_history_store
        self.settings_store = settings_store
        self.generation_job_store = generation_job_store
        self.image_editor = image_editor
        self.magic_prompt_planner = magic_prompt_planner
        self.preview_size = preview_size
        self.frame_rate = frame_rate
        self.generation_input_size = generation_input_size
        self.display_rotation = int(os.environ.get("DISPLAY_ST7789_ROTATION", "0"))
        self.web_port = int(os.environ.get("IMAGE_GEN_PORT", "8000"))
        self.camera_rotation_degrees = int(os.environ.get("CAMERA_ROTATION_DEGREES", "270"))
        self.capture_feedback_duration_seconds = max(
            0.05, float(os.environ.get("CAPTURE_FEEDBACK_DURATION_SECONDS", "0.25"))
        )
        self.max_pending_generations = max(0, int(os.environ.get("MAX_PENDING_GENERATIONS", "0")))
        self.capture_root = self.project_root / "data" / "captures"
        self.generated_root = self.project_root / "data" / "generated"
        self.capture_root.mkdir(parents=True, exist_ok=True)
        self.generated_root.mkdir(parents=True, exist_ok=True)
        settings = self.settings_store.load()
        self.app_background_theme = str(settings["app_background_theme"])
        self.camera_username = str(settings["camera_username"])

        self.swap_red_blue = os.environ.get("CAMERA_SWAP_RED_BLUE", "1").strip().lower() not in {
            "0",
            "false",
            "no",
        }
        self.stale_frame_timeout_seconds = float(
            os.environ.get("CAMERA_STALE_FRAME_TIMEOUT_SECONDS", "2.0")
        )
        self.magic_mode_enabled = os.environ.get("MAGIC_MODE_ENABLED", "1").strip().lower() not in {
            "0",
            "false",
            "no",
        }
        self.magic_history = self.magic_history_store.load_entries()
        self.magic_mode_active = False
        self.magic_prompt_pending = False
        self.current_magic_prompt: MagicPromptState | None = self._load_latest_magic_prompt()

        self.prompt_entries = self.prompt_store.load_entries()
        self.prompt_order = tuple(self.prompt_entries.keys())
        self.selected_prompt_index = 0
        self.prompt_picker_index = 0
        initial_prompt_id = self.prompt_order[0]

        self.state = RuntimeState(
            prompts={button: entry["body"] for button, entry in self.prompt_entries.items()},
            prompt_titles={button: entry["title"] for button, entry in self.prompt_entries.items()},
            current_prompt_button=initial_prompt_id,
            preview_calibration={
                "preview_warmth": int(settings["preview_warmth"]),
                "preview_red_gain": int(settings["preview_red_gain"]),
                "preview_green_gain": int(settings["preview_green_gain"]),
                "preview_blue_gain": int(settings["preview_blue_gain"]),
            },
            magic_mode_active=False,
            magic_prompt_title=self.current_magic_prompt.title if self.current_magic_prompt else None,
            magic_prompt_ready=self.current_magic_prompt is not None,
        )
        self.state.pending_jobs = self.generation_job_store.count()
        self.state_lock = Lock()
        self.event_queue: Queue[str] = Queue()
        self.capture_queue: Queue[CaptureRequest | None] = Queue()
        self.magic_prompt_queue: Queue[MagicSeedRequest | None] = Queue()
        self.latest_frame_lock = Lock()
        self.display_lock = Lock()
        self.camera_access_lock = Lock()
        self.image_edit_lock = Lock()

        self.latest_preview_frame: Image.Image | None = None
        self.latest_display_frame: Image.Image | None = None
        self.latest_preview_frame_id = 0
        self.current_display_image: Image.Image | None = None
        self.last_composed_display_image: Image.Image | None = None
        self.last_rendered_frame_id = -1
        self.preview_camera_config = None
        self.modal_background_image: Image.Image | None = None
        self.modal_background_frame_id = -1

        if self.camera_rotation_degrees % 180 == 90:
            self.preview_crop_box = self._build_crop_box(self.preview_size[1], self.preview_size[0])
        else:
            self.preview_crop_box = self._build_crop_box(self.preview_size[0], self.preview_size[1])
        self.preview_calibration_lut = self._build_preview_calibration_lut(self.state.preview_calibration)

        self.capture_feedback_frame: Image.Image | None = None
        self.capture_feedback_started_at = 0.0

        self.gallery_paths = self._load_generated_gallery_paths()
        self.album_index = 0
        self.album_show_source = False
        self.album_preload_generation = 0
        self.album_cached_path: Path | None = None
        self.album_cached_image: Image.Image | None = None
        self.album_source_cached_path: Path | None = None
        self.album_source_cached_image: Image.Image | None = None
        self.album_qr_cached_path: Path | None = None
        self.album_qr_cached_image: Image.Image | None = None
        self.album_qr_cached_url: str | None = None
        self.web_app_qr_image: Image.Image | None = None
        self.web_app_qr_url: str | None = None
        self.font_cache: dict[tuple[str, int], ImageFont.FreeTypeFont | ImageFont.ImageFont] = {}
        self.preview_chrome_cache_key: tuple[object, ...] | None = None
        self.preview_chrome_cache: Image.Image | None = None
        self.battery_overlay_cache_key: tuple[int | None, bool] | None = None
        self.battery_overlay_cache: Image.Image | None = None
        self.ready_unseen_count = 0
        self.wifi_manager = NetworkManagerWifi()
        self.wifi_networks: list[WifiNetwork] = []
        self.wifi_network_index = 0
        self.wifi_selected_network: WifiNetwork | None = None
        self.wifi_detail_index = 0
        self.wifi_password = ""
        self.wifi_keyboard_index = 0
        self.wifi_pending_rollback: WifiRollback | None = None
        self.wifi_connect_thread: Thread | None = None
        self.wifi_connect_message = ""
        self.wifi_connecting = False

        self.button_lookup: dict[int, str] = {}
        self.use_button_polling = False
        self.button_pins: tuple[int, ...] = ()
        self.button_last_states: dict[int, bool] = {}
        self.last_ui_press_time = 0.0
        self.last_shutter_event_times: dict[str, float] = {}

        self.capture_fps_ema = 0.0
        self.display_fps_ema = 0.0
        self.capture_last_frame_at: float | None = None
        self.display_last_frame_at: float | None = None
        self.last_perf_log_at = time.monotonic()
        self.last_drawn_mode: str | None = None
        self.preview_overlay_dirty = True
        self.preview_last_redraw_at = 0.0
        self.menu_last_redraw_at = 0.0
        self.album_last_redraw_at = 0.0

        self.battery_percent: int | None = None
        self.battery_charging = False
        self.battery_last_read_at = 0.0
        self.battery_readings: list[int] = []
        self.battery_sample_size = 25
        self.pisugar_battery_bus = None
        self.pisugar_enabled = (
            os.environ.get("PISUGAR_ENABLED", "0").strip().lower()
            not in {"0", "false", "no", "off"}
        )
        self.pisugar_power_button_shutter_enabled = self.pisugar_enabled and (
            os.environ.get("PISUGAR_POWER_BUTTON_SHUTTER", "1").strip().lower()
            not in {"0", "false", "no", "off"}
        )
        self.pisugar_power_button_last_state = False
        self.pisugar_power_button_pressed_at: float | None = None
        self.pisugar_power_button_last_poll_at = 0.0
        self.pisugar_power_button_available = False
        self.cpu_usage_percent: int | None = None
        self.cpu_last_read_at = 0.0
        self.cpu_previous_totals: tuple[int, int] | None = None
        self.local_ip_cache: str | None = None
        self.local_ip_last_resolved_at = 0.0
        self.pisugar_shortcut_button_configured = False
        self.last_pisugar_config_attempt_at = 0.0
        self.diagnostics_tap_times: list[float] = []

        self.camera_failure: str | None = None
        self.camera_thread: Thread | None = None
        self.capture_worker_thread: Thread | None = None
        self.generation_worker_thread: Thread | None = None
        self.magic_prompt_worker_thread: Thread | None = None
        self.running = True

        self._install_signal_handlers()
        self._prepare_shutter_event_dir()
        self._setup_display()
        self._show_boot_screen()
        self._setup_camera()
        self._setup_buttons()
        self._setup_hot_shoe()
        self._setup_pisugar_battery_bus()
        self.pisugar_shortcut_button_configured = self._configure_pisugar_shortcut_button()
        self.capture_worker_thread = Thread(target=self._capture_worker_loop, daemon=True)
        self.capture_worker_thread.start()
        self.generation_worker_thread = Thread(target=self._generation_worker_loop, daemon=True)
        self.generation_worker_thread.start()
        self.magic_prompt_worker_thread = Thread(target=self._magic_prompt_worker_loop, daemon=True)
        self.magic_prompt_worker_thread.start()

    def _install_signal_handlers(self) -> None:
        def handle_signal(signum, frame) -> None:
            self.running = False

        signal.signal(signal.SIGINT, handle_signal)
        signal.signal(signal.SIGTERM, handle_signal)

    def _prepare_shutter_event_dir(self) -> None:
        SHUTTER_EVENT_DIR.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(SHUTTER_EVENT_DIR, 0o777)
        except OSError:
            pass
        for event_path in SHUTTER_EVENT_DIR.iterdir():
            if not event_path.is_file():
                continue
            try:
                event_path.unlink()
            except OSError:
                continue

    def _setup_pisugar_battery_bus(self) -> None:
        if not self.pisugar_enabled:
            self.pisugar_battery_bus = None
            self.pisugar_power_button_available = False
            return
        try:
            import smbus  # type: ignore

            self.pisugar_battery_bus = smbus.SMBus(1)
            self._initialize_pisugar_power_button_state()
        except Exception:
            self.pisugar_battery_bus = None
            self.pisugar_power_button_available = False

    def _read_pisugar_power_button_pressed(self) -> bool | None:
        """Return the raw PiSugar 3 power-button bit, if the I2C bus is available."""
        if self.pisugar_battery_bus is None:
            return None
        try:
            status = int(self.pisugar_battery_bus.read_byte_data(0x57, 0x02))
        except Exception:
            return None
        return bool(status & 0x01)

    def _initialize_pisugar_power_button_state(self) -> None:
        pressed = self._read_pisugar_power_button_pressed()
        if pressed is None:
            self.pisugar_power_button_available = False
            return
        self.pisugar_power_button_last_state = pressed
        self.pisugar_power_button_available = True

    def _setup_display(self) -> None:
        if os.environ.get("IMAGEGENCAM_HW", "opi").strip().lower() == "pi":
            import displayhatmini
        else:
            from . import opi_hw as displayhatmini

        self.displayhatmini = displayhatmini
        self.buffer = Image.new("RGB", (WIDTH, HEIGHT))
        self.display = displayhatmini.DisplayHATMini(self.buffer, backlight_pwm=True)
        if hasattr(self.display, "st7789") and hasattr(self.display.st7789, "_rotation"):
            self.display.st7789._rotation = self.display_rotation
        self.display.set_backlight(1.0)
        self.display.set_led(0.0, 0.0, 0.0)

    def _setup_camera(self) -> None:
        if os.environ.get("IMAGEGENCAM_HW", "opi").strip().lower() == "pi":
            from picamera2 import Picamera2
        else:
            from .opi_hw import Picamera2

        self.picam2 = Picamera2()
        self.preview_camera_config = self.picam2.create_preview_configuration(
            main={"size": self.preview_size, "format": "RGB888"},
            controls={"FrameRate": self.frame_rate},
            buffer_count=2,
        )
        self.picam2.configure(self.preview_camera_config)
        self.picam2.start()
        time.sleep(1.0)
        self.camera_thread = Thread(target=self._camera_capture_loop, daemon=True)
        self.camera_thread.start()

    def _setup_hot_shoe(self) -> None:
        self.hot_shoe = None
        if os.environ.get("HOTSHOE_ENABLED", "0").strip().lower() not in {"1", "true", "yes", "on"}:
            return
        hot_shoe_cls = getattr(self.displayhatmini, "HotShoe", None)
        if hot_shoe_cls is None:
            logger.warning("HOTSHOE_ENABLED is set but this hardware stack has no hot shoe support")
            return
        try:
            self.hot_shoe = hot_shoe_cls()
            logger.info("Hot shoe trigger ready on line %s", self.hot_shoe.pin)
        except Exception:
            logger.exception("Hot shoe setup failed; continuing without flash")

    def _grab_capture_frames(self) -> tuple[Image.Image | None, Image.Image | None]:
        """Copy the current preview frames for a capture. With a hot shoe
        fitted, fire the flash first and wait for a frame whose exposure
        started after the trigger, so the flash actually lights the photo."""
        if self.hot_shoe is not None:
            with self.latest_frame_lock:
                baseline_id = self.latest_preview_frame_id
            try:
                self.hot_shoe.fire()
            except Exception:
                logger.exception("Hot shoe trigger failed")
            else:
                deadline = time.monotonic() + max(0.5, 3.0 / self.frame_rate)
                while time.monotonic() < deadline:
                    with self.latest_frame_lock:
                        if self.latest_preview_frame_id >= baseline_id + 2:
                            break
                    time.sleep(0.005)
        with self.latest_frame_lock:
            display_frame = self.latest_display_frame.copy() if self.latest_display_frame else None
            source_frame = self.latest_preview_frame.copy() if self.latest_preview_frame else None
        return display_frame, source_frame

    def _camera_capture_loop(self) -> None:
        while self.running:
            try:
                with self.camera_access_lock:
                    frame_array = self.picam2.capture_array("main")
                now = time.monotonic()
                self._record_capture_frame(now)
                if self.swap_red_blue:
                    frame_array = frame_array[:, :, [2, 1, 0]]
                frame = Image.fromarray(frame_array, "RGB")
                with self.latest_frame_lock:
                    self.latest_preview_frame = frame
                    self.latest_preview_frame_id += 1
            except Exception as exc:
                self.camera_failure = f"Camera stream failed: {exc}"
                logger.exception("Camera capture loop failed")
                self._fail_fast_camera_restart(self.camera_failure)
                return

    def _setup_buttons(self) -> None:
        self.button_lookup = {
            self.displayhatmini.DisplayHATMini.BUTTON_A: "ui_down",
            self.displayhatmini.DisplayHATMini.BUTTON_B: "ui_up",
            self.displayhatmini.DisplayHATMini.BUTTON_X: "ui_album",
            self.displayhatmini.DisplayHATMini.BUTTON_Y: "ui_prompt",
        }
        self.button_pins = tuple(self.button_lookup.keys())
        self.button_last_states = {
            pin: self.display.read_button(pin) for pin in self.button_pins
        }
        self.shutter_button_pin = getattr(
            self.displayhatmini.DisplayHATMini, "BUTTON_SHUTTER", None
        )
        self.shutter_button_last_state = (
            self.display.read_button(self.shutter_button_pin)
            if self.shutter_button_pin is not None
            else False
        )
        self.shutter_button_pressed_at: float | None = None

        def callback(pin) -> None:
            if not self.display.read_button(pin):
                return
            action = self.button_lookup.get(pin)
            if action:
                self._queue_ui_event(action)

        try:
            self.display.on_button_pressed(callback)
            self.use_button_polling = False
        except RuntimeError:
            self.use_button_polling = True

    def _queue_ui_event(self, action: str) -> None:
        now = time.monotonic()
        if now - self.last_ui_press_time < 0.11:
            return
        self.last_ui_press_time = now
        self.event_queue.put(action)

    def _queue_shutter_event(self, event_name: str = "shutter") -> None:
        now = time.monotonic()
        last_seen_at = self.last_shutter_event_times.get(event_name, 0.0)
        if now - last_seen_at < 0.125:
            return
        self.last_shutter_event_times[event_name] = now
        self.event_queue.put(event_name)

    def _poll_buttons(self) -> None:
        if not self.use_button_polling:
            return

        for pin in self.button_pins:
            is_pressed = self.display.read_button(pin)
            was_pressed = self.button_last_states.get(pin, False)
            self.button_last_states[pin] = is_pressed
            if not is_pressed or was_pressed:
                continue
            action = self.button_lookup.get(pin)
            if action:
                self._queue_ui_event(action)

    def _poll_shutter_button(self, now: float) -> None:
        """GPIO shutter switch: short press captures, long press seeds magic mode."""
        if self.shutter_button_pin is None:
            return
        pressed = self.display.read_button(self.shutter_button_pin)
        was_pressed = self.shutter_button_last_state
        self.shutter_button_last_state = pressed

        if pressed and not was_pressed:
            self.shutter_button_pressed_at = now
            return

        if not pressed and was_pressed:
            pressed_at = self.shutter_button_pressed_at
            self.shutter_button_pressed_at = None
            if pressed_at is None:
                return
            if (now - pressed_at) <= PISUGAR_POWER_BUTTON_MAX_SHUTTER_PRESS_SECONDS:
                self._queue_shutter_event("shutter")
            return

        if pressed and self.shutter_button_pressed_at is not None:
            if (now - self.shutter_button_pressed_at) > PISUGAR_POWER_BUTTON_MAX_SHUTTER_PRESS_SECONDS:
                self.shutter_button_pressed_at = None
                if self.magic_mode_enabled:
                    self._queue_shutter_event("magic_shutter")

    def _poll_external_shutter_events(self) -> None:
        if not SHUTTER_EVENT_DIR.exists():
            return
        try:
            event_paths = sorted(
                path for path in SHUTTER_EVENT_DIR.iterdir() if path.is_file()
            )
        except OSError:
            return

        for event_path in event_paths:
            event_name = "shutter"
            try:
                event_body = event_path.read_text(encoding="utf-8").strip()
                if event_body:
                    event_name = event_body
            except OSError:
                event_name = "shutter"
            try:
                event_path.unlink()
            except OSError:
                continue
            self._queue_shutter_event(event_name)

    def _poll_pisugar_power_button(self, now: float) -> None:
        if not self.pisugar_power_button_shutter_enabled:
            return
        if (now - self.pisugar_power_button_last_poll_at) < PISUGAR_POWER_BUTTON_POLL_INTERVAL_SECONDS:
            return
        self.pisugar_power_button_last_poll_at = now

        pressed = self._read_pisugar_power_button_pressed()
        if pressed is None:
            self.pisugar_power_button_available = False
            self.pisugar_power_button_pressed_at = None
            return

        if not self.pisugar_power_button_available:
            self.pisugar_power_button_last_state = pressed
            self.pisugar_power_button_pressed_at = now if pressed else None
            self.pisugar_power_button_available = True
            return

        was_pressed = self.pisugar_power_button_last_state
        self.pisugar_power_button_last_state = pressed

        if pressed and not was_pressed:
            self.pisugar_power_button_pressed_at = now
            return

        if not pressed and was_pressed:
            pressed_at = self.pisugar_power_button_pressed_at
            self.pisugar_power_button_pressed_at = None
            if pressed_at is None:
                return
            if (now - pressed_at) <= PISUGAR_POWER_BUTTON_MAX_SHUTTER_PRESS_SECONDS:
                self._queue_shutter_event("shutter")
            return

        if pressed and self.pisugar_power_button_pressed_at is not None:
            if (now - self.pisugar_power_button_pressed_at) > PISUGAR_POWER_BUTTON_MAX_SHUTTER_PRESS_SECONDS:
                self.pisugar_power_button_pressed_at = None
            return

        if not pressed:
            self.pisugar_power_button_pressed_at = None

    def get_prompts(self) -> dict[str, str]:
        with self.state_lock:
            return dict(self.state.prompts)

    def get_prompt_entries(self) -> list[dict[str, str]]:
        with self.state_lock:
            return [
                {"id": prompt_id, "title": entry["title"], "body": entry["body"]}
                for prompt_id, entry in self.prompt_entries.items()
            ]

    def _load_latest_magic_prompt(self) -> MagicPromptState | None:
        if not self.magic_history:
            return None
        latest = self.magic_history[0]
        reference_capture_path = latest.get("reference_capture_path")
        if not reference_capture_path:
            return None
        resolved = self._resolve_project_path(reference_capture_path)
        if resolved is None:
            return None
        return MagicPromptState(
            history_id=str(latest["id"]),
            title=str(latest["title"]),
            body=str(latest["body"]),
            reference_capture_path=resolved,
        )

    def _resolve_project_path(self, stored_path: str) -> Path | None:
        candidate = self._project_path_from_stored_path(stored_path)
        if candidate.is_file():
            return candidate
        return None

    def _project_path_from_stored_path(self, stored_path: str) -> Path:
        candidate = Path(stored_path)
        if not candidate.is_absolute():
            candidate = (self.project_root / candidate).resolve()
        return candidate

    def _project_relative_path(self, path: Path) -> str:
        try:
            return path.relative_to(self.project_root).as_posix()
        except ValueError:
            return str(path)

    def _refresh_pending_jobs_count(self) -> None:
        with self.state_lock:
            self.state.pending_jobs = self.generation_job_store.count()

    @staticmethod
    def _retry_delay_seconds(attempts: int) -> float:
        retry_step = max(0, int(attempts) - 1)
        return min(QUEUE_RETRY_MAX_SECONDS, QUEUE_RETRY_BASE_SECONDS * (2**retry_step))

    def _save_generation_job(self, job: GenerationJob) -> None:
        created_at = datetime.now().isoformat(timespec="seconds")
        payload: dict[str, object] = {
            "created_at": created_at,
            "updated_at": created_at,
            "next_attempt_at": created_at,
            "attempts": 0,
            "prompt_button": job.prompt_button,
            "prompt_title": job.prompt_title,
            "prompt_body": job.prompt_body,
            "capture_path": self._project_relative_path(job.capture_path),
            "generated_path": self._project_relative_path(job.generated_path),
            "reference_paths": [self._project_relative_path(path) for path in job.reference_paths],
            "magic_history_id": job.magic_history_id,
            "last_error": None,
        }
        self.generation_job_store.save_entry(job.generated_path.stem, payload)
        self._refresh_pending_jobs_count()

    def _load_generation_job(self, payload: dict[str, object]) -> GenerationJob:
        capture_path = self._project_path_from_stored_path(str(payload.get("capture_path") or ""))
        generated_path = self._project_path_from_stored_path(str(payload.get("generated_path") or ""))
        reference_paths = tuple(
            self._project_path_from_stored_path(str(path))
            for path in payload.get("reference_paths", [])
            if str(path).strip()
        )
        return GenerationJob(
            prompt_button=str(payload.get("prompt_button") or "prompt"),
            prompt_title=str(payload.get("prompt_title") or "Prompt"),
            prompt_body=str(payload.get("prompt_body") or ""),
            capture_path=capture_path,
            generated_path=generated_path,
            reference_paths=reference_paths,
            magic_history_id=str(payload.get("magic_history_id") or "").strip() or None,
        )

    def _reschedule_generation_job(self, job_id: str, payload: dict[str, object], error: Exception) -> None:
        attempts = int(payload.get("attempts") or 0) + 1
        updated_at = datetime.now()
        payload.update(
            {
                "attempts": attempts,
                "updated_at": updated_at.isoformat(timespec="seconds"),
                "next_attempt_at": (
                    updated_at.timestamp() + self._retry_delay_seconds(attempts)
                ),
                "last_error": str(error),
            }
        )
        payload["next_attempt_at"] = datetime.fromtimestamp(
            float(payload["next_attempt_at"])
        ).isoformat(timespec="seconds")
        self.generation_job_store.save_entry(job_id, payload)

    def get_magic_history_entries(self) -> list[dict[str, str | None]]:
        return [dict(entry) for entry in self.magic_history]

    def _refresh_magic_state_snapshot(self) -> None:
        with self.state_lock:
            self.state.magic_mode_active = self.magic_mode_active
            self.state.magic_prompt_title = (
                self.current_magic_prompt.title
                if self.current_magic_prompt and not self.magic_prompt_pending
                else None
            )
            self.state.magic_prompt_ready = (
                self.current_magic_prompt is not None and not self.magic_prompt_pending
            )

    def add_magic_history_entry_as_prompt(self, entry_id: str) -> list[dict[str, str]]:
        selected_entry = next((entry for entry in self.magic_history if entry["id"] == entry_id), None)
        if selected_entry is None:
            raise ValueError("Magic history entry not found.")

        prompt_entries = self.get_prompt_entries()
        prompt_entries.insert(
            0,
            {
                "id": "",
                "title": str(selected_entry["title"]),
                "body": str(selected_entry["body"]),
            }
        )
        updated_entries = self.update_prompt_entries(prompt_entries)
        new_prompt_id = updated_entries[0]["id"] if updated_entries else None
        if new_prompt_id is not None:
            self.magic_history = self.magic_history_store.mark_promoted(entry_id, new_prompt_id)
        return updated_entries

    def mark_magic_history_promoted(self, entry_id: str, prompt_id: str) -> list[dict[str, str | None]]:
        self.magic_history = self.magic_history_store.mark_promoted(entry_id, prompt_id)
        return self.get_magic_history_entries()

    def update_prompts(self, prompts: dict[str, str]) -> dict[str, str]:
        cleaned = self.prompt_store.save(prompts)
        updated_entries = [
            {
                "id": prompt_id,
                "title": self.prompt_entries.get(prompt_id, {"title": "New Prompt"})["title"],
                "body": cleaned[prompt_id],
            }
            for prompt_id in cleaned
        ]
        self.update_prompt_entries(updated_entries)
        return cleaned

    def update_prompt_entries(self, prompts: object) -> list[dict[str, str]]:
        cleaned_entries = self.prompt_store.save_entries(prompts)
        with self.state_lock:
            self.prompt_entries = {
                button: {"title": entry["title"], "body": entry["body"]}
                for button, entry in cleaned_entries.items()
            }
            self.prompt_order = tuple(self.prompt_entries.keys())
            current_button = self.state.current_prompt_button
            if current_button not in self.prompt_order:
                current_button = self.prompt_order[0]
            self.selected_prompt_index = self.prompt_order.index(current_button)
            self.prompt_picker_index = self.selected_prompt_index
            self.state.prompts = {
                button: entry["body"] for button, entry in self.prompt_entries.items()
            }
            self.state.prompt_titles = {
                button: entry["title"] for button, entry in self.prompt_entries.items()
            }
            self.state.current_prompt_button = current_button
            if self.state.mode == "preview" and not self.magic_mode_active:
                self.state.status_message = f"Ready with {self.prompt_entries[current_button]['title']}"
        self._refresh_magic_state_snapshot()
        self.preview_overlay_dirty = True
        return self.get_prompt_entries()

    def get_status_snapshot(self) -> dict[str, str | None]:
        with self.state_lock:
            return {
                "mode": self.state.mode,
                "status_message": self.state.status_message,
                "last_button": self.state.last_button,
                "last_error": self.state.last_error,
                "last_capture_path": self.state.last_capture_path,
                "last_generated_path": self.state.last_generated_path,
                "current_prompt_button": self.state.current_prompt_button,
                "current_prompt_title": self.state.prompt_titles.get(
                    self.state.current_prompt_button, ""
                ),
                "app_background_theme": self.app_background_theme,
                "capture_fps": self.state.capture_fps,
                "display_fps": self.state.display_fps,
                "pending_jobs": str(self.state.pending_jobs),
                "ready_images": str(self.state.ready_images),
                "magic_mode_active": "true" if self.state.magic_mode_active else "false",
                "magic_prompt_title": self.state.magic_prompt_title,
                "magic_prompt_ready": "true" if self.state.magic_prompt_ready else "false",
                "camera_username": self.camera_username,
            }

    @staticmethod
    def _format_bytes(value: int) -> str:
        amount = float(value)
        for unit in ("B", "KB", "MB", "GB"):
            if amount < 1024 or unit == "GB":
                return f"{amount:.1f} {unit}" if unit != "B" else f"{int(amount)} B"
            amount /= 1024
        return f"{amount:.1f} GB"

    def get_device_details(self) -> dict[str, object]:
        self._refresh_battery_status()
        disk = shutil.disk_usage(self.project_root)
        base_url = self._get_local_base_url()
        ip_address = base_url.replace("http://", "").rstrip("/").split(":", 1)[0]
        battery_status = "Unknown"
        if self.battery_percent is not None:
            battery_status = f"{self.battery_percent}%"
            if self.battery_charging:
                battery_status = f"{battery_status} charging"
        cpu_value = self._refresh_cpu_usage()
        return {
            "battery_status": battery_status,
            "battery_percent": self.battery_percent,
            "battery_charging": self.battery_charging,
            "wifi_network": self._get_wifi_ssid(),
            "ip_address": ip_address,
            "mac_address": self._get_mac_address(),
            "hostname": socket.gethostname().strip() or "imagegencam",
            "app_url": self._get_short_app_url(),
            "storage_status": (
                f"{self._format_bytes(disk.free)} free of {self._format_bytes(disk.total)}"
            ),
            "storage_free_bytes": disk.free,
            "storage_total_bytes": disk.total,
            "cpu_status": f"{cpu_value}%" if cpu_value is not None else "Unknown",
        }

    def get_app_background_theme(self) -> str:
        return self.app_background_theme

    def get_camera_username(self) -> str:
        return self.camera_username

    def update_camera_username(self, username: str) -> str:
        current_settings = self.settings_store.load()
        current_settings["camera_username"] = username
        cleaned = self.settings_store.save(current_settings)
        self.camera_username = str(cleaned["camera_username"])
        return self.camera_username

    def update_app_background_theme(self, theme: str) -> str:
        current_settings = self.settings_store.load()
        current_settings["app_background_theme"] = theme
        cleaned = self.settings_store.save(current_settings)
        self.app_background_theme = str(cleaned["app_background_theme"])
        return self.app_background_theme

    def get_preview_calibration(self) -> dict[str, int]:
        with self.state_lock:
            return dict(self.state.preview_calibration)

    def update_preview_calibration(self, updates: dict[str, object]) -> dict[str, int]:
        current_settings = self.settings_store.load()
        current_settings.update(updates)
        cleaned = self.settings_store.save(current_settings)
        preview_calibration = {
            "preview_warmth": int(cleaned["preview_warmth"]),
            "preview_red_gain": int(cleaned["preview_red_gain"]),
            "preview_green_gain": int(cleaned["preview_green_gain"]),
            "preview_blue_gain": int(cleaned["preview_blue_gain"]),
        }
        with self.state_lock:
            self.state.preview_calibration = preview_calibration
        self.preview_calibration_lut = self._build_preview_calibration_lut(preview_calibration)
        with self.latest_frame_lock:
            if self.latest_preview_frame is not None:
                self.latest_display_frame = self._build_display_preview_frame(
                    self.latest_preview_frame
                )
        return preview_calibration

    def _build_crop_box(self, width: int, height: int) -> tuple[int, int, int, int]:
        current_ratio = width / height
        target_ratio = WIDTH / HEIGHT
        if current_ratio > target_ratio:
            new_width = int(height * target_ratio)
            left = (width - new_width) // 2
            return (left, 0, left + new_width, height)
        new_height = int(width / target_ratio)
        top = (height - new_height) // 2
        return (0, top, width, top + new_height)

    def _crop_to_display_ratio(self, image: Image.Image) -> Image.Image:
        img = image.convert("RGB")
        return img.crop(self._build_crop_box(img.width, img.height))

    def _apply_camera_rotation(self, image: Image.Image) -> Image.Image:
        normalized = self.camera_rotation_degrees % 360
        if normalized == 90:
            return image.transpose(Image.Transpose.ROTATE_90)
        if normalized == 180:
            return image.transpose(Image.Transpose.ROTATE_180)
        if normalized == 270:
            return image.transpose(Image.Transpose.ROTATE_270)
        return image

    def _fit_camera_for_display(self, image: Image.Image) -> Image.Image:
        img = self._apply_camera_rotation(image.convert("RGB"))
        img = img.crop(self.preview_crop_box)
        return img.resize((WIDTH, HEIGHT), Image.Resampling.NEAREST)

    def _fit_for_generation(self, image: Image.Image) -> Image.Image:
        img = self._apply_camera_rotation(image.convert("RGB"))
        crop_box = self._build_crop_box(img.width, img.height)
        img = img.crop(crop_box)
        return img.resize(self.generation_input_size, Image.Resampling.BILINEAR)

    def _fit_generated_for_display(self, image: Image.Image) -> Image.Image:
        img = self._crop_to_display_ratio(image)
        return img.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS)

    def _build_display_preview_frame(self, image: Image.Image) -> Image.Image:
        display_image = self._fit_camera_for_display(image)
        if self.preview_calibration_lut is not None:
            display_image = self._apply_preview_calibration(display_image)
        return display_image

    def _build_preview_calibration_lut(
        self,
        calibration: dict[str, int],
    ) -> tuple[list[int], list[int], list[int]] | None:
        if calibration == {
            "preview_warmth": 0,
            "preview_red_gain": 100,
            "preview_green_gain": 100,
            "preview_blue_gain": 100,
        }:
            return None

        warmth = calibration["preview_warmth"] / 100.0
        red_multiplier = max(
            0.3,
            min(2.0, (calibration["preview_red_gain"] / 100.0) + (warmth * 0.35)),
        )
        green_multiplier = max(0.3, min(2.0, calibration["preview_green_gain"] / 100.0))
        blue_multiplier = max(
            0.3,
            min(2.0, (calibration["preview_blue_gain"] / 100.0) - (warmth * 0.35)),
        )

        red_lut = [min(255, int(value * red_multiplier)) for value in range(256)]
        green_lut = [min(255, int(value * green_multiplier)) for value in range(256)]
        blue_lut = [min(255, int(value * blue_multiplier)) for value in range(256)]
        return red_lut, green_lut, blue_lut

    def _apply_preview_calibration(self, image: Image.Image) -> Image.Image:
        if self.preview_calibration_lut is None:
            return image

        red_channel, green_channel, blue_channel = image.convert("RGB").split()
        red_lut, green_lut, blue_lut = self.preview_calibration_lut
        red_channel = red_channel.point(red_lut)
        green_channel = green_channel.point(green_lut)
        blue_channel = blue_channel.point(blue_lut)
        return Image.merge("RGB", (red_channel, green_channel, blue_channel))

    def get_calibration_preview_jpeg(self) -> bytes | None:
        with self.latest_frame_lock:
            preview_image = self.latest_display_frame.copy() if self.latest_display_frame else None

        if preview_image is None:
            return None

        buffer = BytesIO()
        preview_image.save(buffer, format="JPEG", quality=70)
        return buffer.getvalue()

    def get_screen_preview_jpeg(self) -> bytes | None:
        with self.display_lock:
            image = self.last_composed_display_image.copy() if self.last_composed_display_image else None

        if image is None:
            return None

        buffer = BytesIO()
        image.save(buffer, format="JPEG", quality=45, optimize=False)
        return buffer.getvalue()

    def _render_to_display(self, image: Image.Image, *, decorate_battery: bool = True) -> None:
        self.current_display_image = image
        self._record_display_frame(time.monotonic())
        composed = self._decorate_with_battery(image) if decorate_battery else image
        self.last_composed_display_image = composed
        with self.display_lock:
            self.buffer.paste(composed)
            self.display.display()

    def _update_fps_ema(self, previous: float, delta: float) -> float:
        if delta <= 0:
            return previous
        instant = 1.0 / delta
        if previous <= 0:
            return instant
        return (previous * 0.82) + (instant * 0.18)

    def _record_capture_frame(self, now: float) -> None:
        if self.capture_last_frame_at is not None:
            self.capture_fps_ema = self._update_fps_ema(
                self.capture_fps_ema, now - self.capture_last_frame_at
            )
        self.capture_last_frame_at = now
        with self.state_lock:
            self.state.capture_fps = f"{self.capture_fps_ema:.1f}" if self.capture_fps_ema > 0 else "-"

    def _check_stale_camera(self, now: float) -> None:
        if self.capture_last_frame_at is None:
            return
        stale_for = now - self.capture_last_frame_at
        if stale_for <= self.stale_frame_timeout_seconds:
            return
        message = (
            f"Camera frames stalled for {stale_for:.2f}s "
            f"(threshold {self.stale_frame_timeout_seconds:.2f}s)"
        )
        logger.error(message)
        with self.state_lock:
            self.state.last_error = message
            self.state.status_message = "Camera stalled. Restarting service."
        self._fail_fast_camera_restart(message)

    def _fail_fast_camera_restart(self, message: str) -> None:
        logger.error("Fail-fast restart: %s", message)
        try:
            self._show_text_screen("Camera stalled", "Restarting service...")
        except Exception:
            pass
        logging.shutdown()
        os._exit(1)

    def _record_display_frame(self, now: float) -> None:
        if self.display_last_frame_at is not None:
            self.display_fps_ema = self._update_fps_ema(
                self.display_fps_ema, now - self.display_last_frame_at
            )
        self.display_last_frame_at = now
        with self.state_lock:
            self.state.display_fps = f"{self.display_fps_ema:.1f}" if self.display_fps_ema > 0 else "-"

    def _maybe_log_perf(self) -> None:
        now = time.monotonic()
        if now - self.last_perf_log_at < 10.0:
            return
        self.last_perf_log_at = now
        snapshot = self.get_status_snapshot()
        logger.info(
            "Preview perf capture_fps=%s display_fps=%s mode=%s pending=%s ready=%s",
            snapshot["capture_fps"],
            snapshot["display_fps"],
            snapshot["mode"],
            snapshot["pending_jobs"],
            snapshot["ready_images"],
        )

    def _set_led(self, red: float, green: float, blue: float) -> None:
        red = green = blue = 0.0
        with self.display_lock:
            self.display.set_led(red, green, blue)

    def _load_font(
        self,
        size: int,
        font_path: str = FONT_PATH,
    ) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
        cache_key = (font_path, size)
        cached = self.font_cache.get(cache_key)
        if cached is not None:
            return cached
        try:
            font = ImageFont.truetype(font_path, size=size)
        except OSError:
            font = ImageFont.load_default()
        self.font_cache[cache_key] = font
        return font

    def _wrap_text_pixels(
        self,
        text: str,
        font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
        max_width: int,
    ) -> list[str]:
        words = text.split()
        if not words:
            return [""]

        probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
        lines: list[str] = []
        current = words[0]
        for word in words[1:]:
            candidate = f"{current} {word}"
            if probe.textlength(candidate, font=font) <= max_width:
                current = candidate
            else:
                lines.append(current)
                current = word
        lines.append(current)
        return lines

    def _truncate_text_pixels(
        self,
        text: str,
        font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
        max_width: int,
    ) -> str:
        probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
        if probe.textlength(text, font=font) <= max_width:
            return text
        trimmed = text.rstrip()
        while trimmed and probe.textlength(f"{trimmed}...", font=font) > max_width:
            trimmed = trimmed[:-1].rstrip()
        return f"{trimmed}..." if trimmed else "..."

    def _fit_wrapped_text(
        self,
        text: str,
        max_width: int,
        max_lines: int,
        start_size: int,
        min_size: int,
        font_path: str = FONT_PATH,
    ) -> tuple[list[str], ImageFont.FreeTypeFont | ImageFont.ImageFont]:
        for size in range(start_size, min_size - 1, -1):
            font = self._load_font(size, font_path=font_path)
            lines = self._wrap_text_pixels(text, font, max_width)
            if len(lines) <= max_lines:
                return lines, font

            trimmed = lines[:max_lines]
            trimmed[-1] = self._truncate_text_pixels(trimmed[-1], font, max_width)
            return trimmed, font

        fallback_font = self._load_font(min_size, font_path=font_path)
        fallback_lines = self._wrap_text_pixels(text, fallback_font, max_width)[:max_lines]
        if fallback_lines:
            fallback_lines[-1] = self._truncate_text_pixels(
                fallback_lines[-1], fallback_font, max_width
            )
        return fallback_lines, fallback_font

    def _show_text_screen(self, title: str, subtitle: str = "", fill=(255, 255, 255)) -> None:
        screen = Image.new("RGB", (WIDTH, HEIGHT), fill)
        draw = ImageDraw.Draw(screen)
        title_font = self._load_font(28)
        body_font = self._load_font(18)
        draw.text((12, 18), title[:24], font=title_font, fill=(0, 0, 0))
        if subtitle:
            lines = self._wrap_text_pixels(subtitle, body_font, WIDTH - 24)
            y = 72
            for line in lines[:6]:
                draw.text((12, y), line, font=body_font, fill=(0, 0, 0))
                y += 22
        self._render_to_display(screen)

    def _show_boot_screen(self) -> None:
        boot_path = self.project_root / "assets" / "boot-screen.png"
        if not boot_path.exists():
            self._show_text_screen("ImageGenCam v2.0", "Starting up...")
            return
        try:
            with Image.open(boot_path) as source:
                fitted = ImageOps.fit(
                    source.convert("RGB"),
                    (WIDTH, HEIGHT),
                    method=Image.Resampling.LANCZOS,
                )
            self._render_to_display(fitted)
        except Exception:
            logger.exception("Failed to load boot screen image")
            self._show_text_screen("ImageGenCam v2.0", "Starting up...")

    def _pisugar_command(self, command: str) -> str | None:
        if not self.pisugar_enabled:
            return None
        try:
            with socket.create_connection(("127.0.0.1", 8423), timeout=0.5) as client:
                client.sendall(f"{command}\n".encode("utf-8"))
                return client.recv(256).decode("utf-8", errors="ignore").strip()
        except OSError:
            return None

    def _configure_pisugar_shortcut_button(self) -> bool:
        script_path = self.project_root / "scripts" / "pisugar_trigger_shutter.sh"
        if not script_path.exists():
            logger.warning("PiSugar shortcut hook missing: %s", script_path)
            return False

        commands = [
            "set_button_enable long false",
        ]
        if self.magic_mode_enabled:
            commands.extend(
                (
                    f"set_button_shell single {script_path} magic_shutter",
                    "set_button_enable single true",
                    "set_button_enable double false",
                )
            )
        else:
            commands.extend(
                (
                    "set_button_enable single false",
                    "set_button_enable double false",
                )
            )
        for command in commands:
            response = self._pisugar_command(command)
            if response and "done" in response.lower():
                continue
            if response is not None:
                logger.warning("PiSugar command %s -> %s", command, response)
            return False
        return True

    def _maybe_configure_pisugar_button(self) -> None:
        if not self.pisugar_enabled:
            return
        if self.pisugar_shortcut_button_configured:
            return
        now = time.monotonic()
        if (now - self.last_pisugar_config_attempt_at) < 10.0:
            return
        self.last_pisugar_config_attempt_at = now
        self.pisugar_shortcut_button_configured = self._configure_pisugar_shortcut_button()

    def _refresh_battery_status(self) -> None:
        now = time.monotonic()
        if (now - self.battery_last_read_at) < BATTERY_REFRESH_INTERVAL_SECONDS:
            return
        self.battery_last_read_at = now

        if self._refresh_battery_status_via_socket():
            return
        self._refresh_battery_status_via_i2c()

    def _record_battery_sample(self, sample: float) -> bool:
        if not 0 <= sample <= 100:
            logger.warning("Ignoring implausible PiSugar battery reading: %s", sample)
            return False
        self.battery_readings.append(int(round(sample)))
        if len(self.battery_readings) > self.battery_sample_size:
            self.battery_readings = self.battery_readings[-self.battery_sample_size :]
        self.battery_percent = int(sum(self.battery_readings) / len(self.battery_readings))
        return True

    def _refresh_battery_status_via_i2c(self) -> bool:
        if self.pisugar_battery_bus is None:
            return False
        try:
            capacity = int(self.pisugar_battery_bus.read_byte_data(0x57, 0x2A))
            status = int(self.pisugar_battery_bus.read_byte_data(0x57, 0x02))
        except Exception:
            return False
        if not self._record_battery_sample(capacity):
            return False
        self.battery_charging = bool(status & 0x80)
        return True

    def _refresh_battery_status_via_socket(self) -> bool:
        battery_response = self._pisugar_command("get battery")
        charging_response = self._pisugar_command("get battery_charging")
        updated = False

        if battery_response and ":" in battery_response:
            try:
                value = float(battery_response.split(":", 1)[1].strip())
                updated = self._record_battery_sample(value)
            except ValueError:
                pass

        if charging_response and ":" in charging_response:
            self.battery_charging = charging_response.split(":", 1)[1].strip().lower() == "true"
        return updated

    def _get_battery_overlay(self) -> Image.Image | None:
        self._refresh_battery_status()
        if self.battery_percent is None:
            return None

        cache_key = (self.battery_percent, self.battery_charging)
        if self.battery_overlay_cache_key == cache_key and self.battery_overlay_cache is not None:
            return self.battery_overlay_cache

        overlay = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        draw = ImageDraw.Draw(overlay)

        icon_w = 24
        icon_h = 11
        padding = 8
        x = WIDTH - padding - icon_w
        y = 12

        draw.rounded_rectangle(
            (x, y, x + icon_w - 4, y + icon_h), radius=2, outline=(255, 255, 255), width=2
        )
        draw.rounded_rectangle(
            (x + icon_w - 4, y + 3, x + icon_w, y + icon_h - 3),
            radius=1,
            fill=(255, 255, 255),
        )

        inner_padding = 3
        fill_width = int(((icon_w - 10) * self.battery_percent) / 100)
        fill_color = (90, 255, 126) if self.battery_percent > 25 else (255, 203, 79)
        if self.battery_percent <= 12:
            fill_color = (255, 107, 107)
        if fill_width > 0:
            draw.rounded_rectangle(
                (
                    x + inner_padding,
                    y + inner_padding,
                    x + inner_padding + fill_width,
                    y + icon_h - inner_padding,
                ),
                radius=1,
                fill=fill_color,
            )

        if self.battery_charging:
            bolt = [
                (x - 10, y + 1),
                (x - 5, y + 1),
                (x - 7, y + 5),
                (x - 3, y + 5),
                (x - 10, y + icon_h + 1),
                (x - 8, y + 7),
                (x - 12, y + 7),
            ]
            draw.polygon(bolt, fill=(255, 218, 76), outline=(0, 0, 0))

        self.battery_overlay_cache_key = cache_key
        self.battery_overlay_cache = overlay
        return overlay

    def _draw_battery_overlay(self, image: Image.Image) -> Image.Image:
        overlay = self._get_battery_overlay()
        if overlay is None:
            return image
        return Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB")

    def _decorate_with_battery(self, image: Image.Image) -> Image.Image:
        return self._draw_battery_overlay(image)

    @staticmethod
    def _fast_blur(image: Image.Image, amount: float) -> Image.Image:
        if amount <= 0:
            return image.copy()
        factor = max(1, int(round(1 + (amount * 0.9))))
        reduced = image.resize(
            (max(1, WIDTH // factor), max(1, HEIGHT // factor)),
            Image.Resampling.BILINEAR,
        )
        return reduced.resize((WIDTH, HEIGHT), Image.Resampling.BILINEAR)

    def _get_modal_background(self) -> Image.Image:
        with self.latest_frame_lock:
            base = self.latest_display_frame.copy() if self.latest_display_frame else None
            frame_id = self.latest_preview_frame_id

        if base is None:
            return Image.new("RGB", (WIDTH, HEIGHT), (230, 234, 238))

        if self.modal_background_image is not None and self.modal_background_frame_id == frame_id:
            return self.modal_background_image.copy()

        blurred = self._fast_blur(base, 10.0).convert("RGBA")
        softened = Image.alpha_composite(
            blurred,
            Image.new("RGBA", (WIDTH, HEIGHT), (255, 255, 255, 118)),
        ).convert("RGB")
        self.modal_background_image = softened
        self.modal_background_frame_id = frame_id
        return softened.copy()

    def _draw_chip(
        self,
        draw: ImageDraw.ImageDraw,
        box: tuple[int, int, int, int],
        label: str,
        *,
        active: bool = False,
        badge: str | None = None,
    ) -> None:
        fill = (255, 255, 255) if active else (18, 18, 18)
        outline = (255, 255, 255)
        text_fill = (0, 0, 0) if active else (255, 255, 255)
        draw.rounded_rectangle(box, radius=7, fill=fill, outline=outline, width=2)
        font = self._load_font(12)
        text_width = draw.textlength(label, font=font)
        text_x = box[0] + ((box[2] - box[0]) - text_width) / 2
        text_y = box[1] + 8
        draw.text((text_x, text_y), label, font=font, fill=text_fill)
        if badge:
            badge_box = (box[2] - 18, box[1] - 6, box[2] + 6, box[1] + 18)
            draw.rounded_rectangle(badge_box, radius=8, fill=(255, 193, 7))
            badge_font = self._load_font(10)
            badge_text = self._truncate_text_pixels(badge, badge_font, 18)
            badge_width = draw.textlength(badge_text, font=badge_font)
            draw.text(
                (badge_box[0] + ((badge_box[2] - badge_box[0]) - badge_width) / 2, badge_box[1] + 3),
                badge_text,
                font=badge_font,
                fill=(0, 0, 0),
            )

    def _draw_sparkle_icon(
        self,
        draw: ImageDraw.ImageDraw,
        center_x: int,
        center_y: int,
        *,
        color: tuple[int, int, int] = (18, 18, 18),
        radius: int = 6,
    ) -> None:
        draw.line((center_x - radius, center_y, center_x + radius, center_y), fill=color, width=2)
        draw.line((center_x, center_y - radius, center_x, center_y + radius), fill=color, width=2)
        draw.line(
            (center_x - radius + 1, center_y - radius + 1, center_x + radius - 1, center_y + radius - 1),
            fill=color,
            width=1,
        )
        draw.line(
            (center_x - radius + 1, center_y + radius - 1, center_x + radius - 1, center_y - radius + 1),
            fill=color,
            width=1,
        )

    def _draw_album_sparkle(self, draw: ImageDraw.ImageDraw, center_x: int, center_y: int) -> None:
        phase = time.monotonic() * 5.0
        radius = 4 + int(((math.sin(phase) + 1.0) / 2.0) * 3.0)
        self._draw_sparkle_icon(
            draw,
            center_x,
            center_y,
            color=(255, 238, 130),
            radius=radius,
        )

    def _apply_glass_panel(
        self,
        image: Image.Image,
        box: tuple[int, int, int, int],
        *,
        radius: int = 14,
        fill: tuple[int, int, int, int] = (255, 255, 255, 172),
        outline: tuple[int, int, int, int] = (255, 255, 255, 230),
        width: int = 2,
    ) -> Image.Image:
        overlay = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        draw = ImageDraw.Draw(overlay)
        draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)
        return Image.alpha_composite(image, overlay)

    def _draw_tab_icon(
        self,
        draw: ImageDraw.ImageDraw,
        box: tuple[int, int, int, int],
        icon: str,
    ) -> None:
        x0, y0, x1, y1 = box
        cx = (x0 + x1) // 2
        cy = (y0 + y1) // 2
        color = (18, 18, 18)

        if icon == "sparkle":
            self._draw_sparkle_icon(draw, cx, cy, color=color, radius=6)
            return

        if icon == "album":
            draw.rounded_rectangle((cx - 9, cy - 6, cx + 7, cy + 7), radius=3, outline=color, width=2)
            draw.rounded_rectangle((cx - 6, cy - 9, cx + 10, cy + 4), radius=3, outline=color, width=2)
            draw.line((cx - 5, cy + 3, cx + 4, cy + 3), fill=color, width=2)
            return

        if icon == "check":
            draw.line((cx - 8, cy + 1, cx - 2, cy + 7), fill=color, width=3)
            draw.line((cx - 2, cy + 7, cx + 9, cy - 6), fill=color, width=3)
            return

        if icon == "back":
            draw.line((cx - 8, cy, cx + 8, cy), fill=color, width=3)
            draw.line((cx - 8, cy, cx - 1, cy - 7), fill=color, width=3)
            draw.line((cx - 8, cy, cx - 1, cy + 7), fill=color, width=3)
            return

        if icon == "download":
            draw.line((cx, cy - 8, cx, cy + 3), fill=color, width=3)
            draw.line((cx - 6, cy - 1, cx, cy + 5), fill=color, width=3)
            draw.line((cx + 6, cy - 1, cx, cy + 5), fill=color, width=3)
            draw.line((cx - 9, cy + 9, cx + 9, cy + 9), fill=color, width=3)
            draw.line((cx - 9, cy + 9, cx - 9, cy + 4), fill=color, width=2)
            draw.line((cx + 9, cy + 9, cx + 9, cy + 4), fill=color, width=2)
            return

        if icon == "up_arrow":
            points = [(cx, cy - 7), (cx - 8, cy + 5), (cx + 8, cy + 5)]
            draw.polygon(points, fill=color)
            return

        if icon == "down_arrow":
            points = [(cx, cy + 7), (cx - 8, cy - 5), (cx + 8, cy - 5)]
            draw.polygon(points, fill=color)
            return

    def _resolve_tab_side(self, side: str) -> str:
        """Map logical tab sides to the physical side the buttons live on.

        Call sites use "left" for the controls side (the original HAT has its
        buttons left of the panel). UI_CONTROLS_SIDE=right mirrors every tab
        for chassis designs with the button cluster right of the screen.
        """
        controls = getattr(self, "_ui_controls_side", None)
        if controls is None:
            controls = os.environ.get("UI_CONTROLS_SIDE", "left").strip().lower()
            controls = controls if controls in {"left", "right"} else "left"
            self._ui_controls_side = controls
        if controls == "right":
            return "right" if side == "left" else "left"
        return side

    def _draw_side_tab(
        self,
        image: Image.Image,
        label: str | None = None,
        *,
        icon: str | None = None,
        y: int,
        side: str = "left",
        active: bool = False,
    ) -> Image.Image:
        side = self._resolve_tab_side(side)
        font = self._load_font(12)
        probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
        width = 42 if icon and not label else max(60, min(90, int(probe.textlength(label or "", font=font)) + 18))
        height = 32
        radius = 11
        inset = 6
        right_overhang = 10
        if side == "left":
            x0 = inset
            x1 = x0 + width
        else:
            x1 = WIDTH + right_overhang
            x0 = x1 - width
        y0 = y
        y1 = y0 + height
        fill = (255, 255, 255, 220) if active else (255, 255, 255, 170)
        outline = (255, 255, 255, 232)
        text_fill = (18, 18, 18)
        image = self._apply_glass_panel(
            image,
            (x0, y0, x1, y1),
            radius=radius,
            fill=fill,
            outline=outline,
            width=2,
        )
        draw = ImageDraw.Draw(image)
        if icon:
            self._draw_tab_icon(draw, (x0, y0, x1, y1), icon)
        elif label:
            text_width = draw.textlength(label, font=font)
            text_x = x0 + ((x1 - x0) - text_width) / 2
            draw.text((text_x, y0 + 8), label, font=font, fill=text_fill)
        return image

    def _draw_scroll_hints(self, image: Image.Image) -> Image.Image:
        return image

    def _flatten_wrapped_copy(
        self,
        blocks: list[str],
        font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
        max_width: int,
        max_lines: int,
    ) -> list[str]:
        lines: list[str] = []
        for block in blocks:
            if not block:
                continue
            lines.extend(self._wrap_text_pixels(block, font, max_width))
            if len(lines) >= max_lines:
                break
        if len(lines) > max_lines:
            lines = lines[:max_lines]
            lines[-1] = self._truncate_text_pixels(lines[-1], font, max_width)
        return lines

    def _magic_prompt_display_text(self) -> str:
        with self.state_lock:
            status_message = self.state.status_message

        if status_message == "Magic mode failed":
            if self.current_magic_prompt is not None:
                return self.current_magic_prompt.title
            return "Recipe failed"

        if self.magic_prompt_pending:
            return "Making magic recipe"

        if self.current_magic_prompt is None:
            return "Snap a photo of something unique"

        return self.current_magic_prompt.title

    def _render_magic_prompt_overlay(
        self,
        screen: Image.Image,
        *,
        title_box: tuple[int, int, int, int],
        title_font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
    ) -> Image.Image:
        screen = self._draw_side_tab(screen, icon="back", y=SIDE_CONTROL_TOP_Y, side="left")
        screen = self._draw_side_tab(screen, icon="album", y=SIDE_CONTROL_BOTTOM_Y, side="left")
        magic_title = self._truncate_text_pixels(
            self._magic_prompt_display_text(),
            title_font,
            title_box[2] - title_box[0] - 18,
        )
        screen = self._apply_glass_panel(
            screen,
            title_box,
            radius=12,
            fill=(248, 204, 96, 214),
            outline=(255, 233, 168, 238),
        )

        draw = ImageDraw.Draw(screen)
        text_box = draw.textbbox((0, 0), magic_title, font=title_font)
        text_w = text_box[2] - text_box[0]
        text_h = text_box[3] - text_box[1]
        text_x = title_box[0] + ((title_box[2] - title_box[0]) - text_w) / 2
        text_y = title_box[1] + ((title_box[3] - title_box[1]) - text_h) / 2 - text_box[1]
        draw.text((text_x, text_y), magic_title, font=title_font, fill=(18, 18, 18))
        return screen

    def _pending_jobs_for_badge(self) -> int:
        with self.state_lock:
            return max(0, min(9, self.state.pending_jobs))

    def _preview_chrome_key(self) -> tuple[object, ...]:
        pending = self._pending_jobs_for_badge()
        if self.magic_mode_active:
            return (
                "magic",
                self._magic_prompt_display_text(),
                self.magic_prompt_pending,
                self.current_magic_prompt.title if self.current_magic_prompt else "",
                pending,
            )
        current_entry = self.prompt_entries[self.prompt_order[self.selected_prompt_index]]
        return (
            "normal",
            current_entry["title"],
            self.ready_unseen_count > 0,
            pending,
        )

    def _build_preview_chrome_overlay(self) -> Image.Image:
        screen = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        title_font = self._load_font(15)
        title_box = (58, 12, WIDTH - 48, 44)
        if self.magic_mode_active:
            screen = self._render_magic_prompt_overlay(screen, title_box=title_box, title_font=title_font)
        else:
            screen = self._draw_side_tab(screen, icon="sparkle", y=SIDE_CONTROL_TOP_Y, side="left")
            screen = self._draw_side_tab(screen, icon="album", y=SIDE_CONTROL_BOTTOM_Y, side="left")
            draw = ImageDraw.Draw(screen)
            if self.ready_unseen_count > 0:
                sparkle_x = 42 if self._resolve_tab_side("left") == "left" else WIDTH - 42
                self._draw_sparkle_icon(draw, sparkle_x, SIDE_CONTROL_BOTTOM_Y + 8, color=(255, 238, 130), radius=6)

            current_entry = self.prompt_entries[self.prompt_order[self.selected_prompt_index]]
            title = self._truncate_text_pixels(
                current_entry["title"],
                title_font,
                title_box[2] - title_box[0] - 18,
            )
            screen = self._apply_glass_panel(
                screen,
                title_box,
                radius=12,
                fill=(255, 255, 255, 182),
                outline=(255, 255, 255, 232),
            )
            draw = ImageDraw.Draw(screen)
            text_box = draw.textbbox((0, 0), title, font=title_font)
            text_w = text_box[2] - text_box[0]
            text_h = text_box[3] - text_box[1]
            text_x = title_box[0] + ((title_box[2] - title_box[0]) - text_w) / 2
            text_y = title_box[1] + ((title_box[3] - title_box[1]) - text_h) / 2 - text_box[1]
            draw.text((text_x, text_y), title, font=title_font, fill=(18, 18, 18))

        self._draw_queue_badge(screen)
        return screen

    def _draw_queue_badge(self, screen: Image.Image) -> None:
        """Small right-edge pill showing how many photos are still generating."""
        pending = self._pending_jobs_for_badge()
        if pending <= 0:
            return
        draw = ImageDraw.Draw(screen)
        # Sits under the battery icon's corner on PiSugar builds, top-right otherwise.
        top = 40 if self.pisugar_enabled else 14
        badge_font = self._load_font(13)
        label = str(pending)
        label_w = draw.textlength(label, font=badge_font)
        box = (WIDTH - 34 - int(label_w) - 20, top, WIDTH - 12, top + 26)
        draw.rounded_rectangle(box, radius=13, fill=(18, 18, 18, 208), outline=(255, 255, 255, 232), width=2)
        # Amber three-quarter arc reads as "working".
        arc_box = (box[0] + 7, top + 7, box[0] + 19, top + 19)
        draw.arc(arc_box, start=300, end=210, fill=(255, 193, 7), width=2)
        draw.text((box[0] + 25, top + 5), label, font=badge_font, fill=(255, 255, 255))

    def _get_preview_chrome_overlay(self) -> Image.Image:
        cache_key = self._preview_chrome_key()
        if self.preview_chrome_cache_key != cache_key or self.preview_chrome_cache is None:
            self.preview_chrome_cache = self._build_preview_chrome_overlay()
            self.preview_chrome_cache_key = cache_key
        return self.preview_chrome_cache

    def _compose_preview_overlay(self, base: Image.Image) -> Image.Image:
        composed = Image.alpha_composite(base.convert("RGBA"), self._get_preview_chrome_overlay())
        battery_overlay = self._get_battery_overlay()
        if battery_overlay is not None:
            composed = Image.alpha_composite(composed, battery_overlay)
        return composed.convert("RGB")

    def _render_preview_frame(self) -> None:
        with self.latest_frame_lock:
            source = self.latest_preview_frame
            frame_id = self.latest_preview_frame_id
        if source is None:
            return
        base = self._build_display_preview_frame(source)
        with self.latest_frame_lock:
            if frame_id == self.latest_preview_frame_id:
                self.latest_display_frame = base
        self._render_to_display(self._compose_preview_overlay(base), decorate_battery=False)

    def _render_prompt_picker_frame(self) -> None:
        screen = self._get_modal_background().convert("RGBA")
        screen = self._draw_side_tab(screen, icon="check", y=SIDE_CONTROL_TOP_Y, side="left", active=True)
        screen = self._draw_side_tab(screen, icon="back", y=SIDE_CONTROL_BOTTOM_Y, side="left")
        screen = self._draw_scroll_hints(screen)
        item_font = self._load_font(14)
        visible_count = 4
        total_prompts = len(self.prompt_order)
        window_start = max(0, self.prompt_picker_index - 1)
        max_start = max(0, total_prompts - visible_count)
        window_start = min(window_start, max_start)
        visible_prompt_ids = self.prompt_order[window_start : window_start + visible_count]
        y = 28
        for absolute_index, button in enumerate(visible_prompt_ids, start=window_start):
            entry = self.prompt_entries[button]
            box = (54, y, WIDTH - 56, y + 38)
            active = absolute_index == self.prompt_picker_index
            fill = (255, 255, 255, 228) if active else (255, 255, 255, 154)
            screen = self._apply_glass_panel(
                screen,
                box,
                radius=12,
                fill=fill,
                outline=(255, 255, 255, 232),
            )
            draw = ImageDraw.Draw(screen)
            label = self._truncate_text_pixels(entry["title"], item_font, box[2] - box[0] - 24)
            draw.text((box[0] + 12, box[1] + 10), label, font=item_font, fill=(0, 0, 0))
            y += 44

        self._render_to_display(screen.convert("RGB"))

    def _load_generated_gallery_paths(self) -> list[Path]:
        paths = [path for path in self.generated_root.rglob("*") if self._is_generated_image_file(path)]
        return sorted(paths, key=lambda path: path.stat().st_mtime_ns, reverse=True)

    @staticmethod
    def _is_generated_image_file(path: Path) -> bool:
        return (
            path.is_file()
            and not path.name.startswith(".")
            and path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}
        )

    def delete_generated_image(self, relative_path: str) -> bool:
        decoded_relative = unquote(relative_path).lstrip("/")
        image_path = (self.generated_root / decoded_relative).resolve()
        try:
            image_path.relative_to(self.generated_root.resolve())
        except ValueError:
            return False
        if not self._is_generated_image_file(image_path):
            return False

        try:
            image_path.unlink()
        except OSError:
            logger.exception("Failed to delete generated image %s", image_path)
            return False

        metadata_path = self._get_generated_metadata_path(image_path)
        try:
            metadata_path.unlink()
        except FileNotFoundError:
            pass
        except OSError:
            logger.warning("Failed to delete generated image metadata %s", metadata_path)

        self.gallery_paths = [path for path in self.gallery_paths if path != image_path]
        if self.album_cached_path == image_path:
            self._invalidate_album_cache()
        self.album_index = min(self.album_index, max(0, len(self.gallery_paths) - 1))
        with self.state_lock:
            if self.state.last_generated_path == str(image_path):
                self.state.last_generated_path = (
                    str(self.gallery_paths[0]) if self.gallery_paths else None
                )
            self.state.ready_images = min(self.state.ready_images, len(self.gallery_paths))
        return True

    def _current_album_path(self) -> Path | None:
        if not self.gallery_paths:
            return None
        self.album_index = max(0, min(self.album_index, len(self.gallery_paths) - 1))
        return self.gallery_paths[self.album_index]

    def _get_album_display_image(self) -> Image.Image | None:
        path = self._current_album_path()
        if path is None:
            return None
        if self.album_cached_path == path and self.album_cached_image is not None:
            return self.album_cached_image.copy()

        try:
            with Image.open(path) as source:
                fitted = self._fit_generated_for_display(source.convert("RGB"))
        except Exception:
            logger.exception("Failed to load album image %s", path)
            self.gallery_paths = [candidate for candidate in self.gallery_paths if candidate != path]
            self._invalidate_album_cache()
            if not self.gallery_paths:
                self.album_index = 0
                return None
            self.album_index = min(self.album_index, len(self.gallery_paths) - 1)
            return self._get_album_display_image()

        self.album_cached_path = path
        self.album_cached_image = fitted
        return fitted.copy()

    def _get_generated_metadata_path(self, generated_path: Path) -> Path:
        return Path(f"{generated_path}.json")

    def _read_generation_metadata(self, generated_path: Path) -> dict[str, object]:
        metadata_path = self._get_generated_metadata_path(generated_path)
        if not metadata_path.is_file():
            return {}
        try:
            loaded = json.loads(metadata_path.read_text(encoding="utf-8"))
        except Exception:
            return {}
        if isinstance(loaded, dict):
            return loaded
        return {}

    def _save_generation_metadata_payload(
        self,
        generated_path: Path,
        metadata: dict[str, object],
    ) -> None:
        metadata_path = self._get_generated_metadata_path(generated_path)
        metadata_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")

    def _write_generation_metadata(
        self,
        generated_path: Path,
        capture_path: Path,
        *,
        extra: dict[str, str] | None = None,
    ) -> None:
        try:
            relative_capture = capture_path.relative_to(self.project_root).as_posix()
        except ValueError:
            relative_capture = str(capture_path)
        metadata = self._read_generation_metadata(generated_path)
        metadata["capture_path"] = relative_capture
        if extra:
            metadata.update(extra)
        self._save_generation_metadata_payload(generated_path, metadata)

    def _update_generation_metadata(
        self,
        generated_path: Path,
        updates: dict[str, object],
    ) -> None:
        metadata = self._read_generation_metadata(generated_path)
        metadata.update(updates)
        self._save_generation_metadata_payload(generated_path, metadata)

    def _resolve_capture_path(self, stored_path: str) -> Path | None:
        return self._resolve_project_path(stored_path)

    def _parse_media_timestamp(self, path: Path) -> datetime | None:
        parts = path.stem.split("-")
        if len(parts) < 2:
            return None
        try:
            if len(parts) >= 3 and len(parts[2]) == 6 and parts[2].isdigit():
                return datetime.strptime(f"{parts[0]}-{parts[1]}-{parts[2]}", "%Y%m%d-%H%M%S-%f")
            return datetime.strptime(f"{parts[0]}-{parts[1]}", "%Y%m%d-%H%M%S")
        except ValueError:
            return None

    def _find_capture_for_generated(self, generated_path: Path) -> Path | None:
        metadata_path = self._get_generated_metadata_path(generated_path)
        if metadata_path.is_file():
            try:
                metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            except Exception:
                metadata = {}
            stored_capture = metadata.get("capture_path")
            if isinstance(stored_capture, str):
                resolved = self._resolve_capture_path(stored_capture)
                if resolved is not None:
                    return resolved

        try:
            relative_day = generated_path.parent.relative_to(self.generated_root)
        except ValueError:
            relative_day = Path()
        capture_dir = self.capture_root / relative_day
        if not capture_dir.exists():
            return None

        generated_stem = generated_path.stem
        stem_without_suffix = (
            generated_stem[:-10] if generated_stem.endswith("-generated") else generated_stem
        )
        exact_matches = sorted(capture_dir.glob(f"{stem_without_suffix}-source.*"))
        if exact_matches:
            return exact_matches[0]

        stem_parts = stem_without_suffix.split("-")
        prompt_suffix = "-".join(stem_parts[2:]) if len(stem_parts) > 2 else ""
        pattern = f"*-{prompt_suffix}-source.*" if prompt_suffix else "*-source.*"
        candidates = sorted(path for path in capture_dir.glob(pattern) if path.is_file())
        if not candidates:
            return None

        generated_timestamp = self._parse_media_timestamp(generated_path)
        if generated_timestamp is None:
            return candidates[0]

        def candidate_key(path: Path) -> float:
            candidate_timestamp = self._parse_media_timestamp(path)
            if candidate_timestamp is None:
                return float("inf")
            return abs((candidate_timestamp - generated_timestamp).total_seconds())

        return min(candidates, key=candidate_key)

    def _get_album_source_display_image(self) -> Image.Image | None:
        generated_path = self._current_album_path()
        if generated_path is None:
            return None
        source_path = self._find_capture_for_generated(generated_path)
        if source_path is None:
            return None
        if self.album_source_cached_path == source_path and self.album_source_cached_image is not None:
            return self.album_source_cached_image.copy()

        try:
            with Image.open(source_path) as source:
                fitted = self._fit_generated_for_display(source.convert("RGB"))
        except Exception:
            logger.exception("Failed to load album source image %s", source_path)
            return None

        self.album_source_cached_path = source_path
        self.album_source_cached_image = fitted
        return fitted.copy()

    def _preload_album_compare_async(self) -> None:
        generated_path = self._current_album_path()
        if generated_path is None:
            return

        self.album_preload_generation += 1
        generation = self.album_preload_generation

        def worker() -> None:
            source_path = self._find_capture_for_generated(generated_path)
            if source_path is None:
                return
            if self.album_source_cached_path == source_path and self.album_source_cached_image is not None:
                return
            try:
                with Image.open(source_path) as source:
                    fitted = self._fit_generated_for_display(source.convert("RGB"))
            except Exception:
                return
            if generation != self.album_preload_generation:
                return
            current_path = self._current_album_path()
            if current_path != generated_path:
                return
            self.album_source_cached_path = source_path
            self.album_source_cached_image = fitted

        Thread(target=worker, daemon=True).start()

    def _invalidate_album_cache(self) -> None:
        self.album_cached_path = None
        self.album_cached_image = None
        self.album_source_cached_path = None
        self.album_source_cached_image = None
        self.album_qr_cached_path = None
        self.album_qr_cached_image = None
        self.album_qr_cached_url = None

    def _get_local_base_url(self) -> str:
        now = time.monotonic()
        if self.local_ip_cache and (now - self.local_ip_last_resolved_at) < 30.0:
            return self._format_web_url(self.local_ip_cache)

        resolved = None
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                client.connect(("8.8.8.8", 80))
                resolved = client.getsockname()[0]
        except OSError:
            try:
                candidates = socket.gethostbyname_ex(socket.gethostname())[2]
                resolved = next((candidate for candidate in candidates if not candidate.startswith("127.")), None)
            except OSError:
                resolved = None

        if not resolved:
            resolved = f"{socket.gethostname()}.local"

        self.local_ip_cache = resolved
        self.local_ip_last_resolved_at = now
        return self._format_web_url(resolved)

    def _format_web_url(self, host: str) -> str:
        if self.web_port == 80:
            return f"http://{host}"
        return f"http://{host}:{self.web_port}"

    def _get_short_app_url(self) -> str:
        hostname = socket.gethostname().strip() or "imagegencam"
        if not hostname.endswith(".local"):
            hostname = f"{hostname}.local"
        return self._format_web_url(hostname)

    def _get_wifi_ssid(self) -> str:
        try:
            return self.wifi_manager.current_ssid()
        except Exception:
            logger.exception("Failed to read Wi-Fi SSID")
            return "Unknown"

    def _get_mac_address(self) -> str:
        for interface in ("wlan0", "eth0"):
            address_path = Path("/sys/class/net") / interface / "address"
            try:
                value = address_path.read_text(encoding="utf-8").strip()
            except OSError:
                continue
            if value:
                return value
        return "Unknown"

    def _refresh_cpu_usage(self) -> int | None:
        now = time.monotonic()
        if self.cpu_usage_percent is not None and (now - self.cpu_last_read_at) < 1.0:
            return self.cpu_usage_percent

        try:
            cpu_line = Path("/proc/stat").read_text(encoding="utf-8").splitlines()[0]
            values = [int(part) for part in cpu_line.split()[1:]]
        except Exception:
            return self.cpu_usage_percent

        idle = values[3] + values[4]
        total = sum(values)
        if self.cpu_previous_totals is not None:
            previous_total, previous_idle = self.cpu_previous_totals
            delta_total = total - previous_total
            delta_idle = idle - previous_idle
            if delta_total > 0:
                busy = max(0.0, min(1.0, 1.0 - (delta_idle / delta_total)))
                self.cpu_usage_percent = int(round(busy * 100))
        self.cpu_previous_totals = (total, idle)
        self.cpu_last_read_at = now
        return self.cpu_usage_percent

    def _get_web_app_qr_image(self) -> tuple[Image.Image, str]:
        url = f"{self._get_local_base_url()}/"
        if self.web_app_qr_image is not None and self.web_app_qr_url == url:
            return self.web_app_qr_image.copy(), url

        qr = qrcode.QRCode(border=1, box_size=4, error_correction=qrcode.constants.ERROR_CORRECT_M)
        qr.add_data(url)
        qr.make(fit=True)
        qr_image = qr.make_image(fill_color="black", back_color="white").convert("RGB")
        qr_image = ImageOps.contain(qr_image, (92, 92), Image.Resampling.NEAREST)
        panel = Image.new("RGB", (104, 104), (255, 255, 255))
        panel.paste(qr_image, ((104 - qr_image.width) // 2, (104 - qr_image.height) // 2))
        self.web_app_qr_image = panel
        self.web_app_qr_url = url
        return panel.copy(), url

    def _render_diagnostics_frame(self) -> None:
        screen = self._get_modal_background().convert("RGBA")
        screen = self._draw_side_tab(screen, label="Wi-Fi", y=SIDE_CONTROL_TOP_Y, side="left", active=True)
        screen = self._draw_side_tab(screen, icon="back", y=SIDE_CONTROL_BOTTOM_Y, side="left")
        screen = self._draw_side_tab(screen, label="INFO", y=SIDE_CONTROL_TOP_Y, side="right")
        screen = self._apply_glass_panel(
            screen,
            (76, 16, WIDTH - 18, HEIGHT - 16),
            radius=18,
            fill=(255, 255, 255, 192),
            outline=(255, 255, 255, 236),
        )
        draw = ImageDraw.Draw(screen)
        title_font = self._load_font(15)
        body_font = self._load_font(12)
        value_font = self._load_font(11)
        meta_font = self._load_font(9)
        note_font = self._load_font(8)
        content_x = 88
        draw.text((content_x, 28), "Phone app", font=title_font, fill=(18, 18, 18))

        ssid = self._get_wifi_ssid()
        short_url = self._get_short_app_url().replace("http://", "")
        draw.text((content_x, 62), "Wi-Fi", font=body_font, fill=(18, 18, 18))
        draw.text(
            (content_x, 82),
            self._truncate_text_pixels(ssid, value_font, 90),
            font=value_font,
            fill=(18, 18, 18),
        )
        draw.text((content_x, 116), "Open", font=body_font, fill=(18, 18, 18))
        for index, line in enumerate(self._wrap_text_pixels(short_url, meta_font, 90)[:3]):
            draw.text((content_x, 136 + index * 14), line, font=meta_font, fill=(18, 18, 18))
        note = "Note: your phone and camera must be on the same Wi-Fi network or mobile hotspot."
        for index, line in enumerate(self._wrap_text_pixels(note, note_font, 206)[:3]):
            draw.text((content_x, 176 + index * 12), line, font=note_font, fill=(60, 60, 60))
        draw.text((content_x, 214), "top-left: Wi-Fi / top-right: info", font=meta_font, fill=(60, 60, 60))

        qr_panel, _url = self._get_web_app_qr_image()
        qr_x = WIDTH - qr_panel.width - 30
        qr_y = 66
        screen.paste(qr_panel, (qr_x, qr_y))
        self._render_to_display(screen.convert("RGB"))

    def _render_diagnostics_detail_frame(self) -> None:
        screen = self._get_modal_background().convert("RGBA")
        screen = self._draw_side_tab(screen, label="Wi-Fi", y=SIDE_CONTROL_TOP_Y, side="left", active=True)
        screen = self._draw_side_tab(screen, icon="back", y=SIDE_CONTROL_BOTTOM_Y, side="left")
        screen = self._apply_glass_panel(
            screen,
            (52, 16, WIDTH - 18, HEIGHT - 16),
            radius=18,
            fill=(255, 255, 255, 194),
            outline=(255, 255, 255, 236),
        )
        draw = ImageDraw.Draw(screen)
        title_font = self._load_font(15)
        body_font = self._load_font(12)
        value_font = self._load_font(11)
        draw.text((68, 28), "Diagnostics", font=title_font, fill=(18, 18, 18))

        base_url = self._get_local_base_url()
        cpu_value = self._refresh_cpu_usage()
        ip_value = base_url.replace("http://", "").rstrip("/").split(":", 1)[0]
        labels = [
            ("Wi-Fi", self._get_wifi_ssid()),
            ("IP", ip_value),
            ("MAC", self._get_mac_address()),
            ("CPU", f"{cpu_value}%" if cpu_value is not None else "--"),
        ]
        y = 58
        for label, value in labels:
            draw.text((68, y), label, font=body_font, fill=(18, 18, 18))
            draw.text(
                (110, y + 1),
                self._truncate_text_pixels(value, value_font, 154),
                font=value_font,
                fill=(18, 18, 18),
            )
            y += 24
        self._render_to_display(screen.convert("RGB"))

    def _render_wifi_menu_frame(self) -> None:
        screen = self._get_modal_background().convert("RGBA")
        screen = self._draw_side_tab(screen, icon="check", y=SIDE_CONTROL_TOP_Y, side="left", active=True)
        screen = self._draw_side_tab(screen, icon="back", y=SIDE_CONTROL_BOTTOM_Y, side="left")
        screen = self._draw_scroll_hints(screen)
        screen = self._apply_glass_panel(
            screen,
            (52, 14, WIDTH - 52, HEIGHT - 14),
            radius=18,
            fill=(255, 255, 255, 194),
            outline=(255, 255, 255, 236),
        )
        draw = ImageDraw.Draw(screen)
        title_font = self._load_font(15)
        item_font = self._load_font(12)
        meta_font = self._load_font(10)
        draw.text((68, 26), "Wi-Fi", font=title_font, fill=(18, 18, 18))
        draw.text((120, 29), self._truncate_text_pixels(self._get_wifi_ssid(), meta_font, 120), font=meta_font, fill=(60, 60, 60))

        if not self.wifi_networks:
            draw.text((74, 96), "No networks found", font=item_font, fill=(18, 18, 18))
            draw.text((74, 116), "Press shutter to scan", font=meta_font, fill=(60, 60, 60))
            self._render_to_display(screen.convert("RGB"))
            return

        visible_count = 4
        window_start = max(0, self.wifi_network_index - 1)
        max_start = max(0, len(self.wifi_networks) - visible_count)
        window_start = min(window_start, max_start)
        y = 50
        for absolute_index, network in enumerate(
            self.wifi_networks[window_start : window_start + visible_count],
            start=window_start,
        ):
            active = absolute_index == self.wifi_network_index
            box = (66, y, WIDTH - 66, y + 36)
            fill = (20, 20, 20, 228) if active else (255, 255, 255, 138)
            outline = (20, 20, 20, 245) if active else (255, 255, 255, 230)
            screen = self._apply_glass_panel(screen, box, radius=11, fill=fill, outline=outline, width=2)
            draw = ImageDraw.Draw(screen)
            prefix = "* " if network.active else ""
            label = self._truncate_text_pixels(f"{prefix}{network.ssid}", item_font, box[2] - box[0] - 44)
            text_fill = (255, 255, 255) if active else (18, 18, 18)
            meta_fill = (225, 225, 225) if active else (60, 60, 60)
            draw.text((box[0] + 9, box[1] + 8), label, font=item_font, fill=text_fill)
            if network.saved and not network.active:
                dot_color = (255, 204, 72) if active else (28, 132, 255)
                draw.ellipse((box[2] - 44, box[1] + 13, box[2] - 34, box[1] + 23), fill=dot_color)
            signal = "--" if network.signal is None else str(network.signal)
            draw.text((box[2] - 28, box[1] + 10), signal, font=meta_font, fill=meta_fill)
            y += 40

        if self.wifi_connect_message:
            draw.text(
                (68, HEIGHT - 30),
                self._truncate_text_pixels(self.wifi_connect_message, meta_font, WIDTH - 138),
                font=meta_font,
                fill=(60, 60, 60),
            )
        else:
            draw.text((68, HEIGHT - 30), "select: network   shutter: scan", font=meta_font, fill=(60, 60, 60))
        self._render_to_display(screen.convert("RGB"))

    def _wifi_detail_options(self) -> list[str]:
        network = self.wifi_selected_network
        if network is None:
            return ["Back"]
        if network.active:
            return ["Current Network", "Enter Password", "Back"] if network.secure else ["Current Network", "Back"]
        if network.saved and network.secure:
            return ["Connect", "Enter Password", "Back"]
        if network.secure:
            return ["Enter Password", "Back"]
        return ["Connect", "Back"]

    def _render_wifi_detail_frame(self) -> None:
        network = self.wifi_selected_network
        if network is None:
            self._render_wifi_menu_frame()
            return

        screen = self._get_modal_background().convert("RGBA")
        screen = self._draw_side_tab(screen, icon="check", y=SIDE_CONTROL_TOP_Y, side="left", active=True)
        screen = self._draw_side_tab(screen, icon="back", y=SIDE_CONTROL_BOTTOM_Y, side="left")
        screen = self._draw_scroll_hints(screen)
        screen = self._apply_glass_panel(
            screen,
            (52, 14, WIDTH - 52, HEIGHT - 14),
            radius=18,
            fill=(255, 255, 255, 202),
            outline=(255, 255, 255, 236),
        )
        draw = ImageDraw.Draw(screen)
        title_font = self._load_font(15)
        item_font = self._load_font(12)
        meta_font = self._load_font(10)
        draw.text(
            (68, 25),
            self._truncate_text_pixels(network.ssid, title_font, WIDTH - 142),
            font=title_font,
            fill=(18, 18, 18),
        )
        tags: list[str] = []
        if network.active:
            tags.append("connected")
        if network.saved:
            tags.append("saved")
        if network.secure:
            tags.append("locked")
        if network.signal is not None:
            tags.append(f"{network.signal}%")
        draw.text((68, 46), " · ".join(tags), font=meta_font, fill=(60, 60, 60))

        options = self._wifi_detail_options()
        self.wifi_detail_index = max(0, min(self.wifi_detail_index, len(options) - 1))
        y = 74
        for index, option in enumerate(options):
            active = index == self.wifi_detail_index
            box = (72, y, WIDTH - 72, y + 34)
            fill = (20, 20, 20, 228) if active else (255, 255, 255, 148)
            outline = (20, 20, 20, 245) if active else (255, 255, 255, 230)
            screen = self._apply_glass_panel(screen, box, radius=11, fill=fill, outline=outline, width=2)
            draw = ImageDraw.Draw(screen)
            draw.text(
                (box[0] + 10, box[1] + 8),
                self._truncate_text_pixels(option, item_font, box[2] - box[0] - 20),
                font=item_font,
                fill=(255, 255, 255) if active else (18, 18, 18),
            )
            y += 40

        draw.text((68, HEIGHT - 30), "select: choose action", font=meta_font, fill=(60, 60, 60))
        self._render_to_display(screen.convert("RGB"))

    def _render_wifi_keyboard_frame(self) -> None:
        network_name = self.wifi_selected_network.ssid if self.wifi_selected_network else "Network"
        screen = self._get_modal_background().convert("RGBA")
        screen = self._draw_side_tab(screen, label="TYPE", y=SIDE_CONTROL_TOP_Y, side="left", active=True)
        screen = self._draw_side_tab(screen, label="DEL", y=SIDE_CONTROL_BOTTOM_Y, side="left")
        screen = self._draw_scroll_hints(screen)
        screen = self._apply_glass_panel(
            screen,
            (52, 14, WIDTH - 52, HEIGHT - 14),
            radius=18,
            fill=(255, 255, 255, 198),
            outline=(255, 255, 255, 236),
        )
        draw = ImageDraw.Draw(screen)
        title_font = self._load_font(14)
        body_font = self._load_font(12)
        meta_font = self._load_font(10)
        draw.text((68, 26), self._truncate_text_pixels(network_name, title_font, 170), font=title_font, fill=(18, 18, 18))
        masked = "*" * min(len(self.wifi_password), 18)
        draw.rounded_rectangle((68, 54, WIDTH - 68, 84), radius=9, fill=(255, 255, 255), outline=(220, 220, 220), width=2)
        draw.text((80, 62), masked or "password", font=body_font, fill=(18, 18, 18) if masked else (110, 110, 110))

        current_char = WIFI_KEYBOARD_CHARS[self.wifi_keyboard_index]
        current_label = "space" if current_char == " " else current_char
        draw.rounded_rectangle((118, 98, WIDTH - 118, 144), radius=14, fill=(255, 255, 255), outline=(18, 18, 18), width=2)
        char_font = self._load_font(22 if current_char == " " else 28)
        char_width = draw.textlength(current_label, font=char_font)
        draw.text((WIDTH / 2 - char_width / 2, 106 if current_char == " " else 103), current_label, font=char_font, fill=(18, 18, 18))

        draw.text((75, 158), "right buttons: choose char", font=meta_font, fill=(60, 60, 60))
        draw.text((75, 174), "top-left: type   bottom-left: delete", font=meta_font, fill=(60, 60, 60))
        draw.text((75, 190), "shutter: connect", font=meta_font, fill=(60, 60, 60))
        self._render_to_display(screen.convert("RGB"))

    def _render_wifi_connecting_frame(self) -> None:
        screen = self._get_modal_background().convert("RGBA")
        screen = self._apply_glass_panel(
            screen,
            (54, 44, WIDTH - 54, HEIGHT - 44),
            radius=18,
            fill=(255, 255, 255, 205),
            outline=(255, 255, 255, 236),
        )
        draw = ImageDraw.Draw(screen)
        title_font = self._load_font(16)
        body_font = self._load_font(12)
        draw.text((82, 82), "Trying Wi-Fi...", font=title_font, fill=(18, 18, 18))
        draw.text(
            (82, 112),
            self._truncate_text_pixels(self.wifi_connect_message or "Rollback is armed.", body_font, 160),
            font=body_font,
            fill=(60, 60, 60),
        )
        self._render_to_display(screen.convert("RGB"))

    def _render_wifi_confirm_frame(self) -> None:
        screen = self._get_modal_background().convert("RGBA")
        screen = self._draw_side_tab(screen, label="KEEP", y=SIDE_CONTROL_TOP_Y, side="left", active=True)
        screen = self._draw_side_tab(screen, label="UNDO", y=SIDE_CONTROL_BOTTOM_Y, side="left")
        screen = self._apply_glass_panel(
            screen,
            (54, 36, WIDTH - 46, HEIGHT - 36),
            radius=18,
            fill=(255, 255, 255, 205),
            outline=(255, 255, 255, 236),
        )
        draw = ImageDraw.Draw(screen)
        title_font = self._load_font(16)
        body_font = self._load_font(12)
        meta_font = self._load_font(10)
        seconds_left = 0
        if self.wifi_pending_rollback:
            seconds_left = max(0, int(self.wifi_pending_rollback.expires_at - time.monotonic()))
        draw.text((78, 62), "Wi-Fi changed", font=title_font, fill=(18, 18, 18))
        draw.text((78, 94), self._truncate_text_pixels(self._get_wifi_ssid(), body_font, 170), font=body_font, fill=(18, 18, 18))
        draw.text((78, 122), "Press KEEP if this works.", font=body_font, fill=(60, 60, 60))
        draw.text((78, 144), f"Auto-rollback in {seconds_left}s", font=meta_font, fill=(60, 60, 60))
        self._render_to_display(screen.convert("RGB"))

    def _build_current_album_download_url(self) -> str | None:
        path = self._current_album_path()
        if path is None:
            return None
        relative_path = path.relative_to(self.generated_root).as_posix()
        return f"{self._get_local_base_url()}/generated/{quote(relative_path)}"

    def _get_album_qr_image(self) -> tuple[Image.Image | None, str | None]:
        path = self._current_album_path()
        if path is None:
            return None, None
        url = self._build_current_album_download_url()
        if url is None:
            return None, None
        if (
            self.album_qr_cached_path == path
            and self.album_qr_cached_image is not None
            and self.album_qr_cached_url == url
        ):
            return self.album_qr_cached_image.copy(), url

        qr = qrcode.QRCode(border=1, box_size=4, error_correction=qrcode.constants.ERROR_CORRECT_M)
        qr.add_data(url)
        qr.make(fit=True)
        qr_image = qr.make_image(fill_color="black", back_color="white").convert("RGB")
        qr_image = ImageOps.contain(qr_image, (136, 136), Image.Resampling.NEAREST)
        panel = Image.new("RGB", (148, 148), (255, 255, 255))
        panel.paste(qr_image, ((148 - qr_image.width) // 2, (148 - qr_image.height) // 2))

        self.album_qr_cached_path = path
        self.album_qr_cached_url = url
        self.album_qr_cached_image = panel
        return panel.copy(), url

    def _render_album_frame(self) -> None:
        image = (
            self._get_album_source_display_image()
            if self.album_show_source
            else self._get_album_display_image()
        )
        if image is None:
            screen = self._get_modal_background().convert("RGBA")
            screen = self._draw_side_tab(screen, icon="download", y=SIDE_CONTROL_TOP_Y, side="left", active=True)
            screen = self._draw_side_tab(screen, icon="back", y=SIDE_CONTROL_BOTTOM_Y, side="left")
            screen = self._draw_scroll_hints(screen)
            draw = ImageDraw.Draw(screen)
            title_font = self._load_font(22)
            body_font = self._load_font(14)
            if self.gallery_paths and self.album_show_source:
                draw.text((76, 82), "No Source Found", font=title_font, fill=(18, 18, 18))
                draw.text((76, 110), "Press shutter to go back.", font=body_font, fill=(18, 18, 18))
            else:
                draw.text((86, 82), "Album Empty", font=title_font, fill=(18, 18, 18))
                draw.text((86, 110), "Take a photo to add one.", font=body_font, fill=(18, 18, 18))
            self._render_to_display(screen.convert("RGB"))
            return

        screen = image.convert("RGBA")
        overlay = Image.new("RGBA", (WIDTH, HEIGHT), (255, 255, 255, 38))
        screen = Image.alpha_composite(screen, overlay)
        screen = self._draw_side_tab(screen, icon="download", y=SIDE_CONTROL_TOP_Y, side="left", active=True)
        screen = self._draw_side_tab(screen, icon="back", y=SIDE_CONTROL_BOTTOM_Y, side="left")
        screen = self._draw_scroll_hints(screen)

        self._render_to_display(screen.convert("RGB"))

    def _render_album_download_frame(self) -> None:
        qr_panel, _download_url = self._get_album_qr_image()
        if qr_panel is None:
            self._render_album_frame()
            return

        base = self._get_album_display_image()
        if base is None:
            screen = self._get_modal_background().convert("RGBA")
        else:
            screen = self._fast_blur(base, 10.0).convert("RGBA")
            screen = Image.alpha_composite(
                screen,
                Image.new("RGBA", (WIDTH, HEIGHT), (255, 255, 255, 108)),
            )

        screen = self._draw_side_tab(screen, icon="back", y=SIDE_CONTROL_BOTTOM_Y, side="left")
        screen = self._apply_glass_panel(
            screen,
            (58, 18, WIDTH - 58, HEIGHT - 18),
            radius=18,
            fill=(255, 255, 255, 196),
            outline=(255, 255, 255, 236),
        )
        panel_box = (58, 18, WIDTH - 58, HEIGHT - 18)
        qr_x = panel_box[0] + ((panel_box[2] - panel_box[0]) - qr_panel.width) // 2
        qr_y = panel_box[1] + ((panel_box[3] - panel_box[1]) - qr_panel.height) // 2
        screen.paste(qr_panel, (qr_x, qr_y))
        self._render_to_display(screen.convert("RGB"))

    def _render_capture_feedback_frame(self) -> None:
        if self.capture_feedback_frame is None:
            self._render_preview_frame()
            return

        elapsed = time.monotonic() - self.capture_feedback_started_at
        with self.latest_frame_lock:
            live_frame = self.latest_display_frame.copy() if self.latest_display_frame else None

        if live_frame is None:
            live_frame = self.capture_feedback_frame

        total_duration = self.capture_feedback_duration_seconds

        progress = min(1.0, max(0.0, elapsed / max(0.01, total_duration)))
        composed = Image.blend(self.capture_feedback_frame, live_frame, progress).convert("RGBA")
        flash_alpha = int(220 * ((1.0 - progress) ** 1.8))
        if flash_alpha > 0:
            overlay = Image.new("RGBA", (WIDTH, HEIGHT), (255, 255, 255, flash_alpha))
            composed = Image.alpha_composite(composed, overlay)
        self._render_to_display(composed.convert("RGB"))

    def _timestamp(self) -> str:
        return datetime.now().strftime("%Y%m%d-%H%M%S-%f")

    def _save_capture(self, image: Image.Image, button: str) -> Path:
        day_dir = self.capture_root / datetime.now().strftime("%Y-%m-%d")
        day_dir.mkdir(parents=True, exist_ok=True)
        path = day_dir / f"{self._timestamp()}-{button.lower()}-source.jpg"
        image.save(path, format="JPEG", quality=90)
        return path

    def _save_magic_reference_capture(self, image: Image.Image) -> Path:
        day_dir = self.capture_root / datetime.now().strftime("%Y-%m-%d")
        day_dir.mkdir(parents=True, exist_ok=True)
        path = day_dir / f"{self._timestamp()}-magic-reference.jpg"
        image.save(path, format="JPEG", quality=90)
        return path

    def _save_generated_path(self, button: str) -> Path:
        day_dir = self.generated_root / datetime.now().strftime("%Y-%m-%d")
        day_dir.mkdir(parents=True, exist_ok=True)
        return day_dir / f"{self._timestamp()}-{button.lower()}-generated{self.image_editor.output_extension}"

    def recreate_vertical_from_generated(self, relative_path: str) -> dict[str, object]:
        decoded_relative = unquote(relative_path).lstrip("/")
        source_path = (self.generated_root / decoded_relative).resolve()
        try:
            source_path.relative_to(self.generated_root.resolve())
        except ValueError as exc:
            raise ValueError("Invalid generated image path.") from exc
        if not source_path.is_file():
            raise FileNotFoundError("Generated image not found.")

        story_prompt = (
            "Edit the attached image into a clean 9:16 vertical composition suitable for a phone screen. "
            "Zoom out or extend the scene naturally so the original content stays fully visible and centered in the taller frame. "
            "Preserve the same subject, style, lighting, and overall look. Do not add text, borders, or watermarks."
        )
        output_path = self._save_generated_path("story")
        with self.image_edit_lock:
            result_path = self.image_editor.edit_image(
                source_path,
                story_prompt,
                output_path,
                size="1024x1536",
            )

        try:
            relative_source = source_path.relative_to(self.project_root).as_posix()
        except ValueError:
            relative_source = str(source_path)
        self._write_generation_metadata(
            result_path,
            source_path,
            extra={"source_generated_path": relative_source, "variant": "story"},
        )
        with self.state_lock:
            self.state.last_generated_path = str(result_path)

        stat = result_path.stat()
        relative_generated = result_path.relative_to(self.generated_root).as_posix()
        return {
            "filename": result_path.name,
            "relative_path": relative_generated,
            "image_url": f"/generated/{quote(relative_generated)}",
            "download_url": f"/download/generated/{quote(relative_generated)}",
            "modified_unix": stat.st_mtime,
            "size_bytes": stat.st_size,
        }

    def _prepare_capture_feedback(self, display_frame: Image.Image | None) -> None:
        base = display_frame.copy() if display_frame is not None else Image.new("RGB", (WIDTH, HEIGHT), (0, 0, 0))
        self.capture_feedback_frame = base
        self.capture_feedback_started_at = time.monotonic()

    def _start_magic_mode(self) -> None:
        if not self.magic_mode_enabled:
            return
        self.magic_mode_active = True
        self._refresh_magic_state_snapshot()
        self.preview_overlay_dirty = True

    def _stop_magic_mode(self) -> None:
        self.magic_mode_active = False
        with self.state_lock:
            current_prompt = self.prompt_entries[self.prompt_order[self.selected_prompt_index]]["title"]
            self.state.mode = "preview"
            self.state.status_message = f"Ready with {current_prompt}"
        self._refresh_magic_state_snapshot()
        self.last_drawn_mode = None
        self.preview_overlay_dirty = True

    def _queue_magic_prompt_from_current_frame(self) -> None:
        if not self.magic_mode_enabled:
            return
        if self.magic_prompt_pending:
            with self.state_lock:
                self.state.status_message = "Magic prompt is still being written"
            return

        display_frame, source_frame = self._grab_capture_frames()

        if display_frame is None or source_frame is None:
            with self.state_lock:
                self.state.last_error = "No camera frame available yet."
                self.state.status_message = "Preview warming up"
            return

        self._start_magic_mode()
        self.magic_prompt_pending = True
        self._prepare_capture_feedback(display_frame)
        with self.state_lock:
            self.state.mode = "capture_feedback"
            self.state.last_error = None
            self.state.status_message = "Building a magic prompt from this reference photo"
            self.state.magic_mode_active = True
            self.state.magic_prompt_ready = False
            self.state.magic_prompt_title = None
        self.magic_prompt_queue.put(MagicSeedRequest(source_image=source_frame))
        self.preview_overlay_dirty = True

    def _enqueue_magic_generation_from_current_frame(self) -> None:
        if not self.magic_mode_enabled:
            self._enqueue_generation_from_current_frame()
            return
        if self.magic_prompt_pending:
            with self.state_lock:
                self.state.status_message = "Magic prompt is still being written"
            return
        if self.current_magic_prompt is None:
            with self.state_lock:
                self.state.status_message = "Press the shortcut button to create a magic prompt first"
            return

        display_frame, source_frame = self._grab_capture_frames()

        if display_frame is None or source_frame is None:
            with self.state_lock:
                self.state.last_error = "No camera frame available yet."
                self.state.status_message = "Preview warming up"
            return

        with self.state_lock:
            pending_jobs = self.state.pending_jobs
        if self.max_pending_generations and pending_jobs >= self.max_pending_generations:
            with self.state_lock:
                self.state.last_error = "Generation queue is full."
                self.state.status_message = "Queue full. Wait for album."
            return

        self._prepare_capture_feedback(display_frame)
        self.capture_queue.put(
            CaptureRequest(
                prompt_button="magic",
                prompt_title=self.current_magic_prompt.title,
                prompt_body=self.current_magic_prompt.body,
                source_image=source_frame,
                reference_paths=(self.current_magic_prompt.reference_capture_path,),
                magic_history_id=self.current_magic_prompt.history_id,
            )
        )
        with self.state_lock:
            self.state.mode = "capture_feedback"
            self.state.last_button = "magic"
            self.state.last_error = None
            self.state.pending_jobs += 1
            self.state.status_message = f"Using magic prompt: {self.current_magic_prompt.title}"
        self.preview_overlay_dirty = True

    def _enqueue_generation_from_current_frame(self) -> None:
        with self.state_lock:
            pending_jobs = self.state.pending_jobs
        if self.max_pending_generations and pending_jobs >= self.max_pending_generations:
            with self.state_lock:
                self.state.last_error = "Generation queue is full."
                self.state.status_message = "Queue full. Wait for album."
            return

        display_frame, source_frame = self._grab_capture_frames()

        if display_frame is None or source_frame is None:
            with self.state_lock:
                self.state.last_error = "No camera frame available yet."
                self.state.status_message = "Preview warming up"
            return

        prompt_button = self.prompt_order[self.selected_prompt_index]
        prompt_entry = self.prompt_entries[prompt_button]
        self._prepare_capture_feedback(display_frame)
        self.capture_queue.put(
            CaptureRequest(
                prompt_button=prompt_button,
                prompt_title=prompt_entry["title"],
                prompt_body=prompt_entry["body"],
                source_image=source_frame,
            )
        )

        with self.state_lock:
            self.state.mode = "capture_feedback"
            self.state.last_button = prompt_button
            self.state.last_error = None
            self.state.pending_jobs += 1
            self.state.status_message = f"{prompt_entry['title']} queued"
        self.preview_overlay_dirty = True

    def _capture_worker_loop(self) -> None:
        while self.running:
            try:
                request = self.capture_queue.get(timeout=0.25)
            except Empty:
                continue

            if request is None:
                return

            try:
                capture_image = self._fit_for_generation(request.source_image)
                capture_path = self._save_capture(capture_image, request.prompt_button)
                generated_path = self._save_generated_path(request.prompt_button)
                job = GenerationJob(
                    prompt_button=request.prompt_button,
                    prompt_title=request.prompt_title,
                    prompt_body=request.prompt_body,
                    capture_path=capture_path,
                    generated_path=generated_path,
                    reference_paths=request.reference_paths,
                    magic_history_id=request.magic_history_id,
                )
                with self.state_lock:
                    self.state.last_capture_path = str(capture_path)
                    self.state.last_error = None
                self._save_generation_job(job)
            except Exception as exc:
                logger.exception("Capture failed")
                with self.state_lock:
                    self.state.last_error = str(exc)
                    self.state.status_message = "Capture failed. Preview ready."
                    self.state.pending_jobs = max(0, self.state.pending_jobs - 1)
                self.preview_overlay_dirty = True

    def _magic_prompt_worker_loop(self) -> None:
        while self.running:
            try:
                request = self.magic_prompt_queue.get(timeout=0.25)
            except Empty:
                continue

            if request is None:
                return

            try:
                reference_image = self._fit_for_generation(request.source_image)
                reference_path = self._save_magic_reference_capture(reference_image)
                planned = self.magic_prompt_planner.create_magic_prompt(reference_path)
                try:
                    stored_reference = reference_path.relative_to(self.project_root).as_posix()
                except ValueError:
                    stored_reference = str(reference_path)
                history_entry = self.magic_history_store.add_entry(
                    {
                        "id": f"magic-{reference_path.stem}",
                        "created_at": datetime.now().isoformat(timespec="seconds"),
                        "title": planned["title"],
                        "body": planned["prompt"],
                        "reference_capture_path": stored_reference,
                        "promoted_prompt_id": None,
                    }
                )
                self.magic_history = self.magic_history_store.load_entries()
                self.current_magic_prompt = MagicPromptState(
                    history_id=str(history_entry["id"]),
                    title=str(history_entry["title"]),
                    body=str(history_entry["body"]),
                    reference_capture_path=reference_path,
                )
                with self.state_lock:
                    self.state.last_error = None
                    self.state.status_message = f"Magic prompt ready: {self.current_magic_prompt.title}"
                self._refresh_magic_state_snapshot()
                self.preview_overlay_dirty = True
            except (OpenAIImageError, Exception) as exc:
                logger.exception("Magic prompt generation failed")
                with self.state_lock:
                    self.state.last_error = str(exc)
                    self.state.status_message = "Magic mode failed"
                self.preview_overlay_dirty = True
            finally:
                self.magic_prompt_pending = False
                self._refresh_magic_state_snapshot()

    def _generation_worker_loop(self) -> None:
        while self.running:
            due_entry = self.generation_job_store.next_due_entry()
            if due_entry is None:
                time.sleep(0.25)
                continue

            job_id, payload = due_entry
            job = self._load_generation_job(payload)

            started_at = time.monotonic()
            try:
                if job.generated_path.is_file():
                    result_path = job.generated_path
                else:
                    with self.image_edit_lock:
                        result_path = self.image_editor.edit_image(
                            job.capture_path,
                            job.prompt_body,
                            job.generated_path,
                            reference_paths=list(job.reference_paths),
                        )
                extra_metadata: dict[str, str] = {}
                if job.reference_paths:
                    try:
                        extra_metadata["magic_reference_path"] = job.reference_paths[0].relative_to(
                            self.project_root
                        ).as_posix()
                    except ValueError:
                        extra_metadata["magic_reference_path"] = str(job.reference_paths[0])
                if job.magic_history_id:
                    extra_metadata["magic_history_id"] = job.magic_history_id
                self._write_generation_metadata(
                    result_path,
                    job.capture_path,
                    extra=extra_metadata or None,
                )
                self.generation_job_store.delete_entry(job_id)
                logger.info(
                    "Generation completed in %.2fs for %s",
                    time.monotonic() - started_at,
                    job.prompt_title,
                )
                self._register_completed_generation(result_path, job)
            except (OpenAIImageError, Exception) as exc:
                logger.exception("Generation failed")
                self._reschedule_generation_job(job_id, payload, exc)
                with self.state_lock:
                    self.state.last_error = str(exc)
                    self.state.status_message = "Generation queued for retry."
                self.preview_overlay_dirty = True

    def _register_completed_generation(self, result_path: Path, job: GenerationJob) -> None:
        current_album_path = self._current_album_path() if self.state.mode == "album" else None
        self.gallery_paths = [path for path in self.gallery_paths if path != result_path]
        self.gallery_paths.insert(0, result_path)
        self._invalidate_album_cache()

        if current_album_path is not None and current_album_path in self.gallery_paths:
            self.album_index = self.gallery_paths.index(current_album_path)
        else:
            self.album_index = 0

        with self.state_lock:
            self.state.last_generated_path = str(result_path)
            self.state.last_error = None
            self.state.pending_jobs = self.generation_job_store.count()
            if self.state.mode != "album":
                self.ready_unseen_count += 1
            else:
                self.ready_unseen_count = 0
            self.state.ready_images = self.ready_unseen_count
            self.state.status_message = f"{job.prompt_title} ready in album"
        self.preview_overlay_dirty = True

    def _exit_to_preview(self) -> None:
        with self.state_lock:
            self.state.mode = "preview"
            if self.magic_mode_active:
                if self.magic_prompt_pending:
                    self.state.status_message = "Finding magic..."
                elif self.current_magic_prompt is not None:
                    self.state.status_message = f"Magic ready: {self.current_magic_prompt.title}"
                else:
                    self.state.status_message = "Magic mode"
            else:
                current_prompt = self.prompt_entries[self.prompt_order[self.selected_prompt_index]]["title"]
                self.state.status_message = f"Ready with {current_prompt}"
        self._refresh_magic_state_snapshot()
        self.last_drawn_mode = None
        self.preview_overlay_dirty = True

    def _enter_prompt_picker(self) -> None:
        self.prompt_picker_index = self.selected_prompt_index
        with self.state_lock:
            self.state.mode = "prompt_picker"
            self.state.status_message = "Choose a prompt"
        self.last_drawn_mode = None

    def _select_prompt_from_picker(self) -> None:
        self.selected_prompt_index = self.prompt_picker_index
        selected_button = self.prompt_order[self.selected_prompt_index]
        with self.state_lock:
            self.state.current_prompt_button = selected_button
            self.state.status_message = f"Ready with {self.prompt_entries[selected_button]['title']}"
            self.state.mode = "preview"
        self.last_drawn_mode = None
        self.preview_overlay_dirty = True

    def _enter_album(self) -> None:
        self.gallery_paths = self._load_generated_gallery_paths()
        self.album_index = 0
        self.album_show_source = False
        self.ready_unseen_count = 0
        self._invalidate_album_cache()
        self._preload_album_compare_async()
        with self.state_lock:
            self.state.mode = "album"
            self.state.ready_images = 0
            self.state.status_message = "Album"
        self.last_drawn_mode = None
        self.preview_overlay_dirty = True

    def _enter_album_download(self) -> None:
        if self._current_album_path() is None:
            return
        with self.state_lock:
            self.state.mode = "album_download"
            self.state.status_message = "Scan QR to download"
        self.last_drawn_mode = None

    def _enter_diagnostics(self) -> None:
        with self.state_lock:
            self.state.mode = "diagnostics"
            self.state.status_message = "Diagnostics"
        self.last_drawn_mode = None

    def _enter_wifi_menu(self, *, rescan: bool = False) -> None:
        try:
            self.wifi_networks = self.wifi_manager.scan_networks()
            self.wifi_connect_message = "Scan complete" if rescan else ""
        except Exception as exc:
            logger.exception("Wi-Fi scan failed")
            self.wifi_networks = self.wifi_manager.list_saved_networks()
            self.wifi_connect_message = f"Scan failed: {exc}"
        self.wifi_network_index = max(0, min(self.wifi_network_index, max(0, len(self.wifi_networks) - 1)))
        with self.state_lock:
            self.state.mode = "wifi_menu"
            self.state.status_message = "Wi-Fi"
        self.last_drawn_mode = None

    def _scroll_wifi_menu(self, delta: int) -> None:
        if not self.wifi_networks:
            return
        self.wifi_network_index = (self.wifi_network_index + delta) % len(self.wifi_networks)
        self.last_drawn_mode = None

    def _select_wifi_network(self) -> None:
        if not self.wifi_networks:
            self._enter_wifi_menu(rescan=True)
            return
        self.wifi_selected_network = self.wifi_networks[self.wifi_network_index]
        options = self._wifi_detail_options()
        if "Connect" in options:
            self.wifi_detail_index = options.index("Connect")
        elif "Enter Password" in options:
            self.wifi_detail_index = options.index("Enter Password")
        else:
            self.wifi_detail_index = 0
        with self.state_lock:
            self.state.mode = "wifi_detail"
            self.state.status_message = self.wifi_selected_network.ssid
        self.last_drawn_mode = None

    def _scroll_wifi_detail(self, delta: int) -> None:
        options = self._wifi_detail_options()
        if not options:
            return
        self.wifi_detail_index = (self.wifi_detail_index + delta) % len(options)
        self.last_drawn_mode = None

    def _select_wifi_detail_option(self) -> None:
        network = self.wifi_selected_network
        if network is None:
            self._enter_wifi_menu()
            return
        options = self._wifi_detail_options()
        option = options[max(0, min(self.wifi_detail_index, len(options) - 1))]
        if option == "Connect":
            self._start_wifi_connection(network, "")
            return
        if option == "Enter Password":
            self._enter_wifi_password_for_selected()
            return
        self._enter_wifi_menu()

    def _enter_wifi_password_for_selected(self) -> None:
        network = self.wifi_selected_network
        if network is None:
            if not self.wifi_networks:
                self._enter_wifi_menu(rescan=True)
                return
            network = self.wifi_networks[self.wifi_network_index]
            self.wifi_selected_network = network
        if not network.secure:
            self.wifi_connect_message = "Open network: no password"
            self.last_drawn_mode = None
            return
        self.wifi_selected_network = network
        self.wifi_password = ""
        self.wifi_keyboard_index = 0
        with self.state_lock:
            self.state.mode = "wifi_keyboard"
            self.state.status_message = f"Password for {network.ssid}"
        self.last_drawn_mode = None

    def _start_wifi_connection(self, network: WifiNetwork, password: str) -> None:
        if self.wifi_connecting:
            return
        previous_connection = self.wifi_manager.active_connection_name()
        self.wifi_pending_rollback = self.wifi_manager.schedule_rollback(previous_connection)
        self.wifi_connecting = True
        self.wifi_connect_message = f"Trying {network.ssid}"
        with self.state_lock:
            self.state.mode = "wifi_connecting"
            self.state.status_message = self.wifi_connect_message
        self.last_drawn_mode = None

        def worker() -> None:
            try:
                if network.saved and not password:
                    result = self.wifi_manager.connect_saved(network)
                else:
                    result = self.wifi_manager.connect_new(network.ssid, password)
                ok = result.returncode == 0
                detail = (result.stderr or result.stdout).strip().splitlines()
                message = detail[-1] if detail else ("Connected" if ok else "Connection failed")
                self.wifi_connect_message = message[:80]
                with self.state_lock:
                    self.state.mode = "wifi_confirm" if ok else "wifi_menu"
                    self.state.status_message = self.wifi_connect_message
            except Exception as exc:
                logger.exception("Wi-Fi connection attempt failed")
                self.wifi_connect_message = f"Wi-Fi failed: {exc}"
                with self.state_lock:
                    self.state.mode = "wifi_menu"
                    self.state.status_message = self.wifi_connect_message
            finally:
                self.wifi_connecting = False
                self.last_drawn_mode = None

        self.wifi_connect_thread = Thread(target=worker, daemon=True)
        self.wifi_connect_thread.start()

    def _confirm_wifi_connection(self) -> None:
        self.wifi_manager.confirm_rollback(self.wifi_pending_rollback)
        self.wifi_pending_rollback = None
        self.wifi_connect_message = "Wi-Fi kept"
        self._enter_diagnostics()

    def _rollback_wifi_now(self) -> None:
        rollback = self.wifi_pending_rollback
        self.wifi_pending_rollback = None
        if rollback and rollback.previous_connection:
            Thread(
                target=lambda: self.wifi_manager.connect_saved(
                    WifiNetwork(
                        ssid=rollback.previous_connection,
                        saved=True,
                        active=False,
                        secure=True,
                        connection_name=rollback.previous_connection,
                    )
                ),
                daemon=True,
            ).start()
        self.wifi_connect_message = "Rolling back"
        self._enter_diagnostics()

    def _keyboard_add_char(self) -> None:
        self.wifi_password += WIFI_KEYBOARD_CHARS[self.wifi_keyboard_index]
        self.last_drawn_mode = None

    def _keyboard_backspace_or_exit(self) -> None:
        if self.wifi_password:
            self.wifi_password = self.wifi_password[:-1]
            self.last_drawn_mode = None
            return
        self._enter_wifi_menu()

    def _keyboard_scroll(self, delta: int) -> None:
        self.wifi_keyboard_index = (self.wifi_keyboard_index + delta) % len(WIFI_KEYBOARD_CHARS)
        self.last_drawn_mode = None

    def _keyboard_submit(self) -> None:
        if self.wifi_selected_network is None:
            self._enter_wifi_menu()
            return
        self._start_wifi_connection(self.wifi_selected_network, self.wifi_password)

    def _maybe_trigger_diagnostics(self, event: str, mode: str) -> bool:
        if mode != "preview" or event != "ui_up":
            return False
        now = time.monotonic()
        self.diagnostics_tap_times = [
            stamp for stamp in self.diagnostics_tap_times if (now - stamp) < 1.2
        ]
        self.diagnostics_tap_times.append(now)
        if len(self.diagnostics_tap_times) < 3:
            return False
        self.diagnostics_tap_times.clear()
        self._enter_diagnostics()
        return True

    def _scroll_prompt_picker(self, delta: int) -> None:
        if not self.prompt_order:
            return
        self.prompt_picker_index = (self.prompt_picker_index + delta) % len(self.prompt_order)
        self.last_drawn_mode = None

    def _scroll_album(self, delta: int) -> None:
        if not self.gallery_paths:
            return
        self.album_index = (self.album_index + delta) % len(self.gallery_paths)
        self.album_show_source = False
        self._invalidate_album_cache()
        self._preload_album_compare_async()
        self.last_drawn_mode = None

    def _toggle_album_compare(self) -> None:
        if not self.gallery_paths:
            return
        if not self.album_show_source:
            source_image = self._get_album_source_display_image()
            if source_image is None:
                with self.state_lock:
                    self.state.status_message = "No source photo found"
                return
        self.album_show_source = not self.album_show_source
        with self.state_lock:
            self.state.status_message = "Original photo" if self.album_show_source else "Edited photo"
        self.last_drawn_mode = None

    def _handle_event(self, event: str) -> None:
        mode = self.get_status_snapshot()["mode"]

        if event == "magic_shutter":
            if mode in {"preview", "capture_feedback"}:
                self._queue_magic_prompt_from_current_frame()
            elif mode == "album" and self.magic_mode_active:
                self._queue_magic_prompt_from_current_frame()
            return

        if event == "shutter":
            if mode in {"preview", "capture_feedback"}:
                if self.magic_mode_active:
                    self._enqueue_magic_generation_from_current_frame()
                else:
                    self._enqueue_generation_from_current_frame()
            elif mode == "album":
                self._toggle_album_compare()
            elif mode == "wifi_menu":
                self._enter_wifi_menu(rescan=True)
            elif mode == "wifi_detail":
                self._select_wifi_detail_option()
            elif mode == "wifi_keyboard":
                self._keyboard_submit()
            return

        if self._maybe_trigger_diagnostics(event, mode):
            return

        if mode == "capture_feedback":
            return

        if mode == "preview" and self.magic_mode_active:
            if event == "ui_prompt":
                self._stop_magic_mode()
                return
            if event == "ui_album":
                self._enter_album()
            return

        if event == "ui_prompt":
            if mode == "prompt_picker":
                self._select_prompt_from_picker()
            elif mode == "album":
                self._enter_album_download()
            elif mode == "album_download":
                return
            elif mode == "diagnostics":
                self._enter_wifi_menu()
            elif mode == "diagnostics_detail":
                self._enter_wifi_menu()
            elif mode == "wifi_menu":
                self._select_wifi_network()
            elif mode == "wifi_detail":
                self._select_wifi_detail_option()
            elif mode == "wifi_keyboard":
                self._keyboard_add_char()
            elif mode == "wifi_confirm":
                self._confirm_wifi_connection()
            elif mode == "wifi_connecting":
                return
            else:
                self._enter_prompt_picker()
            return

        if event == "ui_album":
            if mode == "album_download":
                self._enter_album()
                return
            if mode == "prompt_picker":
                self._exit_to_preview()
                return
            if mode == "diagnostics":
                self._exit_to_preview()
                return
            if mode == "diagnostics_detail":
                self._exit_to_preview()
                return
            if mode == "wifi_menu":
                self._enter_diagnostics()
                return
            if mode == "wifi_detail":
                self._enter_wifi_menu()
                return
            if mode == "wifi_keyboard":
                self._keyboard_backspace_or_exit()
                return
            if mode == "wifi_confirm":
                self._rollback_wifi_now()
                return
            if mode == "wifi_connecting":
                return
            if mode == "album":
                self._exit_to_preview()
            else:
                self._enter_album()
            return

        if event == "ui_up":
            if mode == "prompt_picker":
                self._scroll_prompt_picker(-1)
            elif mode == "album":
                self._scroll_album(-1)
            elif mode == "wifi_menu":
                self._scroll_wifi_menu(-1)
            elif mode == "wifi_detail":
                self._scroll_wifi_detail(-1)
            elif mode == "wifi_keyboard":
                self._keyboard_scroll(-1)
            elif mode == "diagnostics":
                with self.state_lock:
                    self.state.mode = "diagnostics_detail"
                    self.state.status_message = "Diagnostics"
                self.last_drawn_mode = None
            return

        if event == "ui_down":
            if mode == "prompt_picker":
                self._scroll_prompt_picker(1)
            elif mode == "album":
                self._scroll_album(1)
            elif mode == "wifi_menu":
                self._scroll_wifi_menu(1)
            elif mode == "wifi_detail":
                self._scroll_wifi_detail(1)
            elif mode == "wifi_keyboard":
                self._keyboard_scroll(1)

    def run(self) -> None:
        try:
            while self.running:
                now = time.monotonic()
                if self.camera_failure is not None:
                    raise RuntimeError(self.camera_failure)
                self._check_stale_camera(now)
                self._maybe_configure_pisugar_button()

                self._poll_buttons()
                self._poll_shutter_button(now)
                self._poll_external_shutter_events()
                self._poll_pisugar_power_button(now)

                try:
                    while True:
                        event = self.event_queue.get_nowait()
                        self._handle_event(event)
                except Empty:
                    pass

                snapshot = self.get_status_snapshot()
                mode = snapshot["mode"]

                if mode == "capture_feedback":
                    if (now - self.capture_feedback_started_at) >= self.capture_feedback_duration_seconds:
                        self._exit_to_preview()
                    elif (
                        self.last_drawn_mode != mode
                        or (now - self.menu_last_redraw_at) >= MENU_REDRAW_INTERVAL_SECONDS
                    ):
                        self._render_capture_feedback_frame()
                        self.menu_last_redraw_at = now
                        self.last_drawn_mode = mode
                elif mode == "preview":
                    with self.latest_frame_lock:
                        frame_id = self.latest_preview_frame_id
                    if (
                        self.last_drawn_mode != mode
                        or self.preview_overlay_dirty
                        or (
                            frame_id != self.last_rendered_frame_id
                            and (now - self.preview_last_redraw_at) >= PREVIEW_REDRAW_INTERVAL_SECONDS
                        )
                    ):
                        self._render_preview_frame()
                        self.preview_last_redraw_at = now
                        self.last_drawn_mode = mode
                        self.last_rendered_frame_id = frame_id
                        self.preview_overlay_dirty = False
                elif mode == "prompt_picker":
                    if (
                        self.last_drawn_mode != mode
                        or (now - self.menu_last_redraw_at) >= MENU_REDRAW_INTERVAL_SECONDS
                    ):
                        self._render_prompt_picker_frame()
                        self.menu_last_redraw_at = now
                        self.last_drawn_mode = mode
                elif mode == "album":
                    if (
                        self.last_drawn_mode != mode
                        or (now - self.album_last_redraw_at) >= ALBUM_REDRAW_INTERVAL_SECONDS
                    ):
                        self._render_album_frame()
                        self.album_last_redraw_at = now
                        self.last_drawn_mode = mode
                elif mode == "album_download":
                    if (
                        self.last_drawn_mode != mode
                        or (now - self.album_last_redraw_at) >= ALBUM_REDRAW_INTERVAL_SECONDS
                    ):
                        self._render_album_download_frame()
                        self.album_last_redraw_at = now
                        self.last_drawn_mode = mode
                elif mode == "diagnostics":
                    if (
                        self.last_drawn_mode != mode
                        or (now - self.menu_last_redraw_at) >= 1.0
                    ):
                        self._render_diagnostics_frame()
                        self.menu_last_redraw_at = now
                        self.last_drawn_mode = mode
                elif mode == "diagnostics_detail":
                    if (
                        self.last_drawn_mode != mode
                        or (now - self.menu_last_redraw_at) >= 1.0
                    ):
                        self._render_diagnostics_detail_frame()
                        self.menu_last_redraw_at = now
                        self.last_drawn_mode = mode
                elif mode == "wifi_menu":
                    if (
                        self.last_drawn_mode != mode
                        or (now - self.menu_last_redraw_at) >= MENU_REDRAW_INTERVAL_SECONDS
                    ):
                        self._render_wifi_menu_frame()
                        self.menu_last_redraw_at = now
                        self.last_drawn_mode = mode
                elif mode == "wifi_detail":
                    if (
                        self.last_drawn_mode != mode
                        or (now - self.menu_last_redraw_at) >= MENU_REDRAW_INTERVAL_SECONDS
                    ):
                        self._render_wifi_detail_frame()
                        self.menu_last_redraw_at = now
                        self.last_drawn_mode = mode
                elif mode == "wifi_keyboard":
                    if (
                        self.last_drawn_mode != mode
                        or (now - self.menu_last_redraw_at) >= MENU_REDRAW_INTERVAL_SECONDS
                    ):
                        self._render_wifi_keyboard_frame()
                        self.menu_last_redraw_at = now
                        self.last_drawn_mode = mode
                elif mode == "wifi_connecting":
                    if (
                        self.last_drawn_mode != mode
                        or (now - self.menu_last_redraw_at) >= 0.5
                    ):
                        self._render_wifi_connecting_frame()
                        self.menu_last_redraw_at = now
                        self.last_drawn_mode = mode
                elif mode == "wifi_confirm":
                    if (
                        self.last_drawn_mode != mode
                        or (now - self.menu_last_redraw_at) >= 1.0
                    ):
                        self._render_wifi_confirm_frame()
                        self.menu_last_redraw_at = now
                        self.last_drawn_mode = mode
                else:
                    time.sleep(0.01)

                self._maybe_log_perf()
                time.sleep(0.005)
        finally:
            self.shutdown()

    def shutdown(self) -> None:
        self.running = False
        try:
            self.capture_queue.put_nowait(None)
        except Exception:
            pass
        try:
            self.magic_prompt_queue.put_nowait(None)
        except Exception:
            pass
        if self.camera_thread and self.camera_thread.is_alive():
            self.camera_thread.join(timeout=0.5)
        if self.capture_worker_thread and self.capture_worker_thread.is_alive():
            self.capture_worker_thread.join(timeout=0.5)
        if self.generation_worker_thread and self.generation_worker_thread.is_alive():
            self.generation_worker_thread.join(timeout=0.5)
        if self.magic_prompt_worker_thread and self.magic_prompt_worker_thread.is_alive():
            self.magic_prompt_worker_thread.join(timeout=0.5)
        try:
            self.picam2.stop()
        except Exception:
            pass
        if getattr(self, "hot_shoe", None) is not None:
            try:
                self.hot_shoe.close()
            except Exception:
                pass
        try:
            self._set_led(0.0, 0.0, 0.0)
        except Exception:
            pass
        try:
            self._show_text_screen("ImageGenCam", "Stopped")
        except Exception:
            pass
