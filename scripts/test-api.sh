#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
mkdir -p evidence .runtime
.venv/bin/python tests/http_check.py 2>&1 | tee evidence/api-acceptance.txt
