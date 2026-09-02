from __future__ import annotations

import json
import tempfile
import unittest
import zipfile
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from io import BytesIO
from pathlib import Path
from threading import Lock, Thread

from PIL import Image

from imagegencam.controller import ImageGenCamController
from imagegencam.web import build_handler


def write_image(path: Path, size: tuple[int, int] = (32, 24), colour: str = "red") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", size, colour).save(path)
    return path


class _FakeState:
    def __init__(self, last_generated_path: str | None = None) -> None:
        self.last_generated_path = last_generated_path
        self.ready_images = 0


class _RouteController:
    """Controller surface the HTTP layer actually reaches for.

    The existing web tests call the module-level helpers directly. These tests
    go through the request line instead, because the routes are where the
    helpers get wired to a path -- a route calling the *unsafe* lookup, or
    mapping an exception to the wrong status, is invisible to a helper test.
    """

    def __init__(self, project_root: Path, last_generated_path: str | None = None) -> None:
        self.project_root = project_root
        self.generated_root = project_root / "data" / "generated"
        self._snapshot = {"last_generated_path": last_generated_path}
        self.state = _FakeState(last_generated_path)
        self.state_lock = Lock()
        self.gallery_paths: list[Path] = []
        self.album_cached_path: Path | None = None
        self.album_index = 0
        self.saved_username: str | None = None
        self.saved_theme: str | None = None
        self.promotions: list[tuple[str, str]] = []
        self.recreated: list[str] = []
        self.magic_entries: list[dict[str, object]] = []
        self.screen_preview: bytes | None = None
        self.recreate_error: Exception | None = None

    delete_generated_image = ImageGenCamController.delete_generated_image
    _is_generated_image_file = staticmethod(ImageGenCamController._is_generated_image_file)
    _get_generated_metadata_path = ImageGenCamController._get_generated_metadata_path

    def _invalidate_album_cache(self) -> None:
        self.album_cached_path = None

    def get_status_snapshot(self) -> dict[str, str | None]:
        return dict(self._snapshot)

    def get_prompt_entries(self) -> list[dict[str, str]]:
        return [{"id": "prompt-1", "title": "First", "body": "First prompt"}]

    def update_prompt_entries(self, prompts: object) -> list[dict[str, str]]:
        self.saved_prompts = prompts
        return self.get_prompt_entries()

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

    def get_magic_history_entries(self) -> list[dict[str, object]]:
        return list(self.magic_entries)

    def mark_magic_history_promoted(self, entry_id: str, prompt_id: str) -> list[dict[str, object]]:
        self.promotions.append((entry_id, prompt_id))
        return list(self.magic_entries)

    def update_camera_username(self, username: str) -> str:
        self.saved_username = username.strip()[:24]
        return self.saved_username

    def update_app_background_theme(self, theme: str) -> str:
        self.saved_theme = theme if theme in {"aqua", "silver"} else "aqua"
        return self.saved_theme

    def recreate_vertical_from_generated(self, relative_path: str) -> dict[str, object]:
        if self.recreate_error is not None:
            raise self.recreate_error
        self.recreated.append(relative_path)
        return {"filename": "vertical.jpg", "relative_path": relative_path}

    def get_screen_preview_jpeg(self) -> bytes | None:
        return self.screen_preview


class _RouteTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.generated_root = self.root / "data" / "generated"
        self.capture_root = self.root / "data" / "captures"
        self.generated_root.mkdir(parents=True)
        self.capture_root.mkdir(parents=True)
        self.controller = _RouteController(self.root)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), build_handler(self.controller))
        # serve_forever polls at 0.5s by default, and shutdown() waits for
        # that poll -- half a second of dead time per test. Poll faster.
        Thread(
            target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True
        ).start()
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, bytes, dict[str, str]]:
        host, port = self.server.server_address
        connection = HTTPConnection(host, port, timeout=5)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, response.read(), dict(response.getheaders())
        finally:
            connection.close()

    def get(self, path: str, headers: dict[str, str] | None = None):
        return self.request("GET", path, headers=headers)

    def post_json(self, path: str, payload: object):
        return self.request(
            "POST",
            path,
            body=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )

    def post_form(self, path: str, body: str):
        return self.request(
            "POST",
            path,
            body=body.encode("utf-8"),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )

    def get_json(self, path: str) -> dict:
        status, body, _ = self.get(path)
        self.assertEqual(status, 200, body)
        return json.loads(body)


class JsonApiRouteTests(_RouteTestCase):
    def test_the_image_list_is_served_as_json(self) -> None:
        write_image(self.generated_root / "one.jpg")

        payload = self.get_json("/api/images")

        self.assertEqual([item["filename"] for item in payload["images"]], ["one.jpg"])

    def test_device_details_reach_the_about_tab(self) -> None:
        payload = self.get_json("/api/device-details")

        self.assertEqual(payload["ip_address"], "192.168.1.42")

    def test_a_controller_that_cannot_read_its_details_still_answers(self) -> None:
        # The phone app polls this; an unreadable battery or a missing route
        # must degrade to "Unknown", not 500 the page that shows it.
        def explode() -> dict[str, object]:
            raise OSError("i2c bus is busy")

        self.controller.get_device_details = explode

        payload = self.get_json("/api/device-details")

        self.assertEqual(payload["battery_status"], "Unknown")

    def test_the_magic_history_is_served_with_capture_urls(self) -> None:
        self.controller.magic_entries = [
            {
                "id": "magic-1",
                "created_at": "2026-01-01T00:00:00",
                "title": "Neon",
                "body": "Make it neon",
                "reference_capture_path": "data/captures/day/shot.jpg",
                "promoted_prompt_id": None,
            }
        ]

        payload = self.get_json("/api/magic-history")

        entry = payload["magic_history"][0]
        self.assertEqual(entry["title"], "Neon")
        self.assertEqual(entry["reference_image_url"], "/captures/day/shot.jpg")

    def test_latest_image_metadata_is_served(self) -> None:
        write_image(self.generated_root / "latest.jpg")

        payload = self.get_json("/api/latest-image")

        self.assertEqual(payload["filename"], "latest.jpg")


class StaticRouteTests(_RouteTestCase):
    def test_the_manifest_is_valid_json_with_the_right_content_type(self) -> None:
        status, body, headers = self.get("/manifest.webmanifest")

        self.assertEqual(status, 200)
        self.assertIn("application/manifest+json", headers["Content-Type"])
        self.assertIn("name", json.loads(body))

    def test_the_service_worker_is_served_as_javascript(self) -> None:
        # Served under the wrong type the browser refuses to register it and
        # the app silently stops working offline.
        status, body, headers = self.get("/service-worker.js")

        self.assertEqual(status, 200)
        self.assertIn("application/javascript", headers["Content-Type"])
        self.assertIn(b"addEventListener", body)

    def test_the_app_icon_is_a_png(self) -> None:
        status, body, headers = self.get("/app-icon.png")

        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "image/png")
        self.assertEqual(Image.open(BytesIO(body)).format, "PNG")

    def test_the_home_page_renders(self) -> None:
        status, body, headers = self.get("/")

        self.assertEqual(status, 200)
        self.assertIn("text/html", headers["Content-Type"])
        self.assertIn(b"panel-remote", body)

    def test_an_unknown_path_is_a_404(self) -> None:
        status, _, _ = self.get("/nope")

        self.assertEqual(status, 404)


class ImageServingRouteTests(_RouteTestCase):
    def test_a_generated_image_is_served_inline(self) -> None:
        write_image(self.generated_root / "day" / "shot.jpg")

        status, body, headers = self.get("/generated/day/shot.jpg")

        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "image/jpeg")
        self.assertNotIn("Content-Disposition", headers)
        self.assertEqual(Image.open(BytesIO(body)).size, (32, 24))

    def test_a_download_is_served_as_an_attachment(self) -> None:
        write_image(self.generated_root / "shot.jpg")

        status, _, headers = self.get("/download/generated/shot.jpg")

        self.assertEqual(status, 200)
        self.assertIn('filename="shot.jpg"', headers["Content-Disposition"])

    def test_a_capture_is_served(self) -> None:
        write_image(self.capture_root / "day" / "capture.jpg")

        status, body, _ = self.get("/captures/day/capture.jpg")

        self.assertEqual(status, 200)
        self.assertEqual(Image.open(BytesIO(body)).size, (32, 24))

    def test_a_thumbnail_is_smaller_than_the_original(self) -> None:
        write_image(self.generated_root / "big.jpg", size=(900, 600))

        status, body, headers = self.get("/thumbs/big.jpg")

        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "image/jpeg")
        self.assertLess(max(Image.open(BytesIO(body)).size), 900)

    def test_the_image_routes_refuse_to_climb_out_of_their_directory(self) -> None:
        # Proven at the helper level already -- proven here at the route level,
        # because a route that reached for the raw path instead would pass the
        # helper tests untouched.
        secret = self.root / "secret.jpg"
        write_image(secret)

        for prefix in ("/generated/", "/download/generated/", "/captures/", "/thumbs/"):
            with self.subTest(prefix=prefix):
                status, _, _ = self.get(f"{prefix}../../secret.jpg")

                self.assertEqual(status, 404)
        self.assertTrue(secret.exists())

    def test_a_missing_image_is_a_404_not_a_crash(self) -> None:
        status, _, _ = self.get("/generated/gone.jpg")

        self.assertEqual(status, 404)

    def test_the_screen_preview_reports_unavailable_rather_than_erroring(self) -> None:
        status, _, _ = self.get("/screen-preview.jpg")

        self.assertEqual(status, 503)

    def test_the_screen_preview_is_served_when_the_display_has_a_frame(self) -> None:
        buffer = BytesIO()
        Image.new("RGB", (48, 32), "blue").save(buffer, format="JPEG")
        self.controller.screen_preview = buffer.getvalue()

        status, body, headers = self.get("/screen-preview.jpg")

        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "image/jpeg")
        self.assertEqual(body, self.controller.screen_preview)


class LatestImageCachingTests(_RouteTestCase):
    def test_an_unchanged_image_revalidates_to_304(self) -> None:
        # The phone polls this endpoint continuously; without the ETag it would
        # re-download the full image on every poll.
        write_image(self.generated_root / "latest.jpg")
        status, body, headers = self.get("/latest-image")
        self.assertEqual(status, 200)
        self.assertTrue(body)

        etag = headers["ETag"]
        cached_status, cached_body, _ = self.get("/latest-image", {"If-None-Match": etag})

        self.assertEqual(cached_status, 304)
        self.assertEqual(cached_body, b"")

    def test_a_stale_etag_gets_the_new_image(self) -> None:
        write_image(self.generated_root / "latest.jpg")

        status, body, _ = self.get("/latest-image", {"If-None-Match": '"something-else"'})

        self.assertEqual(status, 200)
        self.assertTrue(body)

    def test_nothing_generated_yet_is_a_404(self) -> None:
        status, _, _ = self.get("/latest-image")

        self.assertEqual(status, 404)


class DownloadAllRouteTests(_RouteTestCase):
    def test_every_generated_image_is_zipped(self) -> None:
        write_image(self.generated_root / "a.jpg")
        write_image(self.generated_root / "day" / "b.jpg")

        status, body, headers = self.get("/download/all")

        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/zip")
        with zipfile.ZipFile(BytesIO(body)) as archive:
            self.assertEqual(sorted(archive.namelist()), ["a.jpg", "day/b.jpg"])


class SettingsRouteTests(_RouteTestCase):
    def test_the_profile_route_saves_a_cleaned_username(self) -> None:
        status, body, _ = self.post_json("/settings/profile", {"camera_username": "  Jerome  "})

        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["camera_username"], "Jerome")
        self.assertEqual(self.controller.saved_username, "Jerome")

    def test_a_missing_username_is_saved_as_empty_rather_than_none(self) -> None:
        # str(None) would persist the literal text "None" as the camera name.
        status, body, _ = self.post_json("/settings/profile", {})

        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["camera_username"], "")

    def test_the_theme_route_reads_a_form_encoded_body(self) -> None:
        status, body, _ = self.post_form("/settings/theme", "app_background_theme=silver")

        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["app_background_theme"], "silver")
        self.assertEqual(self.controller.saved_theme, "silver")

    def test_an_empty_theme_body_falls_back_to_the_default(self) -> None:
        status, body, _ = self.post_form("/settings/theme", "")

        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["app_background_theme"], "aqua")


class MagicHistoryPromoteRouteTests(_RouteTestCase):
    def test_promoting_an_entry_returns_the_refreshed_history(self) -> None:
        self.controller.magic_entries = [
            {
                "id": "magic-1",
                "created_at": "2026-01-01T00:00:00",
                "title": "Neon",
                "body": "Make it neon",
                "promoted_prompt_id": "prompt-1",
            }
        ]

        status, body, _ = self.post_json(
            "/api/magic-history/promote", {"entry_id": "magic-1", "prompt_id": "prompt-1"}
        )

        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["ok"])
        self.assertEqual(self.controller.promotions, [("magic-1", "prompt-1")])

    def test_a_promotion_without_an_entry_is_rejected(self) -> None:
        status, _, _ = self.post_json("/api/magic-history/promote", {"prompt_id": "prompt-1"})

        self.assertEqual(status, 400)
        self.assertEqual(self.controller.promotions, [])


class RecreateVerticalRouteTests(_RouteTestCase):
    """Every failure mode of this route maps to a different status code, and
    the phone app branches on them -- so each mapping is pinned."""

    def test_a_recreate_returns_the_new_image(self) -> None:
        status, body, _ = self.post_json("/api/recreate-vertical", {"relative_path": "shot.jpg"})

        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["image"]["filename"], "vertical.jpg")
        self.assertEqual(self.controller.recreated, ["shot.jpg"])

    def test_a_missing_path_is_rejected_before_the_controller_is_called(self) -> None:
        status, _, _ = self.post_json("/api/recreate-vertical", {})

        self.assertEqual(status, 400)
        self.assertEqual(self.controller.recreated, [])

    def test_a_vanished_source_image_is_a_404(self) -> None:
        self.controller.recreate_error = FileNotFoundError("gone")

        status, _, _ = self.post_json("/api/recreate-vertical", {"relative_path": "gone.jpg"})

        self.assertEqual(status, 404)

    def test_an_unusable_source_image_is_a_400(self) -> None:
        self.controller.recreate_error = ValueError("not a generated image")

        status, _, _ = self.post_json("/api/recreate-vertical", {"relative_path": "notes.txt"})

        self.assertEqual(status, 400)

    def test_an_upstream_failure_is_a_502_not_a_500(self) -> None:
        # The generation call reaches OpenAI; a gateway status tells the app to
        # offer a retry rather than reporting the camera itself as broken.
        self.controller.recreate_error = RuntimeError("upstream refused")

        status, _, _ = self.post_json("/api/recreate-vertical", {"relative_path": "shot.jpg"})

        self.assertEqual(status, 502)


class SaveRouteTests(_RouteTestCase):
    def test_prompts_post_as_json(self) -> None:
        status, body, _ = self.post_json(
            "/save", {"prompts": [{"id": "prompt-1", "title": "T", "body": "B"}]}
        )

        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["ok"])
        self.assertEqual(self.controller.saved_prompts[0]["body"], "B")

    def test_prompts_post_as_a_plain_html_form(self) -> None:
        # The page still works with JavaScript disabled, which means the
        # prompt_title_/prompt_body_ field pairs have to be reassembled here.
        status, _, _ = self.post_form(
            "/save", "prompt_title_prompt-1=Neon&prompt_body_prompt-1=Make+it+neon"
        )

        self.assertEqual(status, 200)
        self.assertEqual(
            self.controller.saved_prompts,
            [{"id": "prompt-1", "title": "Neon", "body": "Make it neon"}],
        )

    def test_a_malformed_json_body_is_rejected(self) -> None:
        status, _, _ = self.request(
            "POST", "/save", body=b"{not json", headers={"Content-Type": "application/json"}
        )

        self.assertEqual(status, 400)

    def test_a_json_body_that_is_not_an_object_is_rejected(self) -> None:
        # json.loads("[]") succeeds, so without the type check the next line
        # calls .get on a list and the connection dies with no response.
        status, _, _ = self.post_json("/api/button", ["shutter"])

        self.assertEqual(status, 400)

    def test_an_unknown_post_path_is_a_404(self) -> None:
        status, _, _ = self.post_json("/api/nonsense", {})

        self.assertEqual(status, 404)


class DeleteRouteTests(_RouteTestCase):
    def test_deleting_a_missing_image_is_a_404(self) -> None:
        status, _, _ = self.post_json("/api/images/delete", {"relative_path": "gone.jpg"})

        self.assertEqual(status, 404)

    def test_a_delete_without_a_path_is_rejected(self) -> None:
        status, _, _ = self.post_json("/api/images/delete", {"relative_path": "   "})

        self.assertEqual(status, 400)

    def test_an_empty_batch_is_rejected(self) -> None:
        status, _, _ = self.post_json("/api/images/delete", {"relative_paths": []})

        self.assertEqual(status, 400)

    def test_a_single_delete_removes_the_file(self) -> None:
        write_image(self.generated_root / "doomed.jpg")

        status, body, _ = self.post_json("/api/images/delete", {"relative_path": "doomed.jpg"})

        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["ok"])
        self.assertFalse((self.generated_root / "doomed.jpg").exists())


class SelectedDownloadRouteTests(_RouteTestCase):
    def test_a_selection_with_no_matches_is_a_404(self) -> None:
        status, _, _ = self.post_json("/download/selected", {"relative_paths": ["gone.jpg"]})

        self.assertEqual(status, 404)

    def test_a_selection_zips_only_what_was_asked_for(self) -> None:
        write_image(self.generated_root / "wanted.jpg")
        write_image(self.generated_root / "ignored.jpg")

        status, body, _ = self.post_json("/download/selected", {"relative_paths": ["wanted.jpg"]})

        self.assertEqual(status, 200)
        with zipfile.ZipFile(BytesIO(body)) as archive:
            self.assertEqual(archive.namelist(), ["wanted.jpg"])


class HeadRequestTests(_RouteTestCase):
    def test_head_on_the_latest_image_sends_headers_without_the_body(self) -> None:
        write_image(self.generated_root / "latest.jpg")
        size = (self.generated_root / "latest.jpg").stat().st_size

        status, body, headers = self.request("HEAD", "/latest-image")

        self.assertEqual(status, 200)
        self.assertEqual(body, b"")
        self.assertEqual(headers["Content-Length"], str(size))

    def test_head_on_the_home_page_reports_the_rendered_length(self) -> None:
        status, body, headers = self.request("HEAD", "/")

        self.assertEqual(status, 200)
        self.assertEqual(body, b"")
        self.assertGreater(int(headers["Content-Length"]), 0)

    def test_head_on_a_download_reports_the_file_size(self) -> None:
        write_image(self.generated_root / "shot.jpg")
        size = (self.generated_root / "shot.jpg").stat().st_size

        status, _, headers = self.request("HEAD", "/download/generated/shot.jpg")

        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Length"], str(size))

    def test_head_on_an_unknown_path_is_a_404(self) -> None:
        status, _, _ = self.request("HEAD", "/nope")

        self.assertEqual(status, 404)


if __name__ == "__main__":
    unittest.main()
