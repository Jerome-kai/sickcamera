from __future__ import annotations

import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from threading import Lock
from unittest import mock

if "spidev" not in sys.modules:
    sys.modules["spidev"] = types.ModuleType("spidev")

from imagegencam import opi_hw, sunxi_gpio
from imagegencam.config import (
    ModelPresetStore,
    default_model_presets,
    normalize_model_presets,
)
from imagegencam.controller import ImageGenCamController


class _FakeInputs:
    def __init__(self, pin_a: int, pin_b: int) -> None:
        self.pin_a, self.pin_b = pin_a, pin_b
        self.a = self.b = True
        self.released = False

    def is_high(self, pin: int) -> bool:
        return self.a if pin == self.pin_a else self.b

    def release(self) -> None:
        self.released = True


class RotaryEncoderTests(unittest.TestCase):
    """Quadrature: clockwise runs 00 -> 10 -> 11 -> 01 -> 00."""

    CW = [(0, 0), (1, 0), (1, 1), (0, 1)]
    CCW = [(0, 0), (0, 1), (1, 1), (1, 0)]

    def _encoder(self):
        fake = _FakeInputs(226, 227)
        with mock.patch.object(sunxi_gpio, "request_inputs", return_value=fake):
            encoder = opi_hw.RotaryEncoder()
        fake.a = fake.b = False
        encoder._state = 0
        encoder._pending = 0
        return encoder, fake

    def _drive(self, encoder, fake, states) -> int:
        total = 0
        for a, b in states:
            fake.a, fake.b = bool(a), bool(b)
            total += encoder.poll()
        return total

    def test_clockwise_detents_count_up(self) -> None:
        encoder, fake = self._encoder()
        self.assertEqual(self._drive(encoder, fake, self.CW * 3 + [(0, 0)]), 3)

    def test_counter_clockwise_counts_down(self) -> None:
        encoder, fake = self._encoder()
        self.assertEqual(self._drive(encoder, fake, self.CCW * 2 + [(0, 0)]), -2)

    def test_a_partial_turn_reports_nothing_yet(self) -> None:
        encoder, fake = self._encoder()
        self.assertEqual(self._drive(encoder, fake, [(1, 0), (1, 1)]), 0)

    def test_a_partial_turn_completes_later(self) -> None:
        # The remainder stays pending rather than being rounded away, so a slow
        # turn still registers once the click finishes.
        encoder, fake = self._encoder()
        self._drive(encoder, fake, [(1, 0), (1, 1)])
        self.assertEqual(self._drive(encoder, fake, [(0, 1), (0, 0)]), 1)

    def test_contact_bounce_does_not_invent_a_step(self) -> None:
        # This is why the decoder is a transition table and not the usual
        # "watch A, sample B": that shortcut counts every bounce.
        encoder, fake = self._encoder()
        self.assertEqual(
            self._drive(encoder, fake, [(1, 0), (0, 0), (1, 0), (0, 0), (1, 0), (0, 0)]), 0
        )

    def test_jitter_in_a_detent_is_ignored(self) -> None:
        encoder, fake = self._encoder()
        self.assertEqual(self._drive(encoder, fake, [(0, 1), (0, 0)] * 4), 0)

    def test_close_releases_the_lines(self) -> None:
        encoder, fake = self._encoder()
        encoder.close()
        self.assertTrue(fake.released)


class ModelPresetTests(unittest.TestCase):
    def test_defaults_come_from_the_environment(self) -> None:
        with mock.patch.dict(
            "os.environ",
            {"IMAGE_GEN_MODEL": "google/gemini-2.5-flash-image", "IMAGE_GEN_API": "chat"},
        ):
            presets = default_model_presets()
        self.assertEqual(presets[0]["model"], "google/gemini-2.5-flash-image")
        self.assertEqual(presets[0]["api"], "chat")
        # The gateway prefix is noise on a 3.5" screen.
        self.assertEqual(presets[0]["label"], "gemini-2.5-flash-image")

    def test_entries_without_a_model_are_dropped(self) -> None:
        cleaned = normalize_model_presets(
            [{"label": "Real", "model": "m1"}, {"label": "Empty", "model": "   "}, "junk"]
        )
        self.assertEqual([entry["model"] for entry in cleaned], ["m1"])

    def test_an_unknown_api_falls_back_to_edits(self) -> None:
        cleaned = normalize_model_presets([{"model": "m1", "api": "telepathy"}])
        self.assertEqual(cleaned[0]["api"], "edits")

    def test_an_empty_list_still_yields_a_usable_model(self) -> None:
        # The camera must always have one known-good model to fall back on.
        for value in ([], "not a list", None, [{"model": ""}]):
            with self.subTest(value=value):
                self.assertEqual(len(normalize_model_presets(value)), 1)

    def test_duplicate_ids_are_made_unique(self) -> None:
        cleaned = normalize_model_presets(
            [{"id": "x", "model": "m1"}, {"id": "x", "model": "m2"}]
        )
        self.assertEqual(len({entry["id"] for entry in cleaned}), 2)

    def test_the_store_round_trips(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "models.json"
            store = ModelPresetStore(path)
            store.save_entries(
                [
                    {"id": "a", "label": "Gemini", "model": "google/x", "api": "chat"},
                    {"id": "b", "label": "GPT", "model": "gpt-image-2", "api": "edits"},
                ]
            )

            reloaded = ModelPresetStore(path).load_entries()

            self.assertEqual([entry["label"] for entry in reloaded], ["Gemini", "GPT"])

    def test_a_corrupt_file_falls_back_instead_of_raising(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "models.json"
            store = ModelPresetStore(path)
            path.write_text("{ truncated", encoding="utf-8")

            self.assertEqual(len(store.load_entries()), 1)

    def test_a_seeded_store_is_valid_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "models.json"
            ModelPresetStore(path)
            self.assertIsInstance(json.loads(path.read_text(encoding="utf-8")), list)


class _FakeState:
    def __init__(self) -> None:
        self.mode = "preview"
        self.status_message = ""


class _FakeEditor:
    def __init__(self) -> None:
        self.model = "start"
        self.api_mode = "edits"


class _WheelStub:
    """Controller surface for the model wheel, exercised unbound."""

    def __init__(self, presets) -> None:
        self.model_presets = presets
        self.model_index = 0
        self.model_picker_index = 0
        self.model_picker_until = 0.0
        self.model_picker_hold_seconds = 2.5
        self.image_editor = _FakeEditor()
        self.state = _FakeState()
        self.state_lock = Lock()
        self.last_drawn_mode = "preview"
        self.model_wheel = None

    current_model_preset = ImageGenCamController.current_model_preset
    _apply_selected_model = ImageGenCamController._apply_selected_model
    _scroll_model_picker = ImageGenCamController._scroll_model_picker

    def get_status_snapshot(self) -> dict[str, str]:
        return {"mode": self.state.mode}


class ModelSelectionTests(unittest.TestCase):
    PRESETS = [
        {"id": "a", "label": "Gemini", "model": "google/gemini", "api": "chat"},
        {"id": "b", "label": "GPT", "model": "gpt-image-2", "api": "edits"},
        {"id": "c", "label": "Qwen", "model": "Qwen/edit", "api": "generations"},
    ]

    def test_applying_a_preset_retargets_the_editor(self) -> None:
        stub = _WheelStub(self.PRESETS)
        stub.model_index = 2

        stub._apply_selected_model()

        self.assertEqual(stub.image_editor.model, "Qwen/edit")
        self.assertEqual(stub.image_editor.api_mode, "generations")

    def test_the_first_click_only_opens_the_picker(self) -> None:
        # Changing the model before the owner can see what it was would make
        # the wheel feel like it fired one click early.
        stub = _WheelStub(self.PRESETS)

        stub._scroll_model_picker(1)

        self.assertEqual(stub.state.mode, "model_picker")
        self.assertEqual(stub.model_index, 0)
        self.assertEqual(stub.image_editor.model, "start")

    def test_the_next_click_changes_the_model(self) -> None:
        stub = _WheelStub(self.PRESETS)
        stub._scroll_model_picker(1)

        stub._scroll_model_picker(1)

        self.assertEqual(stub.model_index, 1)
        self.assertEqual(stub.image_editor.model, "gpt-image-2")
        self.assertEqual(stub.image_editor.api_mode, "edits")

    def test_the_selection_wraps_both_ways(self) -> None:
        stub = _WheelStub(self.PRESETS)
        stub._scroll_model_picker(1)  # open

        stub._scroll_model_picker(-1)
        self.assertEqual(stub.model_index, len(self.PRESETS) - 1)

        stub._scroll_model_picker(1)
        self.assertEqual(stub.model_index, 0)

    def test_turning_extends_the_time_the_picker_stays_up(self) -> None:
        stub = _WheelStub(self.PRESETS)
        stub._scroll_model_picker(1)
        first = stub.model_picker_until

        stub._scroll_model_picker(1)

        self.assertGreater(stub.model_picker_until, first)

    def test_a_wheel_with_no_models_does_nothing(self) -> None:
        stub = _WheelStub([])

        stub._scroll_model_picker(1)

        self.assertEqual(stub.state.mode, "preview")
        self.assertIsNone(stub.current_model_preset())

    def test_an_out_of_range_index_is_clamped(self) -> None:
        stub = _WheelStub(self.PRESETS)
        stub.model_index = 99

        self.assertEqual(stub.current_model_preset()["label"], "Qwen")


if __name__ == "__main__":
    unittest.main()
