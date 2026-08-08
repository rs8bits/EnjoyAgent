# Final asynchronous-ingestion confirmation

Test date: 2026-08-08 (Asia/Shanghai)

## Reproducibility boundary

- Executable JAR SHA-256: `7d81a182cead856933635eddf2b69d60b9fdc4104dd346727c398e519adf960f`
- Confirmation matrix SHA-256: `c55ba3b82dda3fba378828d2fa47af7b1a0ed8c68565d03c49799b87a46cdb49`
- Document SHA-256: `1aeca7128d93552ed525b68e29b973b90c2dbaed4c4fc218c9d47e7bb1599562`
- Workload: 16 concurrent 64 KiB documents, one loop, four warmup documents per case
- Runtime: 1 GiB fixed heap with G1 GC
- Embedding mock: 1024 dimensions, fixed 50 ms delay per request
- Repetitions: three formal runs per case; figures below use `median (min–max)`

Raw local session: `performance/results/ingest-tuning-20260808T085510Z-50209/`.

## Results

| Metric | Baseline `2/1/10` | Candidate `4/2/20` | Change |
|---|---:|---:|---:|
| Final READY P95 | 6,787 ms (6,603–7,168) | 2,865 ms (2,668–3,216) | -57.8% |
| Completion throughput | 2.357 doc/s (2.232–2.423) | 5.585 doc/s (4.970–5.995) | +137.0% |
| Upload P95 | 60 ms (60–104) | 72 ms (67–124) | +20.0% latency |
| Embedding requests/document | 10 (10–10) | 5 (5–5) | -50.0% |
| Maximum embedding in-flight | 2 (2–2) | 4 (4–4) | Explicitly capped at 4 |
| Maximum application CPU usage | 13.0% (10.3–21.6) | 17.2% (11.0–23.6) | +4.2 percentage points |
| Maximum JVM heap used | 604.082 MiB (586.582–654.500) | 603.804 MiB (497.492–650.492) | Essentially unchanged |
| Sampled RabbitMQ ready peak | 2 (2–2) | 0 (0–0) | See sampling caveat |
| Sampled RabbitMQ unacked peak | 2 (2–2) | 0 (0–0) | See sampling caveat |
| Formal failures | 0/48 | 0/48 | No regression |

The candidate is the selected balanced configuration: worker concurrency 4, per-consumer prefetch 2
and embedding batch size 20. It reduces final completion latency and halves upstream request count
without exceeding four simultaneous embedding calls. The trade-off is a small absolute increase in
HTTP 202 upload latency and moderately higher observed process CPU; heap usage remains flat.

Runtime pressure was sampled every 250 ms. All six formal runs reported `ok` for both Actuator
Prometheus and the RabbitMQ management API, with no sampling errors. A sampled queue peak of zero
means that no non-zero value was observed; it does not prove instantaneous queue depth was always
zero, because both sampling and RabbitMQ management statistics have finite resolution.

## Integrity checks

- 6/6 formal JMeter summaries passed; 96/96 formal documents reached `READY`.
- Including warmup, 104/104 documents reached `READY` and 104/104 Outbox records reached `PUBLISHED`.
- Every formal run processed 1,504 embedding inputs with zero embedding errors.
- RabbitMQ ended with ready 0, unacked 0 and consumers 0.
- The application, embedding mock and JMeter processes were stopped; ports 8080 and 18080 were free.

These values are a same-host local A/B comparison, not a production capacity claim. They must not be
mixed with the earlier Stage 3 four-thread/sequential-loop result because this confirmation uses a
16-document burst specifically to expose worker, prefetch and batch-size behavior.
