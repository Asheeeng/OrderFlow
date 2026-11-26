#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
mkdir -p .runtime
for port in "$MYSQL_PRIMARY_PORT" "$MYSQL_REPLICA_PORT"; do
  echo "验证 127.0.0.1:$port / of_inspect / orderflow"
  inspect() { MYSQL_PWD="$DB_INSPECT_PASSWORD" mysql --protocol=tcp -h127.0.0.1 -P "$port" -uof_inspect -Dorderflow "$@"; }
  inspect -t < database/experiments/inspect.sql
  if inspect -e "UPDATE users SET active=1 WHERE 1=0" 2>.runtime/inspect-deny.err; then
    echo "FAIL: of_inspect 存在写权限" >&2
    exit 1
  fi
  code="$(sed -nE 's/^ERROR ([0-9]+).*/\1/p' .runtime/inspect-deny.err)"
  [[ "$code" == 1142 || "$port" == "$MYSQL_REPLICA_PORT" && "$code" == 1290 ]]
  printf 'PASS: SQL 查询成功、尝试 UPDATE 被拒绝 %s。\n' "$code"
done
