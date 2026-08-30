#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODE="${1:-all}"
MAVEN_BIN="${MAVEN_BIN:-./mvnw}"
SKIP_BUILD="${STAGE4_SKIP_BUILD:-false}"
KEEP_INFRA="${STAGE4_KEEP_INFRA:-false}"
ORIGINAL_RUNNING_SERVICES=""
INFRA_CAPTURED=false

fail() {
  echo "$*" >&2
  exit 1
}

normalize_boolean() {
  case "$2" in
    true|TRUE|1|yes|YES) printf 'true' ;;
    false|FALSE|0|no|NO) printf 'false' ;;
    *) fail "$1 must be true or false" ;;
  esac
}

service_was_running() {
  grep -Fxq "$1" <<< "${ORIGINAL_RUNNING_SERVICES}"
}

service_is_running() {
  docker compose -f "${PROJECT_DIR}/compose.yml" ps --status running --services \
    | grep -Fxq "$1"
}

restore_infrastructure() {
  local service
  [[ "${INFRA_CAPTURED}" == "true" && "${KEEP_INFRA}" == "false" ]] || return 0
  for service in redis rabbitmq postgres minio; do
    if ! service_was_running "${service}" && service_is_running "${service}"; then
      docker compose -f "${PROJECT_DIR}/compose.yml" stop "${service}" >/dev/null 2>&1 || true
    fi
  done
}

wait_for_infrastructure() {
  local attempt
  for ((attempt = 1; attempt <= 90; attempt++)); do
    if docker compose exec -T postgres pg_isready -U "${DB_USERNAME:-enjoy}" -d "${DB_NAME:-enjoy_agent}" >/dev/null 2>&1 \
      && [[ "$(docker compose exec -T redis redis-cli ping 2>/dev/null || true)" == "PONG" ]] \
      && docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping >/dev/null 2>&1 \
      && curl --fail --silent --max-time 2 http://127.0.0.1:9000/minio/health/ready >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "Infrastructure did not become ready within 90 seconds"
}

trap restore_infrastructure EXIT

case "${MODE}" in
  all|faults|tuning) ;;
  *) fail "Usage: $0 [all|faults|tuning]" ;;
esac

SKIP_BUILD="$(normalize_boolean STAGE4_SKIP_BUILD "${SKIP_BUILD}")"
KEEP_INFRA="$(normalize_boolean STAGE4_KEEP_INFRA "${KEEP_INFRA}")"

for command_name in docker java python3 curl; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || fail "Required executable not found: ${command_name}"
done
docker compose version >/dev/null

if [[ "${MODE}" != "faults" ]]; then
  JMETER_BIN="${JMETER_BIN:-jmeter}"
  [[ -x "${JMETER_BIN}" ]] || command -v "${JMETER_BIN}" >/dev/null 2>&1 \
    || fail "JMeter not found; set JMETER_BIN to Apache JMeter 5.6.3"
  export JMETER_BIN
fi

cd "${PROJECT_DIR}"
if [[ "${SKIP_BUILD}" == "false" ]]; then
  if [[ ! -x "${MAVEN_BIN}" ]] && ! command -v "${MAVEN_BIN}" >/dev/null 2>&1; then
    fail "Maven not found; set MAVEN_BIN to an executable Maven path"
  fi
  "${MAVEN_BIN}" clean package
fi

APP_JAR="${PROJECT_DIR}/target/enjoy-agent-0.0.1-SNAPSHOT.jar"
[[ -r "${APP_JAR}" ]] || fail "Executable JAR not found: ${APP_JAR}"

FIXTURE="${PROJECT_DIR}/performance/fixtures/generated/document-64k.txt"
if [[ ! -r "${FIXTURE}" ]]; then
  python3 "${PROJECT_DIR}/performance/fixtures/generate-document.py" \
    --bytes 65536 \
    --output "${FIXTURE}"
fi

ORIGINAL_RUNNING_SERVICES="$(docker compose ps --status running --services 2>/dev/null || true)"
INFRA_CAPTURED=true
docker compose up -d postgres redis minio rabbitmq
wait_for_infrastructure

if [[ "${MODE}" == "all" || "${MODE}" == "faults" ]]; then
  "${PROJECT_DIR}/performance/fault-injection/run-fault-drills.sh" all
fi

if [[ "${MODE}" == "all" || "${MODE}" == "tuning" ]]; then
  "${PROJECT_DIR}/performance/tuning/run-ingest-matrix.sh"
fi

echo "Stage 4 ${MODE} workflow completed. Generated evidence is under performance/results/."
