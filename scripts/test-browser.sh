#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
mkdir -p evidence
(cd frontend && npx --no-install playwright test --reporter=line) 2>&1 | tee evidence/browser-acceptance.txt
