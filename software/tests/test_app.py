from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from imagegencam import app


class _StubController:
    instances: list["_StubController"] = []

    def __init__(self, **kwargs) -> None:
        self.kwargs = kwargs
        self.ran = False
        self.run_error: Exception | None = None
        _StubController.instances.append(self)

    def run(self) -> None:
        self.ran = True
        if self.run_error is not None:
            raise self.run_error


class _StubWebServer:
    instances: list["_StubWebServer"] = []

    def __init__(self, controller, host: str, port: int) -> None:
        self.controller = controller
        self.host = host
        self.port = port
        self.started = False
        self.stopped = False
        _StubWebServer.instances.append(self)

    def start(self) -> None:
        self.started = True

    def stop(self) -> None:
        self.stopped = True


class MainWiringTests(unittest.TestCase):
    """main() is the only place the stores, the editor, the controller and the
    web server are joined up. Nothing else imports it, so a rename or a
    signature change here fails at boot on the device and nowhere earlier."""

    def setUp(self) -> None:
        _StubController.instances.clear()
        _StubWebServer.instances.clear()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        patches = [
            mock.patch.object(app, "ImageGenCamController", _StubController),
            mock.patch.object(app, "WebServerThread", _StubWebServer),
            # Reading a developer's real .env would leak their settings in.
            mock.patch.object(app, "load_env_file"),
            mock.patch.object(app, "logging"),
        ]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)

    def _run(self, env: dict[str, str] | None = None) -> _StubController:
        environment = {
            "OPENAI_API_KEY": "key",
            "IMAGE_GEN_PORT": "8000",
            **(env or {}),
        }
        with mock.patch.dict(os.environ, environment):
            app.main()
        return _StubController.instances[-1]

    def test_the_controller_is_built_and_run(self) -> None:
        controller = self._run()

        self.assertTrue(controller.ran)

    def test_the_stores_are_rooted_in_the_project_data_directory(self) -> None:
        controller = self._run()

        project_root = controller.kwargs["project_root"]
        self.assertEqual(
            controller.kwargs["prompt_store"].path, project_root / "data" / "prompts.json"
        )
        self.assertEqual(
            controller.kwargs["settings_store"].path, project_root / "data" / "settings.json"
        )
        self.assertEqual(
            controller.kwargs["generation_job_store"].path,
            project_root / "data" / "queue" / "generation",
        )

    def test_the_project_root_is_the_software_directory(self) -> None:
        # parents[2] from src/imagegencam/app.py; an extra package level would
        # silently point every store at the wrong place.
        controller = self._run()

        self.assertEqual(controller.kwargs["project_root"], Path(app.__file__).resolve().parents[2])

    def test_the_image_editor_is_configured_from_the_environment(self) -> None:
        controller = self._run(
            {
                "IMAGE_GEN_MODEL": "gpt-image-1",
                "IMAGE_GEN_QUALITY": "high",
                "IMAGE_GEN_SIZE": "1024x1024",
                "IMAGE_GEN_OUTPUT_FORMAT": "webp",
            }
        )

        editor = controller.kwargs["image_editor"]
        self.assertEqual(editor.model, "gpt-image-1")
        self.assertEqual(editor.quality, "high")
        self.assertEqual(editor.size, "1024x1024")
        self.assertEqual(editor.output_format, "webp")

    def test_the_camera_sizes_are_read_from_the_environment(self) -> None:
        controller = self._run(
            {
                "CAMERA_PREVIEW_WIDTH": "640",
                "CAMERA_PREVIEW_HEIGHT": "480",
                "CAMERA_FRAME_RATE": "24",
                "IMAGE_GEN_INPUT_WIDTH": "800",
                "IMAGE_GEN_INPUT_HEIGHT": "600",
            }
        )

        self.assertEqual(controller.kwargs["preview_size"], (640, 480))
        self.assertEqual(controller.kwargs["frame_rate"], 24)
        self.assertEqual(controller.kwargs["generation_input_size"], (800, 600))

    def test_the_web_server_binds_the_configured_host_and_port(self) -> None:
        self._run({"IMAGE_GEN_HOST": "127.0.0.1", "IMAGE_GEN_PORT": "9090"})

        server = _StubWebServer.instances[-1]
        self.assertEqual((server.host, server.port), ("127.0.0.1", 9090))
        self.assertTrue(server.started)

    def test_the_web_server_is_started_before_the_controller_runs(self) -> None:
        controller = self._run()

        # The stub records start() at construction time, so the server being
        # started at all by the time run() returned is what this pins.
        self.assertTrue(_StubWebServer.instances[-1].started)
        self.assertIs(_StubWebServer.instances[-1].controller, controller)

    def test_the_web_server_is_stopped_on_a_clean_exit(self) -> None:
        self._run()

        self.assertTrue(_StubWebServer.instances[-1].stopped)

    def test_the_web_server_is_stopped_when_the_controller_crashes(self) -> None:
        # Otherwise the port stays bound and the service cannot restart.
        def boom(**kwargs):
            controller = _StubController(**kwargs)
            controller.run_error = RuntimeError("camera vanished")
            return controller

        with mock.patch.object(app, "ImageGenCamController", boom):
            with self.assertRaises(RuntimeError):
                self._run()

        self.assertTrue(_StubWebServer.instances[-1].stopped)


if __name__ == "__main__":
    unittest.main()
