#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-worktree}"
SENTRY_DSN_KEY="SENTRY""_DSN"

PATTERNS=(
  'sk-[A-Za-z0-9_-]{20,}'
  'OPENAI_API_KEY=sk-[A-Za-z0-9_-]{20,}'
  'AKIA[0-9A-Z]{16}'
  'BEGIN (RSA|OPENSSH|DSA|EC|PRIVATE) KEY'
  'xox[baprs]-[A-Za-z0-9-]+'
  'gh[pousr]_[A-Za-z0-9_]+'
  'AIza[0-9A-Za-z_-]{35}'
  'Bearer [A-Za-z0-9._-]{20,}'
  "${SENTRY_DSN_KEY}=.*@"
  'https://[A-Za-z0-9]+@[A-Za-z0-9.-]*sentry\.io/[0-9]+'
)

combine_patterns() {
  local joined=""
  local pattern
  for pattern in "${PATTERNS[@]}"; do
    if [[ -z "${joined}" ]]; then
      joined="(${pattern})"
    else
      joined="${joined}|(${pattern})"
    fi
  done
  printf '%s\n' "${joined}"
}

PATTERN="$(combine_patterns)"

if [[ "${MODE}" == "--staged" ]]; then
  if git diff --cached --quiet --exit-code --; then
    exit 0
  fi

  if git grep --cached -n -I -E "${PATTERN}" -- .; then
    echo
    echo "Potential secret found in staged changes. Remove it before committing." >&2
    exit 1
  fi
  exit 0
fi

if command -v rg >/dev/null 2>&1; then
  if rg -n --hidden --glob '!.git/**' --glob '!software/data/captures/**' --glob '!software/data/generated/**' --glob '!software/data/queue/**' -e "${PATTERN}"; then
    echo
    echo "Potential secret found. Remove it before committing." >&2
    exit 1
  fi
  exit 0
fi

if git grep -n -I -E "${PATTERN}" -- .; then
  echo
  echo "Potential secret found. Remove it before committing." >&2
  exit 1
fi
