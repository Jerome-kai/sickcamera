#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TEMPLATE_PATH="${PROJECT_ROOT}/deploy/imagegencam.service"
SERVICE_NAME="imagegencam.service"
SERVICE_PATH="/etc/systemd/system/${SERVICE_NAME}"
BOOT_SPLASH_TEMPLATE_PATH="${PROJECT_ROOT}/deploy/imagegencam-boot-splash.service"
BOOT_SPLASH_SERVICE_NAME="imagegencam-boot-splash.service"
BOOT_SPLASH_SERVICE_PATH="/etc/systemd/system/${BOOT_SPLASH_SERVICE_NAME}"
SUDOERS_TEMPLATE_PATH="${PROJECT_ROOT}/deploy/imagegencam-nmcli.sudoers"
SUDOERS_PATH="/etc/sudoers.d/imagegencam-nmcli"
UDEV_RULES_TEMPLATE_PATH="${PROJECT_ROOT}/deploy/99-imagegencam-hw.rules"
UDEV_RULES_PATH="/etc/udev/rules.d/99-imagegencam-hw.rules"
WIFI_INTERFACE="${WIFI_INTERFACE:-wlan0}"
UNUSED_SERVICES=(
  bluetooth.service
  colord.service
  cups.service
  cups-browsed.service
  ModemManager.service
  nfs-blkmap.service
  rpcbind.service
)
FAST_BOOT_DISABLED_UNITS=(
  NetworkManager-wait-online.service
)
CLOUD_INIT_UNITS=(
  cloud-config.service
  cloud-final.service
  cloud-init-local.service
  cloud-init-main.service
  cloud-init-network.service
)
FAST_BOOT_DISABLED_TIMERS=(
  apt-daily.timer
  apt-daily-upgrade.timer
)
CLOUD_INIT_DISABLE_PATH="/etc/cloud/cloud-init.disabled"
OUTPUT_PATH=""
PRINT_ONLY=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--print] [--output PATH]

Options:
  --print         Print the rendered service file instead of installing it.
  --output PATH   Write the rendered service file to PATH instead of /etc/systemd/system.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --print)
      PRINT_ONLY=1
      shift
      ;;
    --output)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for --output" >&2
        exit 1
      fi
      OUTPUT_PATH="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

render_service() {
  sed \
    -e "s|__SERVICE_USER__|${USER}|g" \
    -e "s|__PROJECT_ROOT__|${PROJECT_ROOT}|g" \
    "${TEMPLATE_PATH}"
}

render_boot_splash_service() {
  sed \
    -e "s|__PROJECT_ROOT__|${PROJECT_ROOT}|g" \
    "${BOOT_SPLASH_TEMPLATE_PATH}"
}

render_sudoers() {
  sed \
    -e "s|__SERVICE_USER__|${USER}|g" \
    -e "s|__WIFI_INTERFACE__|${WIFI_INTERFACE}|g" \
    "${SUDOERS_TEMPLATE_PATH}"
}

if [[ ${PRINT_ONLY} -eq 1 ]]; then
  render_service
  exit 0
fi

if [[ -n "${OUTPUT_PATH}" ]]; then
  install -m 0644 /dev/null "${OUTPUT_PATH}"
  render_service > "${OUTPUT_PATH}"
  echo "Wrote ${OUTPUT_PATH}"
  exit 0
fi

TMP_FILE="$(mktemp)"
BOOT_SPLASH_TMP_FILE="$(mktemp)"
SUDOERS_TMP_FILE="$(mktemp)"
trap 'rm -f "${TMP_FILE}" "${BOOT_SPLASH_TMP_FILE}" "${SUDOERS_TMP_FILE}"' EXIT
render_service > "${TMP_FILE}"
render_boot_splash_service > "${BOOT_SPLASH_TMP_FILE}"
render_sudoers > "${SUDOERS_TMP_FILE}"

sudo install -m 0644 "${TMP_FILE}" "${SERVICE_PATH}"
sudo install -m 0644 "${BOOT_SPLASH_TMP_FILE}" "${BOOT_SPLASH_SERVICE_PATH}"
sudo install -m 0440 "${SUDOERS_TMP_FILE}" "${SUDOERS_PATH}"
sudo visudo -cf "${SUDOERS_PATH}"
chmod +x "${PROJECT_ROOT}/scripts/show_boot_splash.py"

# GPIO/SPI access for the service user without running as root.
sudo groupadd -f gpio
sudo groupadd -f spi
sudo usermod -aG gpio,spi,video "${USER}"
sudo install -m 0644 "${UDEV_RULES_TEMPLATE_PATH}" "${UDEV_RULES_PATH}"
sudo udevadm control --reload
sudo udevadm trigger --subsystem-match=spidev --subsystem-match=gpio || true

sudo systemctl daemon-reload
sudo systemctl set-default multi-user.target
sudo systemctl enable "${BOOT_SPLASH_SERVICE_NAME}"
for SERVICE in "${UNUSED_SERVICES[@]}"; do
  timeout 12s sudo systemctl disable --now "${SERVICE}" >/dev/null 2>&1 || true
done
for UNIT in "${FAST_BOOT_DISABLED_UNITS[@]}"; do
  timeout 12s sudo systemctl disable --now "${UNIT}" >/dev/null 2>&1 || true
done
for TIMER in "${FAST_BOOT_DISABLED_TIMERS[@]}"; do
  timeout 12s sudo systemctl disable --now "${TIMER}" >/dev/null 2>&1 || true
done
sudo mkdir -p "$(dirname "${CLOUD_INIT_DISABLE_PATH}")"
sudo touch "${CLOUD_INIT_DISABLE_PATH}"
for UNIT in "${CLOUD_INIT_UNITS[@]}"; do
  timeout 12s sudo systemctl disable "${UNIT}" >/dev/null 2>&1 || true
done
echo "Installed ${SERVICE_PATH}"
echo "Installed ${BOOT_SPLASH_SERVICE_PATH}"
echo "Installed ${SUDOERS_PATH} for rollback-safe Wi-Fi switching"
echo "Installed ${UDEV_RULES_PATH} (gpio/spi group access; re-login for group changes)"
echo "Boot mode: headless (multi-user.target)"
echo "Early splash: enabled before network and camera startup"
echo "Disabled unused services: ${UNUSED_SERVICES[*]}"
echo "Disabled for faster boot: ${FAST_BOOT_DISABLED_UNITS[*]} ${FAST_BOOT_DISABLED_TIMERS[*]} ${CLOUD_INIT_UNITS[*]}"
echo "Enable on boot: sudo systemctl enable --now ${SERVICE_NAME}"
echo "Status: sudo systemctl status ${SERVICE_NAME}"
echo "Logs: sudo journalctl -u ${SERVICE_NAME} -f"
echo "Phone app after service start: http://imagegencam.local"
