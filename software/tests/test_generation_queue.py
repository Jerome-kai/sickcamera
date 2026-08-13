from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from threading import Lock

from imagegencam.controller import QUEUE_MAX_ATTEMPTS, ImageGenCamController
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


if __name__ == "__main__":
    unittest.main()
