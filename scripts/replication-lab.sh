#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
mkdir -p evidence
marker="of-repl-$(date +%s)-$(openssl rand -hex 3)"
db primary -e "INSERT INTO orderflow.categories(name) VALUES('$marker')"
wait_replica
result="$(db replica -Nse "SELECT name FROM orderflow.categories WHERE name='$marker'")"
[[ "$result" == "$marker" ]]
db primary -e "DELETE FROM orderflow.categories WHERE name='$marker'"
wait_replica
[[ "$(db replica -Nse "SELECT COUNT(*) FROM orderflow.categories WHERE name='$marker'")" == 0 ]]
status="$(db replica -e 'SHOW REPLICA STATUS\G')"
grep -q 'Replica_IO_Running: Yes' <<<"$status"
grep -q 'Replica_SQL_Running: Yes' <<<"$status"
grep -q 'Auto_Position: 1' <<<"$status"
[[ "$(db replica -Nse 'SELECT @@read_only,@@super_read_only')" == $'1\t1' ]]
{
  printf 'UTC %s\n' "$(date -u +%FT%TZ)"
  echo '主库'
  db primary -e 'SELECT VERSION(),@@server_id,@@gtid_mode,@@binlog_format,@@read_only'
  echo '从库'
  db replica -e 'SELECT VERSION(),@@server_id,@@gtid_mode,@@read_only,@@super_read_only'
  echo '从库 SHOW REPLICA STATUS'
  printf '%s\n' "$status"
  printf 'PASS: 主库新增 %s，等待 GTID 后从库可读，测试记录清理也已复制。\n' "$marker"
} | sed 's/[[:blank:]]*$//' | tee evidence/replication.txt
