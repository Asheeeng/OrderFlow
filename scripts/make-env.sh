#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then
  (umask 077; set -o noclobber
    while IFS= read -r line; do
      if [[ "$line" =~ ^(DB_.*PASSWORD|DEMO_PASSWORD|REDIS_PASSWORD)=$ ]]; then
        printf '%s%s\n' "$line" "$(openssl rand -hex 18)"
      else
        printf '%s\n' "$line"
      fi
    done < .env.example > .env
  )
fi
