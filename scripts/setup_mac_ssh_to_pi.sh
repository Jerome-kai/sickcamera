#!/usr/bin/env bash
set -euo pipefail

HOSTNAME="${1:-imagegencam.local}"
USER_NAME="${2:-imagegencam}"
ALIAS_NAME="${3:-imagegencam}"
CONFIG_DIR="${HOME}/.ssh"
CONFIG_PATH="${CONFIG_DIR}/config"
KEY_PATH="${CONFIG_DIR}/imagegencam_ed25519"
PUBLIC_KEY_PATH="${KEY_PATH}.pub"
START_MARKER="# imagegencam BEGIN"
END_MARKER="# imagegencam END"
LEGACY_START_MARKER="# image""-gen-camera-diy BEGIN"
LEGACY_END_MARKER="# image""-gen-camera-diy END"

usage() {
  cat <<EOF
Usage: $(basename "$0") [HOSTNAME_OR_IP] [USER] [ALIAS]

Examples:
  $(basename "$0")
  $(basename "$0") 192.168.1.42
  $(basename "$0") imagegencam.local imagegencam imagegencam
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

mkdir -p "${CONFIG_DIR}"
chmod 700 "${CONFIG_DIR}"

if [[ ! -f "${KEY_PATH}" ]]; then
  echo "Creating a dedicated SSH key for this camera at ${KEY_PATH}"
  ssh-keygen -t ed25519 -f "${KEY_PATH}" -N "" -C "imagegencam"
fi

if [[ ! -f "${PUBLIC_KEY_PATH}" ]]; then
  ssh-keygen -y -f "${KEY_PATH}" > "${PUBLIC_KEY_PATH}"
fi

touch "${CONFIG_PATH}"
chmod 600 "${CONFIG_PATH}"
cp "${CONFIG_PATH}" "${CONFIG_PATH}.bak-imagegencam"

TMP_FILE="$(mktemp)"
trap 'rm -f "${TMP_FILE}"' EXIT

awk \
  -v start="${START_MARKER}" \
  -v end="${END_MARKER}" \
  -v legacy_start="${LEGACY_START_MARKER}" \
  -v legacy_end="${LEGACY_END_MARKER}" \
  -v alias="${ALIAS_NAME}" '
  function host_block_matches(line,    count, i, token, tokens) {
    if (line !~ /^[[:space:]]*Host[[:space:]]+/) {
      return 0
    }
    sub(/^[[:space:]]*Host[[:space:]]+/, "", line)
    count = split(line, tokens, /[[:space:]]+/)
    for (i = 1; i <= count; i++) {
      token = tokens[i]
      if (token == alias) {
        return 1
      }
    }
    return 0
  }
  $0 == start || $0 == legacy_start {
    in_marker_block = 1
    marker_end = ($0 == legacy_start) ? legacy_end : end
    buffered = $0 ORS
    next
  }
  in_marker_block {
    buffered = buffered $0 ORS
    if ($0 == marker_end) {
      in_marker_block = 0
      marker_end = ""
      buffered = ""
    }
    next
  }
  /^[[:space:]]*(Host|Match)[[:space:]]+/ {
    if (in_alias_block) {
      in_alias_block = 0
    }
  }
  host_block_matches($0) {
    in_alias_block = 1
    next
  }
  in_alias_block {
    next
  }
  { print }
  END {
    if (in_marker_block) {
      printf "%s", buffered
    }
  }
' "${CONFIG_PATH}" > "${TMP_FILE}"

{
  if [[ -s "${TMP_FILE}" ]]; then
    cat "${TMP_FILE}"
    echo
  fi
  echo "${START_MARKER}"
  echo "Host ${ALIAS_NAME}"
  echo "  HostName ${HOSTNAME}"
  echo "  User ${USER_NAME}"
  echo "  IdentityFile ${KEY_PATH}"
  echo "  IdentitiesOnly yes"
  echo "${END_MARKER}"
} > "${CONFIG_PATH}"

echo "Copying this Mac's SSH key to ${USER_NAME}@${HOSTNAME}"
echo "When prompted, enter the Pi password. The password input may look blank."
cat "${PUBLIC_KEY_PATH}" | ssh "${USER_NAME}@${HOSTNAME}" '
  umask 077
  mkdir -p ~/.ssh
  touch ~/.ssh/authorized_keys
  key="$(cat)"
  grep -qxF "$key" ~/.ssh/authorized_keys || printf "%s\n" "$key" >> ~/.ssh/authorized_keys
  chmod 700 ~/.ssh
  chmod 600 ~/.ssh/authorized_keys
'

echo "Testing passwordless SSH alias: ${ALIAS_NAME}"
ssh -o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 "${ALIAS_NAME}" 'echo "ssh key ok"'

echo
echo "Ready for Codex Desktop:"
echo "  SSH host: ${ALIAS_NAME}"
echo "Backup saved at ${CONFIG_PATH}.bak-imagegencam"
