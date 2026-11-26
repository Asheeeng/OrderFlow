#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
mkdir -p evidence .runtime
wait_replica
db primary -B orderflow < database/experiments/counts.sql > .runtime/primary-counts.tsv
db replica -B orderflow < database/experiments/counts.sql > .runtime/replica-counts.tsv
diff -u .runtime/primary-counts.tsv .runtime/replica-counts.tsv
db primary -B orderflow < database/experiments/integrity.sql > .runtime/primary-integrity.tsv
db replica -B orderflow < database/experiments/integrity.sql > .runtime/replica-integrity.tsv
diff -u .runtime/primary-integrity.tsv .runtime/replica-integrity.tsv
{
  printf 'UTC %s\n' "$(date -u +%FT%TZ)"
  db primary -Nse 'SELECT VERSION(),@@gtid_mode,@@binlog_format,@@server_id'
  echo '主从精确行数一致'
  cat .runtime/primary-counts.tsv
  awk -F '\t' 'NR>1 {n+=$2} END {print "total_rows\t" n}' .runtime/primary-counts.tsv
  echo '主从完整性查询结果一致'
  cat .runtime/primary-integrity.tsv
} | tee evidence/data-check.txt
awk -F '\t' 'NR>1 && $2+0!=0 {bad=1} END {exit bad}' .runtime/primary-integrity.tsv
if [[ "${1:-}" == --million ]]; then
  awk -F '\t' 'NR>1 {n+=$2; if($1=="order_items") items=$2}
    END {exit !(n>=1000000 && items>=1000000)}' .runtime/primary-counts.tsv
fi
