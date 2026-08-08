#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PERF_EMAIL="${PERF_EMAIL:-perf-baseline@example.test}"
POSTGRES_SERVICE="${POSTGRES_SERVICE:-postgres}"
DB_NAME="${DB_NAME:-enjoy_agent}"
DB_USERNAME="${DB_USERNAME:-enjoy}"

TENANT_ID="$(printf '%s\n' \
  "SELECT tm.tenant_id FROM tenant_member tm JOIN app_user u ON u.id = tm.user_id WHERE u.email = :'email' AND tm.status = 'ACTIVE' ORDER BY tm.id LIMIT 1;" \
  | docker compose -f "${PROJECT_DIR}/compose.yml" exec -T "${POSTGRES_SERVICE}" \
      psql -U "${DB_USERNAME}" -d "${DB_NAME}" -Atq -v email="${PERF_EMAIL}")"

if [[ ! "${TENANT_ID}" =~ ^[0-9]+$ ]]; then
  echo "Could not resolve an active tenant for PERF_EMAIL=${PERF_EMAIL}" >&2
  exit 1
fi

docker compose -f "${PROJECT_DIR}/compose.yml" exec -T "${POSTGRES_SERVICE}" \
  psql -U "${DB_USERNAME}" -d "${DB_NAME}" \
  -v tenant_id="${TENANT_ID}" \
  -f - < "${SCRIPT_DIR}/knowledge-ingest-dataset.sql"
