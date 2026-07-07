from __future__ import annotations

import unittest

from imagegencam.openai_client import OpenAIImageEditor


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
