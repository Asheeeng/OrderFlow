#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
mkdir -p evidence
backup="$(db primary -Nse "SELECT @@global.slow_query_log,@@global.log_output")"
read -r slow output <<<"$backup"
[[ "$slow" =~ ^[01]$ && "$output" =~ ^[A-Z,]+$ ]]
restore_log() {
  db primary -e "SET GLOBAL slow_query_log=$slow; SET GLOBAL log_output='$output'" >/dev/null
}
trap restore_log EXIT
# Use a single session to restrict long_query_time=0 to this workload.
db primary -e "SET GLOBAL log_output='TABLE'; SET GLOBAL slow_query_log=ON"
run="ofslow_$(date +%s)"
{
  printf 'UTC %s\n' "$(date -u +%FT%TZ)"
  db primary -t orderflow < database/experiments/indexes.sql
  db primary --comments -t orderflow -e "
    SET SESSION long_query_time=0;
    SELECT /* ${run}_date_func */ COUNT(*) FROM orders WHERE DATE(created_at)='2026-11-11';
    SELECT /* ${run}_date_range */ COUNT(*) FROM orders WHERE created_at>='2026-11-11' AND created_at<'2026-11-12';
    SET SESSION long_query_time=10;
    SELECT start_time,query_time,lock_time,rows_sent,rows_examined,CONVERT(sql_text USING utf8mb4) AS sql_text
    FROM mysql.slow_log WHERE sql_text LIKE 'SELECT /* ${run}%' ORDER BY start_time"
} | tee evidence/indexes.txt
[[ "$(grep -cE "^[|].*SELECT /[*] ${run}_" evidence/indexes.txt)" -eq 2 ]]
