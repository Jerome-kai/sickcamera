#!/usr/bin/env bash
set -euo pipefail

WAIT_SECONDS="${GUI_FALLBACK_WAIT_SECONDS:-300}"
POLL_SECONDS="${GUI_FALLBACK_POLL_SECONDS:-10}"

has_network_connection() {
  nmcli --terse --fields DEVICE,STATE device status 2>/dev/null | grep -Eq ':(connected|connecting)$'
}

elapsed=0
while (( elapsed < WAIT_SECONDS )); do
  if has_network_connection; then
    echo "Network detected; keeping headless mode."
    exit 0
  fi
  sleep "${POLL_SECONDS}"
  elapsed=$((elapsed + POLL_SECONDS))
done

if has_network_connection; then
  echo "Network detected before fallback deadline; keeping headless mode."
  exit 0
fi

echo "No network after ${WAIT_SECONDS}s; starting lightdm for local setup."
/usr/bin/systemctl start lightdm.service
