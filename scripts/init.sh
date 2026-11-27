#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/make-env.sh
source scripts/lib.sh
docker compose up -d --wait
initialize primary 3306
mkdir -p .runtime
if [[ ! -f .runtime/ai-master.key ]]; then
  [[ "$(db primary -Nse 'SELECT COUNT(*) FROM orderflow.ai_runtime_config WHERE chat_secret IS NOT NULL OR embedding_secret IS NOT NULL')" == 0 ]] ||
    { echo '请先恢复 .runtime/ai-master.key' >&2; exit 1; }
  (umask 077; set -o noclobber; openssl rand -hex 32 > .runtime/ai-master.key)
fi
echo 'OrderFlow 数据库、Redis 与 GTID 复制已初始化。'
