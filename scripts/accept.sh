#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/start.sh million
scripts/test-api.sh
scripts/lock-lab.sh
scripts/index-lab.sh
scripts/binlog-lab.sh --isolated-run
scripts/runtime-check.sh
scripts/replication-lab.sh
scripts/check-data.sh --million
scripts/test-browser.sh
tests/git_sync_check.sh
echo '自动化验收通过。Navicat 实机及远程版本由对应检查步骤记录。'
