from __future__ import annotations

import json
import os
import re
from pathlib import Path
from threading import Lock


PROMPT_TITLE_MAX_LENGTH = 22
CAMERA_USERNAME_MAX_LENGTH = 32
DEFAULT_NEW_PROMPT_TITLE = "New Prompt"
DEFAULT_NEW_PROMPT_BODY = "Describe the edit you want."
DEFAULT_PROMPT_ENTRIES = [
    {
        "id": "prompt-1",
        "title": "Pathetic Scribble",
        "body": (
            "Redraw the attached image in the most clumsy, scribbly, and utterly pathetic way "
            "possible. Use a white background, and make it look like it was drawn in an old "
            "computer painting program with a mouse. It should be vaguely similar but also not "
            "really, kind of matching but also off in a confusing, awkward way, with that "
            "low-quality pixel-by-pixel feel that really emphasizes how ridiculously bad it is. "
            "Actually, you know what, whatever, just draw it however you want."
        ),
    },
    {
        "id": "prompt-2",
        "title": "Turn to Cheese",
        "body": "Change nothing about this photo except everyone is turned into cheese.",
    },
    {
        "id": "prompt-3",
        "title": "Goblin Mode",
        "body": (
            "Make the subject into a cute fantasy character in a scrappy handmade indie webcomic style."
            "\n\nUse exaggerated fantasy anatomy with huge pointed ears, oversized expressive yellow "
            "eyes, tiny fangs, simplified facial features, and chibi-adjacent proportions."
            "\n\nDraw with thick uneven black ink outlines, sketchy hand-drawn linework, and "
            "intentionally imperfect shapes."
            "\n\nUse flat cel shading only \u2014 NO painterly rendering, realistic lighting, or "
            "detailed textures."
            "\n\nThe palette should be muted earthy greens, dusty reds, faded browns, warm creams, "
            "and soft olive tones with minimal gradients."
            "\n\nThe subject wears ragged fantasy adventurer clothes with wraps around the arms and "
            "legs and holds a crooked wooden staff with a glowing gem attached. A small bag of "
            "golden tokens sits near their feet. There should never be any text on the final "
            "image. Despite the web comic reference, the image should be focused on the main "
            "subject(s) and the illustrated background without text."
            "\n\nThe expression should feel slightly startled, mischievous, and awkwardly charming."
            "\n\nComposition should feel like an indie animation character sheet or fantasy webcomic "
            "panel with lots of negative space and a sparse lightly sketched forest background "
            "bathed in warm golden light."
            "\n\nAdd tiny doodle-like motion lines, dust puffs, and whimsical comic accents."
            "\n\nPreserve a playful chaotic energy and cozy spooky fantasy mood."
            "\n\nAesthetic references: indie fantasy webcomic, internet sketchbook character art, "
            "handmade cartoon fantasy, cozy spooky zine art, and indie RPG-inspired character sheets."
            "\n\nAvoid: painterly rendering, realism, cinematic lighting, detailed skin texture, "
            "glossy shading, 3D rendering, hyper-detail, polished anime style, realistic "
            "proportions, dramatic depth-of-field, complex backgrounds."
            "\n\nImportant: there should never be any mention of stealing, lying, or any other "
            "criminal activity."
        ),
    },
    {
        "id": "prompt-4",
        "title": "Anime Portrait",
        "body": (
            "Create a trending anime art style image from the uploaded subject. Use confident "
            "line-work with slight variation and minimal cel shading using flat shadow shapes. "
            "Use bright, saturated colors and clean graphic lighting. The style that is defined "
            "by its exaggerated, cartoonish character proportions featuring highly expressive, "
            "simplistic facial features that allow for immense emotional range, with highly "
            "varied stretched anatomy. Transform the environment into a slightly warped space "
            "with playful perspective distortion and simplified objects. Composition and tone is "
            "energetic, lively, and comedic in a fully stylized, non-realistic world."
        ),
    },
    {
        "id": "prompt-5",
        "title": "Graduation Day",
        "body": (
            "Transform this photo into a joyful graduation ceremony scene while keeping every "
            "person clearly recognizable — same faces, same poses, same framing."
            "\n\nDress the subject(s) in a classic graduation cap (mortarboard with a swinging "
            "tassel) and flowing gown; if they hold anything, replace or accompany it with a "
            "rolled diploma tied with a ribbon."
            "\n\nTransform the background into a celebratory campus commencement: a stage or "
            "lawn with banners, other gowned graduates softly blurred behind, golden-hour "
            "sunlight, and confetti plus a few mortarboards frozen mid-air as if just tossed."
            "\n\nColor palette: warm golds, deep academic blues and maroons, crisp white "
            "accents. Slightly cinematic lighting, celebratory and proud mood, like the best "
            "photo from the best day of school."
            "\n\nAnimals or objects in the photo also graduate: give them tiny caps and "
            "diplomas. Do not add any readable text, logos, or school names anywhere."
        ),
    },
]
DEFAULT_PROMPTS = {
    entry["id"]: entry["body"] for entry in DEFAULT_PROMPT_ENTRIES
}

DEFAULT_SETTINGS = {
    "app_background_theme": "aqua",
    "camera_username": "",
    "preview_warmth": 0,
    "preview_red_gain": 100,
    "preview_green_gain": 100,
    "preview_blue_gain": 100,
}

VALID_APP_BACKGROUND_THEMES = {"aqua", "silver", "lavender", "mint", "sunset"}


def _clamp_int(value: object, default: int, minimum: int, maximum: int) -> int:
    try:
        numeric = int(str(value).strip())
    except (TypeError, ValueError):
        return default
    return max(minimum, min(maximum, numeric))


def _default_prompt_map() -> dict[str, dict[str, str]]:
    return {
        entry["id"]: {"title": entry["title"], "body": entry["body"]}
        for entry in DEFAULT_PROMPT_ENTRIES
    }


def _normalize_magic_history_id(value: object, used_ids: set[str], fallback_index: int) -> str:
    raw = str(value or "").strip().lower()
    raw = re.sub(r"[^a-z0-9]+", "-", raw).strip("-")
    if not raw:
        raw = f"magic-{fallback_index}"

    candidate = raw
    suffix = 2
    while candidate in used_ids:
        candidate = f"{raw}-{suffix}"
        suffix += 1
    used_ids.add(candidate)
    return candidate


def _normalize_prompt_title(value: object) -> str:
    cleaned = str(value or "").strip()
    if not cleaned:
        cleaned = DEFAULT_NEW_PROMPT_TITLE
    return cleaned[:PROMPT_TITLE_MAX_LENGTH].strip() or DEFAULT_NEW_PROMPT_TITLE


def _normalize_prompt_body(value: object) -> str:
    cleaned = str(value or "").strip()
    return cleaned or DEFAULT_NEW_PROMPT_BODY


def _normalize_camera_username(value: object) -> str:
    cleaned = str(value or "").strip().lower()
    cleaned = re.sub(r"\s+", "-", cleaned)
    cleaned = re.sub(r"[^a-z0-9._-]+", "", cleaned)
    return cleaned[:CAMERA_USERNAME_MAX_LENGTH].strip("._-")


def _normalize_prompt_id(value: object, used_ids: set[str], fallback_index: int) -> str:
    raw = str(value or "").strip().lower()
    raw = re.sub(r"[^a-z0-9]+", "-", raw).strip("-")
    if not raw:
        raw = f"prompt-{fallback_index}"

    candidate = raw
    suffix = 2
    while candidate in used_ids:
        candidate = f"{raw}-{suffix}"
        suffix += 1
    used_ids.add(candidate)
    return candidate


def normalize_prompt_entries(
    prompts: object,
    *,
    ensure_defaults_when_empty: bool = True,
) -> dict[str, dict[str, str]]:
    if isinstance(prompts, dict):
        iterable = []
        for key, value in prompts.items():
            if isinstance(value, dict):
                iterable.append(
                    {
                        "id": value.get("id", key),
                        "title": value.get("title", DEFAULT_NEW_PROMPT_TITLE),
                        "body": value.get("body", DEFAULT_NEW_PROMPT_BODY),
                    }
                )
            else:
                iterable.append(
                    {
                        "id": key,
                        "title": DEFAULT_NEW_PROMPT_TITLE,
                        "body": value,
                    }
                )
    elif isinstance(prompts, list):
        iterable = [entry for entry in prompts if isinstance(entry, dict)]
    else:
        iterable = []

    cleaned: dict[str, dict[str, str]] = {}
    used_ids: set[str] = set()
    for index, entry in enumerate(iterable, start=1):
        prompt_id = _normalize_prompt_id(entry.get("id"), used_ids, index)
        cleaned[prompt_id] = {
            "title": _normalize_prompt_title(entry.get("title")),
            "body": _normalize_prompt_body(entry.get("body")),
        }

    if not cleaned and ensure_defaults_when_empty:
        return _default_prompt_map()
    return cleaned


def normalize_magic_history_entries(entries: object) -> list[dict[str, str | None]]:
    if not isinstance(entries, list):
        return []

    cleaned: list[dict[str, str | None]] = []
    used_ids: set[str] = set()
    for index, entry in enumerate(entries, start=1):
        if not isinstance(entry, dict):
            continue
        cleaned.append(
            {
                "id": _normalize_magic_history_id(entry.get("id"), used_ids, index),
                "created_at": str(entry.get("created_at") or "").strip(),
                "title": _normalize_prompt_title(entry.get("title")),
                "body": _normalize_prompt_body(entry.get("body")),
                "reference_capture_path": str(entry.get("reference_capture_path") or "").strip() or None,
                "promoted_prompt_id": str(entry.get("promoted_prompt_id") or "").strip() or None,
            }
        )
    return cleaned


def normalize_settings(data: dict[str, object]) -> dict[str, int | str]:
    settings: dict[str, int | str] = DEFAULT_SETTINGS.copy()

    app_background_theme = str(
        data.get("app_background_theme", settings["app_background_theme"])
    ).strip()
    if app_background_theme in VALID_APP_BACKGROUND_THEMES:
        settings["app_background_theme"] = app_background_theme

    settings["camera_username"] = _normalize_camera_username(
        data.get("camera_username", settings["camera_username"])
    )

    settings["preview_warmth"] = _clamp_int(
        data.get("preview_warmth"),
        int(DEFAULT_SETTINGS["preview_warmth"]),
        -40,
        40,
    )
    settings["preview_red_gain"] = _clamp_int(
        data.get("preview_red_gain"),
        int(DEFAULT_SETTINGS["preview_red_gain"]),
        60,
        160,
    )
    settings["preview_green_gain"] = _clamp_int(
        data.get("preview_green_gain"),
        int(DEFAULT_SETTINGS["preview_green_gain"]),
        60,
        160,
    )
    settings["preview_blue_gain"] = _clamp_int(
        data.get("preview_blue_gain"),
        int(DEFAULT_SETTINGS["preview_blue_gain"]),
        60,
        160,
    )
    return settings


def load_env_file(path: Path) -> None:
    if not path.exists():
        return

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        os.environ.setdefault(key, value)


class PromptStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            self.save_entries(DEFAULT_PROMPT_ENTRIES)

    def load_entries(self) -> dict[str, dict[str, str]]:
        with self._lock:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        return normalize_prompt_entries(data)

    def load(self) -> dict[str, str]:
        entries = self.load_entries()
        return {prompt_id: entry["body"] for prompt_id, entry in entries.items()}

    def save_entries(self, prompts: object) -> dict[str, dict[str, str]]:
        cleaned = normalize_prompt_entries(prompts)
        serializable = [
            {"id": prompt_id, "title": entry["title"], "body": entry["body"]}
            for prompt_id, entry in cleaned.items()
        ]
        with self._lock:
            self.path.write_text(
                json.dumps(serializable, indent=2) + "\n",
                encoding="utf-8",
            )
        return cleaned

    def save(self, prompts: dict[str, str]) -> dict[str, str]:
        cleaned_entries = self.save_entries(
            [
                {
                    "id": prompt_id,
                    "title": DEFAULT_NEW_PROMPT_TITLE,
                    "body": body,
                }
                for prompt_id, body in prompts.items()
            ]
        )
        return {prompt_id: entry["body"] for prompt_id, entry in cleaned_entries.items()}


class SettingsStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            self.save(DEFAULT_SETTINGS)

    def load(self) -> dict[str, int | str]:
        with self._lock:
            data = json.loads(self.path.read_text(encoding="utf-8"))

        return normalize_settings(data)

    def save(self, settings: dict[str, object]) -> dict[str, int | str]:
        cleaned = normalize_settings(settings)

        with self._lock:
            self.path.write_text(
                json.dumps(cleaned, indent=2) + "\n",
                encoding="utf-8",
            )
        return cleaned


class MagicHistoryStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            self.save_entries([])

    def load_entries(self) -> list[dict[str, str | None]]:
        with self._lock:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        return normalize_magic_history_entries(data)

    def save_entries(self, entries: object) -> list[dict[str, str | None]]:
        cleaned = normalize_magic_history_entries(entries)
        with self._lock:
            self.path.write_text(
                json.dumps(cleaned, indent=2) + "\n",
                encoding="utf-8",
            )
        return cleaned

    def add_entry(self, entry: dict[str, object]) -> dict[str, str | None]:
        entries = self.load_entries()
        entries.insert(0, dict(entry))
        cleaned = self.save_entries(entries)
        return cleaned[0]

    def mark_promoted(self, entry_id: str, prompt_id: str) -> list[dict[str, str | None]]:
        entries = self.load_entries()
        for entry in entries:
            if entry["id"] == entry_id:
                entry["promoted_prompt_id"] = prompt_id.strip() or None
                break
        return self.save_entries(entries)
