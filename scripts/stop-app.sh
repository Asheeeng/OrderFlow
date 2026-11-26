#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd)"
for service in api web; do
  file=".runtime/$service.pid"
  if [[ -f "$file" ]]; then
    pid="$(cat "$file")"
    if [[ "$pid" =~ ^[1-9][0-9]*$ ]] && ps -p "$pid" -o command= 2>/dev/null | grep -F "$root" >/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
    rm -f "$file"
  fi
done
