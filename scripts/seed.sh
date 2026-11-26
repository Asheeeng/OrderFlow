#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
mkdir -p .runtime
.venv/bin/python database/seed/generate.py "${1:-small}" 2>&1 | tee ".runtime/seed-${1:-small}.log"
wait_replica
