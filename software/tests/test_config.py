from __future__ import annotations

import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path

from imagegencam.config import (
    DEFAULT_PROMPT_ENTRIES,
    DEFAULT_PROMPTS,
    MagicHistoryStore,
    PROMPT_TITLE_MAX_LENGTH,
    PromptStore,
    SettingsStore,
    load_env_file,
    normalize_settings,
    read_json_or_default,
    write_json_atomic,
)
from imagegencam.job_store import PersistentJobStore


class PromptStoreTests(unittest.TestCase):
    def test_store_initializes_with_default_prompt_list(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "prompts.json"
            store = PromptStore(path)

            entries = store.load_entries()

            self.assertEqual(len(entries), len(DEFAULT_PROMPT_ENTRIES))
            self.assertEqual(next(iter(entries.values()))["title"], DEFAULT_PROMPT_ENTRIES[0]["title"])

    def test_default_prompt_data_matches_fallback_entries(self) -> None:
        prompts_path = Path(__file__).resolve().parents[1] / "data" / "prompts.json"
        saved_entries = json.loads(prompts_path.read_text(encoding="utf-8"))

        self.assertEqual(saved_entries, DEFAULT_PROMPT_ENTRIES)

    def test_load_entries_accepts_legacy_prompt_strings(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "prompts.json"
            path.write_text('{"A": "legacy body"}\n', encoding="utf-8")

            store = PromptStore(path)
            entries = store.load_entries()

            self.assertEqual(list(entries.keys()), ["a"])
            self.assertEqual(entries["a"]["title"], "New Prompt")
            self.assertEqual(entries["a"]["body"], "legacy body")

    def test_save_entries_persists_titles_and_bodies(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "prompts.json"
            store = PromptStore(path)

            entries = store.save_entries(
                [
                    {"id": "portrait", "title": "My Portrait", "body": "Use this prompt"},
                ]
            )

            self.assertEqual(entries["portrait"]["title"], "My Portrait")
            self.assertEqual(entries["portrait"]["body"], "Use this prompt")
            self.assertEqual(store.load_entries()["portrait"]["title"], "My Portrait")

    def test_save_entries_truncates_titles(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "prompts.json"
            store = PromptStore(path)

            entries = store.save_entries(
                [
                    {"id": "long", "title": "X" * (PROMPT_TITLE_MAX_LENGTH + 10), "body": "Prompt body"},
                ]
            )

            self.assertEqual(len(entries["long"]["title"]), PROMPT_TITLE_MAX_LENGTH)

    def test_save_entries_allows_more_than_four_prompts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "prompts.json"
            store = PromptStore(path)

            entries = store.save_entries(
                [
                    {"id": f"prompt-{index}", "title": f"Prompt {index}", "body": f"Body {index}"}
                    for index in range(1, 7)
                ]
            )

            self.assertEqual(len(entries), 6)
            self.assertEqual(list(entries.keys())[-1], "prompt-6")

    def test_magic_history_store_adds_and_marks_promoted_entries(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "magic_history.json"
            store = MagicHistoryStore(path)

            added = store.add_entry(
                {
                    "id": "magic-shoes",
                    "created_at": "2026-05-07T10:00:00",
                    "title": "Shoe Zoom",
                    "body": "Turn shoes into oversized chrome sculptures.",
                    "reference_capture_path": "data/captures/2026-05-07/magic-reference.jpg",
                }
            )
            self.assertEqual(added["id"], "magic-shoes")
            self.assertEqual(store.load_entries()[0]["title"], "Shoe Zoom")

            updated = store.mark_promoted("magic-shoes", "prompt-9")
            self.assertEqual(updated[0]["promoted_prompt_id"], "prompt-9")

    def test_load_env_file_sets_missing_values(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            env_path = Path(tmp) / ".env"
            env_path.write_text("OPENAI_API_KEY=test-key\nIMAGE_GEN_PORT=9000\n", encoding="utf-8")

            os.environ.pop("OPENAI_API_KEY", None)
            os.environ.pop("IMAGE_GEN_PORT", None)
            load_env_file(env_path)

            self.assertEqual(os.environ["OPENAI_API_KEY"], "test-key")
            self.assertEqual(os.environ["IMAGE_GEN_PORT"], "9000")

    def test_settings_store_persists_app_background_theme(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "settings.json"
            store = SettingsStore(path)

            settings = store.save({"app_background_theme": "silver"})

            self.assertEqual(settings["app_background_theme"], "silver")
            self.assertEqual(store.load()["app_background_theme"], "silver")

    def test_settings_store_persists_normalized_camera_username(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "settings.json"
            store = SettingsStore(path)

            settings = store.save({"camera_username": "  Luna Echo!!  "})

            self.assertEqual(settings["camera_username"], "luna-echo")
            self.assertEqual(store.load()["camera_username"], "luna-echo")

    def test_settings_store_clamps_preview_calibration(self) -> None:
        cleaned = normalize_settings(
            {
                "preview_warmth": "999",
                "preview_red_gain": "20",
                "preview_green_gain": "120",
                "preview_blue_gain": "-5",
            }
        )

        self.assertEqual(cleaned["preview_warmth"], 40)
        self.assertEqual(cleaned["preview_red_gain"], 60)
        self.assertEqual(cleaned["preview_green_gain"], 120)
        self.assertEqual(cleaned["preview_blue_gain"], 60)

    def test_persistent_job_store_returns_oldest_due_entry(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = PersistentJobStore(Path(tmp) / "jobs")
            now = datetime(2026, 5, 8, 12, 0, 0)
            store.save_entry(
                "later",
                {
                    "created_at": (now - timedelta(minutes=10)).isoformat(timespec="seconds"),
                    "next_attempt_at": (now + timedelta(minutes=1)).isoformat(timespec="seconds"),
                },
            )
            store.save_entry(
                "due-second",
                {
                    "created_at": (now - timedelta(minutes=4)).isoformat(timespec="seconds"),
                    "next_attempt_at": (now - timedelta(seconds=10)).isoformat(timespec="seconds"),
                },
            )
            store.save_entry(
                "due-first",
                {
                    "created_at": (now - timedelta(minutes=5)).isoformat(timespec="seconds"),
                    "next_attempt_at": (now - timedelta(seconds=10)).isoformat(timespec="seconds"),
                },
            )

            job_id, payload = store.next_due_entry(now) or ("", {})

            self.assertEqual(job_id, "due-first")
            self.assertEqual(payload["id"], "due-first")

    def test_persistent_job_store_can_delete_entries(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = PersistentJobStore(Path(tmp) / "jobs")
            store.save_entry("alpha", {"created_at": "2026-05-08T12:00:00", "next_attempt_at": "2026-05-08T12:00:00"})

            self.assertEqual(store.count(), 1)
            store.delete_entry("alpha")
            self.assertEqual(store.count(), 0)


class DurableStoreTests(unittest.TestCase):
    def test_atomic_write_leaves_no_temp_file_behind(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "settings.json"

            write_json_atomic(path, {"a": 1})

            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), {"a": 1})
            self.assertEqual([item.name for item in Path(tmp).iterdir()], ["settings.json"])

    def test_atomic_write_keeps_the_old_file_when_the_write_fails(self) -> None:
        # A power cut mid-write leaves the temp file damaged, never the real one.
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "prompts.json"
            write_json_atomic(path, ["original"])

            class Unserializable:
                pass

            with self.assertRaises(TypeError):
                write_json_atomic(path, [Unserializable()])

            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), ["original"])
            self.assertEqual([item.name for item in Path(tmp).iterdir()], ["prompts.json"])

    def test_reading_a_truncated_file_falls_back_instead_of_raising(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "prompts.json"
            path.write_text('[{"id": "prompt-1", "ti', encoding="utf-8")

            self.assertEqual(read_json_or_default(path, ["fallback"]), ["fallback"])

    def test_a_damaged_file_is_kept_for_inspection(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "prompts.json"
            path.write_text("not json at all", encoding="utf-8")

            read_json_or_default(path, [])

            quarantined = Path(tmp) / "prompts.json.corrupt"
            self.assertTrue(quarantined.is_file())
            self.assertEqual(quarantined.read_text(encoding="utf-8"), "not json at all")
            self.assertFalse(path.exists())

    def test_missing_file_falls_back_without_quarantining(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "settings.json"

            self.assertEqual(read_json_or_default(path, {"ok": True}), {"ok": True})
            self.assertFalse((Path(tmp) / "settings.json.corrupt").exists())

    def test_prompt_store_survives_a_corrupt_file(self) -> None:
        # This is the startup path: raising here used to kill the service on
        # every boot until somebody deleted the file by hand.
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "prompts.json"
            store = PromptStore(path)
            path.write_text("[{tru", encoding="utf-8")

            entries = store.load_entries()

            self.assertEqual(
                list(entries), [entry["id"] for entry in DEFAULT_PROMPT_ENTRIES]
            )

    def test_settings_store_survives_a_corrupt_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "settings.json"
            store = SettingsStore(path)
            path.write_text("", encoding="utf-8")

            self.assertEqual(store.load()["app_background_theme"], "aqua")

    def test_magic_history_store_survives_a_corrupt_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "magic_history.json"
            store = MagicHistoryStore(path)
            path.write_text("\x00\x01", encoding="utf-8")

            self.assertEqual(store.load_entries(), [])


class PersistentJobStoreResilienceTests(unittest.TestCase):
    """The queue is read back after power loss, so every entry on disk is
    potentially half-written -- one bad file must not stall the whole queue."""

    def _store(self, tmp: str) -> PersistentJobStore:
        return PersistentJobStore(Path(tmp) / "jobs")

    def test_a_corrupt_entry_is_skipped_rather_than_blocking_the_queue(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = self._store(tmp)
            store.save_entry(
                "good",
                {"created_at": "2026-05-08T12:00:00", "next_attempt_at": "2026-05-08T12:00:00"},
            )
            (store.path / "broken.json").write_text("{ truncated", encoding="utf-8")

            job_id, _ = store.next_due_entry(datetime(2026, 5, 8, 12, 30)) or ("", {})

            self.assertEqual(job_id, "good")

    def test_an_entry_that_is_not_an_object_is_skipped(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = self._store(tmp)
            (store.path / "listy.json").write_text("[1, 2, 3]", encoding="utf-8")

            self.assertIsNone(store.next_due_entry(datetime(2026, 5, 8, 12, 30)))

    def test_an_entry_with_no_id_falls_back_to_its_filename(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = self._store(tmp)
            (store.path / "orphan.json").write_text(
                '{"next_attempt_at": "2026-05-08T12:00:00"}', encoding="utf-8"
            )

            job_id, payload = store.next_due_entry(datetime(2026, 5, 8, 12, 30)) or ("", {})

            self.assertEqual(job_id, "orphan")
            self.assertEqual(payload["id"], "orphan")

    def test_an_entry_with_an_unreadable_timestamp_is_treated_as_overdue(self) -> None:
        # Better to retry a job with a mangled timestamp than to strand it in
        # the queue forever, keeping the badge lit with work that never runs.
        with tempfile.TemporaryDirectory() as tmp:
            store = self._store(tmp)
            store.save_entry("odd", {"created_at": "", "next_attempt_at": "not-a-date"})

            job_id, _ = store.next_due_entry(datetime(2026, 5, 8, 12, 30)) or ("", {})

            self.assertEqual(job_id, "odd")

    def test_a_job_that_is_not_due_yet_is_left_alone(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = self._store(tmp)
            store.save_entry("later", {"next_attempt_at": "2026-05-08T13:00:00"})

            self.assertIsNone(store.next_due_entry(datetime(2026, 5, 8, 12, 0)))

    def test_loading_a_missing_entry_returns_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(self._store(tmp).load_entry("never-existed"))

    def test_loading_a_corrupt_entry_returns_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = self._store(tmp)
            (store.path / "broken.json").write_text("{ truncated", encoding="utf-8")

            self.assertIsNone(store.load_entry("broken"))

    def test_loading_an_entry_that_is_not_an_object_returns_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = self._store(tmp)
            (store.path / "listy.json").write_text("[]", encoding="utf-8")

            self.assertIsNone(store.load_entry("listy"))

    def test_a_saved_entry_round_trips(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = self._store(tmp)

            saved = store.save_entry("alpha", {"prompt": "neon"})

            self.assertEqual(saved["id"], "alpha")
            self.assertEqual(store.load_entry("alpha"), {"prompt": "neon", "id": "alpha"})

    def test_deleting_an_entry_that_is_already_gone_is_harmless(self) -> None:
        # Two workers can finish the same job after a restart.
        with tempfile.TemporaryDirectory() as tmp:
            self._store(tmp).delete_entry("never-existed")

    def test_saving_leaves_no_temp_file_behind(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = self._store(tmp)

            store.save_entry("alpha", {"prompt": "neon"})

            self.assertEqual(list(store.path.glob("*.tmp")), [])
            self.assertEqual(store.count(), 1)



if __name__ == "__main__":
    unittest.main()
