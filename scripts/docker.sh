#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
project="${ORDERFLOW_PROJECT:-orderflow-docker}"
env_file="${ORDERFLOW_ENV_FILE:-.env.docker}"

die() { printf '%s\n' "$*" >&2; exit 1; }
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ && "$project" != orderflow ]] ||
  die 'Use a distinct Compose project name. orderflow is reserved for the host deployment.'
[[ "${env_file##*/}" != .env ]] ||
  die 'The host .env is reserved. Use .env.docker or a different Docker environment file.'

setting() {
  [[ -f "$env_file" ]] || die "No $env_file. Run scripts/docker.sh prepare or start."
  local line
  line="$(grep -E "^$1=" "$env_file")" || die "$1 is missing from $env_file."
  [[ "$line" != *$'\n'* ]] || die "$1 is defined more than once."
  printf '%s\n' "${line#*=}" | tr -d '\r'
}

random_hex() {
  LC_ALL=C od -An -v -tx1 -N "$1" /dev/urandom | tr -d '[:space:]'
}

prepare() {
  local requested="${1:-}"
  [[ -z "$requested" || "$requested" == small || "$requested" == million ]] ||
    die 'Data mode must be small or million.'
  if [[ ! -f "$env_file" ]]; then
    (
      umask 077
      temporary="$(mktemp "${env_file}.XXXXXX")"
      trap 'rm -f "$temporary"' EXIT
      while IFS= read -r line; do
        case "$line" in
          SEED_MODE=*) printf 'SEED_MODE=%s\n' "${requested:-million}" ;;
          DB_REPL_PASSWORD=) printf '%s%s\n' "$line" "$(random_hex 16)" ;;
          *_PASSWORD=) printf '%s%s\n' "$line" "$(random_hex 18)" ;;
          *) printf '%s\n' "$line" ;;
        esac
      done < .env.docker.example > "$temporary"
      ln "$temporary" "$env_file" 2>/dev/null || [[ -f "$env_file" ]]
    )
  fi
  local existing
  existing="$(setting SEED_MODE)"
  [[ "$existing" == small || "$existing" == million ]] || die 'Invalid SEED_MODE in Docker environment file.'
  if [[ -n "$requested" && "$requested" != "$existing" ]]; then
    die "This environment uses $existing. Use another project name and environment file to select $requested."
  fi
}

compose() (
  # The per-deployment env file also wins if the caller exported host settings.
  unset SEED_MODE WEB_PORT MYSQL_PRIMARY_PORT MYSQL_REPLICA_PORT
  unset DB_ROOT_PASSWORD DB_APP_PASSWORD DB_INSPECT_PASSWORD DB_REPL_PASSWORD DEMO_PASSWORD REDIS_PASSWORD
  docker compose --project-name "$project" --file compose.docker.yml --env-file "$env_file" "$@"
)

check_docker() {
  [[ -f "$env_file" ]] || die "No $env_file. Run scripts/docker.sh start first."
  [[ "$(docker info --format '{{.OSType}}' 2>/dev/null)" == linux ]] ||
    die 'Start Docker Desktop and select the Linux container engine.'
}

case "${1:-}" in
  prepare)
    prepare "${2:-}"
    printf 'Prepared %s (mode: %s)\n' "$env_file" "$(setting SEED_MODE)"
    ;;
  start)
    prepare "${2:-}"
    check_docker
    printf 'Starting %s (mode: %s). First import can take several minutes.\n' "$project" "$(setting SEED_MODE)"
    if ! compose up --build -d --wait --wait-timeout 3600; then
      compose ps -a >&2 || true
      die 'Startup failed. See scripts/docker.sh logs init.'
    fi
    printf 'OrderFlow http://127.0.0.1:%s/\n' "$(setting WEB_PORT)"
    ;;
  stop)
    check_docker
    compose stop
    ;;
  status)
    check_docker
    compose ps -a
    ;;
  logs)
    check_docker
    compose logs --tail=80 "${2:-init}"
    ;;
  password)
    case "${2:-}" in
      demo) setting DEMO_PASSWORD ;;
      navicat) setting DB_INSPECT_PASSWORD ;;
      *) die 'Select a password with: scripts/docker.sh password demo|navicat' ;;
    esac
    ;;
  *)
    die 'Usage: scripts/docker.sh prepare [small|million] | start [small|million] | stop | status | logs [service] | password demo|navicat'
    ;;
esac
