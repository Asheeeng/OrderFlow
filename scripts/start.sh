#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/init.sh
source scripts/lib.sh
mkdir -p .runtime
scripts/stop-app.sh
if [[ "$(db primary -Nse 'SELECT COUNT(*) FROM orderflow.users')" == 0 ]]; then
  [[ -x .venv/bin/python ]] || python3 -m venv .venv
  .venv/bin/pip install -r database/seed/requirements.txt
  scripts/seed.sh "${1:-small}"
fi
mvn -q -f backend/pom.xml package
(cd frontend && npm ci && npm run build)
export OF_EVIDENCE_DIR="$root/evidence"
api_pid=''
web_pid=''
cleanup_on_failure() {
  [[ -z "$api_pid" ]] || kill "$api_pid" 2>/dev/null || true
  [[ -z "$web_pid" ]] || kill "$web_pid" 2>/dev/null || true
  rm -f .runtime/api.pid .runtime/web.pid
}
trap cleanup_on_failure ERR
nohup java -Dorderflow.instance="$root" -jar backend/target/orderflow-1.0.0-SNAPSHOT.jar > .runtime/api.log 2>&1 </dev/null &
api_pid=$!
printf '%s\n' "$api_pid" > .runtime/api.pid
nohup node "$root/frontend/node_modules/vite/bin/vite.js" preview "$root/frontend" --host 127.0.0.1 --port "$WEB_PORT" --strictPort > .runtime/web.log 2>&1 </dev/null &
web_pid=$!
printf '%s\n' "$web_pid" > .runtime/web.pid
for ((attempt=0; attempt<60; attempt++)); do
  if curl -fsS "http://127.0.0.1:$WEB_PORT/api/categories" >/dev/null; then
    printf 'OrderFlow http://127.0.0.1:%s\n' "$WEB_PORT"
    exit 0
  fi
  kill -0 "$api_pid" "$web_pid"
  sleep 1
done
echo '应用未就绪，请查看 .runtime/api.log 和 .runtime/web.log' >&2
cleanup_on_failure
exit 1
