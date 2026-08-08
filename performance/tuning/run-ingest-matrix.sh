#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MATRIX_FILE="${TUNING_CASES_FILE:-${SCRIPT_DIR}/ingest-matrix.tsv}"
APP_JAR="${TUNING_APP_JAR:-${PROJECT_DIR}/target/enjoy-agent-0.0.1-SNAPSHOT.jar}"
DOCUMENT_FILE="${PERF_DOCUMENT_FILE:-${PROJECT_DIR}/performance/fixtures/generated/document-64k.txt}"
JMETER_BIN="${JMETER_BIN:-jmeter}"
APP_HOST="${TUNING_APP_HOST:-127.0.0.1}"
APP_PORT="${TUNING_APP_PORT:-8080}"
MOCK_HOST="${TUNING_MOCK_HOST:-127.0.0.1}"
MOCK_PORT="${TUNING_MOCK_PORT:-18080}"
MOCK_DIMENSIONS="${TUNING_MOCK_DIMENSIONS:-1024}"
MOCK_DELAY_MS="${TUNING_MOCK_DELAY_MS:-50}"
REPETITIONS="${TUNING_REPETITIONS:-3}"
THREADS="${TUNING_THREADS:-16}"
LOOPS="${TUNING_LOOPS:-1}"
WARMUP_DOCUMENTS="${TUNING_WARMUP_DOCUMENTS:-4}"
POLL_INTERVAL_MS="${TUNING_POLL_INTERVAL_MS:-100}"
POLL_TIMEOUT_SECONDS="${TUNING_POLL_TIMEOUT_SECONDS:-180}"
STARTUP_TIMEOUT_SECONDS="${TUNING_STARTUP_TIMEOUT_SECONDS:-90}"
HEAP_MB="${TUNING_HEAP_MB:-1024}"
MANAGE_MOCK="${TUNING_MANAGE_MOCK:-true}"
AUTO_PREPARE_DATASET="${TUNING_AUTO_PREPARE_DATASET:-true}"
CONTINUE_ON_ERROR="${TUNING_CONTINUE_ON_ERROR:-false}"
DRY_RUN="${TUNING_DRY_RUN:-false}"
BASELINE_CASE="${TUNING_BASELINE_CASE:-baseline-w2-p1-b10}"
MAX_BALANCED_IN_FLIGHT="${TUNING_MAX_BALANCED_IN_FLIGHT:-4}"
SAMPLE_INTERVAL_MS="${TUNING_SAMPLE_INTERVAL_MS:-250}"
SAMPLE_HTTP_TIMEOUT_MS="${TUNING_SAMPLE_HTTP_TIMEOUT_MS:-1000}"
PERF_EMAIL="${PERF_EMAIL:-perf-baseline@example.test}"
PERF_PASSWORD="${PERF_PASSWORD:-PerfPassw0rd!}"
KNOWLEDGE_BASE_ID="${PERF_KNOWLEDGE_BASE_ID:-}"
POSTGRES_SERVICE="${POSTGRES_SERVICE:-postgres}"
DB_NAME_VALUE="${DB_NAME:-enjoy_agent}"
DB_USERNAME_VALUE="${DB_USERNAME:-enjoy}"
RABBIT_MANAGEMENT_HOST="${TUNING_RABBIT_MANAGEMENT_HOST:-127.0.0.1}"
RABBIT_MANAGEMENT_PORT="${RABBITMQ_MANAGEMENT_PORT:-15672}"
RABBIT_QUEUE_URL="${TUNING_RABBIT_QUEUE_URL:-http://${RABBIT_MANAGEMENT_HOST}:${RABBIT_MANAGEMENT_PORT}/api/queues/%2F/knowledge.document.ingest.queue}"
RABBIT_USERNAME_VALUE="${RABBITMQ_USERNAME:-guest}"
RABBIT_PASSWORD_VALUE="${RABBITMQ_PASSWORD:-guest}"

APP_PID=""
MOCK_PID=""
SAMPLER_PID=""
SESSION_DIR=""
MANIFEST=""

fail() {
  echo "$*" >&2
  exit 1
}

require_positive_integer() {
  local name="$1"
  local value="$2"
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] || fail "${name} must be a positive integer"
}

require_nonnegative_integer() {
  local name="$1"
  local value="$2"
  [[ "${value}" =~ ^[0-9]+$ ]] || fail "${name} must be a non-negative integer"
}

normalize_boolean() {
  case "$2" in
    true|TRUE|1|yes|YES) printf 'true' ;;
    false|FALSE|0|no|NO) printf 'false' ;;
    *) fail "$1 must be true or false" ;;
  esac
}

stop_process() {
  local pid="$1"
  local attempt
  if [[ -z "${pid}" ]] || ! kill -0 "${pid}" 2>/dev/null; then
    return
  fi
  kill "${pid}" 2>/dev/null || true
  for ((attempt = 0; attempt < 20; attempt++)); do
    if ! kill -0 "${pid}" 2>/dev/null; then
      wait "${pid}" 2>/dev/null || true
      return
    fi
    sleep 1
  done
  kill -KILL "${pid}" 2>/dev/null || true
  wait "${pid}" 2>/dev/null || true
}

cleanup() {
  stop_process "${SAMPLER_PID}"
  SAMPLER_PID=""
  stop_process "${APP_PID}"
  APP_PID=""
  stop_process "${MOCK_PID}"
  MOCK_PID=""
}

trap cleanup EXIT
trap 'exit 130' INT TERM

wait_for_http() {
  local url="$1"
  local pid="$2"
  local timeout="$3"
  local log_file="$4"
  local attempt
  for ((attempt = 0; attempt < timeout; attempt++)); do
    if curl --fail --silent --show-error --max-time 2 "${url}" >/dev/null 2>&1; then
      return
    fi
    if [[ -n "${pid}" ]] && ! kill -0 "${pid}" 2>/dev/null; then
      echo "Process exited before ${url} became ready" >&2
      tail -n 80 "${log_file}" >&2 || true
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for ${url}" >&2
  tail -n 80 "${log_file}" >&2 || true
  return 1
}

start_mock() {
  local mock_log="${SESSION_DIR}/embedding-mock.log"
  if [[ "${MANAGE_MOCK}" == "false" ]]; then
    wait_for_http "http://${MOCK_HOST}:${MOCK_PORT}/health" "" 1 "${mock_log}" \
      || fail "External embedding mock is not healthy"
    return
  fi
  if curl --silent --max-time 1 "http://${MOCK_HOST}:${MOCK_PORT}/health" >/dev/null 2>&1; then
    fail "Port ${MOCK_PORT} already serves an embedding mock; set TUNING_MANAGE_MOCK=false to reuse it"
  fi
  python3 "${PROJECT_DIR}/performance/mock/openai_embedding_server.py" \
    --host "${MOCK_HOST}" \
    --port "${MOCK_PORT}" \
    --dimensions "${MOCK_DIMENSIONS}" \
    --delay-ms "${MOCK_DELAY_MS}" \
    > "${mock_log}" 2>&1 &
  MOCK_PID=$!
  wait_for_http "http://${MOCK_HOST}:${MOCK_PORT}/health" "${MOCK_PID}" 30 "${mock_log}" \
    || fail "Embedding mock did not become ready"
}

validate_mock_config() {
  curl --fail --silent --show-error "http://${MOCK_HOST}:${MOCK_PORT}/stats" \
    | EXPECTED_DIMENSIONS="${MOCK_DIMENSIONS}" EXPECTED_DELAY_MS="${MOCK_DELAY_MS}" python3 -c '
import json, os, sys
stats = json.load(sys.stdin)
expected_dimensions = int(os.environ["EXPECTED_DIMENSIONS"])
expected_delay_ms = int(os.environ["EXPECTED_DELAY_MS"])
actual_dimensions = stats.get("configured_dimensions")
actual_delay_ms = stats.get("configured_delay_ms")
if actual_dimensions != expected_dimensions:
    raise SystemExit(f"Embedding mock dimensions={actual_dimensions}, expected {expected_dimensions}")
if actual_delay_ms != expected_delay_ms:
    raise SystemExit(f"Embedding mock delay_ms={actual_delay_ms}, expected {expected_delay_ms}")
'
}

start_app() {
  local case_id="$1"
  local workers="$2"
  local prefetch="$3"
  local batch_size="$4"
  local app_log="${SESSION_DIR}/${case_id}-application.log"

  if curl --silent --max-time 1 "http://${APP_HOST}:${APP_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
    fail "An application is already running on ${APP_HOST}:${APP_PORT}; stop it before the matrix"
  fi
  SPRING_PROFILES_ACTIVE=perf \
  SERVER_PORT="${APP_PORT}" \
  APP_MODEL_GATEWAY_OPENAI_BASE_URL="http://${MOCK_HOST}:${MOCK_PORT}" \
  APP_PLATFORM_OPENAI_API_KEY=performance-test-key \
  APP_KNOWLEDGE_EMBEDDING_DIMENSIONS="${MOCK_DIMENSIONS}" \
  APP_KNOWLEDGE_EMBEDDING_BATCH_SIZE="${batch_size}" \
  APP_KNOWLEDGE_ASYNC_INGEST_ENABLED=true \
  APP_KNOWLEDGE_INGEST_WORKER_CONCURRENCY="${workers}" \
  APP_KNOWLEDGE_INGEST_PREFETCH="${prefetch}" \
  APP_KNOWLEDGE_SEARCH_ENABLED=false \
  APP_BILLING_ENABLED=false \
  java "-Xms${HEAP_MB}m" "-Xmx${HEAP_MB}m" -XX:+UseG1GC -jar "${APP_JAR}" \
    > "${app_log}" 2>&1 &
  APP_PID=$!
  wait_for_http \
    "http://${APP_HOST}:${APP_PORT}/actuator/health/readiness" \
    "${APP_PID}" \
    "${STARTUP_TIMEOUT_SECONDS}" \
    "${app_log}" \
    || fail "Application did not become ready for ${case_id}"
}

prepare_dataset() {
  local register_payload
  local resolved_id
  if [[ -n "${KNOWLEDGE_BASE_ID}" ]]; then
    require_positive_integer PERF_KNOWLEDGE_BASE_ID "${KNOWLEDGE_BASE_ID}"
    return
  fi
  [[ "${AUTO_PREPARE_DATASET}" == "true" ]] \
    || fail "PERF_KNOWLEDGE_BASE_ID is required when TUNING_AUTO_PREPARE_DATASET=false"
  command -v docker >/dev/null 2>&1 || fail "docker is required to seed the tuning dataset"

  register_payload=$(PERF_EMAIL="${PERF_EMAIL}" PERF_PASSWORD="${PERF_PASSWORD}" python3 -c \
    'import json, os; print(json.dumps({"email": os.environ["PERF_EMAIL"], "password": os.environ["PERF_PASSWORD"], "displayName": "Performance Tuning"}))')
  curl --silent --show-error --output /dev/null \
    --header 'Content-Type: application/json' \
    --data "${register_payload}" \
    "http://${APP_HOST}:${APP_PORT}/api/auth/register" || true

  PERF_EMAIL="${PERF_EMAIL}" \
  POSTGRES_SERVICE="${POSTGRES_SERVICE}" \
  DB_NAME="${DB_NAME_VALUE}" \
  DB_USERNAME="${DB_USERNAME_VALUE}" \
    "${PROJECT_DIR}/performance/seed/run-knowledge-ingest-seed.sh" \
    > "${SESSION_DIR}/seed-output.txt"

  resolved_id=$(printf '%s\n' \
    "SELECT kb.id FROM knowledge_base kb JOIN tenant_member tm ON tm.tenant_id = kb.tenant_id JOIN app_user u ON u.id = tm.user_id WHERE u.email = :'email' AND tm.status = 'ACTIVE' AND kb.name = 'perf-knowledge-ingest' ORDER BY kb.id LIMIT 1;" \
    | docker compose -f "${PROJECT_DIR}/compose.yml" exec -T "${POSTGRES_SERVICE}" \
        psql -U "${DB_USERNAME_VALUE}" -d "${DB_NAME_VALUE}" -Atq -v email="${PERF_EMAIL}")
  [[ "${resolved_id}" =~ ^[1-9][0-9]*$ ]] \
    || fail "Could not resolve perf-knowledge-ingest knowledge base ID"
  KNOWLEDGE_BASE_ID="${resolved_id}"
  echo "Resolved knowledge base ID: ${KNOWLEDGE_BASE_ID}"
}

append_manifest() {
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$@" >> "${MANIFEST}"
}

write_unavailable_runtime_sample() {
  local output="$1"
  local reason="$2"
  RUNTIME_OUTPUT="${output}" RUNTIME_REASON="${reason}" python3 -c '
import json, os, pathlib
payload = {
    "schema_version": 1,
    "status": "unavailable",
    "fatal_error": os.environ["RUNTIME_REASON"],
    "sample_attempts": 0,
    "actuator": {"status": "unavailable", "successful_samples": 0, "failed_samples": 0},
    "rabbitmq": {"status": "unavailable", "successful_samples": 0, "failed_samples": 0},
    "error_counts": {os.environ["RUNTIME_REASON"]: 1},
    "samples": [],
}
pathlib.Path(os.environ["RUNTIME_OUTPUT"]).write_text(
    json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
'
}

start_runtime_sampler() {
  local output="$1"
  RABBITMQ_USERNAME="${RABBIT_USERNAME_VALUE}" \
  RABBITMQ_PASSWORD="${RABBIT_PASSWORD_VALUE}" \
  python3 "${SCRIPT_DIR}/sample-ingest-runtime.py" \
    --actuator-url "http://${APP_HOST}:${APP_PORT}/actuator/prometheus" \
    --rabbit-url "${RABBIT_QUEUE_URL}" \
    --interval-ms "${SAMPLE_INTERVAL_MS}" \
    --http-timeout-ms "${SAMPLE_HTTP_TIMEOUT_MS}" \
    --output "${output}" \
    > "${output%.json}.log" 2>&1 &
  SAMPLER_PID=$!
}

stop_runtime_sampler() {
  local output="$1"
  stop_process "${SAMPLER_PID}"
  SAMPLER_PID=""
  if [[ ! -s "${output}" ]]; then
    write_unavailable_runtime_sample "${output}" "runtime sampler exited without output"
  fi
}

run_documents() {
  local case_id="$1"
  local workers="$2"
  local prefetch="$3"
  local batch_size="$4"
  local repetition="$5"
  local phase="$6"
  local run_threads="$7"
  local run_loops="${LOOPS}"
  local variant="${SESSION_TAG}-${case_id}-${phase}-r${repetition}"
  local runner_log="${SESSION_DIR}/${variant}-runner.log"
  local result_dir=""
  local runner_status
  local runtime_sample="${SESSION_DIR}/${variant}-runtime-samples.json"

  if [[ "${phase}" == "warmup" ]]; then
    run_loops=1
  else
    start_runtime_sampler "${runtime_sample}"
  fi

  set +e
  PERF_HOST="${APP_HOST}" \
  PERF_PORT="${APP_PORT}" \
  PERF_EMAIL="${PERF_EMAIL}" \
  PERF_PASSWORD="${PERF_PASSWORD}" \
  PERF_KNOWLEDGE_BASE_ID="${KNOWLEDGE_BASE_ID}" \
  PERF_DOCUMENT_FILE="${DOCUMENT_FILE}" \
  PERF_THREADS="${run_threads}" \
  PERF_LOOPS="${run_loops}" \
  PERF_RAMP_SECONDS=0 \
  PERF_EXPECTED_STATUS=202 \
  PERF_POLL_UNTIL_READY=true \
  PERF_POLL_INTERVAL_MS="${POLL_INTERVAL_MS}" \
  PERF_POLL_TIMEOUT_SECONDS="${POLL_TIMEOUT_SECONDS}" \
  PERF_MAX_ERROR_RATE=0 \
  PERF_VARIANT="${variant}" \
  PERF_EMBEDDING_STATS_URL="http://${MOCK_HOST}:${MOCK_PORT}/stats" \
  PERF_RESET_EMBEDDING_STATS=true \
  JMETER_BIN="${JMETER_BIN}" \
    "${PROJECT_DIR}/performance/jmeter/run-document-upload.sh" \
    2>&1 | tee "${runner_log}"
  runner_status=${PIPESTATUS[0]}
  set -e

  if [[ "${phase}" == "formal" ]]; then
    stop_runtime_sampler "${runtime_sample}"
  fi

  result_dir=$(sed -n 's/^Results: //p' "${runner_log}" | tail -n 1)
  if [[ -z "${result_dir}" ]]; then
    result_dir=$(find "${PROJECT_DIR}/performance/results" -maxdepth 1 -type d \
      -name "${variant}-*" -print | sort | tail -n 1)
  fi
  if [[ "${runner_status}" -eq 0 && -n "${result_dir}" ]]; then
    if [[ "${phase}" == "formal" ]]; then
      cp "${runtime_sample}" "${result_dir}/runtime-samples.json"
    fi
    {
      echo "tuning_case=${case_id}"
      echo "worker_concurrency=${workers}"
      echo "prefetch=${prefetch}"
      echo "embedding_batch_size=${batch_size}"
      echo "matrix_repetition=${repetition}"
      echo "matrix_phase=${phase}"
    } >> "${result_dir}/metadata.txt"
    append_manifest \
      "${case_id}" "${workers}" "${prefetch}" "${batch_size}" \
      "${repetition}" "${phase}" success "${result_dir}"
    return
  fi

  append_manifest \
    "${case_id}" "${workers}" "${prefetch}" "${batch_size}" \
    "${repetition}" "${phase}" failed "${result_dir}"
  if [[ "${CONTINUE_ON_ERROR}" == "false" ]]; then
    fail "Tuning run failed: ${variant}; see ${runner_log}"
  fi
}

validate_case_value() {
  local case_id="$1"
  local workers="$2"
  local prefetch="$3"
  local batch_size="$4"
  [[ "${case_id}" =~ ^[A-Za-z0-9._-]+$ ]] || fail "Invalid case_id: ${case_id}"
  case "${workers}" in 2|4|8) ;; *) fail "${case_id}: worker_concurrency must be 2, 4 or 8" ;; esac
  case "${prefetch}" in 1|2|4) ;; *) fail "${case_id}: prefetch must be 1, 2 or 4" ;; esac
  case "${batch_size}" in 5|10|20) ;; *) fail "${case_id}: embedding_batch_size must be 5, 10 or 20" ;; esac
}

validate_matrix() {
  local seen_case_ids="|"
  local case_count=0
  local baseline_found=false
  local case_id workers prefetch batch_size note
  local expected_header=$'case_id\tworker_concurrency\tprefetch\tembedding_batch_size\tnote'
  local actual_header

  [[ -f "${MATRIX_FILE}" && -r "${MATRIX_FILE}" ]] || fail "Matrix file not found: ${MATRIX_FILE}"
  actual_header=$(head -n 1 "${MATRIX_FILE}")
  [[ "${actual_header}" == "${expected_header}" ]] || fail "Unexpected matrix header in ${MATRIX_FILE}"
  while IFS=$'\t' read -r case_id workers prefetch batch_size note; do
    [[ -n "${case_id}" ]] || continue
    [[ "${case_id}" == \#* ]] && continue
    validate_case_value "${case_id}" "${workers}" "${prefetch}" "${batch_size}"
    case "${seen_case_ids}" in
      *"|${case_id}|"*) fail "Duplicate case_id in matrix: ${case_id}" ;;
    esac
    seen_case_ids="${seen_case_ids}${case_id}|"
    case_count=$((case_count + 1))
    if [[ "${case_id}" == "${BASELINE_CASE}" ]]; then
      baseline_found=true
    fi
    printf '%-26s workers=%s prefetch=%s batch=%s  %s\n' \
      "${case_id}" "${workers}" "${prefetch}" "${batch_size}" "${note}"
  done < <(tail -n +2 "${MATRIX_FILE}")
  [[ "${case_count}" -gt 0 ]] || fail "Matrix contains no cases"
  [[ "${baseline_found}" == "true" ]] || fail "Baseline case is absent from matrix: ${BASELINE_CASE}"
}

for pair in \
  "TUNING_APP_PORT:${APP_PORT}" \
  "TUNING_MOCK_PORT:${MOCK_PORT}" \
  "TUNING_MOCK_DIMENSIONS:${MOCK_DIMENSIONS}" \
  "TUNING_REPETITIONS:${REPETITIONS}" \
  "TUNING_THREADS:${THREADS}" \
  "TUNING_LOOPS:${LOOPS}" \
  "TUNING_POLL_INTERVAL_MS:${POLL_INTERVAL_MS}" \
  "TUNING_POLL_TIMEOUT_SECONDS:${POLL_TIMEOUT_SECONDS}" \
  "TUNING_STARTUP_TIMEOUT_SECONDS:${STARTUP_TIMEOUT_SECONDS}" \
  "TUNING_HEAP_MB:${HEAP_MB}" \
  "TUNING_MAX_BALANCED_IN_FLIGHT:${MAX_BALANCED_IN_FLIGHT}" \
  "TUNING_SAMPLE_INTERVAL_MS:${SAMPLE_INTERVAL_MS}" \
  "TUNING_SAMPLE_HTTP_TIMEOUT_MS:${SAMPLE_HTTP_TIMEOUT_MS}"; do
  require_positive_integer "${pair%%:*}" "${pair#*:}"
done
require_nonnegative_integer TUNING_MOCK_DELAY_MS "${MOCK_DELAY_MS}"
require_nonnegative_integer TUNING_WARMUP_DOCUMENTS "${WARMUP_DOCUMENTS}"
MANAGE_MOCK=$(normalize_boolean TUNING_MANAGE_MOCK "${MANAGE_MOCK}")
AUTO_PREPARE_DATASET=$(normalize_boolean TUNING_AUTO_PREPARE_DATASET "${AUTO_PREPARE_DATASET}")
CONTINUE_ON_ERROR=$(normalize_boolean TUNING_CONTINUE_ON_ERROR "${CONTINUE_ON_ERROR}")
DRY_RUN=$(normalize_boolean TUNING_DRY_RUN "${DRY_RUN}")

echo "Validated tuning matrix:"
validate_matrix
if [[ "${DRY_RUN}" == "true" ]]; then
  echo "Dry run complete; no process or result was created."
  exit 0
fi

for command_name in curl java python3 git shasum; do
  command -v "${command_name}" >/dev/null 2>&1 || fail "Required executable not found: ${command_name}"
done
[[ -x "${JMETER_BIN}" ]] || command -v "${JMETER_BIN}" >/dev/null 2>&1 \
  || fail "JMeter executable not found: ${JMETER_BIN}"
[[ -f "${APP_JAR}" && -r "${APP_JAR}" ]] || fail "Executable JAR not found: ${APP_JAR}"
[[ -f "${DOCUMENT_FILE}" && -r "${DOCUMENT_FILE}" ]] || fail "Document fixture not found: ${DOCUMENT_FILE}"

RUN_STAMP=$(date -u +%Y%m%dT%H%M%SZ)
SESSION_TAG="ingest-tuning-${RUN_STAMP}-$$"
SESSION_DIR="${PROJECT_DIR}/performance/results/${SESSION_TAG}"
MANIFEST="${SESSION_DIR}/runs.tsv"
mkdir -p "${SESSION_DIR}"
printf 'case_id\tworker_concurrency\tprefetch\tembedding_batch_size\trepetition\tphase\tstatus\tresult_dir\n' \
  > "${MANIFEST}"

{
  echo "timestamp_utc=${RUN_STAMP}"
  echo "matrix_file=${MATRIX_FILE}"
  echo "matrix_sha256=$(shasum -a 256 "${MATRIX_FILE}" | awk '{print $1}')"
  echo "app_jar=${APP_JAR}"
  echo "app_jar_sha256=$(shasum -a 256 "${APP_JAR}" | awk '{print $1}')"
  echo "document_file=${DOCUMENT_FILE}"
  echo "document_sha256=$(shasum -a 256 "${DOCUMENT_FILE}" | awk '{print $1}')"
  echo "threads=${THREADS}"
  echo "loops=${LOOPS}"
  echo "repetitions=${REPETITIONS}"
  echo "warmup_documents=${WARMUP_DOCUMENTS}"
  echo "mock_dimensions=${MOCK_DIMENSIONS}"
  echo "mock_delay_ms=${MOCK_DELAY_MS}"
  echo "heap_mb=${HEAP_MB}"
  echo "sample_interval_ms=${SAMPLE_INTERVAL_MS}"
  echo "sample_http_timeout_ms=${SAMPLE_HTTP_TIMEOUT_MS}"
  echo "rabbit_queue_url=${RABBIT_QUEUE_URL}"
  echo "git_commit=$(git -C "${PROJECT_DIR}" rev-parse HEAD)"
  echo "git_dirty_count=$(git -C "${PROJECT_DIR}" status --short | wc -l | tr -d ' ')"
  java -version 2>&1 | sed 's/^/java=/'
  "${JMETER_BIN}" -j "${SESSION_DIR}/version-jmeter.log" --version 2>&1 | sed 's/^/jmeter=/'
} > "${SESSION_DIR}/metadata.txt"

start_mock
validate_mock_config

DATASET_PREPARED=false
while IFS=$'\t' read -r case_id workers prefetch batch_size note; do
  [[ -n "${case_id}" ]] || continue
  [[ "${case_id}" == \#* ]] && continue

  echo "Case ${case_id}: workers=${workers} prefetch=${prefetch} batch=${batch_size} (${note})"
  start_app "${case_id}" "${workers}" "${prefetch}" "${batch_size}"
  if [[ "${DATASET_PREPARED}" == "false" ]]; then
    prepare_dataset
    DATASET_PREPARED=true
    echo "knowledge_base_id=${KNOWLEDGE_BASE_ID}" >> "${SESSION_DIR}/metadata.txt"
  fi

  if [[ "${WARMUP_DOCUMENTS}" -gt 0 ]]; then
    run_documents \
      "${case_id}" "${workers}" "${prefetch}" "${batch_size}" \
      1 warmup "${WARMUP_DOCUMENTS}"
  fi
  for ((repetition = 1; repetition <= REPETITIONS; repetition++)); do
    run_documents \
      "${case_id}" "${workers}" "${prefetch}" "${batch_size}" \
      "${repetition}" formal "${THREADS}"
  done

  stop_process "${APP_PID}"
  APP_PID=""
done < <(tail -n +2 "${MATRIX_FILE}")

python3 "${SCRIPT_DIR}/aggregate-ingest-matrix.py" \
  "${MANIFEST}" \
  --baseline-case "${BASELINE_CASE}" \
  --max-in-flight "${MAX_BALANCED_IN_FLIGHT}" \
  --output "${SESSION_DIR}/aggregate.json" \
  --markdown-output "${SESSION_DIR}/report.md"

echo "Matrix results: ${SESSION_DIR}"
