from __future__ import annotations

import logging
import os
from pathlib import Path

from .config import MagicHistoryStore, PromptStore, SettingsStore, load_env_file
from .controller import ImageGenCamController
from .job_store import PersistentJobStore
from .openai_client import OpenAIImageEditor, OpenAIMagicPromptPlanner
from .web import WebServerThread


def main() -> None:
    project_root = Path(__file__).resolve().parents[2]
    load_env_file(project_root / ".env")
    logging.basicConfig(
        level=os.environ.get("IMAGE_GEN_LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    host = os.environ.get("IMAGE_GEN_HOST", "0.0.0.0")
    port = int(os.environ.get("IMAGE_GEN_PORT", "8000"))
    model = os.environ.get("IMAGE_GEN_MODEL", "gpt-image-2")
    quality = os.environ.get("IMAGE_GEN_QUALITY", "low")
    size = os.environ.get("IMAGE_GEN_SIZE", "1536x1024")
    output_format = os.environ.get("IMAGE_GEN_OUTPUT_FORMAT", "jpeg")
    output_compression = int(os.environ.get("IMAGE_GEN_OUTPUT_COMPRESSION", "85"))
    timeout_seconds = float(os.environ.get("IMAGE_GEN_TIMEOUT_SECONDS", "90"))
    input_width = int(os.environ.get("IMAGE_GEN_INPUT_WIDTH", "1024"))
    input_height = int(os.environ.get("IMAGE_GEN_INPUT_HEIGHT", "768"))
    preview_width = int(os.environ.get("CAMERA_PREVIEW_WIDTH", "480"))
    preview_height = int(os.environ.get("CAMERA_PREVIEW_HEIGHT", "360"))
    frame_rate = int(os.environ.get("CAMERA_FRAME_RATE", "10"))

    prompt_store = PromptStore(project_root / "data" / "prompts.json")
    magic_history_store = MagicHistoryStore(project_root / "data" / "magic_history.json")
    settings_store = SettingsStore(project_root / "data" / "settings.json")
    generation_job_store = PersistentJobStore(project_root / "data" / "queue" / "generation")
    controller = ImageGenCamController(
        project_root=project_root,
        prompt_store=prompt_store,
        magic_history_store=magic_history_store,
        settings_store=settings_store,
        generation_job_store=generation_job_store,
        image_editor=OpenAIImageEditor(
            model=model,
            quality=quality,
            size=size,
            output_format=output_format,
            output_compression=output_compression,
            timeout_seconds=timeout_seconds,
        ),
        magic_prompt_planner=OpenAIMagicPromptPlanner(
            model=os.environ.get("MAGIC_MODE_MODEL", "gpt-4.1-mini"),
            timeout_seconds=float(os.environ.get("MAGIC_MODE_TIMEOUT_SECONDS", "30")),
        ),
        preview_size=(preview_width, preview_height),
        frame_rate=frame_rate,
        generation_input_size=(input_width, input_height),
    )
    web_server = WebServerThread(controller, host, port)
    web_server.start()

    try:
        controller.run()
    finally:
        web_server.stop()


if __name__ == "__main__":
    main()
