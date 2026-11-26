#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/init.sh
source scripts/lib.sh
mkdir -p .runtime
scripts/stop-app.sh
mode="${1:-}"
if [[ -z "$mode" && "$(db primary -Nse 'SELECT COUNT(*) FROM orderflow.users')" == 0 ]]; then
  mode=small
fi
[[ -x .venv/bin/python ]] || python3 -m venv .venv
if ! .venv/bin/python -c 'import bcrypt, cryptography, faker, pymysql' 2>/dev/null; then
  .venv/bin/pip install -r database/seed/requirements.txt
fi
if [[ -n "$mode" ]]; then scripts/seed.sh "$mode"; fi
mvn -q -f backend/pom.xml package
(cd frontend && npm ci --no-audit --no-fund && npm run build)
export OF_EVIDENCE_DIR="$root/evidence"
api_pid=''
web_pid=''
cleanup_on_failure() {
  [[ -z "$api_pid" ]] || kill "$api_pid" 2>/dev/null || true
  [[ -z "$web_pid" ]] || kill "$web_pid" 2>/dev/null || true
  rm -f .runtime/api.pid .runtime/web.pid
}
trap cleanup_on_failure ERR
read -r api_pid web_pid <<< "$(python3 - "$root" "$WEB_PORT" <<'PYTHON'
import subprocess
import sys
from pathlib import Path

root, web_port = sys.argv[1:]
runtime = Path(root) / ".runtime"
commands = {
    "api": ["java", f"-Dorderflow.instance={root}", "-jar", "backend/target/orderflow-1.0.0.jar"],
    "web": ["node", f"{root}/frontend/node_modules/vite/bin/vite.js", "preview",
            f"{root}/frontend", "--host", "127.0.0.1", "--port", web_port, "--strictPort"],
}
pids = []
for service, command in commands.items():
    with (runtime / (service + ".log")).open("w") as log:
        process = subprocess.Popen(command, cwd=root, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    (runtime / (service + ".pid")).write_text(str(process.pid) + "\n")
    pids.append(process.pid)
print(*pids)
PYTHON
)"
for ((attempt=0; attempt<60; attempt++)); do
  if curl --noproxy '*' -fsS "http://127.0.0.1:$WEB_PORT/api/categories" >/dev/null 2>&1; then
    printf 'OrderFlow http://127.0.0.1:%s\n' "$WEB_PORT"
    exit 0
  fi
  kill -0 "$api_pid" "$web_pid"
  sleep 1
done
echo '应用未就绪，请查看 .runtime/api.log 和 .runtime/web.log' >&2
cleanup_on_failure
exit 1
