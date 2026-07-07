#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${PROJECT_ROOT}"
PYTHON_BIN="${PROJECT_ROOT}/.venv/bin/python3"

if [[ ! -x "${PYTHON_BIN}" ]]; then
  PYTHON_BIN="$(command -v python3)"
fi

export PYTHONPATH="${PROJECT_ROOT}/src"
SERVICE_IMAGE_GEN_PORT="${IMAGE_GEN_SERVICE_PORT:-}"

if [[ -f "${PROJECT_ROOT}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${PROJECT_ROOT}/.env"
  set +a
fi

if [[ -n "${SERVICE_IMAGE_GEN_PORT}" ]]; then
  export IMAGE_GEN_PORT="${SERVICE_IMAGE_GEN_PORT}"
fi

exec "${PYTHON_BIN}" -m imagegencam.app
