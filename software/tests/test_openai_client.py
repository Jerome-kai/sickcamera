from __future__ import annotations

import base64
import io
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

from imagegencam.openai_client import (
    OpenAIImageEditor,
    OpenAIImageError,
    OpenAIMagicPromptPlanner,
)


class _FakeOpenAI:
    def __init__(self, **kwargs) -> None:
        self.kwargs = kwargs


class RequireClientTests(unittest.TestCase):
    def setUp(self) -> None:
        fake_module = types.ModuleType("openai")
        fake_module.OpenAI = _FakeOpenAI
        self._module_patch = mock.patch.dict(sys.modules, {"openai": fake_module})
        self._module_patch.start()
        self.addCleanup(self._module_patch.stop)

    def test_base_url_env_is_passed_to_client(self) -> None:
        editor = OpenAIImageEditor()
        env = {"OPENAI_API_KEY": "key-1", "OPENAI_BASE_URL": "https://gateway.example/v1"}
        with mock.patch.dict(os.environ, env):
            client = editor._require_client()
        self.assertEqual(client.kwargs["base_url"], "https://gateway.example/v1")
        self.assertEqual(client.kwargs["api_key"], "key-1")

    def test_empty_base_url_means_default_endpoint(self) -> None:
        editor = OpenAIImageEditor()
        with mock.patch.dict(os.environ, {"OPENAI_API_KEY": "key-1", "OPENAI_BASE_URL": ""}):
            client = editor._require_client()
        self.assertIsNone(client.kwargs["base_url"])

    def test_client_rebuilt_when_base_url_changes(self) -> None:
        editor = OpenAIImageEditor()
        with mock.patch.dict(os.environ, {"OPENAI_API_KEY": "key-1", "OPENAI_BASE_URL": ""}):
            first = editor._require_client()
            second = editor._require_client()
        self.assertIs(first, second)
        with mock.patch.dict(
            os.environ, {"OPENAI_API_KEY": "key-1", "OPENAI_BASE_URL": "https://gateway.example/v1"}
        ):
            third = editor._require_client()
        self.assertIsNot(first, third)


class OpenAIImageEditorTests(unittest.TestCase):
    def test_output_extension_matches_output_format(self) -> None:
        self.assertEqual(OpenAIImageEditor(output_format="jpeg").output_extension, ".jpg")
        self.assertEqual(OpenAIImageEditor(output_format="webp").output_extension, ".webp")
        self.assertEqual(OpenAIImageEditor(output_format="png").output_extension, ".png")

    def test_invalid_output_format_falls_back_to_jpeg(self) -> None:
        editor = OpenAIImageEditor(output_format="bmp")

        self.assertEqual(editor.output_format, "jpeg")
        self.assertEqual(editor.output_extension, ".jpg")

    def test_api_mode_from_env_and_invalid_falls_back_to_edits(self) -> None:
        with mock.patch.dict(os.environ, {"IMAGE_GEN_API": "chat"}):
            self.assertEqual(OpenAIImageEditor().api_mode, "chat")
        with mock.patch.dict(os.environ, {"IMAGE_GEN_API": "generations"}):
            self.assertEqual(OpenAIImageEditor().api_mode, "generations")
        with mock.patch.dict(os.environ, {"IMAGE_GEN_API": "nonsense"}):
            self.assertEqual(OpenAIImageEditor().api_mode, "edits")
        self.assertEqual(OpenAIImageEditor(api_mode="chat").api_mode, "chat")


def _png_bytes() -> bytes:
    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGB", (4, 4), (255, 0, 0)).save(buffer, format="PNG")
    return buffer.getvalue()


def _data_url(image_bytes: bytes) -> str:
    return "data:image/png;base64," + base64.b64encode(image_bytes).decode("ascii")


class _FakeChatCompletions:
    def __init__(self, response) -> None:
        self.response = response
        self.captured_kwargs = None

    def create(self, **kwargs):
        self.captured_kwargs = kwargs
        return self.response


class ChatModeEditTests(unittest.TestCase):
    def _run_edit(self, response) -> tuple[Path, _FakeChatCompletions]:
        editor = OpenAIImageEditor(model="google/gemini-2.5-flash-image", api_mode="chat")
        completions = _FakeChatCompletions(response)
        fake_client = types.SimpleNamespace(
            chat=types.SimpleNamespace(completions=completions)
        )
        editor._client = fake_client
        editor._client_config = ("key", None)
        tmp = Path(tempfile.mkdtemp())
        source = tmp / "source.jpg"
        source.write_bytes(_png_bytes())
        output = tmp / "result.jpg"
        with mock.patch.dict(os.environ, {"OPENAI_API_KEY": "key", "OPENAI_BASE_URL": ""}):
            editor.edit_image(source_path=source, prompt="test", output_path=output)
        return output, completions

    def test_chat_mode_sends_prompt_and_image_and_saves_result(self) -> None:
        response = {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": "here you go",
                        "images": [
                            {"type": "image_url", "image_url": {"url": _data_url(_png_bytes())}}
                        ],
                    }
                }
            ]
        }
        output, completions = self._run_edit(_dict_to_namespace(response))

        self.assertTrue(output.exists())
        self.assertGreater(output.stat().st_size, 0)
        kwargs = completions.captured_kwargs
        self.assertEqual(kwargs["model"], "google/gemini-2.5-flash-image")
        content = kwargs["messages"][0]["content"]
        self.assertEqual(content[0]["type"], "text")
        self.assertIn("test", content[0]["text"])
        self.assertEqual(content[1]["type"], "image_url")
        self.assertTrue(content[1]["image_url"]["url"].startswith("data:image/"))

    def test_chat_mode_reads_image_from_content_parts(self) -> None:
        response = {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": [
                            {"type": "text", "text": "done"},
                            {"type": "image_url", "image_url": {"url": _data_url(_png_bytes())}},
                        ],
                    }
                }
            ]
        }
        output, _ = self._run_edit(_dict_to_namespace(response))
        self.assertTrue(output.exists())

    def test_chat_mode_raises_when_no_image_returned(self) -> None:
        response = {"choices": [{"message": {"role": "assistant", "content": "sorry, no"}}]}
        with self.assertRaises(OpenAIImageError):
            self._run_edit(_dict_to_namespace(response))


class GenerationsModeEditTests(unittest.TestCase):
    def _run_edit(self, response_payload, downloaded=None) -> tuple[Path, dict]:
        editor = OpenAIImageEditor(model="Qwen/Qwen-Image-Edit-2509", api_mode="generations")
        tmp = Path(tempfile.mkdtemp())
        source = tmp / "source.jpg"
        source.write_bytes(_png_bytes())
        output = tmp / "result.jpg"
        captured: dict = {}

        class _FakeResponse:
            def __init__(self, body: bytes) -> None:
                self._body = body

            def read(self) -> bytes:
                return self._body

            def __enter__(self):
                return self

            def __exit__(self, *exc) -> None:
                return None

        def fake_urlopen(request, timeout=None):
            if isinstance(request, str):
                captured["download_url"] = request
                return _FakeResponse(downloaded or b"")
            captured["url"] = request.full_url
            captured["headers"] = dict(request.headers)
            captured["payload"] = __import__("json").loads(request.data)
            return _FakeResponse(__import__("json").dumps(response_payload).encode())

        env = {"OPENAI_API_KEY": "sf-key", "OPENAI_BASE_URL": "https://api.siliconflow.cn/v1"}
        with mock.patch.dict(os.environ, env):
            with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
                editor.edit_image(source_path=source, prompt="test", output_path=output)
        return output, captured

    def test_generations_mode_posts_image_and_downloads_result_url(self) -> None:
        payload = {"images": [{"url": "https://cdn.example/result.png"}]}
        output, captured = self._run_edit(payload, downloaded=_png_bytes())

        self.assertEqual(captured["url"], "https://api.siliconflow.cn/v1/images/generations")
        self.assertEqual(captured["headers"]["Authorization"], "Bearer sf-key")
        self.assertEqual(captured["payload"]["model"], "Qwen/Qwen-Image-Edit-2509")
        self.assertIn("test", captured["payload"]["prompt"])
        self.assertTrue(captured["payload"]["image"].startswith("data:image/"))
        self.assertEqual(captured["download_url"], "https://cdn.example/result.png")
        self.assertTrue(output.exists())
        self.assertGreater(output.stat().st_size, 0)

    def test_generations_mode_accepts_openai_style_b64_data(self) -> None:
        payload = {"data": [{"b64_json": base64.b64encode(_png_bytes()).decode("ascii")}]}
        output, _ = self._run_edit(payload)
        self.assertTrue(output.exists())

    def test_generations_mode_raises_when_no_image(self) -> None:
        with self.assertRaises(OpenAIImageError):
            self._run_edit({"images": []})


class MagicChatModeTests(unittest.TestCase):
    def _run_plan(self, content: str) -> tuple[dict, _FakeChatCompletions]:
        planner = OpenAIMagicPromptPlanner(model="Qwen/Qwen2.5-VL-32B-Instruct", api_mode="chat")
        response = _dict_to_namespace(
            {"choices": [{"message": {"role": "assistant", "content": content}}]}
        )
        completions = _FakeChatCompletions(response)
        planner._client = types.SimpleNamespace(
            chat=types.SimpleNamespace(completions=completions)
        )
        planner._client_config = ("key", None)
        tmp = Path(tempfile.mkdtemp())
        reference = tmp / "ref.jpg"
        reference.write_bytes(_png_bytes())
        with mock.patch.dict(os.environ, {"OPENAI_API_KEY": "key", "OPENAI_BASE_URL": ""}):
            plan = planner.create_magic_prompt(reference)
        return plan, completions

    def test_chat_mode_sends_image_and_parses_json(self) -> None:
        plan, completions = self._run_plan('{"title": "Tiny Hat", "prompt": "Add a tiny hat."}')

        self.assertEqual(plan, {"title": "Tiny Hat", "prompt": "Add a tiny hat."})
        content = completions.captured_kwargs["messages"][0]["content"]
        self.assertEqual(content[1]["type"], "image_url")
        self.assertTrue(content[1]["image_url"]["url"].startswith("data:image/"))

    def test_chat_mode_strips_code_fences_and_prose(self) -> None:
        plan, _ = self._run_plan(
            'Sure! Here it is:\n```json\n{"title": "Big Sun", "prompt": "Add a huge sun."}\n```'
        )
        self.assertEqual(plan["title"], "Big Sun")

    def test_chat_mode_raises_on_non_json_reply(self) -> None:
        with self.assertRaises(OpenAIImageError):
            self._run_plan("I cannot help with that.")

    def test_magic_api_mode_from_env(self) -> None:
        with mock.patch.dict(os.environ, {"MAGIC_MODE_API": "chat"}):
            self.assertEqual(OpenAIMagicPromptPlanner().api_mode, "chat")
        with mock.patch.dict(os.environ, {"MAGIC_MODE_API": "nonsense"}):
            self.assertEqual(OpenAIMagicPromptPlanner().api_mode, "responses")


def _dict_to_namespace(value):
    if isinstance(value, dict):
        return types.SimpleNamespace(**{k: _dict_to_namespace(v) for k, v in value.items()})
    if isinstance(value, list):
        return [_dict_to_namespace(item) for item in value]
    return value


if __name__ == "__main__":
    unittest.main()
