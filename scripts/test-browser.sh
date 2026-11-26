#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
mkdir -p evidence
(cd frontend && env -u FORCE_COLOR NO_COLOR=1 npx --no-install playwright test --reporter=list) 2>&1 | tee evidence/browser-acceptance.txt
