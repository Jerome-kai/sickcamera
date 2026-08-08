from __future__ import annotations

import base64
import json
import tempfile
import unittest
import urllib.error
import urllib.request
from io import BytesIO
from pathlib import Path

from PIL import Image

from imagegencam.config import normalize_prompt_entries
from imagegencam.web import (
    WebServerThread,
    decode_image_data_url,
    get_prompt_reference_image,
    render_page,
)


def _png_data_url() -> str:
    buffer = BytesIO()
    Image.new("RGB", (4, 4), (10, 120, 200)).save(buffer, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode()


class PromptReferenceNormalizationTests(unittest.TestCase):
    def test_reference_image_is_preserved(self) -> None:
        entries = normalize_prompt_entries(
            [{"id": "goblin", "title": "Goblin", "body": "make a goblin", "reference_image": "goblin.jpg"}]
        )
        self.assertEqual(entries["goblin"]["reference_image"], "goblin.jpg")

    def test_reference_image_defaults_to_empty(self) -> None:
        entries = normalize_prompt_entries([{"id": "plain", "title": "Plain", "body": "body"}])
        self.assertEqual(entries["plain"]["reference_image"], "")

    def test_reference_image_path_traversal_is_stripped(self) -> None:
        entries = normalize_prompt_entries(
            [{"id": "evil", "title": "Evil", "body": "body", "reference_image": "../../../etc/passwd"}]
        )
        self.assertEqual(entries["evil"]["reference_image"], "passwd")

    def test_reference_image_rejects_bare_dot_segments(self) -> None:
        entries = normalize_prompt_entries(
            [{"id": "dots", "title": "Dots", "body": "body", "reference_image": ".."}]
        )
        self.assertEqual(entries["dots"]["reference_image"], "")


class PromptStorePersistenceTests(unittest.TestCase):
    def test_reference_image_survives_a_save_and_reload(self) -> None:
        from imagegencam.config import PromptStore

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "prompts.json"
            store = PromptStore(path)
            store.save_entries(
                [{"id": "goblin", "title": "Goblin", "body": "body", "reference_image": "goblin.jpg"}]
            )

            reloaded = PromptStore(path).load_entries()

        self.assertEqual(reloaded["goblin"]["reference_image"], "goblin.jpg")

    def test_prompts_without_an_image_stay_text_only_on_disk(self) -> None:
        from imagegencam.config import PromptStore

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "prompts.json"
            PromptStore(path).save_entries([{"id": "plain", "title": "Plain", "body": "body"}])
            written = json.loads(path.read_text())

        self.assertEqual(written, [{"id": "plain", "title": "Plain", "body": "body"}])


class DataUrlDecodingTests(unittest.TestCase):
    def test_decodes_a_base64_image(self) -> None:
        self.assertTrue(decode_image_data_url(_png_data_url()).startswith(b"\x89PNG"))

    def test_rejects_a_non_image_data_url(self) -> None:
        with self.assertRaises(ValueError):
            decode_image_data_url("data:text/plain;base64,aGk=")

    def test_rejects_malformed_base64(self) -> None:
        with self.assertRaises(ValueError):
            decode_image_data_url("data:image/png;base64,not!valid!base64")

    def test_rejects_an_oversized_payload(self) -> None:
        oversized = base64.b64encode(b"\x00" * (13 * 1024 * 1024)).decode()
        with self.assertRaises(ValueError):
            decode_image_data_url(f"data:image/png;base64,{oversized}")


class _PromptImageController:
    def __init__(self, project_root: Path) -> None:
        self.project_root = project_root
        self.prompt_reference_root = project_root / "data" / "prompt-references"
        self.prompt_reference_root.mkdir(parents=True, exist_ok=True)
        self.entries = [
            {"id": "prompt-1", "title": "First", "body": "First prompt", "reference_image": ""},
        ]
        self.attached: list[tuple[str, int]] = []
        self.cleared: list[str] = []

    def get_status_snapshot(self) -> dict[str, str | None]:
        return {"last_generated_path": None}

    def get_prompt_entries(self) -> list[dict[str, str]]:
        return [dict(entry) for entry in self.entries]

    def get_device_details(self) -> dict[str, object]:
        return {}

    def set_prompt_reference_image(self, prompt_id: str, image_bytes: bytes) -> str:
        if prompt_id != "prompt-1":
            raise ValueError(f"Unknown prompt: {prompt_id}")
        self.attached.append((prompt_id, len(image_bytes)))
        filename = f"{prompt_id}.jpg"
        Image.new("RGB", (4, 4)).save(self.prompt_reference_root / filename, format="JPEG")
        self.entries[0]["reference_image"] = filename
        return filename

    def clear_prompt_reference_image(self, prompt_id: str) -> bool:
        self.cleared.append(prompt_id)
        self.entries[0]["reference_image"] = ""
        return True


class PromptReferenceServingTests(unittest.TestCase):
    def test_serves_only_files_inside_the_reference_folder(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            controller = _PromptImageController(Path(tmp))
            (controller.prompt_reference_root / "ok.jpg").write_bytes(b"jpeg")
            (Path(tmp) / "secret.txt").write_text("nope")

            self.assertIsNotNone(get_prompt_reference_image(controller, "ok.jpg"))
            self.assertIsNone(get_prompt_reference_image(controller, "../secret.txt"))
            self.assertIsNone(get_prompt_reference_image(controller, "%2e%2e/secret.txt"))
            self.assertIsNone(get_prompt_reference_image(controller, "missing.jpg"))


class PromptReferenceUiTests(unittest.TestCase):
    def test_prompt_card_offers_an_image_slot(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            html = render_page(_PromptImageController(Path(tmp))).decode("utf-8")

        self.assertIn("prompt-reference-input", html)
        self.assertIn("/api/prompts/reference", html)
        self.assertIn("/prompt-references/", html)


class PromptReferenceEndpointTests(unittest.TestCase):
    def _post(self, port: int, path: str, body: dict) -> tuple[int, dict]:
        request = urllib.request.Request(
            f"http://127.0.0.1:{port}{path}",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as exc:
            return exc.code, {}

    def test_attach_and_clear_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            controller = _PromptImageController(Path(tmp))
            server = WebServerThread(controller, "127.0.0.1", 0)
            server.start()
            try:
                port = server.server.server_address[1]

                status, payload = self._post(
                    port, "/api/prompts/reference", {"prompt_id": "prompt-1", "image": _png_data_url()}
                )
                self.assertEqual(status, 200)
                self.assertEqual(payload["reference_image"], "prompt-1.jpg")
                self.assertEqual(payload["prompt_entries"][0]["reference_image"], "prompt-1.jpg")

                with urllib.request.urlopen(
                    f"http://127.0.0.1:{port}/prompt-references/prompt-1.jpg", timeout=5
                ) as response:
                    self.assertEqual(response.status, 200)

                cleared_status, cleared = self._post(
                    port, "/api/prompts/reference/delete", {"prompt_id": "prompt-1"}
                )
                self.assertEqual(cleared_status, 200)
                self.assertTrue(cleared["ok"])
                self.assertEqual(cleared["prompt_entries"][0]["reference_image"], "")
            finally:
                server.stop()

        self.assertEqual(controller.attached, [("prompt-1", controller.attached[0][1])])
        self.assertEqual(controller.cleared, ["prompt-1"])

    def test_accepts_a_body_larger_than_the_default_post_ceiling(self) -> None:
        """A phone photo dwarfs MAX_POST_BODY_BYTES; the endpoint must not 413."""
        from imagegencam.web import MAX_POST_BODY_BYTES

        buffer = BytesIO()
        # Noise so JPEG cannot compress it down under the default ceiling.
        image = Image.new("RGB", (900, 900))
        image.putdata([(index % 256, (index * 7) % 256, (index * 13) % 256) for index in range(900 * 900)])
        image.save(buffer, format="JPEG", quality=95)
        photo = buffer.getvalue()
        data_url = "data:image/jpeg;base64," + base64.b64encode(photo).decode()
        self.assertGreater(len(data_url), MAX_POST_BODY_BYTES)

        with tempfile.TemporaryDirectory() as tmp:
            controller = _PromptImageController(Path(tmp))
            server = WebServerThread(controller, "127.0.0.1", 0)
            server.start()
            try:
                port = server.server.server_address[1]
                status, payload = self._post(
                    port, "/api/prompts/reference", {"prompt_id": "prompt-1", "image": data_url}
                )
            finally:
                server.stop()

        self.assertEqual(status, 200)
        self.assertEqual(payload["reference_image"], "prompt-1.jpg")

    def test_bad_requests_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            controller = _PromptImageController(Path(tmp))
            server = WebServerThread(controller, "127.0.0.1", 0)
            server.start()
            try:
                port = server.server.server_address[1]
                self.assertEqual(self._post(port, "/api/prompts/reference", {})[0], 400)
                self.assertEqual(
                    self._post(port, "/api/prompts/reference", {"prompt_id": "prompt-1"})[0], 400
                )
                self.assertEqual(
                    self._post(
                        port,
                        "/api/prompts/reference",
                        {"prompt_id": "prompt-1", "image": "data:text/plain;base64,aGk="},
                    )[0],
                    400,
                )
                # An unknown prompt id must not create a stray file.
                self.assertEqual(
                    self._post(
                        port,
                        "/api/prompts/reference",
                        {"prompt_id": "nope", "image": _png_data_url()},
                    )[0],
                    400,
                )
                self.assertEqual(self._post(port, "/api/prompts/reference/delete", {})[0], 400)
            finally:
                server.stop()


class _ControllerStub:
    """Minimal stand-in so the prompt-image helpers can be unit tested.

    The real controller opens the camera and the display in its constructor,
    so the methods are exercised unbound against this stub instead.
    """

    def __init__(self, project_root: Path, prompt_entries: dict) -> None:
        self.project_root = project_root
        self.prompt_entries = prompt_entries

    @property
    def prompt_reference_root(self) -> Path:
        return self.project_root / "data" / "prompt-references"


class ControllerPromptReferenceTests(unittest.TestCase):
    def setUp(self) -> None:
        from imagegencam.controller import ImageGenCamController

        self.controller_class = ImageGenCamController

    def test_editing_text_keeps_an_attached_image(self) -> None:
        stub = _ControllerStub(Path("/tmp"), {"p1": {"reference_image": "p1.jpg"}})

        merged = self.controller_class._keep_existing_reference_images(
            stub, [{"id": "p1", "title": "New title", "body": "New body"}]
        )

        self.assertEqual(merged[0]["reference_image"], "p1.jpg")

    def test_an_explicit_reference_image_wins(self) -> None:
        stub = _ControllerStub(Path("/tmp"), {"p1": {"reference_image": "old.jpg"}})

        merged = self.controller_class._keep_existing_reference_images(
            stub, [{"id": "p1", "title": "T", "body": "B", "reference_image": ""}]
        )

        self.assertEqual(merged[0]["reference_image"], "")

    def test_reference_path_resolves_only_existing_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stub = _ControllerStub(
                root,
                {
                    "here": {"reference_image": "here.jpg"},
                    "gone": {"reference_image": "gone.jpg"},
                    "none": {"reference_image": ""},
                },
            )
            stub.prompt_reference_root.mkdir(parents=True, exist_ok=True)
            (stub.prompt_reference_root / "here.jpg").write_bytes(b"jpeg")

            resolve = self.controller_class.prompt_reference_path
            self.assertIsNotNone(resolve(stub, "here"))
            self.assertIsNone(resolve(stub, "gone"))
            self.assertIsNone(resolve(stub, "none"))
            self.assertIsNone(resolve(stub, "missing-prompt"))


if __name__ == "__main__":
    unittest.main()
