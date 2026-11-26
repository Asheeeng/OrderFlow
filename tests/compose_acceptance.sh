#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ORDERFLOW_PROJECT:?Set a dedicated acceptance project name}"
: "${ORDERFLOW_ENV_FILE:?Set a dedicated Docker environment file}"
[[ "$ORDERFLOW_PROJECT" =~ ^[a-z0-9][a-z0-9_-]+$ && "$ORDERFLOW_PROJECT" != orderflow && "$ORDERFLOW_PROJECT" != orderflow-docker ]] ||
  { echo 'Use an isolated acceptance project.' >&2; exit 2; }
[[ "${ORDERFLOW_ENV_FILE##*/}" == .env.docker.* ]] ||
  { echo 'Use a separate .env.docker.* file for the acceptance project.' >&2; exit 2; }

compose() (
  unset SEED_MODE WEB_PORT MYSQL_PRIMARY_PORT MYSQL_REPLICA_PORT
  unset DB_ROOT_PASSWORD DB_APP_PASSWORD DB_INSPECT_PASSWORD DB_REPL_PASSWORD DEMO_PASSWORD REDIS_PASSWORD
  docker compose --project-name "$ORDERFLOW_PROJECT" --env-file "$ORDERFLOW_ENV_FILE" --file compose.docker.yml "$@"
)
db() {
  local service="$1"
  shift
  compose exec -T "$service" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --socket=/run/orderflow/mysql.sock --default-character-set=utf8mb4 "$@"' sh "$@"
}
redis() {
  compose exec -T redis sh -c 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli "$@"' sh "$@"
}
wait_replica() {
  local gtid
  gtid="$(db primary -Nse 'SELECT @@GLOBAL.gtid_executed')"
  [[ "$(db replica -Nse "SELECT WAIT_FOR_EXECUTED_GTID_SET('$gtid',180)")" == 0 ]]
}
counts() { db "$1" -B orderflow < database/experiments/counts.sql; }

printf 'UTC %s, isolated project=%s\n' "$(date -u +%FT%TZ)" "$ORDERFLOW_PROJECT"
docker info --format 'Docker engine {{.OSType}}/{{.Architecture}}'
docker compose version
scripts/docker.sh status
for service in frontend backend init; do
  id="$(compose ps -aq "$service")"
  docker inspect --format '{{.Name}} {{.State.Status}} {{.State.ExitCode}} started={{.State.StartedAt}} finished={{.State.FinishedAt}}' "$id"
done
compose logs --no-color --tail=25 init
wait_replica
diff -u <(counts primary) <(counts replica)
before="$(counts primary)"
printf '%s\n' "$before"
awk -F '\t' '$1=="order_items"{detail=$2} NR>1{total+=$2} END {
  printf "total_rows\t%d\n",total
  exit !(detail>=1000000 && total>=1000000)
}' <<< "$before"

for service in primary replica; do
  db "$service" -Nse 'SELECT VERSION(),@@server_id,@@GLOBAL.gtid_mode,@@GLOBAL.super_read_only'
  integrity="$(db "$service" -B orderflow < database/experiments/integrity.sql)"
  printf '%s integrity\n%s\n' "$service" "$integrity"
  awk -F '\t' 'NR>1 && $2!=0 {bad=1} END {exit bad || NR!=10}' <<< "$integrity"
done
status="$(db replica -e 'SHOW REPLICA STATUS\G')"
grep -E '(Source_Host|Source_SSL_Allowed|Auto_Position|Replica_IO_Running:|Replica_SQL_Running:|Seconds_Behind_Source):?' <<< "$status"
grep -q 'Replica_IO_Running: Yes' <<< "$status"
grep -q 'Replica_SQL_Running: Yes' <<< "$status"
grep -q 'Auto_Position: 1' <<< "$status"

mode="$(sed -n 's/^SEED_MODE=//p' "$ORDERFLOW_ENV_FILE" | tr -d '\r')"
init_log="$(compose run --rm --no-deps -T init)"
grep -q 'historical data already present' <<< "$init_log"
grep -q "READY: mode=$mode" <<< "$init_log"
[[ "$(counts primary)" == "$before" ]]
echo 'PASS: a second initialization preserved every business-table row count.'

if wrong_mode="$(compose run --rm --no-deps -T -e SEED_MODE=small init 2>&1)"; then
  echo 'A different seed mode should have been rejected.' >&2
  exit 1
fi
grep -q '数据库已有其他种子模式' <<< "$wrong_mode"
[[ "$(counts primary)" == "$before" ]]
echo 'PASS: wrong seed mode was rejected without modifying business data.'

compose run --rm --no-deps -T -e OF_TEST_API_BASE=http://frontend/api \
  -e OF_API_FIXTURE_FILE=/tmp/orderflow-test.json --entrypoint python init - < tests/http_check.py
wait_replica
expected="$(counts primary)"
demo_order="$(db primary -Nse 'SELECT MAX(id) FROM orderflow.orders')"
marker="orderflow:compose-acceptance:$(date +%s)-$$"
redis SET "$marker" persistence-probe >/dev/null

scripts/docker.sh stop >/dev/null
compose up -d --wait --wait-timeout 600 --force-recreate
wait_replica
[[ "$(counts primary)" == "$expected" && "$(counts replica)" == "$expected" ]]
[[ "$(db replica -Nse "SELECT id FROM orderflow.orders WHERE id=$demo_order")" == "$demo_order" ]]
[[ "$(redis GET "$marker")" == persistence-probe ]]
redis DEL "$marker" >/dev/null
[[ "$(db replica -Nse 'SELECT @@GLOBAL.super_read_only')" == 1 ]]
echo "PASS: after container recreation MySQL counts, order $demo_order, replica read-only and Redis AOF persisted."
scripts/docker.sh status
