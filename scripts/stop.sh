#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/stop-app.sh
docker compose stop
echo '已停止 OrderFlow，保留数据库和 Redis 数据卷。'
