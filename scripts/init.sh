#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/make-env.sh
source scripts/lib.sh
docker compose up -d --wait
initialize primary 3306
echo 'OrderFlow 数据库、Redis 与 GTID 复制已初始化。'
