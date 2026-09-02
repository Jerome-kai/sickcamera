from __future__ import annotations

import io
import json
import tempfile
import unittest
from pathlib import Path
from threading import Lock
from unittest import mock

from PIL import Image

from imagegencam.controller import GenerationJob, ImageGenCamController
from imagegencam.job_store import PersistentJobStore


def _jpeg_bytes(colour: tuple[int, int, int] = (30, 90, 160)) -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (64, 48), colour).save(buffer, "JPEG")
    return buffer.getvalue()


class _FakeState:
    def __init__(self) -> None:
        self.mode = "preview"
        self.status_message = ""
        self.last_error: str | None = None
        self.last_generated_path: str | None = None
        self.pending_jobs = 0
        self.ready_images = 0


class _FakeEditor:
    """Stands in for OpenAIImageEditor: writes a believable output file, or
    raises whatever the test asked it to."""

    def __init__(self, error: Exception | None = None) -> None:
        self.error = error
        self.calls: list[dict] = []

    def edit_image(self, capture_path, prompt, output_path, reference_paths=None):
        self.calls.append(
            {
                "capture_path": capture_path,
                "prompt": prompt,
                "output_path": output_path,
                "reference_paths": list(reference_paths or []),
            }
        )
        if self.error is not None:
            raise self.error
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(_jpeg_bytes((200, 40, 40)))
        return output_path


class _DrainingStore:
    """The real job store, wrapped so the worker loop terminates.

    The loop runs until `self.running` goes false, which on the device happens
    at shutdown. Here it stops as soon as the queue has nothing due -- plus a
    hard cap, so a job that stays due (a retry the loop could not rewrite)
    fails the test instead of hanging it.
    """

    MAX_PASSES = 50

    def __init__(self, store: PersistentJobStore, stub: "_WorkerStub") -> None:
        self._store = store
        self._stub = stub
        self.reschedule_error: Exception | None = None
        self.passes = 0

    def next_due_entry(self):
        self.passes += 1
        if self.passes > self.MAX_PASSES:
            self._stub.running = False
            return None
        entry = self._store.next_due_entry()
        if entry is None:
            self._stub.running = False
        return entry

    def save_entry(self, job_id: str, payload: dict) -> None:
        if self.reschedule_error is not None:
            raise self.reschedule_error
        self._store.save_entry(job_id, payload)

    def __getattr__(self, name):
        return getattr(self._store, name)


class _WorkerStub:
    """Controller surface for the generation worker.

    The loop is the piece that turns a queued job into a file on disk, a
    metadata sidecar, a gallery entry and a status line -- and it is the piece
    that must never die, because the thread is never restarted.
    """

    _generation_worker_loop = ImageGenCamController._generation_worker_loop
    _register_completed_generation = ImageGenCamController._register_completed_generation
    _load_generation_job = ImageGenCamController._load_generation_job
    _save_generation_job = ImageGenCamController._save_generation_job
    _reschedule_generation_job = ImageGenCamController._reschedule_generation_job
    _abandon_generation_job = ImageGenCamController._abandon_generation_job
    _write_generation_metadata = ImageGenCamController._write_generation_metadata
    _read_generation_metadata = ImageGenCamController._read_generation_metadata
    _save_generation_metadata_payload = ImageGenCamController._save_generation_metadata_payload
    _get_generated_metadata_path = ImageGenCamController._get_generated_metadata_path
    _project_relative_path = ImageGenCamController._project_relative_path
    _project_path_from_stored_path = ImageGenCamController._project_path_from_stored_path
    _refresh_pending_jobs_count = ImageGenCamController._refresh_pending_jobs_count
    _current_album_path = ImageGenCamController._current_album_path
    _invalidate_album_cache = ImageGenCamController._invalidate_album_cache
    _is_complete_image = staticmethod(ImageGenCamController._is_complete_image)
    _retry_delay_seconds = staticmethod(ImageGenCamController._retry_delay_seconds)
    failed_jobs_root = ImageGenCamController.failed_jobs_root

    def __init__(self, root: Path) -> None:
        self.project_root = root
        self.generated_root = root / "data" / "generated"
        self.capture_root = root / "data" / "captures"
        self.generated_root.mkdir(parents=True, exist_ok=True)
        self.capture_root.mkdir(parents=True, exist_ok=True)
        self.generation_job_store = _DrainingStore(
            PersistentJobStore(root / "data" / "queue" / "generation"), self
        )
        self.image_editor = _FakeEditor()
        self.image_edit_lock = Lock()
        self.state = _FakeState()
        self.state_lock = Lock()
        self.running = True
        self.gallery_paths: list[Path] = []
        self.album_index = 0
        self.album_cached_path: Path | None = None
        self.album_cached_image = None
        self.album_source_cached_path: Path | None = None
        self.album_source_cached_image = None
        self.album_qr_cached_path: Path | None = None
        self.album_qr_cached_image = None
        self.album_qr_cached_url: str | None = None
        self.ready_unseen_count = 0
        self.preview_overlay_dirty = False

    def queue_job(
        self,
        *,
        name: str = "shot",
        reference_paths: tuple[Path, ...] = (),
        magic_history_id: str | None = None,
    ) -> GenerationJob:
        capture_path = self.capture_root / f"{name}.jpg"
        capture_path.write_bytes(_jpeg_bytes())
        job = GenerationJob(
            prompt_button="prompt-1",
            prompt_title="Neon",
            prompt_body="Make it neon",
            capture_path=capture_path,
            generated_path=self.generated_root / f"{name}.jpg",
            reference_paths=reference_paths,
            magic_history_id=magic_history_id,
        )
        self._save_generation_job(job)
        return job

    def run_worker(self) -> None:
        # The loop sleeps a quarter second whenever the queue is empty; the
        # tests do not need to wait it out.
        with mock.patch("time.sleep"):
            self._generation_worker_loop()

    def metadata_for(self, job: GenerationJob) -> dict:
        return json.loads(Path(f"{job.generated_path}.json").read_text(encoding="utf-8"))


class _WorkerTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.stub = _WorkerStub(Path(self.tmp.name))


class GenerationWorkerSuccessTests(_WorkerTestCase):
    def test_a_queued_job_produces_the_generated_image(self) -> None:
        job = self.stub.queue_job()

        self.stub.run_worker()

        self.assertTrue(job.generated_path.is_file())
        self.assertEqual(len(self.stub.image_editor.calls), 1)

    def test_the_prompt_and_capture_reach_the_editor(self) -> None:
        job = self.stub.queue_job()

        self.stub.run_worker()

        call = self.stub.image_editor.calls[0]
        self.assertEqual(call["prompt"], "Make it neon")
        self.assertEqual(call["capture_path"], job.capture_path)

    def test_a_finished_job_leaves_the_queue(self) -> None:
        # A job left in the queue is retried forever and keeps the badge lit.
        self.stub.queue_job()

        self.stub.run_worker()

        self.assertEqual(self.stub.generation_job_store.count(), 0)
        self.assertEqual(self.stub.state.pending_jobs, 0)

    def test_the_sidecar_records_which_photo_it_came_from(self) -> None:
        # The album's compare view finds the original through this field.
        job = self.stub.queue_job()

        self.stub.run_worker()

        self.assertEqual(self.stub.metadata_for(job)["capture_path"], "data/captures/shot.jpg")

    def test_a_magic_job_records_its_reference_and_history_entry(self) -> None:
        reference = self.stub.capture_root / "reference.jpg"
        reference.write_bytes(_jpeg_bytes())
        job = self.stub.queue_job(reference_paths=(reference,), magic_history_id="magic-1")

        self.stub.run_worker()

        metadata = self.stub.metadata_for(job)
        self.assertEqual(metadata["magic_reference_path"], "data/captures/reference.jpg")
        self.assertEqual(metadata["magic_history_id"], "magic-1")
        self.assertEqual(self.stub.image_editor.calls[0]["reference_paths"], [reference])

    def test_the_finished_image_is_announced(self) -> None:
        job = self.stub.queue_job()

        self.stub.run_worker()

        self.assertEqual(self.stub.state.last_generated_path, str(job.generated_path))
        self.assertIn("Neon", self.stub.state.status_message)
        self.assertIsNone(self.stub.state.last_error)

    def test_several_queued_jobs_are_all_worked_through(self) -> None:
        for index in range(3):
            self.stub.queue_job(name=f"shot-{index}")

        self.stub.run_worker()

        self.assertEqual(len(self.stub.image_editor.calls), 3)
        self.assertEqual(self.stub.generation_job_store.count(), 0)


class ExistingOutputTests(_WorkerTestCase):
    def test_a_finished_output_is_not_generated_a_second_time(self) -> None:
        # The queue survives a reboot, so a job whose output already landed
        # must not be paid for again.
        job = self.stub.queue_job()
        job.generated_path.write_bytes(_jpeg_bytes((10, 200, 10)))

        self.stub.run_worker()

        self.assertEqual(self.stub.image_editor.calls, [])
        self.assertEqual(self.stub.generation_job_store.count(), 0)
        self.assertEqual(self.stub.state.last_generated_path, str(job.generated_path))

    def test_a_half_written_output_is_generated_again(self) -> None:
        # A power cut mid-write leaves a truncated file at the final path.
        # Accepting it would publish a corrupt photo to the album.
        job = self.stub.queue_job()
        whole = _jpeg_bytes()
        job.generated_path.write_bytes(whole[: len(whole) // 2])

        self.stub.run_worker()

        self.assertEqual(len(self.stub.image_editor.calls), 1)
        self.assertTrue(ImageGenCamController._is_complete_image(job.generated_path))

    def test_an_empty_output_file_is_generated_again(self) -> None:
        job = self.stub.queue_job()
        job.generated_path.write_bytes(b"")

        self.stub.run_worker()

        self.assertEqual(len(self.stub.image_editor.calls), 1)


class GenerationWorkerFailureTests(_WorkerTestCase):
    def test_a_failed_job_is_queued_for_another_attempt(self) -> None:
        self.stub.image_editor = _FakeEditor(RuntimeError("upstream timeout"))
        self.stub.queue_job()

        self.stub.run_worker()

        self.assertEqual(self.stub.generation_job_store.count(), 1)
        self.assertEqual(self.stub.state.last_error, "upstream timeout")
        self.assertIn("retry", self.stub.state.status_message.lower())

    def test_a_failure_does_not_publish_a_generated_image(self) -> None:
        self.stub.image_editor = _FakeEditor(RuntimeError("nope"))
        job = self.stub.queue_job()

        self.stub.run_worker()

        self.assertFalse(job.generated_path.exists())
        self.assertIsNone(self.stub.state.last_generated_path)
        self.assertEqual(self.stub.gallery_paths, [])

    def test_the_worker_survives_a_failure_and_keeps_serving_the_queue(self) -> None:
        # One bad job used to be able to end the loop, which parks every later
        # photo in the queue for the rest of the process.
        class _FailsOnce(_FakeEditor):
            def edit_image(self, capture_path, prompt, output_path, reference_paths=None):
                if not self.calls:
                    self.calls.append({"capture_path": capture_path})
                    raise RuntimeError("first one fails")
                return super().edit_image(capture_path, prompt, output_path, reference_paths)

        self.stub.image_editor = _FailsOnce()
        self.stub.queue_job(name="doomed")
        self.stub.queue_job(name="fine")

        self.stub.run_worker()

        self.assertEqual(len(self.stub.image_editor.calls), 2)
        self.assertTrue((self.stub.generated_root / "fine.jpg").is_file())

    def test_the_worker_survives_a_recovery_that_also_fails(self) -> None:
        # The retry path writes to disk, and a full disk is exactly when it
        # fails. Letting that escape would kill the thread for good.
        self.stub.image_editor = _FakeEditor(RuntimeError("upstream timeout"))
        self.stub.queue_job()
        # Armed only once the job is on disk: it is the *rewrite* on the
        # failure path that has to be survivable.
        self.stub.generation_job_store.reschedule_error = OSError("No space left on device")

        self.stub.run_worker()

        self.assertEqual(self.stub.state.last_error, "upstream timeout")
        self.assertIn("failed", self.stub.state.status_message.lower())
        # It kept taking passes at the queue rather than ending the thread.
        self.assertGreater(self.stub.generation_job_store.passes, 1)


class RegisterCompletedGenerationTests(_WorkerTestCase):
    def _register(self, name: str) -> Path:
        path = self.stub.generated_root / name
        path.write_bytes(_jpeg_bytes())
        job = GenerationJob(
            prompt_button="prompt-1",
            prompt_title="Neon",
            prompt_body="body",
            capture_path=self.stub.capture_root / "shot.jpg",
            generated_path=path,
        )
        self.stub._register_completed_generation(path, job)
        return path

    def test_the_newest_image_goes_to_the_front_of_the_album(self) -> None:
        first = self._register("first.jpg")
        second = self._register("second.jpg")

        self.assertEqual(self.stub.gallery_paths, [second, first])

    def test_a_regenerated_image_is_not_listed_twice(self) -> None:
        self._register("shot.jpg")
        again = self._register("shot.jpg")

        self.assertEqual(self.stub.gallery_paths, [again])

    def test_the_unseen_badge_counts_images_finished_outside_the_album(self) -> None:
        self._register("a.jpg")
        self._register("b.jpg")

        self.assertEqual(self.stub.state.ready_images, 2)

    def test_the_badge_clears_while_the_album_is_open(self) -> None:
        self.stub.ready_unseen_count = 3
        self.stub.state.mode = "album"

        self._register("a.jpg")

        self.assertEqual(self.stub.state.ready_images, 0)

    def test_browsing_the_album_stays_on_the_same_photo(self) -> None:
        # A generation finishing must not yank the album out from under
        # someone scrolling through it.
        older = self._register("older.jpg")
        self._register("newer.jpg")
        self.stub.state.mode = "album"
        self.stub.album_index = self.stub.gallery_paths.index(older)

        self._register("newest.jpg")

        self.assertEqual(self.stub.gallery_paths[self.stub.album_index], older)

    def test_the_album_jumps_to_the_newest_photo_when_it_is_not_open(self) -> None:
        self._register("older.jpg")
        newest = self._register("newest.jpg")

        self.assertEqual(self.stub.gallery_paths[self.stub.album_index], newest)

    def test_the_cached_album_frame_is_dropped(self) -> None:
        # Otherwise the album keeps redrawing the previous photo.
        self.stub.album_cached_path = Path("/stale.jpg")

        self._register("a.jpg")

        self.assertIsNone(self.stub.album_cached_path)


if __name__ == "__main__":
    unittest.main()
