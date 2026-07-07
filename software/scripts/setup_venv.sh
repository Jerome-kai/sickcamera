#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

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

rm -rf .venv
python3 -m venv --system-site-packages .venv
"${PROJECT_ROOT}/.venv/bin/pip" install --upgrade pip
install_python_deps

echo "Virtual environment ready at ${PROJECT_ROOT}/.venv"
echo "Run the full app setup with:"
echo "  ./scripts/setup.sh"
