#!/usr/bin/env python3
"""End-to-end API test: verify the OpenAI gateway works before the hardware arrives.

Generates a small test photo, then exercises both API calls the camera makes:
  1. /v1/images/edits  (photo transformation — the core feature)
  2. /v1/responses     (Magic mode prompt planning; skipped if MAGIC_MODE_ENABLED=0)

Run from the software/ directory:
    .venv/bin/python3 scripts/gateway_test.py

Needs OPENAI_API_KEY (and OPENAI_BASE_URL + prefixed model names if using a
gateway such as the Vercel AI Gateway) set in software/.env.
"""

from __future__ import annotations

import os
import sys
import traceback
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from imagegencam.config import load_env_file  # noqa: E402

load_env_file(PROJECT_ROOT / ".env")

from imagegencam.openai_client import (  # noqa: E402
    OpenAIImageEditor,
    OpenAIMagicPromptPlanner,
)


def make_test_photo(path: Path) -> Path:
    from PIL import Image, ImageDraw

    image = Image.new("RGB", (1024, 768), (70, 130, 180))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 500, 1024, 768), fill=(60, 160, 60))  # ground
    draw.ellipse((820, 60, 950, 190), fill=(255, 220, 80))  # sun
    draw.rectangle((380, 300, 640, 500), fill=(180, 90, 60))  # house
    draw.polygon([(350, 300), (510, 180), (670, 300)], fill=(120, 50, 30))  # roof
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "JPEG", quality=90)
    return path


def main() -> int:
    if not os.environ.get("OPENAI_API_KEY", "").strip():
        print("FAIL: OPENAI_API_KEY is not set. Edit software/.env first.")
        return 1
    base_url = os.environ.get("OPENAI_BASE_URL", "").strip()
    print(f"Endpoint: {base_url or 'https://api.openai.com/v1 (default)'}")

    out_dir = PROJECT_ROOT / "data" / "gateway-test"
    source = make_test_photo(out_dir / "test-source.jpg")
    print(f"Test photo written: {source}")

    failures = 0

    model = os.environ.get("IMAGE_GEN_MODEL", "gpt-image-2")
    api_mode = os.environ.get("IMAGE_GEN_API", "edits").strip().lower()
    endpoint_name = {
        "chat": "chat.completions",
        "generations": "images/generations",
    }.get(api_mode, "images.edit")
    print(f"\n[1/2] {endpoint_name} (IMAGE_GEN_API={api_mode}) via model {model} (may take ~30-90s)...")
    try:
        editor = OpenAIImageEditor(
            model=model,
            quality=os.environ.get("IMAGE_GEN_QUALITY", "low"),
            size="1024x1024",
            output_format="jpeg",
            timeout_seconds=float(os.environ.get("IMAGE_GEN_TIMEOUT_SECONDS", "90")),
        )
        result = editor.edit_image(
            source_path=source,
            prompt="Make it look like a cozy watercolor painting at sunset.",
            output_path=out_dir / "test-result.jpg",
        )
        print(f"PASS: image generated -> {result} ({result.stat().st_size} bytes)")
    except Exception:
        failures += 1
        print(f"FAIL: {endpoint_name} did not work. Full error:")
        traceback.print_exc()

    if os.environ.get("MAGIC_MODE_ENABLED", "1").strip().lower() in {"0", "false", "no"}:
        print("\n[2/2] Magic mode disabled (MAGIC_MODE_ENABLED=0) — skipping /v1/responses test.")
    else:
        magic_model = os.environ.get("MAGIC_MODE_MODEL", "gpt-4.1-mini")
        magic_mode = os.environ.get("MAGIC_MODE_API", "responses").strip().lower()
        magic_endpoint = "chat.completions" if magic_mode == "chat" else "responses.create"
        print(f"\n[2/2] {magic_endpoint} (MAGIC_MODE_API={magic_mode}) via model {magic_model}...")
        try:
            planner = OpenAIMagicPromptPlanner(model=magic_model)
            plan = planner.create_magic_prompt(source)
            print(f"PASS: magic prompt -> {plan}")
        except Exception:
            failures += 1
            print(f"FAIL: {magic_endpoint} did not work. Full error:")
            traceback.print_exc()

    print()
    if failures:
        print(f"{failures} test(s) failed. If you use a gateway, check that it proxies")
        print("the failing endpoint and that model names carry the provider prefix.")
        print("Gateways without /v1/images/edits (e.g. Vercel AI Gateway) need:")
        print("  IMAGE_GEN_API=chat")
        print("  IMAGE_GEN_MODEL=google/gemini-2.5-flash-image  (or another image-capable model)")
        print("Chinese providers (e.g. SiliconFlow) need the china recipe — see .env.example:")
        print("  IMAGE_GEN_API=generations + MAGIC_MODE_API=chat")
        return 1
    print("All good — the API path is fully working. The camera will work once wired up.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
