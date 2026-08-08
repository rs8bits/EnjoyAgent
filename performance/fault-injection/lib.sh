#!/usr/bin/env bash

# Shared runtime for the stage-4 fault-injection suite.  The caller must use
# `set -euo pipefail` before sourcing this file.

FI_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FI_PROJECT_DIR="$(cd "${FI_SCRIPT_DIR}/../.." && pwd)"
FI_COMPOSE_FILE="${FI_COMPOSE_FILE:-${FI_PROJECT_DIR}/compose.yml}"
FI_JAR="${FI_JAR:-${FI_PROJECT_DIR}/target/enjoy-agent-0.0.1-SNAPSHOT.jar}"
FI_APP_PORT="${FI_APP_PORT:-18081}"
FI_SECONDARY_APP_PORT="${FI_SECONDARY_APP_PORT:-18083}"
FI_EMBEDDING_PORT="${FI_EMBEDDING_PORT:-18082}"
FI_RABBITMQ_MANAGEMENT_PORT="${FI_RABBITMQ_MANAGEMENT_PORT:-15672}"
FI_DB_NAME="${DB_NAME:-enjoy_agent}"
FI_DB_USERNAME="${DB_USERNAME:-enjoy}"
FI_RABBITMQ_USERNAME="${RABBITMQ_USERNAME:-guest}"
FI_RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-guest}"
FI_HTTP_TIMEOUT_SECONDS="${FI_HTTP_TIMEOUT_SECONDS:-30}"
FI_WAIT_TIMEOUT_SECONDS="${FI_WAIT_TIMEOUT_SECONDS:-90}"
FI_DOCUMENT_TIMEOUT_SECONDS="${FI_DOCUMENT_TIMEOUT_SECONDS:-90}"
FI_CACHE_CONVERGENCE_TIMEOUT_SECONDS="${FI_CACHE_CONVERGENCE_TIMEOUT_SECONDS:-5}"
FI_WORKER_DELAY_MS="${FI_WORKER_DELAY_MS:-5000}"
FI_FIXTURE="${FI_FIXTURE:-${FI_PROJECT_DIR}/performance/fixtures/generated/document-64k.txt}"
FI_JAVA_OPTS="${FI_JAVA_OPTS:--Xms512m -Xmx512m -XX:+UseG1GC}"

FI_RUN_STAMP="${FI_RUN_STAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
FI_RUN_ID="${FI_RUN_ID:-fi_${FI_RUN_STAMP}_$$}"
FI_SAFE_RUN_ID="$(printf '%s' "${FI_RUN_ID}" | tr -cd 'A-Za-z0-9._-')"
FI_RESULT_DIR="${FI_RESULT_DIR:-${FI_PROJECT_DIR}/performance/results/fault-injection-${FI_RUN_STAMP}-$$}"
FI_RESULTS_TSV="${FI_RESULT_DIR}/scenario-results.tsv"
FI_METADATA_FILE="${FI_RESULT_DIR}/metadata.env"
FI_PRIMARY_LOG="${FI_RESULT_DIR}/application-primary.log"
FI_SECONDARY_LOG="${FI_RESULT_DIR}/application-secondary.log"
FI_MOCK_LOG="${FI_RESULT_DIR}/embedding-mock.log"
FI_EMAIL="$(printf '%s' "${FI_EMAIL:-fault-${FI_SAFE_RUN_ID}@example.test}" | tr '[:upper:]' '[:lower:]')"
FI_PASSWORD="${FI_PASSWORD:-FaultPassw0rd!}"
FI_EXCHANGE="${FI_EXCHANGE:-enjoy.knowledge.${FI_SAFE_RUN_ID}}"
FI_QUEUE="${FI_QUEUE:-enjoy.knowledge.${FI_SAFE_RUN_ID}.queue}"
FI_PARKING_QUEUE="${FI_PARKING_QUEUE:-enjoy.knowledge.${FI_SAFE_RUN_ID}.parking}"
FI_ROUTING_KEY="${FI_ROUTING_KEY:-knowledge.${FI_SAFE_RUN_ID}.ingest}"
FI_PARKING_ROUTING_KEY="${FI_PARKING_ROUTING_KEY:-knowledge.${FI_SAFE_RUN_ID}.parking}"

FI_PRIMARY_PID=""
FI_SECONDARY_PID=""
FI_MOCK_PID=""
FI_ACCESS_TOKEN=""
FI_TENANT_ID=""
FI_KNOWLEDGE_BASE_ID=""
FI_CHAT_MODEL_CONFIG_ID=""
FI_CURRENT_SCENARIO="bootstrap"
FI_CURRENT_SCENARIO_RECORDED=false
FI_ORIGINAL_RUNNING_SERVICES=""
FI_INFRA_CAPTURED=false

fi_log() {
  printf '[fault-injection] %s\n' "$*"
}

fi_die() {
  printf '[fault-injection] ERROR: %s\n' "$*" >&2
  return 1
}

fi_require_positive_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]]; then
    fi_die "${name} must be a positive integer"
  fi
}

fi_require_tools() {
  local tool
  for tool in curl docker java jq python3 shasum; do
    command -v "${tool}" >/dev/null 2>&1 || fi_die "Required executable not found: ${tool}"
  done
  docker compose version >/dev/null
  [[ -r "${FI_JAR}" ]] || fi_die "Executable JAR not found: ${FI_JAR}"
  [[ -r "${FI_FIXTURE}" ]] || fi_die "Document fixture not found: ${FI_FIXTURE}"
  fi_require_positive_integer FI_APP_PORT "${FI_APP_PORT}"
  fi_require_positive_integer FI_SECONDARY_APP_PORT "${FI_SECONDARY_APP_PORT}"
  fi_require_positive_integer FI_EMBEDDING_PORT "${FI_EMBEDDING_PORT}"
  [[ "${FI_APP_PORT}" != "${FI_SECONDARY_APP_PORT}" ]] || fi_die "Application ports must differ"
  [[ "${FI_APP_PORT}" != "${FI_EMBEDDING_PORT}" ]] || fi_die "Application and Mock ports must differ"
  [[ "${FI_SECONDARY_APP_PORT}" != "${FI_EMBEDDING_PORT}" ]] || fi_die "Application and Mock ports must differ"
}

fi_compose() {
  docker compose -f "${FI_COMPOSE_FILE}" "$@"
}

fi_service_was_running() {
  grep -Fxq "$1" <<< "${FI_ORIGINAL_RUNNING_SERVICES}"
}

fi_service_is_running() {
  fi_compose ps --status running --services | grep -Fxq "$1"
}

fi_capture_infra_state() {
  FI_ORIGINAL_RUNNING_SERVICES="$(fi_compose ps --status running --services 2>/dev/null || true)"
  FI_INFRA_CAPTURED=true
}

fi_start_service() {
  local service="$1"
  if fi_service_is_running "${service}"; then
    return
  fi
  if [[ -n "$(fi_compose ps -a -q "${service}" 2>/dev/null)" ]]; then
    fi_compose start "${service}" >/dev/null
  else
    fi_compose up -d --no-deps "${service}" >/dev/null
  fi
}

fi_wait_until() {
  local description="$1"
  local timeout_seconds="$2"
  shift 2
  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    if "$@"; then
      return 0
    fi
    sleep 0.25
  done
  fi_die "Timed out after ${timeout_seconds}s waiting for ${description}"
}

fi_postgres_ready() {
  fi_compose exec -T postgres pg_isready -U "${FI_DB_USERNAME}" -d "${FI_DB_NAME}" >/dev/null 2>&1
}

fi_redis_ready() {
  [[ "$(fi_compose exec -T redis redis-cli ping 2>/dev/null || true)" == "PONG" ]]
}

fi_rabbit_ready() {
  fi_compose exec -T rabbitmq rabbitmq-diagnostics -q ping >/dev/null 2>&1
}

fi_minio_ready() {
  curl --fail --silent --max-time 2 http://127.0.0.1:9000/minio/health/ready >/dev/null 2>&1
}

fi_management_ready() {
  curl --fail --silent --max-time 2 \
    --user "${FI_RABBITMQ_USERNAME}:${FI_RABBITMQ_PASSWORD}" \
    "http://127.0.0.1:${FI_RABBITMQ_MANAGEMENT_PORT}/api/overview" >/dev/null 2>&1
}

fi_start_infrastructure() {
  fi_capture_infra_state
  local service
  for service in postgres redis minio rabbitmq; do
    fi_start_service "${service}"
  done
  fi_wait_until PostgreSQL "${FI_WAIT_TIMEOUT_SECONDS}" fi_postgres_ready
  fi_wait_until Redis "${FI_WAIT_TIMEOUT_SECONDS}" fi_redis_ready
  fi_wait_until RabbitMQ "${FI_WAIT_TIMEOUT_SECONDS}" fi_rabbit_ready
  fi_wait_until RabbitMQ-management "${FI_WAIT_TIMEOUT_SECONDS}" fi_management_ready
  fi_wait_until MinIO "${FI_WAIT_TIMEOUT_SECONDS}" fi_minio_ready
}

fi_assert_no_unfinished_outbox() {
  local table_exists
  local unfinished
  table_exists="$(fi_db_query "SELECT to_regclass('public.knowledge_ingest_outbox') IS NOT NULL;")"
  [[ "${table_exists}" == "t" ]] || return 0
  unfinished="$(fi_db_query "SELECT count(*) FROM knowledge_ingest_outbox WHERE status IN ('PENDING', 'CLAIMED');")"
  [[ "${unfinished}" == "0" ]] || fi_die \
    "Found ${unfinished} existing unfinished Outbox row(s); finish or isolate them before this drill"
}

fi_db_query() {
  local sql="$1"
  printf '%s\n' "${sql}" | fi_compose exec -T postgres \
    psql -X -U "${FI_DB_USERNAME}" -d "${FI_DB_NAME}" -Atq
}

fi_sql_truthy() {
  [[ "$(fi_db_query "$1" 2>/dev/null || true)" == "t" ]]
}

fi_wait_document_status() {
  local document_id="$1"
  local expected_status="$2"
  local timeout_seconds="${3:-${FI_DOCUMENT_TIMEOUT_SECONDS}}"
  fi_wait_until "document ${document_id} status=${expected_status}" "${timeout_seconds}" \
    fi_sql_truthy "SELECT EXISTS (SELECT 1 FROM knowledge_document WHERE id = ${document_id} AND status = '${expected_status}');"
}

fi_wait_outbox_published() {
  local document_id="$1"
  local expected_count="${2:-1}"
  fi_wait_until "${expected_count} published outbox rows for document ${document_id}" \
    "${FI_DOCUMENT_TIMEOUT_SECONDS}" fi_sql_truthy \
    "SELECT (count(*) >= ${expected_count} AND bool_and(status = 'PUBLISHED')) FROM knowledge_ingest_outbox WHERE document_id = ${document_id};"
}

fi_snapshot_document() {
  local document_id="$1"
  local output="$2"
  fi_db_query "SELECT COALESCE(row_to_json(snapshot)::text, '{}') FROM (SELECT id, status, chunk_count, processing_attempts, processing_fence, processing_token, processing_lease_until, next_retry_at, last_error_code, last_error_message, completed_at FROM knowledge_document WHERE id = ${document_id}) snapshot;" > "${output}"
}

fi_snapshot_outbox() {
  local document_id="$1"
  local output="$2"
  fi_db_query "SELECT COALESCE(json_agg(snapshot ORDER BY delivery_attempt)::text, '[]') FROM (SELECT id, document_id, delivery_attempt, status, publish_attempts, available_at, claimed_at, published_at, last_error FROM knowledge_ingest_outbox WHERE document_id = ${document_id}) snapshot;" > "${output}"
}

fi_urlencode() {
  python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"
}

fi_queue_snapshot() {
  local queue_name="$1"
  local output="$2"
  local encoded
  encoded="$(fi_urlencode "${queue_name}")"
  curl --fail --silent --show-error --max-time 5 \
    --user "${FI_RABBITMQ_USERNAME}:${FI_RABBITMQ_PASSWORD}" \
    "http://127.0.0.1:${FI_RABBITMQ_MANAGEMENT_PORT}/api/queues/%2F/${encoded}" \
    --output "${output}"
}

fi_queue_messages_ready() {
  local queue_name="$1"
  local encoded
  encoded="$(fi_urlencode "${queue_name}")"
  curl --fail --silent --max-time 3 \
    --user "${FI_RABBITMQ_USERNAME}:${FI_RABBITMQ_PASSWORD}" \
    "http://127.0.0.1:${FI_RABBITMQ_MANAGEMENT_PORT}/api/queues/%2F/${encoded}" \
    | jq -er '.messages_ready // 0'
}

fi_parking_has_message() {
  local ready
  ready="$(fi_queue_messages_ready "${FI_PARKING_QUEUE}" 2>/dev/null || printf '0')"
  [[ "${ready}" =~ ^[0-9]+$ ]] && (( ready >= 1 ))
}

fi_port_is_free() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    ! lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1
    return
  fi
  ! curl --silent --max-time 1 "http://127.0.0.1:${port}/" >/dev/null 2>&1
}

fi_http_ready() {
  local port="$1"
  curl --fail --silent --max-time 2 \
    "http://127.0.0.1:${port}/actuator/health/readiness" >/dev/null 2>&1
}

fi_mock_ready() {
  curl --fail --silent --max-time 2 \
    "http://127.0.0.1:${FI_EMBEDDING_PORT}/health" >/dev/null 2>&1
}

fi_app_environment() {
  local port="$1"
  shift
  exec env \
    SERVER_PORT="${port}" \
    SPRING_PROFILES_ACTIVE=perf \
    APP_PLATFORM_OPENAI_API_KEY=fault-injection-local-key \
    APP_MODEL_GATEWAY_OPENAI_BASE_URL="http://127.0.0.1:${FI_EMBEDDING_PORT}" \
    APP_CACHE_AUTH_CONTEXT_LOCAL_TTL=2s \
    APP_CACHE_AGENT_CONFIG_LOCAL_TTL=30s \
    APP_KNOWLEDGE_ASYNC_INGEST_ENABLED=true \
    APP_KNOWLEDGE_INGEST_EXCHANGE="${FI_EXCHANGE}" \
    APP_KNOWLEDGE_INGEST_ROUTING_KEY="${FI_ROUTING_KEY}" \
    APP_KNOWLEDGE_INGEST_QUEUE="${FI_QUEUE}" \
    APP_KNOWLEDGE_INGEST_PARKING_ROUTING_KEY="${FI_PARKING_ROUTING_KEY}" \
    APP_KNOWLEDGE_INGEST_PARKING_QUEUE="${FI_PARKING_QUEUE}" \
    APP_KNOWLEDGE_INGEST_WORKER_CONCURRENCY=1 \
    APP_KNOWLEDGE_INGEST_PREFETCH=1 \
    APP_KNOWLEDGE_INGEST_MAX_ATTEMPTS=3 \
    APP_KNOWLEDGE_INGEST_RETRY_DELAYS=1s,1s \
    APP_KNOWLEDGE_INGEST_OUTBOX_RELAY_DELAY_MS=250 \
    APP_KNOWLEDGE_INGEST_OUTBOX_RELAY_INITIAL_DELAY_MS=250 \
    APP_KNOWLEDGE_INGEST_PUBLISHER_CONFIRM_TIMEOUT=3s \
    APP_KNOWLEDGE_INGEST_OUTBOX_PUBLISH_RETRY_DELAY=500ms \
    APP_KNOWLEDGE_INGEST_PROCESSING_LEASE_DURATION=30s \
    SPRING_RABBITMQ_CONNECTION_TIMEOUT=2s \
    "$@"
}

fi_start_app_process() {
  local port="$1"
  local log_file="$2"
  local -a java_options
  read -r -a java_options <<< "${FI_JAVA_OPTS}"
  fi_port_is_free "${port}" || fi_die "Port ${port} is already in use"
  printf '\n=== application start %s port=%s ===\n' "$(date -u +%FT%TZ)" "${port}" >> "${log_file}"
  fi_app_environment "${port}" java "${java_options[@]}" -jar "${FI_JAR}" >> "${log_file}" 2>&1 &
  FI_STARTED_PID=$!
  fi_wait_until "application readiness on port ${port}" "${FI_WAIT_TIMEOUT_SECONDS}" fi_http_ready "${port}"
}

fi_start_primary_app() {
  fi_start_app_process "${FI_APP_PORT}" "${FI_PRIMARY_LOG}"
  FI_PRIMARY_PID="${FI_STARTED_PID}"
}

fi_start_secondary_app() {
  fi_start_app_process "${FI_SECONDARY_APP_PORT}" "${FI_SECONDARY_LOG}"
  FI_SECONDARY_PID="${FI_STARTED_PID}"
}

fi_stop_pid() {
  local pid="$1"
  local signal_name="${2:-TERM}"
  [[ -n "${pid}" ]] || return 0
  kill -0 "${pid}" >/dev/null 2>&1 || return 0
  kill -s "${signal_name}" "${pid}" >/dev/null 2>&1 || true
  if [[ "${signal_name}" == "KILL" ]]; then
    wait "${pid}" >/dev/null 2>&1 || true
    return 0
  fi
  local deadline=$((SECONDS + 15))
  while kill -0 "${pid}" >/dev/null 2>&1 && (( SECONDS < deadline )); do
    sleep 0.25
  done
  if kill -0 "${pid}" >/dev/null 2>&1; then
    kill -KILL "${pid}" >/dev/null 2>&1 || true
  fi
  wait "${pid}" >/dev/null 2>&1 || true
}

fi_start_mock() {
  fi_port_is_free "${FI_EMBEDDING_PORT}" || fi_die "Port ${FI_EMBEDDING_PORT} is already in use"
  python3 "${FI_SCRIPT_DIR}/controllable_embedding_server.py" \
    --port "${FI_EMBEDDING_PORT}" \
    --dimensions 1024 \
    --mode success \
    --delay-ms 20 >> "${FI_MOCK_LOG}" 2>&1 &
  FI_MOCK_PID=$!
  fi_wait_until embedding-mock "${FI_WAIT_TIMEOUT_SECONDS}" fi_mock_ready
}

fi_configure_mock() {
  local mode="$1"
  local delay_ms="$2"
  jq -nc --arg mode "${mode}" --argjson delay_ms "${delay_ms}" \
    '{mode:$mode,delay_ms:$delay_ms}' \
    | curl --fail --silent --show-error --max-time 5 \
        --header 'Content-Type: application/json' \
        --data-binary @- \
        "http://127.0.0.1:${FI_EMBEDDING_PORT}/control"
}

fi_register_and_login() {
  local base_url="http://127.0.0.1:${FI_APP_PORT}"
  local register_payload login_payload login_response
  register_payload="$(jq -nc \
    --arg email "${FI_EMAIL}" \
    --arg password "${FI_PASSWORD}" \
    --arg run_id "${FI_SAFE_RUN_ID}" \
    '{email:$email,password:$password,displayName:("Fault Drill " + $run_id),tenantName:("Fault Drill " + $run_id)}')"
  curl --silent --show-error --max-time "${FI_HTTP_TIMEOUT_SECONDS}" \
    --header 'Content-Type: application/json' \
    --data "${register_payload}" \
    "${base_url}/api/auth/register" \
    --output "${FI_RESULT_DIR}/register.json"

  login_payload="$(jq -nc --arg email "${FI_EMAIL}" --arg password "${FI_PASSWORD}" \
    '{email:$email,password:$password}')"
  login_response="$(curl --fail --silent --show-error --max-time "${FI_HTTP_TIMEOUT_SECONDS}" \
    --header 'Content-Type: application/json' \
    --data "${login_payload}" \
    "${base_url}/api/auth/login")"
  printf '%s\n' "${login_response}" > "${FI_RESULT_DIR}/login.json"
  FI_ACCESS_TOKEN="$(jq -er '.data.accessToken' <<< "${login_response}")"
  FI_TENANT_ID="$(jq -er '.data.currentUser.tenantId' <<< "${login_response}")"
  [[ "${FI_TENANT_ID}" =~ ^[1-9][0-9]*$ ]] || fi_die "Login returned an invalid tenant ID"
}

fi_seed_models_and_knowledge_base() {
  fi_db_query "INSERT INTO model_config (tenant_id, name, provider, model_type, model_name, credential_id, credential_source, enabled) VALUES (${FI_TENANT_ID}, 'fault-chat-${FI_SAFE_RUN_ID}', 'OPENAI', 'CHAT', 'fault-chat-model', NULL, 'PLATFORM', TRUE) ON CONFLICT (tenant_id, name) DO UPDATE SET enabled = TRUE, updated_at = NOW();" >/dev/null
  PERF_EMAIL="${FI_EMAIL}" DB_NAME="${FI_DB_NAME}" DB_USERNAME="${FI_DB_USERNAME}" \
    "${FI_PROJECT_DIR}/performance/seed/run-knowledge-ingest-seed.sh" \
    > "${FI_RESULT_DIR}/knowledge-seed.txt"
  FI_CHAT_MODEL_CONFIG_ID="$(fi_db_query "SELECT id FROM model_config WHERE tenant_id = ${FI_TENANT_ID} AND name = 'fault-chat-${FI_SAFE_RUN_ID}';")"
  FI_KNOWLEDGE_BASE_ID="$(fi_db_query "SELECT id FROM knowledge_base WHERE tenant_id = ${FI_TENANT_ID} AND name = 'perf-knowledge-ingest';")"
  [[ "${FI_CHAT_MODEL_CONFIG_ID}" =~ ^[1-9][0-9]*$ ]] || fi_die "Could not resolve seeded CHAT model"
  [[ "${FI_KNOWLEDGE_BASE_ID}" =~ ^[1-9][0-9]*$ ]] || fi_die "Could not resolve seeded knowledge base"
}

fi_auth_me_request() {
  local port="$1"
  local prefix="$2"
  curl --silent --show-error --max-time "${FI_HTTP_TIMEOUT_SECONDS}" \
    --header "Authorization: Bearer ${FI_ACCESS_TOKEN}" \
    --output "${prefix}.json" \
    --write-out '%{http_code}\t%{time_total}\n' \
    "http://127.0.0.1:${port}/api/auth/me" > "${prefix}.meta"
}

fi_assert_success_response() {
  local prefix="$1"
  local expected_code="${2:-200}"
  local observed_code
  observed_code="$(cut -f1 "${prefix}.meta")"
  [[ "${observed_code}" == "${expected_code}" ]] || fi_die "Expected HTTP ${expected_code}, got ${observed_code}"
  jq -e '.success == true' "${prefix}.json" >/dev/null
}

fi_upload_document() {
  local label="$1"
  local prefix="${FI_RESULT_DIR}/${label}-upload"
  curl --silent --show-error --max-time "${FI_HTTP_TIMEOUT_SECONDS}" \
    --header "Authorization: Bearer ${FI_ACCESS_TOKEN}" \
    --form "file=@${FI_FIXTURE};filename=${label}-${FI_SAFE_RUN_ID}.txt;type=text/plain" \
    --output "${prefix}.json" \
    --write-out '%{http_code}\t%{time_total}\n' \
    "http://127.0.0.1:${FI_APP_PORT}/api/knowledge-bases/${FI_KNOWLEDGE_BASE_ID}/documents" \
    > "${prefix}.meta"
  fi_assert_success_response "${prefix}" 202
  jq -er '.data.id' "${prefix}.json"
}

fi_get_agent_description() {
  local port="$1"
  local agent_id="$2"
  curl --fail --silent --show-error --max-time "${FI_HTTP_TIMEOUT_SECONDS}" \
    --header "Authorization: Bearer ${FI_ACCESS_TOKEN}" \
    "http://127.0.0.1:${port}/api/agents/${agent_id}" | jq -er '.data.description'
}

fi_create_agent() {
  local description="$1"
  local response
  response="$(jq -nc \
    --arg name "fault-agent-${FI_SAFE_RUN_ID}" \
    --arg description "${description}" \
    --argjson model_config_id "${FI_CHAT_MODEL_CONFIG_ID}" \
    '{name:$name,description:$description,systemPrompt:"Fault drill prompt v1",chatModelBindingType:"USER_MODEL",modelConfigId:$model_config_id,officialModelConfigId:null,knowledgeBaseId:null,rerankEnabled:false,rerankModelConfigId:null,contextStrategy:"SLIDING_WINDOW",contextWindowSize:10,memoryEnabled:false,memoryStrategy:null,memoryUpdateMessageThreshold:6,enabled:true}' \
    | curl --fail --silent --show-error --max-time "${FI_HTTP_TIMEOUT_SECONDS}" \
        --header "Authorization: Bearer ${FI_ACCESS_TOKEN}" \
        --header 'Content-Type: application/json' \
        --data-binary @- \
        "http://127.0.0.1:${FI_APP_PORT}/api/agents")"
  printf '%s\n' "${response}" > "${FI_RESULT_DIR}/cache-agent-create.json"
  jq -er '.data.id' <<< "${response}"
}

fi_update_agent() {
  local agent_id="$1"
  local description="$2"
  jq -nc \
    --arg name "fault-agent-${FI_SAFE_RUN_ID}" \
    --arg description "${description}" \
    --argjson model_config_id "${FI_CHAT_MODEL_CONFIG_ID}" \
    '{name:$name,description:$description,systemPrompt:"Fault drill prompt v2",chatModelBindingType:"USER_MODEL",modelConfigId:$model_config_id,officialModelConfigId:null,knowledgeBaseId:null,rerankEnabled:false,rerankModelConfigId:null,contextStrategy:"SLIDING_WINDOW",contextWindowSize:10,memoryEnabled:false,memoryStrategy:null,memoryUpdateMessageThreshold:6,enabled:true,workflowId:null}' \
    | curl --fail --silent --show-error --max-time "${FI_HTTP_TIMEOUT_SECONDS}" \
        --request PUT \
        --header "Authorization: Bearer ${FI_ACCESS_TOKEN}" \
        --header 'Content-Type: application/json' \
        --data-binary @- \
        "http://127.0.0.1:${FI_APP_PORT}/api/agents/${agent_id}" \
        --output "${FI_RESULT_DIR}/cache-agent-update.json"
  jq -e --arg description "${description}" \
    '.success == true and .data.description == $description' \
    "${FI_RESULT_DIR}/cache-agent-update.json" >/dev/null
}

fi_retry_document() {
  local document_id="$1"
  curl --fail --silent --show-error --max-time "${FI_HTTP_TIMEOUT_SECONDS}" \
    --request POST \
    --header "Authorization: Bearer ${FI_ACCESS_TOKEN}" \
    "http://127.0.0.1:${FI_APP_PORT}/api/knowledge-bases/${FI_KNOWLEDGE_BASE_ID}/documents/${document_id}/retry" \
    --output "${FI_RESULT_DIR}/embedding-timeout-manual-retry.json"
  jq -e '.success == true' "${FI_RESULT_DIR}/embedding-timeout-manual-retry.json" >/dev/null
}

fi_monotonic_millis() {
  python3 -c 'import time; print(time.monotonic_ns() // 1_000_000)'
}

fi_write_metadata() {
  mkdir -p "${FI_RESULT_DIR}"
  : > "${FI_RESULTS_TSV}"
  {
    printf 'run_id=%s\n' "${FI_RUN_ID}"
    printf 'started_at_utc=%s\n' "$(date -u +%FT%TZ)"
    printf 'git_commit=%s\n' "$(git -C "${FI_PROJECT_DIR}" rev-parse HEAD)"
    printf 'git_dirty_count=%s\n' "$(git -C "${FI_PROJECT_DIR}" status --short | wc -l | tr -d ' ')"
    printf 'jar=%s\n' "${FI_JAR}"
    printf 'jar_sha256=%s\n' "$(shasum -a 256 "${FI_JAR}" | awk '{print $1}')"
    printf 'fixture=%s\n' "${FI_FIXTURE}"
    printf 'fixture_sha256=%s\n' "$(shasum -a 256 "${FI_FIXTURE}" | awk '{print $1}')"
    printf 'java_version=%s\n' "$(java -version 2>&1 | head -n 1)"
    printf 'docker_compose_version=%s\n' "$(docker compose version --short)"
    printf 'platform=%s\n' "$(uname -sm)"
    printf 'app_port=%s\n' "${FI_APP_PORT}"
    printf 'secondary_app_port=%s\n' "${FI_SECONDARY_APP_PORT}"
    printf 'embedding_port=%s\n' "${FI_EMBEDDING_PORT}"
    printf 'exchange=%s\n' "${FI_EXCHANGE}"
    printf 'queue=%s\n' "${FI_QUEUE}"
    printf 'parking_queue=%s\n' "${FI_PARKING_QUEUE}"
  } > "${FI_METADATA_FILE}"
}

fi_sanitize_field() {
  printf '%s' "$1" | tr '\t\r\n' '   '
}

fi_record_result() {
  local scenario="$1"
  local outcome="$2"
  local summary="$3"
  local evidence="$4"
  printf '%s\t%s\t%s\t%s\n' \
    "$(fi_sanitize_field "${scenario}")" \
    "$(fi_sanitize_field "${outcome}")" \
    "$(fi_sanitize_field "${summary}")" \
    "$(fi_sanitize_field "${evidence}")" >> "${FI_RESULTS_TSV}"
  FI_CURRENT_SCENARIO_RECORDED=true
}

fi_render_report() {
  [[ -f "${FI_METADATA_FILE}" ]] || return 0
  python3 "${FI_SCRIPT_DIR}/render_report.py" \
    --metadata "${FI_METADATA_FILE}" \
    --results "${FI_RESULTS_TSV}" \
    --json-output "${FI_RESULT_DIR}/summary.json" \
    --markdown-output "${FI_RESULT_DIR}/report.md"
}

fi_delete_test_topology() {
  fi_rabbit_ready || return 0
  fi_management_ready || return 0
  local encoded
  for queue_name in "${FI_QUEUE}" "${FI_PARKING_QUEUE}"; do
    encoded="$(fi_urlencode "${queue_name}")"
    curl --silent --output /dev/null --max-time 5 \
      --request DELETE \
      --user "${FI_RABBITMQ_USERNAME}:${FI_RABBITMQ_PASSWORD}" \
      "http://127.0.0.1:${FI_RABBITMQ_MANAGEMENT_PORT}/api/queues/%2F/${encoded}" || true
  done
  encoded="$(fi_urlencode "${FI_EXCHANGE}")"
  curl --silent --output /dev/null --max-time 5 \
    --request DELETE \
    --user "${FI_RABBITMQ_USERNAME}:${FI_RABBITMQ_PASSWORD}" \
    "http://127.0.0.1:${FI_RABBITMQ_MANAGEMENT_PORT}/api/exchanges/%2F/${encoded}" || true
}

fi_quarantine_unfinished_test_work() {
  [[ "${FI_TENANT_ID}" =~ ^[1-9][0-9]*$ ]] || return 0
  fi_postgres_ready || return 0
  fi_db_query "DELETE FROM knowledge_ingest_outbox WHERE status IN ('PENDING', 'CLAIMED') AND document_id IN (SELECT id FROM knowledge_document WHERE tenant_id = ${FI_TENANT_ID}); UPDATE knowledge_document SET status = 'FAILED', completed_at = NOW(), next_retry_at = NULL, last_error_code = 'FAULT_DRILL_ABORTED', last_error_message = 'Fault drill stopped before this task reached a terminal state', processing_token = NULL, processing_lease_until = NULL WHERE tenant_id = ${FI_TENANT_ID} AND status IN ('QUEUED', 'PROCESSING', 'RETRYING', 'INDEXING');" >/dev/null 2>&1 || true
}

fi_restore_infrastructure() {
  [[ "${FI_INFRA_CAPTURED}" == true ]] || return 0
  local service
  for service in redis rabbitmq postgres minio; do
    if fi_service_was_running "${service}"; then
      fi_start_service "${service}" || true
    elif fi_service_is_running "${service}"; then
      fi_compose stop "${service}" >/dev/null 2>&1 || true
    fi
  done
}

fi_cleanup() {
  local exit_code="$1"
  set +e
  if (( exit_code != 0 )) && [[ "${FI_CURRENT_SCENARIO_RECORDED}" != true ]]; then
    fi_record_result "${FI_CURRENT_SCENARIO}" FAIL \
      "suite stopped unexpectedly; inspect application and scenario evidence" \
      "application-primary.log,application-secondary.log,embedding-mock.log"
  fi
  fi_stop_pid "${FI_SECONDARY_PID}" TERM
  fi_stop_pid "${FI_PRIMARY_PID}" TERM
  fi_stop_pid "${FI_MOCK_PID}" TERM
  fi_quarantine_unfinished_test_work
  if [[ "${FI_INFRA_CAPTURED}" == true ]]; then
    fi_start_service rabbitmq
    fi_wait_until RabbitMQ-cleanup 30 fi_rabbit_ready
    fi_wait_until RabbitMQ-management-cleanup 30 fi_management_ready
    fi_delete_test_topology
  fi
  fi_restore_infrastructure
  fi_render_report
  fi_log "Results: ${FI_RESULT_DIR}"
  return "${exit_code}"
}
