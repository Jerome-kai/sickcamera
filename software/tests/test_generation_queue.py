from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from threading import Lock

import io

from PIL import Image

from imagegencam.controller import QUEUE_MAX_ATTEMPTS, ImageGenCamController
from imagegencam.openai_client import OpenAIImageEditor
from imagegencam.job_store import PersistentJobStore


class _QueueStub:
    """Enough controller surface for the reschedule/abandon path.

    The real controller opens the camera and the display in __init__, so it
    cannot be constructed here; these methods are exercised unbound instead.
    """

    def __init__(self, root: Path) -> None:
        self.generation_job_store = PersistentJobStore(root / "queue" / "generation")
        self.state_lock = Lock()
        self.pending_jobs = None

    failed_jobs_root = ImageGenCamController.failed_jobs_root
    _abandon_generation_job = ImageGenCamController._abandon_generation_job
    _retry_delay_seconds = staticmethod(ImageGenCamController._retry_delay_seconds)

    def _refresh_pending_jobs_count(self) -> None:
        self.pending_jobs = self.generation_job_store.count()


def _reschedule(stub, job_id, payload, error):
    return ImageGenCamController._reschedule_generation_job(stub, job_id, payload, error)


class GenerationRetryCapTests(unittest.TestCase):
    def test_a_transient_failure_is_queued_again(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stub = _QueueStub(Path(tmp))
            payload = {"attempts": 0, "created_at": "2026-01-01T00:00:00"}

            self.assertTrue(_reschedule(stub, "job-1", payload, RuntimeError("timeout")))

            self.assertEqual(stub.generation_job_store.count(), 1)
            self.assertEqual(stub.pending_jobs, 1)

    def test_the_camera_gives_up_at_the_attempt_ceiling(self) -> None:
        # A prompt the model permanently refuses used to retry every 15 minutes
        # forever, leaving the queue badge showing work that never cleared.
        with tempfile.TemporaryDirectory() as tmp:
            stub = _QueueStub(Path(tmp))
            payload = {"attempts": QUEUE_MAX_ATTEMPTS - 1, "created_at": "2026-01-01T00:00:00"}

            self.assertFalse(_reschedule(stub, "job-1", payload, RuntimeError("refused")))

            self.assertEqual(stub.generation_job_store.count(), 0)
            self.assertEqual(stub.pending_jobs, 0)

    def test_an_abandoned_job_is_kept_for_inspection(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stub = _QueueStub(Path(tmp))
            payload = {"attempts": QUEUE_MAX_ATTEMPTS - 1, "created_at": "2026-01-01T00:00:00"}

            _reschedule(stub, "job-1", payload, RuntimeError("refused"))

            recorded = stub.failed_jobs_root / "job-1.json"
            self.assertTrue(recorded.is_file())
            saved = json.loads(recorded.read_text(encoding="utf-8"))
            self.assertEqual(saved["last_error"], "refused")
            self.assertEqual(saved["attempts"], QUEUE_MAX_ATTEMPTS)

    def test_it_survives_the_whole_backoff_ladder(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stub = _QueueStub(Path(tmp))
            payload = {"attempts": 0, "created_at": "2026-01-01T00:00:00"}

            outcomes = []
            for _ in range(QUEUE_MAX_ATTEMPTS + 3):
                if stub.generation_job_store.count() == 0 and outcomes:
                    break
                outcomes.append(_reschedule(stub, "job-1", payload, RuntimeError("nope")))

            self.assertEqual(len(outcomes), QUEUE_MAX_ATTEMPTS)
            self.assertTrue(all(outcomes[:-1]))
            self.assertFalse(outcomes[-1])
            self.assertEqual(stub.generation_job_store.count(), 0)


class IncompleteImageTests(unittest.TestCase):
    """A queued job whose output already exists is treated as done, so the
    'already exists' check has to reject the half-written file an interrupted
    write leaves at the final path."""

    @staticmethod
    def _jpeg() -> bytes:
        buffer = io.BytesIO()
        Image.new("RGB", (400, 300), (30, 90, 160)).save(buffer, "JPEG")
        return buffer.getvalue()

    def test_a_whole_image_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "whole.jpg"
            path.write_bytes(self._jpeg())

            self.assertTrue(ImageGenCamController._is_complete_image(path))

    def test_a_truncated_image_is_rejected(self) -> None:
        # verify() passes this -- it only walks the container structure and
        # never decodes the scan data. Only a real load() catches it.
        whole = self._jpeg()
        with tempfile.TemporaryDirectory() as tmp:
            for name, data in (
                ("third.jpg", whole[: len(whole) // 3]),
                ("nearly.jpg", whole[:-40]),
                ("empty.jpg", b""),
                ("garbage.jpg", b"not an image at all"),
            ):
                path = Path(tmp) / name
                path.write_bytes(data)
                with self.subTest(name=name):
                    self.assertFalse(ImageGenCamController._is_complete_image(path))

    def test_a_missing_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(ImageGenCamController._is_complete_image(Path(tmp) / "nope.jpg"))


class AtomicImageWriteTests(unittest.TestCase):
    def test_the_finished_image_appears_whole_or_not_at_all(self) -> None:
        payload = IncompleteImageTests._jpeg()
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "nested" / "out.jpg"

            OpenAIImageEditor._write_output_atomic(output, payload)

            self.assertEqual(output.read_bytes(), payload)
            self.assertEqual(list(output.parent.glob("*.part")), [])

    def test_a_failed_write_leaves_no_partial_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out.jpg"

            with self.assertRaises(TypeError):
                OpenAIImageEditor._write_output_atomic(output, "not bytes")

            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
