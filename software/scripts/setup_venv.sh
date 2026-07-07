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

# The app needs Python 3.10+. Ubuntu 20.04 (legacy Orange Pi image) ships 3.8;
# install a newer one from the deadsnakes PPA if needed.
PYTHON_BIN="${PYTHON_BIN:-}"
if [[ -z "${PYTHON_BIN}" ]]; then
  for candidate in python3 python3.12 python3.11 python3.10; do
    if command -v "${candidate}" >/dev/null 2>&1 && \
       "${candidate}" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
      PYTHON_BIN="${candidate}"
      break
    fi
  done
fi
if [[ -z "${PYTHON_BIN}" ]]; then
  echo "Python 3.10+ not found. On Ubuntu 20.04 install it with:"
  echo "  sudo add-apt-repository ppa:deadsnakes/ppa"
  echo "  sudo apt install python3.10 python3.10-venv python3.10-dev"
  exit 1
fi
echo "Using ${PYTHON_BIN} ($(${PYTHON_BIN} --version 2>&1))"

if ! "${PYTHON_BIN}" -m venv --help >/dev/null 2>&1; then
  echo "${PYTHON_BIN} -m venv is unavailable. Install the matching venv package first,"
  echo "e.g. sudo apt install python3-venv (or python3.10-venv)."
  exit 1
fi

# spidev and gpiod build small C extensions from sdist; make sure the
# toolchain is present (no-op if already installed).
echo "Ensuring build tools for spidev/gpiod (python3-dev, gcc)..."
sudo apt-get install -y python3-dev build-essential >/dev/null 2>&1 || \
  echo "Warning: could not apt-get python3-dev build-essential; pip may fail to build spidev/gpiod."

rm -rf .venv
"${PYTHON_BIN}" -m venv .venv
"${PROJECT_ROOT}/.venv/bin/pip" install --upgrade pip
install_python_deps

echo "Virtual environment ready at ${PROJECT_ROOT}/.venv"
echo "Run the full app setup with:"
echo "  ./scripts/setup.sh"
