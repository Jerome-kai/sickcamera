# China branch (国内直连版)

This branch is the mainland-China fork of the camera: same hardware, same UI,
but the AI calls go to **SiliconFlow (硅基流动)** — a domestic OpenAI-compatible
provider that works without a VPN. The Vercel AI Gateway, OpenAI, and Google
endpoints used on `main` are all blocked by the GFW; this branch replaces them
with one Chinese API key.

## What's different from `main`

Only configuration. The code on `main` already supports the two API modes this
branch relies on; `.env.example` here just defaults to them:

| Setting | `main` | `china` |
|---|---|---|
| `OPENAI_BASE_URL` | Vercel AI Gateway | `https://api.siliconflow.cn/v1` |
| `IMAGE_GEN_API` | `edits` / `chat` | `generations` (`/v1/images/generations` + `image` field) |
| `IMAGE_GEN_MODEL` | gpt-image-2 / gemini-2.5-flash-image | `Qwen/Qwen-Image-Edit-2509` |
| `MAGIC_MODE_API` | `responses` | `chat` (SiliconFlow has no `/v1/responses`) |
| `MAGIC_MODE_MODEL` | gpt-4.1-mini | `Qwen/Qwen2.5-VL-32B-Instruct` |

## Setup

1. Register at [cloud.siliconflow.cn](https://cloud.siliconflow.cn) (phone
   number needed; real-name verification may be required for some models) and
   create an API key (`sk-...`).
2. On the camera: `git fetch && git checkout china`, then copy `.env.example`
   over your settings **carefully** — easiest is to edit your existing
   `software/.env` and change only the five settings in the table above plus
   the API key. Keep your display/button/Wi-Fi settings as they are.
3. Test before travel: `.venv/bin/python3 scripts/gateway_test.py` — both
   `[1/2] images/generations` and `[2/2] chat.completions` must PASS.

## Notes

- **Reference images**: the `generations` path sends only the main photo, so
  magic-mode reference images are skipped (logged as a warning). Prompt-based
  presets (Graduation Day, etc.) work exactly as before.
- **English prompts are fine** — Qwen models handle the built-in English
  presets; you can also write prompts in Chinese in the web UI.
- **Pricing**: Qwen-Image-Edit on SiliconFlow costs roughly ¥0.2–0.3 per
  image; the VL model for magic mode is pennies. Check current pricing at
  siliconflow.cn before a long trip.
- **Alternative providers**: any OpenAI-compatible endpoint that implements
  `/v1/images/generations` with an `image` field works — e.g. Volcano Ark
  (Doubao SeedEdit). Change the three `IMAGE_GEN_*` values and the base URL.
- **Switching back**: `git checkout main` and restore your gateway `.env`
  values. The two branches share all camera code.
