# EnjoyAgent JMeter baseline

Use Apache JMeter 5.6.3 in non-GUI mode. The runner registers/logs in a dedicated benchmark user, runs a separate warmup, and only generates the HTML report from the measurement run.

## Prerequisites

- Java 21
- Apache JMeter 5.6.3
- `python3` and `curl`
- EnjoyAgent running with `SPRING_PROFILES_ACTIVE=perf`

The GUI is only for editing and smoke-debugging the JMX. Do not use GUI mode for formal measurements.

## Scenarios

- `ping`: minimal Servlet/JSON/JVM overhead; no authentication.
- `auth-me`: JWT parsing plus the current tenant membership database snapshot lookup.
- `agents-list`: authentication plus paged Agent query.
- `agent-detail`: authentication plus tenant-scoped Agent cache; requires `PERF_AGENT_ID`.

Use a fixed seed for comparable runs. In particular, `agents-list` should not benchmark a newly registered empty tenant: record the exact Agent count (for example 100 or 1000), and use an Agent from that same seed for `agent-detail`.

## Run

```bash
PERF_SCENARIO=ping \
PERF_THREADS=10 \
PERF_TARGET_RPM=3000 \
PERF_WARMUP_SECONDS=60 \
PERF_DURATION_SECONDS=180 \
./performance/jmeter/run-baseline.sh
```

```bash
PERF_SCENARIO=auth-me \
PERF_THREADS=50 \
PERF_TARGET_RPM=12000 \
PERF_WARMUP_SECONDS=120 \
PERF_DURATION_SECONDS=900 \
./performance/jmeter/run-baseline.sh
```

If JMeter is not on `PATH`, set `JMETER_BIN=/absolute/path/to/jmeter`. Existing users can skip registration/login by passing `PERF_ACCESS_TOKEN`.

Results are written under `performance/results/` and ignored by Git. Each run contains:

- `metadata.txt`: scenario, load, commit and tool versions.
- `warmup.jtl`: excluded from the formal HTML report.
- `measurement.jtl`: raw client-side samples.
- `summary.json`: machine-readable actual throughput, error rate and latency percentiles.
- `server-before.prom` / `server-after.prom`: best-effort application metric snapshots around the measurement window.
- `html/`: JMeter P50/P90/P95/P99, throughput and error report.

Warmup uses `PERF_RAMP_SECONDS`; the separate measurement invocation defaults to `PERF_MEASUREMENT_RAMP_SECONDS=0`, so its JTL does not mix the warmup ramp into the formal window. The runner asserts both HTTP 200 and the unified JSON `success=true`, then exits non-zero when the measured error rate exceeds `PERF_MAX_ERROR_RATE` (default `0.01`). Always report the actual RPS in `summary.json` instead of treating target RPM as achieved throughput.

The runner also rejects a run with fewer than `PERF_MIN_SAMPLES` samples, less than `PERF_MIN_ACHIEVEMENT_RATIO` of the offered load, or a JTL sample window outside 95%-110% of the requested duration. This catches host clock jumps and prematurely stopped runs. Aggregate repeated runs with `aggregate-summaries.py summary-1.json summary-2.json summary-3.json` and report the median plus min/max range.

For a cold-cache comparison, run the application with `APP_CACHE_AGENT_CONFIG_ENABLED=false` and keep that setting identical for all cold-cache repetitions. Warm-cache runs should use a separate application start with caching enabled and the same seed; do not mix a single cold request into an otherwise warm load test.

Run each formal group at least three times with the same JVM heap, Docker limits and data set. Use JMeter as the client-side source of truth and correlate the same time window with the provisioned Grafana dashboard.

Set `PERF_VARIANT` (for example `baseline`, `stage2`) to keep otherwise identical runs grouped in their result-directory names. Formal comparisons must run the packaged JAR with the same JVM flags; `mvn spring-boot:run` adds development-time compiler flags and is not a valid before/after runtime.

## Reproducible document ingestion

Start the deterministic OpenAI-compatible embedding service, then point the benchmark tenant's embedding model base URL at it. Its dimensions must match `APP_KNOWLEDGE_EMBEDDING_DIMENSIONS`.

```bash
python3 performance/mock/openai_embedding_server.py \
  --port 18080 \
  --dimensions 1024 \
  --delay-ms 50
```

The mock exposes `GET /health`, `GET /stats`, and `POST /stats/reset`. It deliberately ignores the bearer credential, returns a deterministic unit vector for each input, and does not write an access log unless `--access-log` is supplied.

Run the synchronous before-case with the HTTP 200 contract:

```bash
PERF_KNOWLEDGE_BASE_ID=2 \
PERF_DOCUMENT_FILE=/absolute/path/to/fixed-document.txt \
PERF_THREADS=4 \
PERF_LOOPS=5 \
PERF_EXPECTED_STATUS=200 \
PERF_EMBEDDING_STATS_URL=http://127.0.0.1:18080/stats \
PERF_RESET_EMBEDDING_STATS=true \
./performance/jmeter/run-document-upload.sh
```

Run the asynchronous after-case with the HTTP 202 contract and poll every accepted document to a terminal state:

```bash
PERF_KNOWLEDGE_BASE_ID=2 \
PERF_DOCUMENT_FILE=/absolute/path/to/fixed-document.txt \
PERF_THREADS=4 \
PERF_LOOPS=5 \
PERF_EXPECTED_STATUS=202 \
PERF_POLL_UNTIL_READY=true \
PERF_POLL_INTERVAL_MS=500 \
PERF_POLL_TIMEOUT_SECONDS=180 \
PERF_EMBEDDING_STATS_URL=http://127.0.0.1:18080/stats \
PERF_RESET_EMBEDDING_STATS=true \
./performance/jmeter/run-document-upload.sh
```

The runner never passes the access token as a command-line property. It records the input file size and SHA-256, raw JTL, HTML report, application metric snapshots, optional embedding-mock snapshots, and `summary.json`. The summary reports `DOCUMENT_UPLOAD` latency separately from the `DOCUMENT_E2E` transaction latency that includes asynchronous polling. A run fails when the expected upload count is missing, an upload or end-to-end sample exceeds `PERF_MAX_ERROR_RATE`, or an asynchronous document reaches `FAILED` or times out.

Aggregate repeated document runs and compare the synchronous and asynchronous variants:

```bash
python3 performance/jmeter/aggregate-document-summaries.py \
  --sync performance/results/sync-r1/summary.json performance/results/sync-r2/summary.json performance/results/sync-r3/summary.json \
  --async performance/results/async-r1/summary.json performance/results/async-r2/summary.json performance/results/async-r3/summary.json \
  --output performance/results/document-sync-vs-async.json
```

Either `--sync` or `--async` can also be used alone to aggregate one variant. The output contains each metric's median and min/max range, total samples/failures and response-code totals. Comparison values use `(async - sync) / sync`; `improvement_percent` is positive when throughput rises or latency/error rate falls.
