from __future__ import annotations

import os
import sys
import types
import unittest
from unittest import mock

from imagegencam.openai_client import OpenAIImageEditor


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


if __name__ == "__main__":
    unittest.main()
