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


class _FakeImages:
    """Stands in for client.images, capturing the request the editor builds."""

    def __init__(self, result=None, error: Exception | None = None) -> None:
        self.result = result
        self.error = error
        self.captured_kwargs: dict | None = None
        self.handles: list = []

    def edit(self, **kwargs):
        self.captured_kwargs = kwargs
        image = kwargs.get("image")
        self.handles = list(image) if isinstance(image, list) else [image]
        if self.error is not None:
            raise self.error
        return self.result


def _image_result(payload: bytes, attribute: str = "b64_json"):
    encoded = base64.b64encode(payload).decode("ascii")
    return types.SimpleNamespace(data=[types.SimpleNamespace(**{attribute: encoded})])


class EditsModeTests(unittest.TestCase):
    """The default API mode -- the one a stock .env uses -- reaches
    /v1/images/edits. The chat and generations modes are covered above."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.source = self.dir / "source.png"
        self.source.write_bytes(_png_bytes())
        self.output = self.dir / "result.jpg"
        # _require_client re-reads the environment on every call and hands back
        # the cached client only when the key and base URL still match.
        env = mock.patch.dict(os.environ, {"OPENAI_API_KEY": "key", "OPENAI_BASE_URL": ""})
        env.start()
        self.addCleanup(env.stop)

    def _editor(self, images: _FakeImages, **kwargs) -> OpenAIImageEditor:
        editor = OpenAIImageEditor(**kwargs)
        editor._client = types.SimpleNamespace(images=images)
        editor._client_config = ("key", None)
        return editor

    def _reference(self, name: str) -> Path:
        path = self.dir / name
        path.write_bytes(_png_bytes())
        return path

    def test_the_edit_request_carries_the_configured_options(self) -> None:
        images = _FakeImages(_image_result(b"generated-bytes"))
        editor = self._editor(images, model="gpt-image-1", quality="high", size="1536x1024")

        editor.edit_image(source_path=self.source, prompt="make it neon", output_path=self.output)

        sent = images.captured_kwargs
        self.assertEqual(sent["model"], "gpt-image-1")
        self.assertEqual(sent["quality"], "high")
        self.assertEqual(sent["size"], "1536x1024")
        self.assertEqual(sent["output_format"], "jpeg")
        self.assertIn("make it neon", sent["prompt"])

    def test_an_explicit_size_overrides_the_configured_one(self) -> None:
        images = _FakeImages(_image_result(b"generated-bytes"))
        editor = self._editor(images, size="1024x1024")

        editor.edit_image(
            source_path=self.source, prompt="p", output_path=self.output, size="1024x1536"
        )

        self.assertEqual(images.captured_kwargs["size"], "1024x1536")

    def test_the_generated_bytes_land_at_the_output_path(self) -> None:
        images = _FakeImages(_image_result(b"generated-bytes"))
        editor = self._editor(images)

        returned = editor.edit_image(
            source_path=self.source, prompt="p", output_path=self.output
        )

        self.assertEqual(returned, self.output)
        self.assertEqual(self.output.read_bytes(), b"generated-bytes")
        # Written through a temp file and renamed, so no .part is left behind.
        self.assertEqual(list(self.dir.glob("*.part")), [])

    def test_an_older_model_asks_for_low_input_fidelity(self) -> None:
        images = _FakeImages(_image_result(b"x"))

        self._editor(images, model="gpt-image-1").edit_image(
            source_path=self.source, prompt="p", output_path=self.output
        )

        self.assertEqual(images.captured_kwargs["input_fidelity"], "low")

    def test_gpt_image_2_is_sent_without_the_input_fidelity_hint(self) -> None:
        for model in ("gpt-image-2", "gpt-image-2-2026-04-21"):
            with self.subTest(model=model):
                images = _FakeImages(_image_result(b"x"))

                self._editor(images, model=model).edit_image(
                    source_path=self.source, prompt="p", output_path=self.output
                )

                self.assertNotIn("input_fidelity", images.captured_kwargs)

    def test_a_gateway_prefixed_model_is_recognised_as_the_same_model(self) -> None:
        # Gateways prefix the vendor ("openai/gpt-image-2"). Matching on the
        # whole string would send input_fidelity to a model that rejects it.
        images = _FakeImages(_image_result(b"x"))

        self._editor(images, model="openai/gpt-image-2").edit_image(
            source_path=self.source, prompt="p", output_path=self.output
        )

        self.assertNotIn("input_fidelity", images.captured_kwargs)

    def test_lossy_formats_carry_a_compression_level(self) -> None:
        for output_format in ("jpeg", "webp"):
            with self.subTest(output_format=output_format):
                images = _FakeImages(_image_result(b"x"))

                self._editor(
                    images, output_format=output_format, output_compression=70
                ).edit_image(source_path=self.source, prompt="p", output_path=self.output)

                self.assertEqual(images.captured_kwargs["output_compression"], 70)

    def test_png_is_sent_without_a_compression_level(self) -> None:
        images = _FakeImages(_image_result(b"x"))

        self._editor(images, output_format="png").edit_image(
            source_path=self.source, prompt="p", output_path=self.output
        )

        self.assertNotIn("output_compression", images.captured_kwargs)

    def test_reference_images_are_sent_after_the_camera_photo(self) -> None:
        images = _FakeImages(_image_result(b"x"))
        references = [self._reference("ref-a.png"), self._reference("ref-b.png")]

        self._editor(images).edit_image(
            source_path=self.source,
            prompt="p",
            output_path=self.output,
            reference_paths=references,
        )

        self.assertEqual(len(images.captured_kwargs["image"]), 3)
        # The wording has to tell the model which of the three is the photo.
        self.assertIn("first attached image", images.captured_kwargs["prompt"])

    def test_a_reference_that_is_not_on_disk_is_dropped(self) -> None:
        images = _FakeImages(_image_result(b"x"))

        self._editor(images).edit_image(
            source_path=self.source,
            prompt="p",
            output_path=self.output,
            reference_paths=[self.dir / "missing.png"],
        )

        # A single handle, not a list: no references survived the filter.
        self.assertFalse(isinstance(images.captured_kwargs["image"], list))

    def test_reference_handles_are_closed_after_a_successful_edit(self) -> None:
        images = _FakeImages(_image_result(b"x"))

        self._editor(images).edit_image(
            source_path=self.source,
            prompt="p",
            output_path=self.output,
            reference_paths=[self._reference("ref.png")],
        )

        self.assertTrue(all(handle.closed for handle in images.handles))

    def test_reference_handles_are_closed_even_when_the_api_call_fails(self) -> None:
        # The camera retries failed jobs for hours; leaking a descriptor per
        # attempt eventually exhausts the process limit on the device.
        images = _FakeImages(error=RuntimeError("upstream refused"))
        editor = self._editor(images)

        with self.assertRaises(RuntimeError):
            editor.edit_image(
                source_path=self.source,
                prompt="p",
                output_path=self.output,
                reference_paths=[self._reference("ref.png")],
            )

        self.assertTrue(all(handle.closed for handle in images.handles))
        self.assertFalse(self.output.exists())

    def test_an_alternative_encoding_field_is_accepted(self) -> None:
        # Some OpenAI-compatible providers name the field image_base64.
        images = _FakeImages(_image_result(b"generated-bytes", attribute="image_base64"))

        self._editor(images).edit_image(
            source_path=self.source, prompt="p", output_path=self.output
        )

        self.assertEqual(self.output.read_bytes(), b"generated-bytes")

    def test_a_response_with_no_image_is_reported_clearly(self) -> None:
        images = _FakeImages(types.SimpleNamespace(data=[]))

        with self.assertRaises(OpenAIImageError):
            self._editor(images).edit_image(
                source_path=self.source, prompt="p", output_path=self.output
            )

        self.assertFalse(self.output.exists())


class ResponsesModePlannerTests(unittest.TestCase):
    """The default magic-prompt path: /v1/responses with a strict JSON schema."""

    def setUp(self) -> None:
        env = mock.patch.dict(
            os.environ,
            {"OPENAI_API_KEY": "key", "OPENAI_BASE_URL": "", "MAGIC_MODE_API": "responses"},
        )
        env.start()
        self.addCleanup(env.stop)

    def _plan(self, output_text: str, **kwargs) -> tuple[dict, dict]:
        captured: dict = {}

        def create(**request):
            captured.update(request)
            return types.SimpleNamespace(output_text=output_text)

        planner = OpenAIMagicPromptPlanner(**kwargs)
        planner._client = types.SimpleNamespace(responses=types.SimpleNamespace(create=create))
        planner._client_config = ("key", None)
        tmp = Path(tempfile.mkdtemp())
        reference = tmp / "reference.png"
        reference.write_bytes(_png_bytes())
        return planner.create_magic_prompt(reference), captured

    def test_the_planner_defaults_to_the_responses_endpoint(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(OpenAIMagicPromptPlanner().api_mode, "responses")

    def test_a_planned_prompt_is_parsed_from_the_response(self) -> None:
        result, _ = self._plan('{"title": "Neon", "prompt": "Make it neon"}')

        self.assertEqual(result["title"], "Neon")
        self.assertEqual(result["prompt"], "Make it neon")

    def test_the_photo_is_sent_inline_with_the_instruction(self) -> None:
        _, captured = self._plan('{"title": "Neon", "prompt": "Make it neon"}')

        content = captured["input"][0]["content"]
        self.assertEqual(content[0]["type"], "input_text")
        self.assertEqual(content[1]["type"], "input_image")
        self.assertTrue(content[1]["image_url"].startswith("data:image/"))

    def test_the_request_pins_a_strict_json_schema(self) -> None:
        # Without strict mode the model prefaces the JSON with prose and the
        # parse fails, which on the device shows as magic mode doing nothing.
        _, captured = self._plan('{"title": "Neon", "prompt": "Make it neon"}')

        schema_format = captured["text"]["format"]
        self.assertEqual(schema_format["type"], "json_schema")
        self.assertTrue(schema_format["strict"])
        self.assertEqual(sorted(schema_format["schema"]["required"]), ["prompt", "title"])

    def test_an_empty_response_is_reported_rather_than_saved(self) -> None:
        with self.assertRaises(OpenAIImageError):
            self._plan("")

    def test_a_long_title_is_trimmed_to_fit_the_display(self) -> None:
        result, _ = self._plan(
            '{"title": "An extremely long title that will not fit", "prompt": "p"}',
            title_max_length=10,
        )

        self.assertLessEqual(len(result["title"]), 10)



def _dict_to_namespace(value):
    if isinstance(value, dict):
        return types.SimpleNamespace(**{k: _dict_to_namespace(v) for k, v in value.items()})
    if isinstance(value, list):
        return [_dict_to_namespace(item) for item in value]
    return value


if __name__ == "__main__":
    unittest.main()
