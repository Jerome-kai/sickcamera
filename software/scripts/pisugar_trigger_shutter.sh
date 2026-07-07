#!/usr/bin/env bash
set -euo pipefail

EVENT_DIR="/tmp/imagegencam-shutter-events"
EVENT_NAME="${1:-shutter}"
mkdir -p "${EVENT_DIR}"
chmod 0777 "${EVENT_DIR}" 2>/dev/null || true

EVENT_FILE="${EVENT_DIR}/$(date +%s%N)-$$"
printf '%s\n' "${EVENT_NAME}" > "${EVENT_FILE}"
chmod 0666 "${EVENT_FILE}" 2>/dev/null || true
