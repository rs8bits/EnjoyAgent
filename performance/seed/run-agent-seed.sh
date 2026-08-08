#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PERF_EMAIL="${PERF_EMAIL:-perf-baseline@example.test}"
PERF_AGENT_COUNT="${PERF_AGENT_COUNT:-10000}"
DB_USERNAME="${DB_USERNAME:-enjoy}"
DB_NAME="${DB_NAME:-enjoy_agent}"

if [[ ! "${PERF_AGENT_COUNT}" =~ ^[1-9][0-9]*$ ]]; then
  echo "PERF_AGENT_COUNT must be a positive integer." >&2
  exit 1
fi

cd "${PROJECT_DIR}"

TENANT_ID=$(printf '%s\n' \
  "select tm.tenant_id from tenant_member tm join app_user u on u.id = tm.user_id where u.email = :'perf_email' order by tm.id limit 1;" \
  | docker compose exec -T postgres psql -U "${DB_USERNAME}" -d "${DB_NAME}" \
      -v perf_email="${PERF_EMAIL}" -Atq)

if [[ -z "${TENANT_ID}" ]]; then
  echo "No tenant found for PERF_EMAIL=${PERF_EMAIL}; register the benchmark user first." >&2
  exit 1
fi

MODEL_CONFIG_ID=$(printf '%s\n' \
  "select id from model_config where tenant_id = :tenant_id and model_type = 'CHAT' and enabled = true order by id limit 1;" \
  | docker compose exec -T postgres psql -U "${DB_USERNAME}" -d "${DB_NAME}" \
      -v tenant_id="${TENANT_ID}" -Atq)

if [[ -z "${MODEL_CONFIG_ID}" ]]; then
  echo "No enabled CHAT model config found for tenant ${TENANT_ID}." >&2
  exit 1
fi

docker compose exec -T postgres psql \
  -U "${DB_USERNAME}" \
  -d "${DB_NAME}" \
  -v tenant_id="${TENANT_ID}" \
  -v model_config_id="${MODEL_CONFIG_ID}" \
  -v agent_count="${PERF_AGENT_COUNT}" \
  < "${SCRIPT_DIR}/agent-dataset.sql"

echo "tenant_id=${TENANT_ID} model_config_id=${MODEL_CONFIG_ID} requested_agents=${PERF_AGENT_COUNT}"
