#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
JMETER_BIN="${JMETER_BIN:-jmeter}"
PERF_PROTOCOL="${PERF_PROTOCOL:-http}"
PERF_HOST="${PERF_HOST:-127.0.0.1}"
PERF_PORT="${PERF_PORT:-8080}"
PERF_SCENARIO="${PERF_SCENARIO:-auth-me}"
PERF_VARIANT="${PERF_VARIANT:-baseline}"
PERF_EMAIL="${PERF_EMAIL:-perf-baseline@example.test}"
PERF_PASSWORD="${PERF_PASSWORD:-PerfPassw0rd!}"
PERF_DISPLAY_NAME="${PERF_DISPLAY_NAME:-Performance Baseline}"
PERF_THREADS="${PERF_THREADS:-20}"
PERF_RAMP_SECONDS="${PERF_RAMP_SECONDS:-10}"
PERF_MEASUREMENT_RAMP_SECONDS="${PERF_MEASUREMENT_RAMP_SECONDS:-0}"
PERF_WARMUP_SECONDS="${PERF_WARMUP_SECONDS:-60}"
PERF_DURATION_SECONDS="${PERF_DURATION_SECONDS:-180}"
PERF_TARGET_RPM="${PERF_TARGET_RPM:-6000}"
PERF_AGENT_ID="${PERF_AGENT_ID:-}"
PERF_MAX_ERROR_RATE="${PERF_MAX_ERROR_RATE:-0.01}"
PERF_MIN_ACHIEVEMENT_RATIO="${PERF_MIN_ACHIEVEMENT_RATIO:-0.90}"
PERF_MIN_SAMPLES="${PERF_MIN_SAMPLES:-100}"
PERF_MIN_DURATION_RATIO="${PERF_MIN_DURATION_RATIO:-0.95}"
PERF_MAX_DURATION_RATIO="${PERF_MAX_DURATION_RATIO:-1.10}"
PERF_ACTUATOR_URL="${PERF_ACTUATOR_URL:-${PERF_PROTOCOL}://${PERF_HOST}:${PERF_PORT}/actuator/prometheus}"

if [[ ! "${PERF_VARIANT}" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "PERF_VARIANT may only contain letters, digits, dot, underscore and dash." >&2
  exit 1
fi

if ! command -v "${JMETER_BIN}" >/dev/null 2>&1; then
  echo "JMeter executable not found: ${JMETER_BIN}" >&2
  echo "Install Apache JMeter 5.6.3 or set JMETER_BIN=/path/to/jmeter." >&2
  exit 1
fi

BASE_URL="${PERF_PROTOCOL}://${PERF_HOST}:${PERF_PORT}"
case "${PERF_SCENARIO}" in
  ping)
    PERF_PATH="/api/system/ping"
    ;;
  auth-me)
    PERF_PATH="/api/auth/me"
    ;;
  agents-list)
    PERF_PATH="/api/agents?page=0&size=20"
    ;;
  agent-detail)
    if [[ -z "${PERF_AGENT_ID}" ]]; then
      echo "PERF_AGENT_ID is required for agent-detail." >&2
      exit 1
    fi
    PERF_PATH="/api/agents/${PERF_AGENT_ID}"
    ;;
  *)
    echo "Unknown PERF_SCENARIO: ${PERF_SCENARIO}" >&2
    exit 1
    ;;
esac

ACCESS_TOKEN="${PERF_ACCESS_TOKEN:-}"
if [[ "${PERF_SCENARIO}" != "ping" && -z "${ACCESS_TOKEN}" ]]; then
  REGISTER_PAYLOAD=$(PERF_EMAIL="${PERF_EMAIL}" PERF_PASSWORD="${PERF_PASSWORD}" PERF_DISPLAY_NAME="${PERF_DISPLAY_NAME}" python3 -c 'import json, os; print(json.dumps({"email": os.environ["PERF_EMAIL"], "password": os.environ["PERF_PASSWORD"], "displayName": os.environ["PERF_DISPLAY_NAME"]}))')
  LOGIN_PAYLOAD=$(PERF_EMAIL="${PERF_EMAIL}" PERF_PASSWORD="${PERF_PASSWORD}" python3 -c 'import json, os; print(json.dumps({"email": os.environ["PERF_EMAIL"], "password": os.environ["PERF_PASSWORD"]}))')

  curl --silent --show-error --output /dev/null \
    --header 'Content-Type: application/json' \
    --data "${REGISTER_PAYLOAD}" \
    "${BASE_URL}/api/auth/register"

  LOGIN_RESPONSE=$(curl --fail --silent --show-error \
    --header 'Content-Type: application/json' \
    --data "${LOGIN_PAYLOAD}" \
    "${BASE_URL}/api/auth/login")
  ACCESS_TOKEN=$(LOGIN_RESPONSE="${LOGIN_RESPONSE}" python3 -c 'import json, os; print(json.loads(os.environ["LOGIN_RESPONSE"])["data"]["accessToken"])')
fi

RUN_STAMP=$(date -u +%Y%m%dT%H%M%SZ)
RESULT_DIR="${PROJECT_DIR}/performance/results/${PERF_VARIANT}-${PERF_SCENARIO}-${RUN_STAMP}"
mkdir -p "${RESULT_DIR}"

{
  echo "timestamp_utc=${RUN_STAMP}"
  echo "scenario=${PERF_SCENARIO}"
  echo "variant=${PERF_VARIANT}"
  echo "path=${PERF_PATH}"
  echo "threads=${PERF_THREADS}"
  echo "target_rpm=${PERF_TARGET_RPM}"
  echo "warmup_ramp_seconds=${PERF_RAMP_SECONDS}"
  echo "measurement_ramp_seconds=${PERF_MEASUREMENT_RAMP_SECONDS}"
  echo "warmup_seconds=${PERF_WARMUP_SECONDS}"
  echo "duration_seconds=${PERF_DURATION_SECONDS}"
  echo "max_error_rate=${PERF_MAX_ERROR_RATE}"
  echo "min_achievement_ratio=${PERF_MIN_ACHIEVEMENT_RATIO}"
  echo "min_samples=${PERF_MIN_SAMPLES}"
  echo "min_duration_ratio=${PERF_MIN_DURATION_RATIO}"
  echo "max_duration_ratio=${PERF_MAX_DURATION_RATIO}"
  echo "git_commit=$(git -C "${PROJECT_DIR}" rev-parse HEAD)"
  echo "git_dirty_count=$(git -C "${PROJECT_DIR}" status --short | wc -l | tr -d ' ')"
  java -version 2>&1 | sed 's/^/java=/'
  "${JMETER_BIN}" -j "${RESULT_DIR}/version-jmeter.log" --version 2>&1 | sed 's/^/jmeter=/'
} > "${RESULT_DIR}/metadata.txt"

COMMON_ARGS=(
  -n
  -t "${SCRIPT_DIR}/enjoy-agent-baseline.jmx"
  -q "${SCRIPT_DIR}/user.properties"
  "-Jprotocol=${PERF_PROTOCOL}"
  "-Jhost=${PERF_HOST}"
  "-Jport=${PERF_PORT}"
  "-Jscenario=${PERF_SCENARIO}"
  "-Jpath=${PERF_PATH}"
  "-Jthreads=${PERF_THREADS}"
  "-JtargetRpm=${PERF_TARGET_RPM}"
)

echo "Warmup: ${PERF_SCENARIO} for ${PERF_WARMUP_SECONDS}s"
JMETER_ACCESS_TOKEN="${ACCESS_TOKEN}" "${JMETER_BIN}" "${COMMON_ARGS[@]}" \
  -JrunLabel=WARMUP \
  "-JrampSeconds=${PERF_RAMP_SECONDS}" \
  "-JdurationSeconds=${PERF_WARMUP_SECONDS}" \
  -j "${RESULT_DIR}/warmup-jmeter.log" \
  -l "${RESULT_DIR}/warmup.jtl"

curl --fail --silent --show-error "${PERF_ACTUATOR_URL}" \
  --output "${RESULT_DIR}/server-before.prom" || true

echo "Measurement: ${PERF_SCENARIO} for ${PERF_DURATION_SECONDS}s"
JMETER_ACCESS_TOKEN="${ACCESS_TOKEN}" "${JMETER_BIN}" "${COMMON_ARGS[@]}" \
  -JrunLabel=MEASURE \
  "-JrampSeconds=${PERF_MEASUREMENT_RAMP_SECONDS}" \
  "-JdurationSeconds=${PERF_DURATION_SECONDS}" \
  -j "${RESULT_DIR}/measurement-jmeter.log" \
  -l "${RESULT_DIR}/measurement.jtl" \
  -e -o "${RESULT_DIR}/html"

curl --fail --silent --show-error "${PERF_ACTUATOR_URL}" \
  --output "${RESULT_DIR}/server-after.prom" || true

MEASUREMENT_JTL="${RESULT_DIR}/measurement.jtl" PERF_MAX_ERROR_RATE="${PERF_MAX_ERROR_RATE}" python3 -c '
import csv, os, sys
with open(os.environ["MEASUREMENT_JTL"], newline="", encoding="utf-8") as source:
    rows = list(csv.DictReader(source))
if not rows:
    raise SystemExit("Measurement produced no JMeter samples")
failed = sum(row.get("success", "").lower() != "true" for row in rows)
error_rate = failed / len(rows)
limit_text = os.environ["PERF_MAX_ERROR_RATE"]
limit = float(limit_text)
print(f"Measurement samples={len(rows)} failed={failed} error_rate={error_rate:.4%}")
if error_rate > limit:
    raise SystemExit(f"Error rate {error_rate:.4%} exceeds PERF_MAX_ERROR_RATE={limit_text}")
'

python3 "${SCRIPT_DIR}/summarize-jtl.py" \
  "${RESULT_DIR}/measurement.jtl" \
  --target-rpm "${PERF_TARGET_RPM}" \
  --output "${RESULT_DIR}/summary.json" \
  | tee "${RESULT_DIR}/summary.txt"

SUMMARY_JSON="${RESULT_DIR}/summary.json" \
PERF_MIN_ACHIEVEMENT_RATIO="${PERF_MIN_ACHIEVEMENT_RATIO}" \
PERF_MIN_SAMPLES="${PERF_MIN_SAMPLES}" \
PERF_EXPECTED_DURATION_SECONDS="${PERF_DURATION_SECONDS}" \
PERF_MIN_DURATION_RATIO="${PERF_MIN_DURATION_RATIO}" \
PERF_MAX_DURATION_RATIO="${PERF_MAX_DURATION_RATIO}" python3 -c '
import json, os
with open(os.environ["SUMMARY_JSON"], encoding="utf-8") as source:
    summary = json.load(source)
minimum_samples = int(os.environ["PERF_MIN_SAMPLES"])
minimum_ratio = float(os.environ["PERF_MIN_ACHIEVEMENT_RATIO"])
expected_duration = float(os.environ["PERF_EXPECTED_DURATION_SECONDS"])
duration_ratio = summary["duration_seconds"] / expected_duration
minimum_duration_ratio = float(os.environ["PERF_MIN_DURATION_RATIO"])
maximum_duration_ratio = float(os.environ["PERF_MAX_DURATION_RATIO"])
if summary["samples"] < minimum_samples:
    raise SystemExit("Only {} samples; expected at least {}".format(summary["samples"], minimum_samples))
if not minimum_duration_ratio <= duration_ratio <= maximum_duration_ratio:
    raise SystemExit(
        "Measured sample window was {:.3f}s ({:.2%} of expected {:.3f}s); allowed range is {:.2%}..{:.2%}".format(
            summary["duration_seconds"], duration_ratio, expected_duration,
            minimum_duration_ratio, maximum_duration_ratio
        )
    )
if summary["achievement_ratio"] < minimum_ratio:
    raise SystemExit(
        "Actual throughput reached {:.2%} of target; expected at least {:.2%}".format(
            summary["achievement_ratio"], minimum_ratio
        )
    )
'

echo "Results: ${RESULT_DIR}"
