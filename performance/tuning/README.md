# Asynchronous document-ingestion tuning

This directory turns the Stage 3 trade-off into a reproducible experiment. It varies the RabbitMQ
worker ceiling, per-consumer prefetch and upstream embedding batch size while holding the JAR, heap,
fixture, mock delay, database and JMeter workload constant.

The default curated matrix is intentionally smaller than a 27-case full factorial design:

- workers: 2, 4 and 8;
- prefetch: 1, 2 and 4;
- embedding batch size: 5, 10 and 20 texts;
- one-factor sweeps around the Stage 3 baseline, a balanced candidate and one upper-pressure case.

`worker_concurrency * prefetch` is the maximum number of unacknowledged deliveries held by this
application instance. Actual embedding concurrency is bounded by the worker count, while batch size
changes how many upstream calls each document needs. Prefetch is not expected to make CPU-bound work
run concurrently by itself; it mainly affects dispatch and buffered work.

## Prerequisites

Start the local infrastructure and package the exact JAR to test:

```bash
docker compose up -d postgres redis minio rabbitmq
./mvnw clean package
```

The checked-in Maven wrapper pins the build toolchain. If you intentionally use another Maven,
set `MAVEN_BIN` and run `"$MAVEN_BIN" clean package`. Install Apache JMeter 5.6.3
and pass its absolute executable path through `JMETER_BIN`. Do not run another EnjoyAgent instance
on port 8080 while the matrix owns the application lifecycle.

The runner starts one deterministic embedding mock for the complete session, restarts the packaged
JAR once per case, registers the performance user if needed, seeds `perf-knowledge-ingest`, performs
a short per-case warmup, then runs each formal repetition. It only terminates processes it started.

## Run the formal matrix

```bash
JMETER_BIN=/absolute/path/to/apache-jmeter-5.6.3/bin/jmeter \
TUNING_REPETITIONS=3 \
./performance/tuning/run-ingest-matrix.sh
```

Defaults mirror the Stage 3 benchmark where possible:

- fixed 64 KiB fixture;
- OpenAI-compatible mock with 1024 dimensions and 50 ms delay per request;
- `-Xms1024m -Xmx1024m -XX:+UseG1GC`;
- asynchronous HTTP 202 contract with polling to `READY`;
- 16 documents released as one burst, one iteration per JMeter thread;
- 100 ms polling and zero tolerated document or embedding errors.

Useful overrides:

```bash
TUNING_CASES_FILE=/absolute/path/to/custom-matrix.tsv
TUNING_REPETITIONS=1
TUNING_THREADS=24
TUNING_WARMUP_DOCUMENTS=4
TUNING_MAX_BALANCED_IN_FLIGHT=4
PERF_KNOWLEDGE_BASE_ID=2
TUNING_AUTO_PREPARE_DATASET=false
TUNING_MANAGE_MOCK=false
```

Custom matrices must keep the same tab-separated header. The runner accepts only the reviewed local
bounds: worker values 2/4/8, prefetch values 1/2/4 and batch sizes 5/10/20. This prevents an accidental
matrix from overwhelming a real embedding endpoint. The runner always points the application at the
local mock; it is not a production-provider load-test tool.

Validate a custom matrix without starting Docker clients, JMeter, the mock or the application:

```bash
TUNING_DRY_RUN=true \
TUNING_CASES_FILE=/absolute/path/to/custom-matrix.tsv \
./performance/tuning/run-ingest-matrix.sh
```

## Outputs and interpretation

Each session is written under `performance/results/ingest-tuning-<timestamp>-<pid>/` and contains:

- exact JAR, matrix and fixture SHA-256 hashes plus runtime metadata;
- application log per case and runner log per repetition;
- `runs.tsv`, linking every warmup/formal attempt to its raw JMeter result directory;
- `runtime-samples.json` per formal run, sampled every 250 ms from Actuator Prometheus and the
  RabbitMQ management API;
- `aggregate.json`, the machine-readable comparison with the Stage 3 baseline case;
- `report.md`, a compact median comparison table.

The aggregate uses final `DOCUMENT_E2E` P95 and completion throughput for tuning. It deliberately does
not use the apparent upload throughput: the current JMeter thread waits for its document to become
terminal before its next loop, so that number is coupled to queue completion. HTTP 202 upload P95 is
still reported as a regression guard.

Runtime sampling reports the maximum observed RabbitMQ ready/unacknowledged depth, application
`process_cpu_usage`, JVM heap usage and consumer count. These are sampled peaks, not guaranteed
instantaneous maxima. Every source records `ok`, `partial` or `unavailable`, successful/failed sample
counts and error counts; a missing scrape is shown as `n/a` and is never converted to zero. Override
the interval with `TUNING_SAMPLE_INTERVAL_MS` or the queue endpoint with
`TUNING_RABBIT_QUEUE_URL`. Rabbit credentials come from `RABBITMQ_USERNAME` and
`RABBITMQ_PASSWORD` and are not written to command-line arguments or metadata.

The mechanical `balanced` selection means the lowest E2E P95 among zero-error cases whose observed
embedding concurrency does not exceed `TUNING_MAX_BALANCED_IN_FLIGHT`. It is not automatically a
production recommendation. Before changing defaults, repeat the winning candidates against the real
provider's documented quotas and monitor database pool saturation, RabbitMQ ready/unacked depth, JVM
GC and embedding error/429 rates.

To re-aggregate an existing session without rerunning JMeter:

```bash
python3 performance/tuning/aggregate-ingest-matrix.py \
  performance/results/ingest-tuning-.../runs.tsv \
  --baseline-case baseline-w2-p1-b10 \
  --max-in-flight 4 \
  --output performance/results/ingest-tuning-.../aggregate.json \
  --markdown-output performance/results/ingest-tuning-.../report.md
```

After a one-run exploratory matrix identifies a candidate, confirm only the baseline and balanced
candidate with three repetitions. The committed confirmation matrix deliberately excludes the
eight-worker upper bound so observed embedding concurrency remains capped at four:

```bash
JMETER_BIN=/absolute/path/to/apache-jmeter-5.6.3/bin/jmeter \
TUNING_CASES_FILE=performance/tuning/ingest-confirmation.tsv \
TUNING_REPETITIONS=3 \
./performance/tuning/run-ingest-matrix.sh
```
