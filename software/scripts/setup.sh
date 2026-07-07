#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_EXAMPLE="${PROJECT_ROOT}/.env.example"
ENV_FILE="${PROJECT_ROOT}/.env"

cd "${PROJECT_ROOT}"

install_python_deps() {
  if "${PROJECT_ROOT}/.venv/bin/pip" install -r requirements.txt; then
    return
  fi

  echo "pip install -r requirements.txt failed. Falling back to a mixed install path."
  "${PROJECT_ROOT}/.venv/bin/pip" install "openai>=1.109.0"
  if ! "${PROJECT_ROOT}/.venv/bin/pip" install "Pillow>=10.0.0"; then
    echo "pip could not install Pillow. Installing python3-pil from apt instead."
    sudo apt-get update --allow-releaseinfo-change
    sudo apt-get install -y python3-pil
  fi
  if ! "${PROJECT_ROOT}/.venv/bin/pip" install "qrcode>=8.2"; then
    echo "pip could not install qrcode. Installing python3-qrcode from apt instead."
    sudo apt-get update --allow-releaseinfo-change
    sudo apt-get install -y python3-qrcode
  fi
}

if ! python3 -m venv --help >/dev/null 2>&1; then
  echo "python3 -m venv is unavailable. Install python3-venv first:"
  echo "sudo apt install python3-venv"
  exit 1
fi

if [[ ! -f "${ENV_EXAMPLE}" ]]; then
  echo "Missing ${ENV_EXAMPLE}" >&2
  exit 1
fi

echo "ImageGenCam setup"
echo
echo "Step 1 of 2: creating the Python environment"
# spidev and gpiod build small C extensions from sdist.
sudo apt-get install -y python3-dev build-essential >/dev/null 2>&1 || true
rm -rf .venv
python3 -m venv .venv
"${PROJECT_ROOT}/.venv/bin/pip" install --upgrade pip
install_python_deps
echo
echo "Step 2 of 2: creating .env"
echo "Use an OpenAI API key (https://platform.openai.com/api-keys) or, if you route"
echo "through a gateway such as the Vercel AI Gateway, that gateway's API key."
echo "For a gateway, also set OPENAI_BASE_URL in .env afterwards."
echo
echo "Paste the API key below. Input is hidden."
printf "OPENAI_API_KEY: "
read -r -s API_KEY
echo

if [[ -z "${API_KEY}" ]]; then
  echo "No API key entered. Aborting." >&2
  exit 1
fi

cp "${ENV_EXAMPLE}" "${ENV_FILE}"
python3 - <<'PY' "${ENV_FILE}" "${API_KEY}"
from pathlib import Path
import sys

env_path = Path(sys.argv[1])
api_key = sys.argv[2]
lines = env_path.read_text(encoding="utf-8").splitlines()
updated = []
replaced = False
for line in lines:
    if line.startswith("OPENAI_API_KEY="):
        updated.append(f"OPENAI_API_KEY={api_key}")
        replaced = True
    else:
        updated.append(line)
if not replaced:
    updated.append(f"OPENAI_API_KEY={api_key}")
env_path.write_text("\n".join(updated) + "\n", encoding="utf-8")
PY

echo
echo "Setup complete."
echo "Run the app with:"
echo "  ./scripts/run.sh"
