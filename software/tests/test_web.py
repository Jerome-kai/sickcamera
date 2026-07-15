from __future__ import annotations

import tempfile
import unittest
import zipfile
from io import BytesIO
from pathlib import Path

from imagegencam.web import (
    build_generated_images_zip,
    build_generated_image_list,
    build_selected_images_zip,
    delete_generated_image_by_relative_path,
    get_capture_image_by_relative_path,
    get_generated_image_by_relative_path,
    get_latest_generated_path,
    get_or_create_thumbnail,
    json_for_inline_script,
    render_page,
)


class _FakeController:
    def __init__(self, project_root: Path, last_generated_path: str | None = None) -> None:
        self.project_root = project_root
        self._snapshot = {"last_generated_path": last_generated_path}

    def get_status_snapshot(self) -> dict[str, str | None]:
        return dict(self._snapshot)

    def get_prompt_entries(self) -> list[dict[str, str]]:
        return [
            {"id": "prompt-1", "title": "First", "body": "First prompt"},
            {"id": "prompt-2", "title": "Second", "body": "Second prompt"},
        ]

    def get_device_details(self) -> dict[str, object]:
        return {
            "battery_status": "74% charging",
            "wifi_network": "Studio Wi-Fi",
            "ip_address": "192.168.1.42",
            "mac_address": "00:11:22:33:44:55",
            "hostname": "imagegencam",
            "app_url": "http://imagegencam.local",
            "storage_status": "12.0 GB free of 32.0 GB",
            "cpu_status": "8%",
        }


class LatestGeneratedPathTests(unittest.TestCase):
    def test_returns_none_when_only_gitkeep_exists(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            generated_root = project_root / "data" / "generated"
            generated_root.mkdir(parents=True, exist_ok=True)
            (generated_root / ".gitkeep").write_text("\n", encoding="utf-8")

            controller = _FakeController(project_root)

            self.assertIsNone(get_latest_generated_path(controller))

    def test_falls_back_to_newest_real_image(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            generated_root = project_root / "data" / "generated" / "2026-04-14"
            generated_root.mkdir(parents=True, exist_ok=True)
            older = generated_root / "older.jpg"
            newer = generated_root / "newer.webp"
            older.write_bytes(b"older")
            newer.write_bytes(b"newer")

            controller = _FakeController(project_root, last_generated_path=str(generated_root / "missing.jpg"))

            self.assertEqual(get_latest_generated_path(controller), newer)

    def test_generated_image_list_includes_download_url(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            generated_root = project_root / "data" / "generated" / "2026-05-07"
            generated_root.mkdir(parents=True, exist_ok=True)
            image_path = generated_root / "story.jpg"
            image_path.write_bytes(b"story")

            controller = _FakeController(project_root)

            items = build_generated_image_list(controller)

            self.assertEqual(len(items), 1)
            self.assertEqual(items[0]["image_url"], "/generated/2026-05-07/story.jpg")
            self.assertEqual(items[0]["download_url"], "/download/generated/2026-05-07/story.jpg")
            self.assertEqual(items[0]["relative_path"], "2026-05-07/story.jpg")

    def test_generated_images_zip_includes_all_images(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            generated_root = project_root / "data" / "generated" / "2026-05-07"
            generated_root.mkdir(parents=True, exist_ok=True)
            (generated_root / "first.jpg").write_bytes(b"first")
            (generated_root / "second.webp").write_bytes(b"second")

            controller = _FakeController(project_root)

            archive = zipfile.ZipFile(BytesIO(build_generated_images_zip(controller)))

            self.assertEqual(
                sorted(archive.namelist()),
                ["2026-05-07/first.jpg", "2026-05-07/second.webp"],
            )

    def test_delete_generated_image_removes_file_and_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            generated_root = project_root / "data" / "generated" / "2026-05-07"
            generated_root.mkdir(parents=True, exist_ok=True)
            image_path = generated_root / "story.jpg"
            metadata_path = generated_root / "story.jpg.json"
            image_path.write_bytes(b"story")
            metadata_path.write_text("{}", encoding="utf-8")

            controller = _FakeController(project_root)

            self.assertTrue(delete_generated_image_by_relative_path(controller, "2026-05-07/story.jpg"))
            self.assertFalse(image_path.exists())
            self.assertFalse(metadata_path.exists())

    def test_delete_generated_image_removes_cached_thumbnail(self) -> None:
        from PIL import Image

        with tempfile.TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            generated_root = project_root / "data" / "generated" / "day"
            generated_root.mkdir(parents=True, exist_ok=True)
            Image.new("RGB", (800, 600), (90, 90, 90)).save(generated_root / "shot.jpg", "JPEG")
            controller = _FakeController(project_root)

            thumb = get_or_create_thumbnail(controller, "day/shot.jpg")
            self.assertTrue(thumb.is_file())

            self.assertTrue(delete_generated_image_by_relative_path(controller, "day/shot.jpg"))
            self.assertFalse(thumb.exists())

    def test_generated_image_lookup_rejects_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            generated_root = project_root / "data" / "generated"
            generated_root.mkdir(parents=True, exist_ok=True)
            outside = project_root / "secret.jpg"
            outside.write_bytes(b"secret")

            controller = _FakeController(project_root)

            self.assertIsNone(get_generated_image_by_relative_path(controller, "../secret.jpg"))

    def test_capture_image_lookup_rejects_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            capture_root = project_root / "data" / "captures"
            capture_root.mkdir(parents=True, exist_ok=True)
            outside = project_root / "secret.jpg"
            outside.write_bytes(b"secret")

            controller = _FakeController(project_root)

            self.assertIsNone(get_capture_image_by_relative_path(controller, "../secret.jpg"))

    def test_prompt_editor_autosaves_without_save_button(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            controller = _FakeController(Path(tmp))

            html = render_page(controller).decode("utf-8")

            self.assertIn('data-tab="prompt">Prompts</button>', html)
            self.assertIn("<h3>Prompts</h3>", html)
            self.assertNotIn("Autosaves changes.", html)
            self.assertIn("schedulePromptSave", html)
            self.assertNotIn('id="save-prompts-button"', html)

    def test_gallery_has_all_and_selected_photo_actions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            controller = _FakeController(Path(tmp))

            html = render_page(controller).decode("utf-8")

            self.assertIn("Download All", html)
            self.assertIn('id="download-selected-button"', html)
            self.assertIn('id="delete-selected-button"', html)
            self.assertIn('id="select-toggle-button"', html)
            self.assertIn("/download/selected", html)
            self.assertIn("/api/images/delete", html)
            self.assertIn("relative_paths: paths", html)
            self.assertIn("thumb_url", html)

    def test_selected_zip_contains_only_requested_images(self) -> None:
        from PIL import Image

        with tempfile.TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            generated_root = project_root / "data" / "generated" / "day"
            generated_root.mkdir(parents=True, exist_ok=True)
            for name in ("a.jpg", "b.jpg", "c.jpg"):
                Image.new("RGB", (8, 8), (200, 100, 50)).save(generated_root / name, "JPEG")
            controller = _FakeController(project_root)

            body = build_selected_images_zip(controller, ["day/a.jpg", "day/c.jpg", "../evil.jpg"])

            self.assertIsNotNone(body)
            with zipfile.ZipFile(BytesIO(body)) as archive:
                self.assertEqual(sorted(archive.namelist()), ["day/a.jpg", "day/c.jpg"])
            self.assertIsNone(build_selected_images_zip(controller, ["missing.jpg"]))

    def test_thumbnail_created_smaller_and_cached(self) -> None:
        from PIL import Image

        with tempfile.TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            generated_root = project_root / "data" / "generated" / "day"
            generated_root.mkdir(parents=True, exist_ok=True)
            Image.new("RGB", (1024, 768), (10, 120, 200)).save(generated_root / "big.jpg", "JPEG")
            controller = _FakeController(project_root)

            thumb = get_or_create_thumbnail(controller, "day/big.jpg")

            self.assertIsNotNone(thumb)
            with Image.open(thumb) as reduced:
                self.assertLessEqual(max(reduced.size), 360)
            first_mtime = thumb.stat().st_mtime_ns
            again = get_or_create_thumbnail(controller, "day/big.jpg")
            self.assertEqual(again, thumb)
            self.assertEqual(again.stat().st_mtime_ns, first_mtime)
            self.assertIsNone(get_or_create_thumbnail(controller, "../escape.jpg"))

    def test_batch_delete_endpoint_removes_all_requested_images(self) -> None:
        import json
        import urllib.request

        from imagegencam.web import WebServerThread

        with tempfile.TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            generated_root = project_root / "data" / "generated" / "day"
            generated_root.mkdir(parents=True, exist_ok=True)
            for name in ("a.jpg", "b.jpg", "c.jpg"):
                (generated_root / name).write_bytes(b"img")
            controller = _FakeController(project_root)

            server = WebServerThread(controller, "127.0.0.1", 0)
            server.start()
            try:
                port = server.server.server_address[1]
                request = urllib.request.Request(
                    f"http://127.0.0.1:{port}/api/images/delete",
                    data=json.dumps({"relative_paths": ["day/a.jpg", "day/c.jpg"]}).encode(),
                    headers={"Content-Type": "application/json"},
                    method="POST",
                )
                with urllib.request.urlopen(request, timeout=5) as response:
                    payload = json.loads(response.read())
            finally:
                server.stop()

            self.assertEqual(payload["deleted"], 2)
            self.assertEqual([item["filename"] for item in payload["images"]], ["b.jpg"])
            self.assertFalse((generated_root / "a.jpg").exists())
            self.assertTrue((generated_root / "b.jpg").exists())

    def test_about_uses_device_details_not_live_preview(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            controller = _FakeController(Path(tmp))

            html = render_page(controller).decode("utf-8")

            self.assertIn("device-details", html)
            self.assertIn("battery_status", html)
            self.assertIn("Studio Wi-Fi", html)
            self.assertNotIn("Live device screen preview", html)
            self.assertNotIn("deviceScreenImage", html)

    def test_add_prompt_inserts_at_top(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            controller = _FakeController(Path(tmp))

            html = render_page(controller).decode("utf-8")

            self.assertIn("promptEntries.unshift", html)
            self.assertNotIn("promptEntries.push", html)

    def test_prompt_payload_cannot_break_out_of_script_tag(self) -> None:
        payload = [{"id": "prompt-1", "title": "x", "body": '</script><script>alert("x")</script>'}]

        encoded = json_for_inline_script(payload)

        self.assertNotIn("</script>", encoded.lower())
        self.assertIn("\\u003c/script", encoded)

    def test_render_page_escapes_prompt_payload_for_inline_script(self) -> None:
        class EvilPromptController(_FakeController):
            def get_prompt_entries(self) -> list[dict[str, str]]:
                return [
                    {
                        "id": "prompt-1",
                        "title": "x",
                        "body": '</script><script>window.evil = true</script>',
                    }
                ]

        with tempfile.TemporaryDirectory() as tmp:
            controller = EvilPromptController(Path(tmp))

            html = render_page(controller).decode("utf-8")

            self.assertEqual(html.lower().count("</script>"), 2)
            self.assertNotIn("</script><script>window.evil = true", html)
            self.assertIn("\\u003c/script\\u003e", html)


if __name__ == "__main__":
    unittest.main()
