#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "${1:-}" == --isolated-run ]] || {
  echo '演练会在两个全新、无网络的临时容器内模拟删除订单。运行方式：scripts/binlog-lab.sh --isolated-run' >&2
  exit 2
}
command -v mysqlbinlog >/dev/null || { echo "安装 MySQL 客户端工具 mysqlbinlog 后再运行" >&2; exit 1; }
mkdir -p .runtime evidence
runtime="$(mktemp -d "$PWD/.runtime/pitr.XXXXXX")"
suffix="${runtime##*.}"
source_name="orderflow-pitr-source-$suffix"
target_name="orderflow-pitr-restore-$suffix"
image="$(sed -n 's/^  image: \(mysql:.*\)$/\1/p' docker-compose.yml)"
[[ "$image" == mysql:8.0.* ]]
export MYSQL_ROOT_PASSWORD="$(openssl rand -hex 18)"
cleanup() { docker rm -f -v "$source_name" "$target_name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
start_server() {
  docker run -d --network none --label orderflow.operation=pitr --name "$1" -e MYSQL_ROOT_PASSWORD "$image" \
    --server-id="$2" --log-bin=lab-bin --binlog-format=ROW --gtid-mode=ON --enforce-gtid-consistency=ON \
    --innodb-buffer-pool-size=96M --default-time-zone=+00:00 >/dev/null
}
mysql_tool() {
  local name="$1" tool="$2"
  shift 2
  docker exec -i "$name" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec "$@"' sh "$tool" "$@"
}
mysql_lab() {
  local name="$1"
  shift
  mysql_tool "$name" mysql -uroot --default-character-set=utf8mb4 "$@"
}
wait_server() {
  for ((attempt=0; attempt<90; attempt++)); do
    if mysql_lab "$1" -h127.0.0.1 -Nse 'SELECT 1' >/dev/null 2>&1; then return; fi
    sleep 1
  done
  docker logs --tail 20 "$1" >&2
  return 1
}
run() {
  echo "UTC $(date -u +%FT%TZ) — isolated containers, network=none, image=$image"
  start_server "$source_name" 91
  start_server "$target_name" 92
  wait_server "$source_name"
  wait_server "$target_name"
  mysql_lab "$source_name" < database/schema/001_tables.sql
  mysql_lab "$source_name" < database/backup/fixtures.sql
  mysql_lab "$source_name" -Nse 'SELECT VERSION(),@@log_bin,@@binlog_format,@@gtid_mode'
  mysql_tool "$source_name" mysqldump -uroot --single-transaction --source-data=2 \
    --set-gtid-purged=OFF --no-tablespaces --databases orderflow > "$runtime/snapshot.dump"
  coordinates="$(sed -nE "s/^-- CHANGE (REPLICATION SOURCE|MASTER) TO (SOURCE|MASTER)_LOG_FILE='([^']*)', (SOURCE|MASTER)_LOG_POS=([0-9]+);$/\3 \5/p" "$runtime/snapshot.dump")"
  read -r binlog start <<< "$coordinates"
  [[ "$binlog" =~ ^lab-bin\.[0-9]+$ && "$start" =~ ^[0-9]+$ ]] || { echo "无法读取快照位点" >&2; return 1; }
  echo "备份位点 $binlog:$start"
  mysql_lab "$source_name" < database/backup/add-order.sql
  read -r current stop rest <<<"$(mysql_lab "$source_name" -Nse 'SHOW MASTER STATUS')"
  [[ "$current" == "$binlog" && "$stop" =~ ^[0-9]+$ && "$stop" -gt "$start" ]]
  mysql_lab "$source_name" < database/backup/delete-order.sql
  echo '误删后源实例（应只有订单 2）'
  mysql_lab "$source_name" -t orderflow < database/backup/verify.sql
  [[ "$(mysql_lab "$source_name" -Nse 'SELECT GROUP_CONCAT(id ORDER BY id) FROM orderflow.orders')" == 2 ]]
  docker cp "$source_name:/var/lib/mysql/$binlog" "$runtime/$binlog"
  mysqlbinlog --version
  echo "mysqlbinlog --base64-output=DECODE-ROWS -vv --start-position=$stop /var/lib/mysql/$binlog"
  mysqlbinlog --base64-output=DECODE-ROWS -vv --start-position="$stop" \
    "$runtime/$binlog" > "$runtime/deletion.txt"
  grep -E "^# at |GTID|^### DELETE FROM|^###   @1=|^COMMIT" "$runtime/deletion.txt"
  grep -Fq '### DELETE FROM `orderflow`.`orders`' "$runtime/deletion.txt"
  echo "在第二台实例恢复备份并重放 mysqlbinlog --start-position=$start --stop-position=$stop --skip-gtids"
  mysql_lab "$target_name" < "$runtime/snapshot.dump"
  mysqlbinlog --skip-gtids --start-position="$start" --stop-position="$stop" \
     "$runtime/$binlog" | mysql_lab "$target_name"
  echo '恢复实例验证（备份中的订单 1 和之后正常新增的订单 2）'
  mysql_lab "$target_name" -t orderflow < database/backup/verify.sql
  [[ "$(mysql_lab "$target_name" -Nse 'SELECT GROUP_CONCAT(id ORDER BY id) FROM orderflow.orders')" == 1,2 ]]
  [[ "$(mysql_lab "$target_name" -Nse 'SELECT available FROM orderflow.inventory WHERE product_id=1')" == 7 ]]
  [[ "$(mysql_lab "$target_name" -Nse 'SELECT SUM(delta) FROM orderflow.inventory_records')" == -3 ]]
  echo 'PASS: 恢复两笔订单和对应明细，没有重放误删事务。'
}
run | tee evidence/binlog.txt
