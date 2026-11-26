#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "${1:-}" == --isolated-run ]] || {
  echo '使用新建临时容器复现 small 模式。运行方式：tests/seed_small.sh --isolated-run' >&2
  exit 2
}
mkdir -p .runtime
set -a
source .env
set +a
export MYSQL_ROOT_PASSWORD="$(openssl rand -hex 18)"
name="orderflow-small-$(openssl rand -hex 4)"
trap 'docker rm -fv "$name" >/dev/null 2>&1 || true' EXIT
docker run --rm -d --name "$name" -e MYSQL_ROOT_PASSWORD -e MYSQL_ROOT_HOST=localhost \
  -p 127.0.0.1::3306 mysql:8.0.44 --local-infile=ON --innodb-buffer-pool-size=70M >/dev/null
ofmysql() { docker exec -i "$name" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 "$@"' sh "$@"; }
for ((i=0;i<90;i++)); do
  if ofmysql -h127.0.0.1 -Nse 'SELECT 1' >/dev/null 2>&1; then break; fi
  sleep 1
done
ofmysql < database/schema/001_tables.sql
ofmysql < database/schema/002_routines.sql
sed -e "s/@APP_PASSWORD@/$DB_APP_PASSWORD/g" -e "s/@INSPECT_PASSWORD@/$DB_INSPECT_PASSWORD/g" -e "s/@REPL_PASSWORD@/$DB_REPL_PASSWORD/g" database/replication/accounts.sql | ofmysql
TEST_PORT="$(docker port "$name" 3306/tcp | sed -n 's/^127\.0\.0\.1://p')"
[[ "$TEST_PORT" =~ ^[0-9]+$ && "$TEST_PORT" != "$MYSQL_PRIMARY_PORT" && "$TEST_PORT" != "$MYSQL_REPLICA_PORT" ]]
MYSQL_PRIMARY_PORT="$TEST_PORT" .venv/bin/python database/seed/generate.py small
MYSQL_PRIMARY_PORT="$TEST_PORT" .venv/bin/python database/seed/generate.py small
ofmysql -B orderflow < database/experiments/counts.sql
ofmysql -B orderflow < database/experiments/integrity.sql | tee .runtime/small-integrity.tsv
awk -F '\t' 'NR>1 && $2+0!=0 {bad=1} END {exit bad}' .runtime/small-integrity.tsv
[[ "$(ofmysql -Nse 'SELECT COUNT(*) FROM orderflow.order_items')" == 1000 ]]
echo 'PASS: 全新隔离库 small 生成、重复运行、1,000 行订单明细及完整性。'
