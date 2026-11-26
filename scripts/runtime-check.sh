#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
mkdir -p evidence
fixture() { .venv/bin/python -c 'import json,sys; print(json.load(open(".runtime/last-api-test.json"))[sys.argv[1]])' "$1"; }
redis() {
  docker compose exec -T redis sh -c 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli "$@"' sh "$@"
}
detail() { curl --noproxy '*' -fsS --max-time 15 "http://127.0.0.1:$WEB_PORT/api/products/$1"; }
restore() { docker compose up -d --wait redis >/dev/null 2>&1 || true; }
marker="orderflow:acceptance:$(openssl rand -hex 6)"
product="$(fixture product)"
order="$(fixture order)"
[[ "$product" =~ ^[0-9]+$ && "$order" =~ ^[0-9]+$ ]]
trap restore EXIT
run() {
  printf 'UTC %s\n' "$(date -u +%FT%TZ)"
  first="$(detail "$product")"
  key="$(redis --scan --pattern "orderflow:product:$product:*" | head -1)"
  [[ -n "$key" ]]
  ttl="$(redis TTL "$key")"
  [[ "$ttl" -gt 0 && "$ttl" -le 90 ]]
  printf 'PASS: 详情已缓存，实测 TTL 为 %s 秒（上限 90）。\n' "$ttl"
  redis SET "$marker" persistence-probe >/dev/null
  docker compose stop redis
  second="$(detail "$product")"
  FIRST="$first" SECOND="$second" .venv/bin/python -c 'import os,json; assert json.loads(os.environ["FIRST"]) == json.loads(os.environ["SECOND"])'
  echo 'PASS: Redis 容器停止后商品详情仍为 HTTP 200，业务字段与在线读取一致。'
  docker compose up -d --wait redis
  [[ "$(redis GET "$marker")" == persistence-probe ]]
  echo 'PASS: Redis AOF 在停止再启动后保留验收标记。'
  redis DEL "$marker" >/dev/null

  wait_replica
  before="$(db primary -Nse "SELECT id,status,total FROM orderflow.orders WHERE id=$order")"
  [[ -n "$before" ]]
  docker compose stop primary replica redis
  docker compose up -d --wait primary replica redis
  wait_replica
  [[ "$(db primary -Nse "SELECT id,status,total FROM orderflow.orders WHERE id=$order")" == "$before" ]]
  [[ "$(db replica -Nse "SELECT id,status,total FROM orderflow.orders WHERE id=$order")" == "$before" ]]
  [[ "$(db replica -Nse 'SELECT @@super_read_only')" == 1 ]]
  detail "$product" >/dev/null
  printf 'PASS: Compose 三服务停启后，订单 %s 主从一致，从库只读和前端代理查询恢复。\n' "$order"
}
run 2>&1 | tee evidence/runtime-acceptance.txt
