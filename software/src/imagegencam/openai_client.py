from __future__ import annotations

import base64
import json
import logging
import mimetypes
import os
import urllib.error
import urllib.request
from pathlib import Path


class OpenAIImageError(RuntimeError):
    pass


logger = logging.getLogger(__name__)


class _OpenAIClientBase:
    def __init__(self, timeout_seconds: float = 90.0, max_retries: int = 1) -> None:
        self.timeout_seconds = timeout_seconds
        self.max_retries = max(0, int(max_retries))
        self._client = None
        self._client_config: tuple[str, str | None] | None = None

    def _require_client(self):
        api_key = os.environ.get("OPENAI_API_KEY", "").strip()
        if not api_key:
            raise OpenAIImageError("OPENAI_API_KEY is not set.")
        base_url = os.environ.get("OPENAI_BASE_URL", "").strip() or None

        config = (api_key, base_url)
        if self._client is not None and self._client_config == config:
            return self._client

        try:
            from openai import OpenAI
        except ImportError as exc:
            raise OpenAIImageError(
                "The openai package is not installed. Run `pip install -r requirements.txt`."
            ) from exc

        self._client = OpenAI(
            api_key=api_key,
            base_url=base_url,
            timeout=self.timeout_seconds,
            max_retries=self.max_retries,
        )
        self._client_config = config
        return self._client

    @staticmethod
    def _build_image_data_url(source_path: Path) -> str:
        content_type = mimetypes.guess_type(source_path.name)[0] or "image/jpeg"
        encoded = base64.b64encode(source_path.read_bytes()).decode("ascii")
        return f"data:{content_type};base64,{encoded}"


class OpenAIImageEditor(_OpenAIClientBase):
    def __init__(
        self,
        model: str = "chatgpt-image-latest",
        quality: str = "low",
        size: str = "1536x1024",
        output_format: str = "jpeg",
        output_compression: int = 85,
        timeout_seconds: float = 90.0,
        max_retries: int = 1,
        api_mode: str | None = None,
    ) -> None:
        super().__init__(timeout_seconds=timeout_seconds, max_retries=max_retries)
        self.model = model
        self.quality = quality
        self.size = size
        self.output_format = output_format if output_format in {"png", "jpeg", "webp"} else "jpeg"
        self.output_compression = max(0, min(100, int(output_compression)))
        # "edits" = /v1/images/edits (api.openai.com); "chat" = image editing via
        # /v1/chat/completions with a multimodal image model — the path gateways like
        # the Vercel AI Gateway support (they do not expose /v1/images/edits);
        # "generations" = /v1/images/generations with an extra `image` field — the
        # image-edit path on Chinese OpenAI-compatible providers (SiliconFlow
        # Qwen-Image-Edit, Volcano Ark SeedEdit), which return an https URL.
        mode = (api_mode or os.environ.get("IMAGE_GEN_API", "edits")).strip().lower()
        self.api_mode = mode if mode in {"edits", "chat", "generations"} else "edits"

    @property
    def output_extension(self) -> str:
        if self.output_format == "jpeg":
            return ".jpg"
        if self.output_format == "webp":
            return ".webp"
        return ".png"

    @staticmethod
    def _extract_image_bytes(result) -> bytes:
        for item in getattr(result, "data", []):
            for attribute in ("b64_json", "image_base64"):
                encoded = getattr(item, attribute, None)
                if encoded:
                    return base64.b64decode(encoded)
        raise OpenAIImageError("OpenAI returned no image data.")

    def edit_image(
        self,
        source_path: Path,
        prompt: str,
        output_path: Path,
        reference_paths: list[Path] | None = None,
        size: str | None = None,
    ) -> Path:
        client = self._require_client()
        reference_paths = [path for path in (reference_paths or []) if path.is_file()]
        requested_size = size or self.size
        if reference_paths:
            full_prompt = (
                "Use the first attached image as the main camera photo to transform. "
                "Use any additional attached images only as reference images for inspiration. "
                "Keep the first image recognizable, but apply the user's requested motif or concept "
                "using the reference image details where helpful. "
                f"User prompt: {prompt}"
            )
        else:
            full_prompt = (
                "Use the attached camera photo as the source image. "
                "Transform it according to the user's request while keeping the result coherent. "
                f"User prompt: {prompt}"
            )
        logger.info(
            "Starting image edit api=%s model=%s size=%s quality=%s format=%s source=%s refs=%s",
            self.api_mode,
            self.model,
            requested_size,
            self.quality,
            self.output_format,
            source_path,
            len(reference_paths),
        )

        if self.api_mode == "chat":
            return self._edit_image_via_chat(
                client, source_path, reference_paths, full_prompt, output_path
            )
        if self.api_mode == "generations":
            return self._edit_image_via_generations(
                source_path, reference_paths, full_prompt, output_path
            )

        with source_path.open("rb") as source_file:
            reference_files = [path.open("rb") for path in reference_paths]
            request_options = {
                "model": self.model,
                "image": [source_file, *reference_files] if reference_files else source_file,
                "prompt": full_prompt,
                "quality": self.quality,
                "size": requested_size,
                "output_format": self.output_format,
                "timeout": self.timeout_seconds,
            }
            # Gateways (e.g. Vercel AI Gateway) prefix model names like "openai/gpt-image-2".
            if self.model.split("/")[-1] not in {"gpt-image-2", "gpt-image-2-2026-04-21"}:
                request_options["input_fidelity"] = "low"
            if self.output_format in {"jpeg", "webp"}:
                request_options["output_compression"] = self.output_compression

            try:
                result = client.images.edit(
                    **request_options,
                )
            finally:
                for reference_file in reference_files:
                    reference_file.close()

        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(self._extract_image_bytes(result))
        logger.info("Saved generated image to %s", output_path)
        return output_path

    def _edit_image_via_chat(
        self,
        client,
        source_path: Path,
        reference_paths: list[Path],
        full_prompt: str,
        output_path: Path,
    ) -> Path:
        # quality/size are chosen by the model on this path; only the prompt and the
        # attached images travel with the request.
        content: list[dict] = [{"type": "text", "text": full_prompt}]
        for path in [source_path, *reference_paths]:
            content.append(
                {"type": "image_url", "image_url": {"url": self._build_image_data_url(path)}}
            )
        response = client.chat.completions.create(
            model=self.model,
            messages=[{"role": "user", "content": content}],
            timeout=self.timeout_seconds,
        )
        image_bytes = self._extract_chat_image_bytes(response)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(self._reencode(image_bytes))
        logger.info("Saved generated image to %s", output_path)
        return output_path

    def _edit_image_via_generations(
        self,
        source_path: Path,
        reference_paths: list[Path],
        full_prompt: str,
        output_path: Path,
    ) -> Path:
        # POSTed with urllib because the OpenAI SDK's images API has no `image`
        # field on the generations call. quality/size are decided by the model.
        api_key = os.environ.get("OPENAI_API_KEY", "").strip()
        if not api_key:
            raise OpenAIImageError("OPENAI_API_KEY is not set.")
        base_url = (
            os.environ.get("OPENAI_BASE_URL", "").strip() or "https://api.openai.com/v1"
        ).rstrip("/")
        if reference_paths:
            logger.warning(
                "IMAGE_GEN_API=generations sends only the source image; "
                "%d reference image(s) skipped",
                len(reference_paths),
            )
        payload = {
            "model": self.model,
            "prompt": full_prompt,
            "image": self._build_image_data_url(source_path),
        }
        request = urllib.request.Request(
            f"{base_url}/images/generations",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                result = json.loads(response.read())
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:500]
            raise OpenAIImageError(
                f"images/generations failed: HTTP {exc.code}: {detail}"
            ) from exc
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            raise OpenAIImageError(f"images/generations failed: {exc}") from exc

        image_bytes = self._extract_generations_image_bytes(result)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(self._reencode(image_bytes))
        logger.info("Saved generated image to %s", output_path)
        return output_path

    def _extract_generations_image_bytes(self, result: dict) -> bytes:
        # SiliconFlow returns {"images": [{"url": ...}]}; OpenAI-style providers
        # return {"data": [{"b64_json": ...}]} or {"data": [{"url": ...}]}.
        items = result.get("images") or result.get("data") or []
        for item in items:
            if not isinstance(item, dict):
                continue
            encoded = item.get("b64_json")
            if encoded:
                return base64.b64decode(encoded)
            url = item.get("url")
            decoded = self._decode_data_url(url)
            if decoded:
                return decoded
            if isinstance(url, str) and url.startswith(("http://", "https://")):
                try:
                    with urllib.request.urlopen(url, timeout=self.timeout_seconds) as download:
                        return download.read()
                except (urllib.error.URLError, TimeoutError) as exc:
                    raise OpenAIImageError(
                        f"Could not download the generated image from {url}: {exc}"
                    ) from exc
        raise OpenAIImageError(
            "images/generations returned no image. Make sure IMAGE_GEN_MODEL is an "
            "image-edit-capable model (e.g. Qwen/Qwen-Image-Edit-2509 on SiliconFlow) "
            "when IMAGE_GEN_API=generations."
        )

    @staticmethod
    def _decode_data_url(url: str) -> bytes | None:
        if not isinstance(url, str) or "base64," not in url:
            return None
        try:
            return base64.b64decode(url.split("base64,", 1)[1])
        except ValueError:
            return None

    @classmethod
    def _extract_chat_image_bytes(cls, response) -> bytes:
        def get(item, key):
            if isinstance(item, dict):
                return item.get(key)
            return getattr(item, key, None)

        for choice in getattr(response, "choices", None) or []:
            message = get(choice, "message")
            if message is None:
                continue
            # Gateways return generated images in message.images as image_url parts;
            # some providers put them in message.content parts instead.
            parts = list(get(message, "images") or [])
            content = get(message, "content")
            if isinstance(content, list):
                parts.extend(content)
            for part in parts:
                image_url = get(part, "image_url")
                url = get(image_url, "url") if image_url is not None else None
                decoded = cls._decode_data_url(url)
                if decoded:
                    return decoded
        raise OpenAIImageError(
            "The model returned no image. Make sure IMAGE_GEN_MODEL is an "
            "image-generation-capable model (e.g. google/gemini-2.5-flash-image on the "
            "Vercel AI Gateway) when IMAGE_GEN_API=chat."
        )

    def _reencode(self, image_bytes: bytes) -> bytes:
        """Convert whatever the model returned (usually PNG) to the configured format."""
        import io

        try:
            from PIL import Image
        except ImportError:
            return image_bytes
        try:
            with Image.open(io.BytesIO(image_bytes)) as image:
                buffer = io.BytesIO()
                if self.output_format == "png":
                    image.save(buffer, format="PNG")
                else:
                    image.convert("RGB").save(
                        buffer,
                        format="WEBP" if self.output_format == "webp" else "JPEG",
                        quality=self.output_compression,
                    )
                return buffer.getvalue()
        except Exception:
            logger.warning("Could not re-encode generated image; saving as returned.")
            return image_bytes


class OpenAIMagicPromptPlanner(_OpenAIClientBase):
    def __init__(
        self,
        model: str = "gpt-4.1-mini",
        timeout_seconds: float = 30.0,
        max_retries: int = 1,
        title_max_length: int = 22,
        api_mode: str | None = None,
    ) -> None:
        super().__init__(timeout_seconds=timeout_seconds, max_retries=max_retries)
        self.model = model
        self.title_max_length = max(8, int(title_max_length))
        # "responses" = /v1/responses with strict JSON schema (api.openai.com and
        # gateways that proxy it); "chat" = plain /v1/chat/completions for
        # providers without a responses endpoint (e.g. Chinese OpenAI-compatible
        # APIs like SiliconFlow).
        mode = (api_mode or os.environ.get("MAGIC_MODE_API", "responses")).strip().lower()
        self.api_mode = mode if mode in {"responses", "chat"} else "responses"

    def _instruction_text(self) -> str:
        return (
            "Look at this camera photo and pick one funny, visually distinct motif, prop, "
            "detail, gesture, texture, or object that could inspire edits to future photos. "
            "Return JSON with: "
            "`title` = a punchy 1-3 word name, max "
            f"{self.title_max_length} characters; "
            "`prompt` = one concise image-edit instruction that tells an image model how to "
            "apply that motif to another photo while keeping the new photo recognizable and coherent. "
            "Do not mention JSON. Do not mention camera UI. Do not describe the whole image."
        )

    def create_magic_prompt(self, reference_path: Path) -> dict[str, str]:
        client = self._require_client()
        data_url = self._build_image_data_url(reference_path)
        logger.info(
            "Starting magic prompt planning api=%s model=%s source=%s",
            self.api_mode,
            self.model,
            reference_path,
        )
        if self.api_mode == "chat":
            raw_output = self._plan_via_chat(client, data_url)
        else:
            raw_output = self._plan_via_responses(client, data_url)
        if not raw_output:
            raise OpenAIImageError("The model returned no magic prompt output.")
        return self._parse_magic_payload(raw_output)

    def _plan_via_responses(self, client, data_url: str) -> str:
        response = client.responses.create(
            model=self.model,
            input=[
                {
                    "role": "user",
                    "content": [
                        {"type": "input_text", "text": self._instruction_text()},
                        {
                            "type": "input_image",
                            "image_url": data_url,
                            "detail": "low",
                        },
                    ],
                }
            ],
            text={
                "format": {
                    "type": "json_schema",
                    "name": "magic_prompt",
                    "strict": True,
                    "schema": {
                        "type": "object",
                        "additionalProperties": False,
                        "properties": {
                            "title": {"type": "string"},
                            "prompt": {"type": "string"},
                        },
                        "required": ["title", "prompt"],
                    },
                }
            },
        )
        return getattr(response, "output_text", "").strip()

    def _plan_via_chat(self, client, data_url: str) -> str:
        # No structured-output guarantee here, so ask for bare JSON and parse
        # leniently (fences stripped in _parse_magic_payload).
        response = client.chat.completions.create(
            model=self.model,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": self._instruction_text()
                            + ' Reply with ONLY the JSON object {"title": ..., "prompt": ...} '
                            "and nothing else — no code fences, no commentary.",
                        },
                        {"type": "image_url", "image_url": {"url": data_url}},
                    ],
                }
            ],
            timeout=self.timeout_seconds,
        )
        choices = getattr(response, "choices", None) or []
        if not choices:
            return ""
        content = getattr(getattr(choices[0], "message", None), "content", None)
        return content.strip() if isinstance(content, str) else ""

    def _parse_magic_payload(self, raw_output: str) -> dict[str, str]:
        candidate = raw_output
        # Chat-mode models sometimes wrap the JSON in ```json fences or prose.
        start, end = candidate.find("{"), candidate.rfind("}")
        if start != -1 and end > start:
            candidate = candidate[start : end + 1]
        try:
            payload = json.loads(candidate)
        except json.JSONDecodeError as exc:
            raise OpenAIImageError(
                f"The model returned invalid magic prompt JSON: {raw_output}"
            ) from exc

        title = str(payload.get("title") or "").strip()
        prompt = str(payload.get("prompt") or "").strip()
        if not title or not prompt:
            raise OpenAIImageError("The model returned an incomplete magic prompt.")
        return {
            "title": title[: self.title_max_length].strip() or "Magic",
            "prompt": prompt,
        }
