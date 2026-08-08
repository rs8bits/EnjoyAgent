#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
JMETER_BIN="${JMETER_BIN:-jmeter}"
PERF_PROTOCOL="${PERF_PROTOCOL:-http}"
PERF_HOST="${PERF_HOST:-127.0.0.1}"
PERF_PORT="${PERF_PORT:-8080}"
PERF_EMAIL="${PERF_EMAIL:-perf-baseline@example.test}"
PERF_PASSWORD="${PERF_PASSWORD:-PerfPassw0rd!}"
PERF_KNOWLEDGE_BASE_ID="${PERF_KNOWLEDGE_BASE_ID:-}"
PERF_DOCUMENT_FILE="${PERF_DOCUMENT_FILE:-}"
PERF_DOCUMENT_CONTENT_TYPE="${PERF_DOCUMENT_CONTENT_TYPE:-text/plain}"
PERF_THREADS="${PERF_THREADS:-1}"
PERF_LOOPS="${PERF_LOOPS:-1}"
PERF_RAMP_SECONDS="${PERF_RAMP_SECONDS:-0}"
PERF_EXPECTED_STATUS="${PERF_EXPECTED_STATUS:-200}"
PERF_POLL_UNTIL_READY="${PERF_POLL_UNTIL_READY:-false}"
PERF_POLL_INTERVAL_MS="${PERF_POLL_INTERVAL_MS:-500}"
PERF_POLL_TIMEOUT_SECONDS="${PERF_POLL_TIMEOUT_SECONDS:-120}"
PERF_CONNECT_TIMEOUT_MS="${PERF_CONNECT_TIMEOUT_MS:-3000}"
PERF_RESPONSE_TIMEOUT_MS="${PERF_RESPONSE_TIMEOUT_MS:-30000}"
PERF_MAX_ERROR_RATE="${PERF_MAX_ERROR_RATE:-0}"
PERF_VARIANT="${PERF_VARIANT:-document-ingest}"
PERF_ACTUATOR_URL="${PERF_ACTUATOR_URL:-${PERF_PROTOCOL}://${PERF_HOST}:${PERF_PORT}/actuator/prometheus}"
PERF_EMBEDDING_STATS_URL="${PERF_EMBEDDING_STATS_URL:-}"
PERF_RESET_EMBEDDING_STATS="${PERF_RESET_EMBEDDING_STATS:-false}"

require_positive_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${name} must be a positive integer." >&2
    exit 1
  fi
}

require_nonnegative_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[0-9]+$ ]]; then
    echo "${name} must be a non-negative integer." >&2
    exit 1
  fi
}

normalize_boolean() {
  case "${2}" in
    true|TRUE|1|yes|YES) printf 'true' ;;
    false|FALSE|0|no|NO) printf 'false' ;;
    *)
      echo "$1 must be true or false." >&2
      exit 1
      ;;
  esac
}

if ! command -v "${JMETER_BIN}" >/dev/null 2>&1; then
  echo "JMeter executable not found: ${JMETER_BIN}" >&2
  echo "Install Apache JMeter 5.6.3 or set JMETER_BIN=/absolute/path/to/jmeter." >&2
  exit 1
fi
for required_command in curl python3; do
  if ! command -v "${required_command}" >/dev/null 2>&1; then
    echo "Required executable not found: ${required_command}" >&2
    exit 1
  fi
done

if [[ ! "${PERF_VARIANT}" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "PERF_VARIANT may only contain letters, digits, dot, underscore and dash." >&2
  exit 1
fi
require_positive_integer PERF_KNOWLEDGE_BASE_ID "${PERF_KNOWLEDGE_BASE_ID}"
require_positive_integer PERF_THREADS "${PERF_THREADS}"
require_positive_integer PERF_LOOPS "${PERF_LOOPS}"
require_nonnegative_integer PERF_RAMP_SECONDS "${PERF_RAMP_SECONDS}"
require_positive_integer PERF_POLL_INTERVAL_MS "${PERF_POLL_INTERVAL_MS}"
require_positive_integer PERF_POLL_TIMEOUT_SECONDS "${PERF_POLL_TIMEOUT_SECONDS}"
require_positive_integer PERF_CONNECT_TIMEOUT_MS "${PERF_CONNECT_TIMEOUT_MS}"
require_positive_integer PERF_RESPONSE_TIMEOUT_MS "${PERF_RESPONSE_TIMEOUT_MS}"

if [[ "${PERF_EXPECTED_STATUS}" != "200" && "${PERF_EXPECTED_STATUS}" != "202" ]]; then
  echo "PERF_EXPECTED_STATUS must be 200 or 202." >&2
  exit 1
fi
PERF_POLL_UNTIL_READY="$(normalize_boolean PERF_POLL_UNTIL_READY "${PERF_POLL_UNTIL_READY}")"
PERF_RESET_EMBEDDING_STATS="$(normalize_boolean PERF_RESET_EMBEDDING_STATS "${PERF_RESET_EMBEDDING_STATS}")"

if [[ -z "${PERF_DOCUMENT_FILE}" || ! -f "${PERF_DOCUMENT_FILE}" ]]; then
  echo "PERF_DOCUMENT_FILE must point to a readable regular file." >&2
  exit 1
fi
if [[ ! -r "${PERF_DOCUMENT_FILE}" ]]; then
  echo "PERF_DOCUMENT_FILE is not readable: ${PERF_DOCUMENT_FILE}" >&2
  exit 1
fi
PERF_DOCUMENT_FILE="$(cd "$(dirname "${PERF_DOCUMENT_FILE}")" && pwd -P)/$(basename "${PERF_DOCUMENT_FILE}")"

if ! python3 - "${PERF_MAX_ERROR_RATE}" <<'PY'
import sys
try:
    value = float(sys.argv[1])
except ValueError:
    raise SystemExit(1)
raise SystemExit(0 if 0 <= value <= 1 else 1)
PY
then
  echo "PERF_MAX_ERROR_RATE must be a number between 0 and 1." >&2
  exit 1
fi

BASE_URL="${PERF_PROTOCOL}://${PERF_HOST}:${PERF_PORT}"
curl --fail --silent --show-error "${BASE_URL}/actuator/health/readiness" >/dev/null

ACCESS_TOKEN="${PERF_ACCESS_TOKEN:-}"
if [[ -z "${ACCESS_TOKEN}" ]]; then
  LOGIN_PAYLOAD=$(PERF_EMAIL="${PERF_EMAIL}" PERF_PASSWORD="${PERF_PASSWORD}" python3 -c 'import json, os; print(json.dumps({"email": os.environ["PERF_EMAIL"], "password": os.environ["PERF_PASSWORD"]}))')
  LOGIN_RESPONSE=$(curl --fail --silent --show-error \
    --header 'Content-Type: application/json' \
    --data "${LOGIN_PAYLOAD}" \
    "${BASE_URL}/api/auth/login")
  ACCESS_TOKEN=$(LOGIN_RESPONSE="${LOGIN_RESPONSE}" python3 -c 'import json, os; print(json.loads(os.environ["LOGIN_RESPONSE"])["data"]["accessToken"])')
fi
if [[ -z "${ACCESS_TOKEN}" ]]; then
  echo "Authentication did not return an access token." >&2
  exit 1
fi

RUN_STAMP=$(date -u +%Y%m%dT%H%M%SZ)
RESULT_DIR="${PROJECT_DIR}/performance/results/${PERF_VARIANT}-${RUN_STAMP}-$$"
mkdir -p "${RESULT_DIR}"
EXPECTED_DOCUMENTS=$((PERF_THREADS * PERF_LOOPS))
POLL_TIMEOUT_MS=$((PERF_POLL_TIMEOUT_SECONDS * 1000))
FILE_SHA256=$(python3 - "${PERF_DOCUMENT_FILE}" <<'PY'
import hashlib, pathlib, sys
digest = hashlib.sha256()
with pathlib.Path(sys.argv[1]).open("rb") as source:
    for block in iter(lambda: source.read(1024 * 1024), b""):
        digest.update(block)
print(digest.hexdigest())
PY
)
FILE_BYTES=$(wc -c < "${PERF_DOCUMENT_FILE}" | tr -d ' ')

{
  echo "timestamp_utc=${RUN_STAMP}"
  echo "variant=${PERF_VARIANT}"
  echo "knowledge_base_id=${PERF_KNOWLEDGE_BASE_ID}"
  echo "document_file=${PERF_DOCUMENT_FILE}"
  echo "document_content_type=${PERF_DOCUMENT_CONTENT_TYPE}"
  echo "document_bytes=${FILE_BYTES}"
  echo "document_sha256=${FILE_SHA256}"
  echo "threads=${PERF_THREADS}"
  echo "loops=${PERF_LOOPS}"
  echo "expected_documents=${EXPECTED_DOCUMENTS}"
  echo "ramp_seconds=${PERF_RAMP_SECONDS}"
  echo "expected_http_status=${PERF_EXPECTED_STATUS}"
  echo "poll_until_ready=${PERF_POLL_UNTIL_READY}"
  echo "poll_interval_ms=${PERF_POLL_INTERVAL_MS}"
  echo "poll_timeout_seconds=${PERF_POLL_TIMEOUT_SECONDS}"
  echo "max_error_rate=${PERF_MAX_ERROR_RATE}"
  echo "git_commit=$(git -C "${PROJECT_DIR}" rev-parse HEAD)"
  echo "git_dirty_count=$(git -C "${PROJECT_DIR}" status --short | wc -l | tr -d ' ')"
  java -version 2>&1 | sed 's/^/java=/'
  "${JMETER_BIN}" -j "${RESULT_DIR}/version-jmeter.log" --version 2>&1 | sed 's/^/jmeter=/'
} > "${RESULT_DIR}/metadata.txt"

curl --fail --silent --show-error "${PERF_ACTUATOR_URL}" \
  --output "${RESULT_DIR}/server-before.prom" || true
if [[ -n "${PERF_EMBEDDING_STATS_URL}" ]]; then
  if [[ "${PERF_RESET_EMBEDDING_STATS}" == "true" ]]; then
    curl --fail --silent --show-error --request POST \
      "${PERF_EMBEDDING_STATS_URL%/}/reset" >/dev/null
  fi
  curl --fail --silent --show-error "${PERF_EMBEDDING_STATS_URL}" \
    --output "${RESULT_DIR}/embedding-stats-before.json" || true
fi

echo "Document ingestion: expected=${EXPECTED_DOCUMENTS} status=${PERF_EXPECTED_STATUS} poll=${PERF_POLL_UNTIL_READY}"
JMETER_ACCESS_TOKEN="${ACCESS_TOKEN}" "${JMETER_BIN}" \
  -n \
  -t "${SCRIPT_DIR}/document-upload.jmx" \
  -q "${SCRIPT_DIR}/user.properties" \
  "-Jprotocol=${PERF_PROTOCOL}" \
  "-Jhost=${PERF_HOST}" \
  "-Jport=${PERF_PORT}" \
  "-JknowledgeBaseId=${PERF_KNOWLEDGE_BASE_ID}" \
  "-JdocumentFile=${PERF_DOCUMENT_FILE}" \
  "-JcontentType=${PERF_DOCUMENT_CONTENT_TYPE}" \
  "-Jthreads=${PERF_THREADS}" \
  "-Jloops=${PERF_LOOPS}" \
  "-JrampSeconds=${PERF_RAMP_SECONDS}" \
  "-JexpectedStatus=${PERF_EXPECTED_STATUS}" \
  "-JpollUntilReady=${PERF_POLL_UNTIL_READY}" \
  "-JpollIntervalMs=${PERF_POLL_INTERVAL_MS}" \
  "-JpollTimeoutMs=${POLL_TIMEOUT_MS}" \
  "-JconnectTimeoutMs=${PERF_CONNECT_TIMEOUT_MS}" \
  "-JresponseTimeoutMs=${PERF_RESPONSE_TIMEOUT_MS}" \
  -j "${RESULT_DIR}/jmeter.log" \
  -l "${RESULT_DIR}/measurement.jtl" \
  -e -o "${RESULT_DIR}/html"

curl --fail --silent --show-error "${PERF_ACTUATOR_URL}" \
  --output "${RESULT_DIR}/server-after.prom" || true
if [[ -n "${PERF_EMBEDDING_STATS_URL}" ]]; then
  curl --fail --silent --show-error "${PERF_EMBEDDING_STATS_URL}" \
    --output "${RESULT_DIR}/embedding-stats-after.json" || true
fi

python3 "${SCRIPT_DIR}/summarize-document-jtl.py" \
  "${RESULT_DIR}/measurement.jtl" \
  --output "${RESULT_DIR}/summary.json" \
  | tee "${RESULT_DIR}/summary.txt"

SUMMARY_JSON="${RESULT_DIR}/summary.json" \
EXPECTED_DOCUMENTS="${EXPECTED_DOCUMENTS}" \
POLL_UNTIL_READY="${PERF_POLL_UNTIL_READY}" \
MAX_ERROR_RATE="${PERF_MAX_ERROR_RATE}" python3 -c '
import json, os
with open(os.environ["SUMMARY_JSON"], encoding="utf-8") as source:
    summary = json.load(source)
expected = int(os.environ["EXPECTED_DOCUMENTS"])
upload = summary["upload"]
if upload["samples"] != expected:
    raise SystemExit("Expected {} upload samples, observed {}".format(expected, upload["samples"]))
gate = upload
if os.environ["POLL_UNTIL_READY"] == "true":
    end_to_end = summary["end_to_end"]
    if end_to_end is None or end_to_end["samples"] != expected:
        observed = 0 if end_to_end is None else end_to_end["samples"]
        raise SystemExit(f"Expected {expected} end-to-end samples, observed {observed}")
    gate = end_to_end
maximum = float(os.environ["MAX_ERROR_RATE"])
if gate["error_rate"] > maximum:
    raise SystemExit("Error rate {:.4%} exceeds {:.4%}".format(gate["error_rate"], maximum))
'

echo "Results: ${RESULT_DIR}"
