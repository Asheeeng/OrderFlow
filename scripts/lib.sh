#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
set -a
source .env
set +a

db() {
  local service="$1"
  shift
  docker compose exec -T "$service" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --default-character-set=utf8mb4 -uroot "$@"' mysql "$@"
}

wait_replica() {
  local gtid result
  gtid="$(db primary -Nse 'SELECT @@GLOBAL.gtid_executed')"
  result="$(db replica -Nse "SELECT WAIT_FOR_EXECUTED_GTID_SET('$gtid',180)")"
  [[ "$result" == 0 ]] || { echo "从库在 180 秒内未追上主库 GTID：$result" >&2; return 1; }
}

initialize() {
  local source_host="$1" source_port="$2" key value status
  for key in DB_APP_PASSWORD DB_INSPECT_PASSWORD DB_REPL_PASSWORD DB_ROOT_PASSWORD REDIS_PASSWORD DEMO_PASSWORD; do
    value="${!key}"
    [[ "$value" =~ ^[a-zA-Z0-9]{16,72}$ ]] || { echo "$key 需要 16–72 位字母数字" >&2; return 1; }
  done
  [[ ${#DB_REPL_PASSWORD} -le 32 ]] || { echo 'MySQL 复制用户密码最多 32 位' >&2; return 1; }
  db primary < database/schema/001_tables.sql
  if [[ "$(db primary -Nse "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='orderflow' AND index_name='idx_orders_date'")" == 0 ]]; then
    db primary < database/indexes/001_indexes.sql
  fi
  if [[ "$(db primary -Nse "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='orderflow' AND trigger_name='product_price_audit'")" == 0 ]]; then
    db primary < database/schema/002_routines.sql
  fi
  if [[ "$(db primary -Nse "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='orderflow' AND routine_name='inspect_orderflow_triggers'")" == 0 ]]; then
    db primary < database/schema/003_inspection.sql
  fi
  sed -e "s/@APP_PASSWORD@/$DB_APP_PASSWORD/g" -e "s/@INSPECT_PASSWORD@/$DB_INSPECT_PASSWORD/g" -e "s/@REPL_PASSWORD@/$DB_REPL_PASSWORD/g" database/replication/accounts.sql | db primary
  local migration
  for migration in database/migrations/V*__*.sql; do
    db primary < "$migration"
  done
  db replica -e 'SET PERSIST super_read_only=ON'
  status="$(db replica -e 'SHOW REPLICA STATUS\G')"
  if [[ -z "$status" ]]; then
    sed -e "s/@SOURCE_HOST@/$source_host/g" -e "s/@SOURCE_PORT@/$source_port/g" -e "s/@REPL_PASSWORD@/$DB_REPL_PASSWORD/g" database/replication/replica.sql | db replica >/dev/null
  elif ! grep -Eq 'Replica_IO_Running: (Connecting|Yes)' <<<"$status" || ! grep -q 'Replica_SQL_Running: Yes' <<<"$status"; then
    db replica -e 'START REPLICA'
  fi
  wait_replica
}
