# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A fork of [openai/imagegencam](https://github.com/openai/imagegencam) — an AI image-generation camera — ported from the Raspberry Pi Zero 2 W to an **Orange Pi Zero 2** (Allwinner H616) with an ST7796 SPI display, a USB UVC camera, and GPIO buttons. The runnable app lives entirely under `software/`. The rest of the repo is hardware docs (`HARDWARE.md`), 3D-print files (`3d model/`), and the upstream Codex end-user tutorial (`AGENTS.md`, `docs/codex-guide/`).

Note: `AGENTS.md` is **not** a developer guide — it scripts the Codex Desktop tutorial persona that walks end users through building the camera. Its safety rules still apply to any agent: never ask for or echo API keys in chat (they live only in `software/.env`), and keep the phone web app local-LAN only.

## Commands

All Python commands run from `software/`.

```bash
cd software

# Full test suite (148+ tests, runs off-device — hardware is mocked)
python3 -m pytest tests/

# One file / one test
python3 -m pytest tests/test_web.py
python3 -m pytest tests/test_wifi_setup.py -k hotspot
```

Tests need only `pytest`, `Pillow`, `numpy`, `qrcode`, and `openai` — **not** the hardware libs (`spidev`, `gpiod`, `opencv-python-headless`), which usually won't be present off-device. `pyproject.toml` sets `pythonpath = ["src"]` for pytest; there is no installable package step.

There is no linter or formatter configured. Repo-root `scripts/install_git_hooks.sh` enables a pre-commit hook that runs `scripts/check_secrets.sh --staged` (regex scan for API keys, private keys, tokens).

On-device only (Orange Pi over SSH — these open real SPI/GPIO/V4L2 devices):

```bash
./scripts/setup.sh              # venv + deps + .env creation (prompts for API key)
./scripts/run.sh                # run the app in the foreground (web UI on :8000)
./scripts/install_service.sh    # install systemd units, udev rules, sudoers, spidev conf
.venv/bin/python3 scripts/display_test.py      # bring-up: color bars
sudo .venv/bin/python3 scripts/button_test.py  # bring-up: per-button DOWN/up
.venv/bin/python3 scripts/gateway_test.py      # verify the OpenAI/gateway API path (no hardware)
./scripts/display_benchmark.py  # measure SPI frame time (stop the service first)
```

## Architecture

`software/ARCHITECTURE.md` is the canonical description; keep it (and `HARDWARE.md` for anything touching wiring, `.env` vars, or OS setup) updated when behavior changes.

Runtime shape (`software/src/imagegencam/`):

- `app.py` — entrypoint (`python3 -m imagegencam.app`). Reads `.env`, wires config stores + OpenAI clients + job store + controller + web server.
- `controller.py` (~4200 lines) — the physical device loop: camera frames, display rendering, button state machine, capture → generation jobs, album, Magic Mode, Wi-Fi/diagnostics screens, tutorial, screen sleep. Most feature work lands here.
- `web.py` (~2600 lines) — the phone companion app, built on **stdlib `http.server.ThreadingHTTPServer` only** (no Flask/FastAPI; dependencies are deliberately minimal for the board). Serves the album, prompt editor, live screen mirror, Wi-Fi picker, and Magic History. HTML/CSS/JS are inline strings in this file.
- `openai_client.py` — all OpenAI API calls. `IMAGE_GEN_API` selects the endpoint shape: `edits` (`/v1/images/edits`, direct api.openai.com), `chat` (multimodal chat-completions, needed for gateways like Vercel AI Gateway that don't proxy image edits), or `generations` (Chinese OpenAI-compatible providers). `OPENAI_BASE_URL` routes through a gateway; gateway model names need a provider prefix (e.g. `google/gemini-2.5-flash-image`).
- `job_store.py` — small durable on-disk queue (`data/queue/generation/`); jobs survive power loss and are retried up to `QUEUE_MAX_ATTEMPTS`, then parked in `data/queue/failed/`.
- `config.py` — JSON-backed stores with normalization: `data/prompts.json`, `data/settings.json`, `data/magic_history.json`.
- `wifi_manager.py` / `wifi_setup.py` — NetworkManager wrapper (via a narrow sudoers rule for specific `nmcli` commands) and the first-run setup hotspot that publishes the camera's own Wi-Fi when no known network is reachable.

### Hardware abstraction

`IMAGEGENCAM_HW` in `.env` selects the board at import time in `controller.py`: `pi` imports the real `displayhatmini`/`picamera2` packages; `opi` (default) imports `opi_hw.py`, a shim that impersonates both interfaces (`DisplayHATMini`-compatible display+buttons, `Picamera2`-compatible USB camera) so the controller runs unmodified on either board. Below the shim:

- `st7796.py` — SPI panel driver (480×320 RGB565).
- `sunxi_gpio.py` — two GPIO backends behind one interface: `gpiod` (kernel 5.10+) and `sysfs` (legacy 4.9 kernel, pull-ups poked via `/dev/mem`, needs root). `GPIO_BACKEND=auto` picks. Line numbers use the sunxi convention `port_index*32 + pin` (PC9 = 73).

All hardware knobs (pins, SPI bus/device/clock, camera device, rotation/inversion) are env vars — see `software/.env.example`, which is the documented reference for every setting.

### Data folders (`software/data/`)

`captures/` (originals), `generated/` (outputs), `queue/` (pending jobs), and `ap_password` are runtime state and gitignored. `prompts.json` is the shipped seed list. Don't commit personal `magic_history.json` content.

## Constraints and conventions

- **Reliability rules** (from `ARCHITECTURE.md`): save captures locally *before* any generation work; persist anything that must survive power loss as small JSON job files, not in-memory queues; Wi-Fi changes must never delete existing NetworkManager profiles and new connection attempts must keep a rollback path to the previously active profile.
- **Performance**: the SPI link, not the CPU, is the preview bottleneck (~16 fps at the default 40 MHz). Keep preview work cheap; avoid per-frame image processing unless it changes visible state; menus/album redraw at lower budgets because they're static between key presses. Measure with `scripts/display_benchmark.py` before optimizing.
- **Security**: the phone web app is unauthenticated by design and must stay local-LAN only. `web.py` caps POST bodies (`MAX_POST_BODY_BYTES`), escapes JSON embedded in inline scripts (`json_for_inline_script`), and restricts `/assets/` file serving — preserve these properties when editing it. API keys live only in `software/.env` (gitignored); never in code, commits, or example files.
- Optional features are removable via env (`MAGIC_MODE_ENABLED=0`, `WIFI_SETUP_PORTAL=0`, `HOTSHOE_ENABLED=0`, `PISUGAR_ENABLED=0`); keep new optional features toggleable the same way.
- Tests are stdlib `unittest`-style classes run under pytest, and mock hardware at module seams (see `tests/test_ux.py` stubbing the controller surface, `tests/test_opi_hw.py`). New logic should stay testable off-device.
- `docs/ROADMAP-V2.md` holds the V2 plans; `docs/kit-image-contract.md` defines what a prebuilt SD card image must contain.
