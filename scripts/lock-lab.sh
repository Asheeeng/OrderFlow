#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
mkdir -p evidence
PYTHONUNBUFFERED=1 .venv/bin/python tests/lock_check.py 2>&1 | tee evidence/concurrency.txt
