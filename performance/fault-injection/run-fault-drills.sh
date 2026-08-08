#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=performance/fault-injection/lib.sh
source "${SCRIPT_DIR}/lib.sh"

usage() {
  printf '%s\n' \
    'Usage: run-fault-drills.sh [all|cache-cross-instance|redis|rabbitmq|worker-restart|embedding-timeout]...' \
    '' \
    'The default is all. The suite starts its own application instances on 18081/18083' \
    'and a controllable embedding server on 18082. Docker volumes are never removed.'
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

declare -a REQUESTED_SCENARIOS=("$@")
if (( ${#REQUESTED_SCENARIOS[@]} == 0 )); then
  REQUESTED_SCENARIOS=(all)
fi

for scenario in "${REQUESTED_SCENARIOS[@]}"; do
  case "${scenario}" in
    all|cache-cross-instance|redis|rabbitmq|worker-restart|embedding-timeout) ;;
    *)
      usage >&2
      fi_die "Unknown scenario: ${scenario}"
      ;;
  esac
done

scenario_requested() {
  local expected="$1"
  local item
  for item in "${REQUESTED_SCENARIOS[@]}"; do
    if [[ "${item}" == "all" || "${item}" == "${expected}" ]]; then
      return 0
    fi
  done
  return 1
}

set_scenario() {
  FI_CURRENT_SCENARIO="$1"
  FI_CURRENT_SCENARIO_RECORDED=false
  fi_log "Running scenario: ${FI_CURRENT_SCENARIO}"
}

wait_service_stopped() {
  local service="$1"
  ! fi_service_is_running "${service}"
}

outbox_failure_recorded() {
  local document_id="$1"
  fi_sql_truthy "SELECT EXISTS (SELECT 1 FROM knowledge_ingest_outbox WHERE document_id = ${document_id} AND publish_attempts >= 1 AND status = 'PENDING' AND last_error IS NOT NULL);"
}

queue_has_ready_message() {
  local queue_name="$1"
  local ready
  ready="$(fi_queue_messages_ready "${queue_name}" 2>/dev/null || printf '0')"
  [[ "${ready}" =~ ^[0-9]+$ ]] && (( ready >= 1 ))
}

document_is_processing_with_owner() {
  local document_id="$1"
  fi_sql_truthy "SELECT EXISTS (SELECT 1 FROM knowledge_document WHERE id = ${document_id} AND status = 'PROCESSING' AND processing_token IS NOT NULL AND processing_lease_until IS NOT NULL);"
}

prometheus_has_auth_l2_error() {
  local metrics_file="$1"
  awk '
    /^enjoy_cache_requests_total\{/ && /cache="auth-context-v1"/ && /result="error"/ && /tier="l2"/ {
      if ($NF + 0 > 0) found = 1
    }
    END { exit(found ? 0 : 1) }
  ' "${metrics_file}"
}

prometheus_has_agent_l1_hit() {
  local metrics_file="$1"
  awk '
    /^enjoy_cache_requests_total\{/ && /cache="agent-config-v4"/ && /result="hit"/ && /tier="l1"/ {
      if ($NF + 0 > 0) found = 1
    }
    END { exit(found ? 0 : 1) }
  ' "${metrics_file}"
}

scenario_cache_cross_instance() {
  set_scenario cache-cross-instance
  local description_v1="cache-v1-${FI_SAFE_RUN_ID}"
  local description_v2="cache-v2-${FI_SAFE_RUN_ID}"
  local agent_id initial_description cached_description observed_description
  local started_ms finished_ms elapsed_ms deadline_ms

  fi_start_secondary_app
  agent_id="$(fi_create_agent "${description_v1}")"
  [[ "${agent_id}" =~ ^[1-9][0-9]*$ ]] || fi_die "Agent creation returned an invalid ID"

  initial_description="$(fi_get_agent_description "${FI_SECONDARY_APP_PORT}" "${agent_id}")"
  cached_description="$(fi_get_agent_description "${FI_SECONDARY_APP_PORT}" "${agent_id}")"
  [[ "${initial_description}" == "${description_v1}" ]] || fi_die "Secondary instance did not read v1"
  [[ "${cached_description}" == "${description_v1}" ]] || fi_die "Secondary instance did not retain v1 in cache"
  curl --fail --silent --show-error \
    "http://127.0.0.1:${FI_SECONDARY_APP_PORT}/actuator/prometheus" \
    --output "${FI_RESULT_DIR}/cache-secondary-before-update.prom"
  prometheus_has_agent_l1_hit "${FI_RESULT_DIR}/cache-secondary-before-update.prom" \
    || fi_die "Instance B did not record an Agent-config L1 hit before the update"

  fi_update_agent "${agent_id}" "${description_v2}"
  started_ms="$(fi_monotonic_millis)"
  deadline_ms=$((started_ms + FI_CACHE_CONVERGENCE_TIMEOUT_SECONDS * 1000))
  observed_description=""
  while (( $(fi_monotonic_millis) < deadline_ms )); do
    observed_description="$(fi_get_agent_description "${FI_SECONDARY_APP_PORT}" "${agent_id}" 2>/dev/null || true)"
    if [[ "${observed_description}" == "${description_v2}" ]]; then
      break
    fi
    sleep 0.02
  done
  finished_ms="$(fi_monotonic_millis)"
  elapsed_ms=$((finished_ms - started_ms))
  [[ "${observed_description}" == "${description_v2}" ]] \
    || fi_die "Cross-instance cache invalidation did not converge within ${FI_CACHE_CONVERGENCE_TIMEOUT_SECONDS}s"
  curl --fail --silent --show-error \
    "http://127.0.0.1:${FI_SECONDARY_APP_PORT}/actuator/prometheus" \
    --output "${FI_RESULT_DIR}/cache-secondary-after-update.prom"
  jq -nc \
    --argjson agent_id "${agent_id}" \
    --arg before "${description_v1}" \
    --arg after "${description_v2}" \
    --argjson convergence_ms "${elapsed_ms}" \
    '{agent_id:$agent_id,before:$before,after:$after,convergence_ms:$convergence_ms}' \
    > "${FI_RESULT_DIR}/cache-cross-instance.json"
  fi_stop_pid "${FI_SECONDARY_PID}" TERM
  FI_SECONDARY_PID=""
  fi_record_result cache-cross-instance PASS \
    "instance B cached v1; instance A updated v2; B observed v2 in ${elapsed_ms} ms (limit ${FI_CACHE_CONVERGENCE_TIMEOUT_SECONDS}s)" \
    "cache-cross-instance.json,cache-secondary-before-update.prom,cache-secondary-after-update.prom"
}

scenario_redis_failover() {
  set_scenario redis
  local outage_started_ms outage_finished_ms outage_elapsed_ms
  fi_auth_me_request "${FI_APP_PORT}" "${FI_RESULT_DIR}/redis-before"
  fi_assert_success_response "${FI_RESULT_DIR}/redis-before" 200
  curl --fail --silent --show-error \
    "http://127.0.0.1:${FI_APP_PORT}/actuator/prometheus" \
    --output "${FI_RESULT_DIR}/redis-before.prom"

  fi_compose stop redis >/dev/null
  fi_wait_until Redis-stop 30 wait_service_stopped redis
  sleep 3
  outage_started_ms="$(fi_monotonic_millis)"
  fi_auth_me_request "${FI_APP_PORT}" "${FI_RESULT_DIR}/redis-during"
  outage_finished_ms="$(fi_monotonic_millis)"
  outage_elapsed_ms=$((outage_finished_ms - outage_started_ms))
  fi_assert_success_response "${FI_RESULT_DIR}/redis-during" 200
  curl --fail --silent --show-error \
    "http://127.0.0.1:${FI_APP_PORT}/actuator/prometheus" \
    --output "${FI_RESULT_DIR}/redis-during.prom"
  prometheus_has_auth_l2_error "${FI_RESULT_DIR}/redis-during.prom" \
    || fi_die "Redis outage request succeeded but no auth L2 error metric was recorded"

  fi_start_service redis
  fi_wait_until Redis-recovery "${FI_WAIT_TIMEOUT_SECONDS}" fi_redis_ready
  sleep 1
  fi_auth_me_request "${FI_APP_PORT}" "${FI_RESULT_DIR}/redis-after"
  fi_assert_success_response "${FI_RESULT_DIR}/redis-after" 200
  fi_record_result redis PASS \
    "after L1 expiry, /api/auth/me stayed HTTP 200 via DB fail-open during Redis outage (${outage_elapsed_ms} ms) and remained 200 after recovery" \
    "redis-before.json,redis-during.json,redis-after.json,redis-during.prom"
}

scenario_rabbitmq_outbox() {
  set_scenario rabbitmq
  local document_id
  fi_configure_mock success 20 > "${FI_RESULT_DIR}/rabbitmq-mock-control.json"
  fi_compose stop rabbitmq >/dev/null
  fi_wait_until RabbitMQ-stop 30 wait_service_stopped rabbitmq

  document_id="$(fi_upload_document rabbitmq-outage)"
  [[ "${document_id}" =~ ^[1-9][0-9]*$ ]] || fi_die "Upload returned an invalid document ID"
  fi_wait_until "Outbox publish failure for document ${document_id}" \
    "${FI_DOCUMENT_TIMEOUT_SECONDS}" outbox_failure_recorded "${document_id}"
  fi_snapshot_document "${document_id}" "${FI_RESULT_DIR}/rabbitmq-during-document.json"
  fi_snapshot_outbox "${document_id}" "${FI_RESULT_DIR}/rabbitmq-during-outbox.json"

  fi_start_service rabbitmq
  fi_wait_until RabbitMQ-recovery "${FI_WAIT_TIMEOUT_SECONDS}" fi_rabbit_ready
  fi_wait_until RabbitMQ-management-recovery "${FI_WAIT_TIMEOUT_SECONDS}" fi_management_ready
  fi_wait_document_status "${document_id}" READY
  fi_wait_outbox_published "${document_id}" 1
  fi_snapshot_document "${document_id}" "${FI_RESULT_DIR}/rabbitmq-after-document.json"
  fi_snapshot_outbox "${document_id}" "${FI_RESULT_DIR}/rabbitmq-after-outbox.json"
  fi_queue_snapshot "${FI_QUEUE}" "${FI_RESULT_DIR}/rabbitmq-after-main-queue.json"

  jq -e '.[0].publish_attempts >= 2 and .[0].status == "PUBLISHED"' \
    "${FI_RESULT_DIR}/rabbitmq-after-outbox.json" >/dev/null \
    || fi_die "Outbox was published but did not record a failed publish followed by retry"
  jq -e '.status == "READY"' "${FI_RESULT_DIR}/rabbitmq-after-document.json" >/dev/null
  fi_record_result rabbitmq PASS \
    "upload returned 202 while RabbitMQ was stopped; Outbox recorded publish failure, retried after recovery, and document ${document_id} reached READY" \
    "rabbitmq-during-outbox.json,rabbitmq-after-outbox.json,rabbitmq-after-document.json,rabbitmq-after-main-queue.json"
}

scenario_worker_restart() {
  set_scenario worker-restart
  local document_id fence_before fence_after attempts_after
  fi_configure_mock success "${FI_WORKER_DELAY_MS}" > "${FI_RESULT_DIR}/worker-mock-slow.json"
  document_id="$(fi_upload_document worker-restart)"
  [[ "${document_id}" =~ ^[1-9][0-9]*$ ]] || fi_die "Upload returned an invalid document ID"
  fi_wait_until "owned PROCESSING document ${document_id}" \
    "${FI_DOCUMENT_TIMEOUT_SECONDS}" document_is_processing_with_owner "${document_id}"
  fi_snapshot_document "${document_id}" "${FI_RESULT_DIR}/worker-before-crash.json"
  fence_before="$(jq -er '.processing_fence' "${FI_RESULT_DIR}/worker-before-crash.json")"

  fi_stop_pid "${FI_PRIMARY_PID}" KILL
  FI_PRIMARY_PID=""
  fi_wait_until "RabbitMQ redelivery for document ${document_id}" \
    "${FI_WAIT_TIMEOUT_SECONDS}" queue_has_ready_message "${FI_QUEUE}"
  fi_queue_snapshot "${FI_QUEUE}" "${FI_RESULT_DIR}/worker-after-crash-queue.json"
  fi_configure_mock success 20 > "${FI_RESULT_DIR}/worker-mock-recovered.json"
  fi_start_primary_app
  fi_wait_document_status "${document_id}" READY
  fi_snapshot_document "${document_id}" "${FI_RESULT_DIR}/worker-after-restart.json"
  fence_after="$(jq -er '.processing_fence' "${FI_RESULT_DIR}/worker-after-restart.json")"
  attempts_after="$(jq -er '.processing_attempts' "${FI_RESULT_DIR}/worker-after-restart.json")"

  (( fence_after > fence_before )) || fi_die "Processing fence did not advance after worker restart"
  [[ "${attempts_after}" == "1" ]] || fi_die "Broker redelivery incorrectly consumed a new logical attempt"
  jq -e '.status == "READY" and .processing_token == null and .processing_lease_until == null' \
    "${FI_RESULT_DIR}/worker-after-restart.json" >/dev/null
  fi_record_result worker-restart PASS \
    "worker was SIGKILLed in PROCESSING; broker redelivery reacquired ownership with fence ${fence_before}->${fence_after}, kept logical attempt=1, and reached READY" \
    "worker-before-crash.json,worker-after-crash-queue.json,worker-after-restart.json"
}

scenario_embedding_timeout() {
  set_scenario embedding-timeout
  local document_id attempts_failed attempts_ready outbox_failed_count parking_ready
  fi_configure_mock request_timeout 50 > "${FI_RESULT_DIR}/embedding-timeout-mock.json"
  document_id="$(fi_upload_document embedding-timeout)"
  [[ "${document_id}" =~ ^[1-9][0-9]*$ ]] || fi_die "Upload returned an invalid document ID"
  fi_wait_document_status "${document_id}" FAILED
  fi_wait_until "parking message for document ${document_id}" \
    "${FI_DOCUMENT_TIMEOUT_SECONDS}" fi_parking_has_message
  fi_snapshot_document "${document_id}" "${FI_RESULT_DIR}/embedding-timeout-failed-document.json"
  fi_snapshot_outbox "${document_id}" "${FI_RESULT_DIR}/embedding-timeout-failed-outbox.json"
  fi_queue_snapshot "${FI_PARKING_QUEUE}" "${FI_RESULT_DIR}/embedding-timeout-parking.json"
  curl --fail --silent --show-error \
    "http://127.0.0.1:${FI_EMBEDDING_PORT}/stats" \
    --output "${FI_RESULT_DIR}/embedding-timeout-mock-stats.json"

  attempts_failed="$(jq -er '.processing_attempts' "${FI_RESULT_DIR}/embedding-timeout-failed-document.json")"
  outbox_failed_count="$(jq -er 'length' "${FI_RESULT_DIR}/embedding-timeout-failed-outbox.json")"
  parking_ready="$(jq -er '.messages_ready' "${FI_RESULT_DIR}/embedding-timeout-parking.json")"
  [[ "${attempts_failed}" == "3" ]] || fi_die "Expected 3 finite attempts, observed ${attempts_failed}"
  [[ "${outbox_failed_count}" == "3" ]] || fi_die "Expected 3 Outbox deliveries, observed ${outbox_failed_count}"
  (( parking_ready >= 1 )) || fi_die "Parking queue did not receive the exhausted delivery"
  jq -e '.status == "FAILED" and .last_error_code == "EMBEDDING_INVOCATION_FAILED"' \
    "${FI_RESULT_DIR}/embedding-timeout-failed-document.json" >/dev/null

  fi_configure_mock success 20 > "${FI_RESULT_DIR}/embedding-timeout-mock-recovered.json"
  fi_retry_document "${document_id}"
  fi_wait_document_status "${document_id}" READY
  fi_wait_outbox_published "${document_id}" 4
  fi_snapshot_document "${document_id}" "${FI_RESULT_DIR}/embedding-timeout-manual-ready.json"
  fi_snapshot_outbox "${document_id}" "${FI_RESULT_DIR}/embedding-timeout-manual-outbox.json"
  attempts_ready="$(jq -er '.processing_attempts' "${FI_RESULT_DIR}/embedding-timeout-manual-ready.json")"
  [[ "${attempts_ready}" == "4" ]] || fi_die "Manual retry did not start a new attempt"
  jq -e '.status == "READY" and .chunk_count > 0' \
    "${FI_RESULT_DIR}/embedding-timeout-manual-ready.json" >/dev/null
  fi_record_result embedding-timeout PASS \
    "injected upstream HTTP 408 exhausted exactly 3 attempts and parked 1 message; after Mock recovery, manual retry attempt 4 reached READY" \
    "embedding-timeout-failed-document.json,embedding-timeout-failed-outbox.json,embedding-timeout-parking.json,embedding-timeout-manual-ready.json"
}

on_error() {
  local line_number="$1"
  local exit_code="$2"
  fi_log "Scenario ${FI_CURRENT_SCENARIO} failed at line ${line_number} with exit ${exit_code}"
}

trap 'on_error "${LINENO}" "$?"' ERR
trap 'fi_cleanup "$?"' EXIT

fi_require_tools
fi_write_metadata
printf 'requested_scenarios=%s\n' "$(IFS=,; printf '%s' "${REQUESTED_SCENARIOS[*]}")" >> "${FI_METADATA_FILE}"
fi_start_infrastructure
fi_assert_no_unfinished_outbox
fi_start_mock
fi_start_primary_app
fi_register_and_login
fi_seed_models_and_knowledge_base

if scenario_requested cache-cross-instance; then
  scenario_cache_cross_instance
fi
if scenario_requested redis; then
  scenario_redis_failover
fi
if scenario_requested rabbitmq; then
  scenario_rabbitmq_outbox
fi
if scenario_requested worker-restart; then
  scenario_worker_restart
fi
if scenario_requested embedding-timeout; then
  scenario_embedding_timeout
fi

FI_CURRENT_SCENARIO=complete
FI_CURRENT_SCENARIO_RECORDED=true
fi_render_report
fi_log "All requested scenarios passed"
