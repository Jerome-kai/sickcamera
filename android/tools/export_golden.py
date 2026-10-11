#!/usr/bin/env python3
"""Record what the Python camera actually does, for the Kotlin port to match.

Runs the camera's own functions -- not a re-statement of them -- on tricky
inputs and writes the results to core/src/test/resources/golden/python.json.
The Kotlin tests assert the port produces identical output, so a drift in a
prompt string or a normalisation rule fails the build instead of quietly
changing what the AI is told.

    python3 android/tools/export_golden.py      (from the repo root)
"""

from __future__ import annotations

import ast
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "software" / "src"))

from imagegencam import config  # noqa: E402
from imagegencam.openai_client import OpenAIImageEditor, OpenAIMagicPromptPlanner  # noqa: E402


def wrapper_prompt(prompt: str, with_references: bool) -> str:
    """Capture the full prompt edit_image sends, via a fake chat client."""
    captured = {}

    class FakeCompletions:
        def create(self, **kwargs):
            captured["text"] = kwargs["messages"][0]["content"][0]["text"]
            raise RuntimeError("stop")

    class FakeClient:
        chat = type("Chat", (), {"completions": FakeCompletions()})()

    editor = OpenAIImageEditor(model="m", api_mode="chat")
    editor._require_client = lambda: FakeClient()
    tmp = Path("/tmp/golden-src.jpg")
    tmp.write_bytes(b"\xff\xd8\xff")
    refs = [tmp] if with_references else []
    try:
        editor.edit_image(tmp, prompt, Path("/tmp/golden-out.jpg"), reference_paths=refs)
    except RuntimeError:
        pass
    return captured["text"]


def story_prompt() -> str:
    """The literal assigned to story_prompt in recreate_vertical_from_generated."""
    source = (REPO / "software" / "src" / "imagegencam" / "controller.py").read_text()
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.FunctionDef) and node.name == "recreate_vertical_from_generated":
            for inner in ast.walk(node):
                if isinstance(inner, ast.Assign) and getattr(inner.targets[0], "id", "") == "story_prompt":
                    return ast.literal_eval(inner.value)
    raise SystemExit("story_prompt not found")


PROMPT_INPUTS = [
    [{"id": "Prompt One!", "title": "  A title that is far too long for the screen ", "body": "  body  "}],
    [{"id": "dup", "title": "a", "body": "x"}, {"id": "dup", "title": "b", "body": "y"}, {"id": "DUP", "title": "c", "body": "z"}],
    [{"id": "", "title": "", "body": ""}, {"title": "no id"}, "not a dict", 7],
    {"legacy-a": "just a body", "legacy-b": {"title": "T", "body": "B"}},
    [{"id": None, "title": None, "body": None}],
    [{"id": "😀 emoji 😀", "title": "😀" * 30, "body": "b"}],
    [],
]

USERNAMES = ["  Jerome Kai  ", "a.b_c-d", "...trim___", "Ünïcödé name", "x" * 50, "", "   ", "MiXeD   Case\tTabs"]

MODEL_INPUTS = [
    [{"id": "a", "label": "  Gemini   Flash  ", "model": "google/gemini-2.5-flash-image", "api": "chat"}],
    [{"model": "gpt-image-2", "api": "TELEPATHY"}, {"model": "   "}, "junk", {"id": "x", "model": "m2"}, {"id": "x", "model": "m3"}],
    [{"label": "", "model": "provider/very-long-model-name-indeed"}],
]


def main() -> None:
    planner = OpenAIMagicPromptPlanner(title_max_length=config.PROMPT_TITLE_MAX_LENGTH)
    golden = {
        "wrapper_prompt_no_refs": wrapper_prompt("Turn everyone into cheese.", False),
        "wrapper_prompt_with_refs": wrapper_prompt("Turn everyone into cheese.", True),
        "magic_instruction": planner._instruction_text(),
        "story_prompt": story_prompt(),
        "default_prompts": [
            {"id": e["id"], "title": e["title"], "body": e["body"]} for e in config.DEFAULT_PROMPT_ENTRIES
        ],
        "prompt_cases": [
            {
                "input": raw,
                "output": [
                    {"id": pid, "title": entry["title"], "body": entry["body"]}
                    for pid, entry in config.normalize_prompt_entries(raw).items()
                ],
            }
            for raw in PROMPT_INPUTS
        ],
        "username_cases": [
            {"input": raw, "output": config._normalize_camera_username(raw)} for raw in USERNAMES
        ],
        "model_cases": [
            {"input": raw, "output": config.normalize_model_presets(raw)} for raw in MODEL_INPUTS
        ],
        "magic_parse_cases": [
            {"input": raw, "output": planner._parse_magic_payload(raw)}
            for raw in [
                '{"title": "Cheese Hat", "prompt": "Put a cheese hat on everyone."}',
                '```json\n{"title": "Duck", "prompt": "Add ducks."}\n```',
                'Sure! {"title": "' + "Very long title " * 4 + '", "prompt": "p"} hope that helps',
            ]
        ],
    }
    out = REPO / "android" / "core" / "src" / "test" / "resources" / "golden" / "python.json"
    out.write_text(json.dumps(golden, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {out.relative_to(REPO)}: {len(golden)} sections")


if __name__ == "__main__":
    main()
