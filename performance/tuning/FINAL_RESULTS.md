# Final asynchronous-ingestion confirmation

Test date: 2026-08-08 (Asia/Shanghai)

## Reproducibility boundary

- Git commit: `ab45b1435bc1e5d8b67766251c068473b56dfc03`
- Executable JAR SHA-256: `04a1b475960e8688f7706af27fa37a205896b450ec0ff5a4521c5d025477c78c`
- Confirmation matrix SHA-256: `c55ba3b82dda3fba378828d2fa47af7b1a0ed8c68565d03c49799b87a46cdb49`
- Document SHA-256: `1aeca7128d93552ed525b68e29b973b90c2dbaed4c4fc218c9d47e7bb1599562`
- Workload: 16 concurrent 64 KiB documents, one loop, four warmup documents per case
- Runtime: 1 GiB fixed heap with G1 GC
- Embedding mock: 1024 dimensions, fixed 50 ms delay per request
- Repetitions: three formal runs per case; figures below use `median (min–max)`

The JAR was built and tested in a clean detached worktree at the commit above. Raw local session:
`performance/results/ingest-tuning-20260808T091600Z-18667/`.

## Results

| Metric | Baseline `2/1/10` | Candidate `4/2/20` | Change |
|---|---:|---:|---:|
| Final READY P95 | 6,784 ms (6,778–7,122) | 2,818 ms (2,752–2,923) | -58.5% |
| Completion throughput | 2.358 doc/s (2.246–2.360) | 5.678 doc/s (5.474–5.814) | +140.8% |
| Upload P95 | 81 ms (53–100) | 70 ms (65–72) | -13.6% |
| Embedding requests/document | 10 (10–10) | 5 (5–5) | -50.0% |
| Maximum embedding in-flight | 2 (2–2) | 4 (4–4) | Explicitly capped at 4 |
| Maximum application CPU usage | 13.4% (8.3–15.7) | 16.8% (12.8–29.0) | +3.4 percentage points |
| Maximum JVM heap used | 648.000 MiB (633.000–655.846) | 589.321 MiB (547.457–606.809) | -9.1% median |
| Sampled RabbitMQ ready peak | 2 (2–2) | 0 (0–0) | See sampling caveat |
| Sampled RabbitMQ unacked peak | 2 (2–2) | 0 (0–0) | See sampling caveat |
| Formal failures | 0/48 | 0/48 | No regression |

The candidate is the selected balanced configuration: worker concurrency 4, per-consumer prefetch 2
and embedding batch size 20. It reduces final completion latency and halves upstream request count
without exceeding four simultaneous embedding calls. In this confirmation, HTTP 202 upload latency
also improved slightly; observed process CPU was moderately higher and median heap use was lower.
The heap difference is reported as an observation rather than attributed to the tuning parameters.

Runtime pressure was sampled every 250 ms. All six formal runs reported `ok` for both Actuator
Prometheus and the RabbitMQ management API, with no sampling errors. A sampled queue peak of zero
means that no non-zero value was observed; it does not prove instantaneous queue depth was always
zero, because both sampling and RabbitMQ management statistics have finite resolution.

## Integrity checks

- The detached commit built successfully with 107/107 Maven tests passing before the benchmark.
- 6/6 formal JMeter summaries passed; 96/96 formal documents reached `READY`.
- Including warmup, 104/104 documents reached `READY` and 104/104 Outbox records reached `PUBLISHED`.
- Every formal run processed 1,504 embedding inputs with zero embedding errors.
- RabbitMQ ended with ready 0, unacked 0 and consumers 0.
- The application, embedding mock and JMeter processes were stopped; ports 8080 and 18080 were free.

These values are a same-host local A/B comparison, not a production capacity claim. They must not be
mixed with the earlier Stage 3 four-thread/sequential-loop result because this confirmation uses a
16-document burst specifically to expose worker, prefetch and batch-size behavior.
