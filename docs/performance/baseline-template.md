# EnjoyAgent performance baseline

## Reproduction metadata

- Git commit:
- Dirty worktree count:
- Test date/timezone:
- Host CPU/RAM/OS:
- Java/JMeter versions:
- JVM options and heap:
- Application instances:
- Docker image tags and resource limits:
- Data set seed, tenant/user/document/chunk counts:
- Scenario, threads, target RPM, warmup and duration:
- Warmup and measurement ramp settings (runner measurement default is zero):
- Mock/real downstream service and configured latency/error rate:

## Client results

| Scenario | Run | Samples | Actual RPS | Error rate | P50 | P95 | P99 |
|---|---:|---:|---:|---:|---:|---:|---:|
| ping | 1 | | | | | | |
| auth-me | 1 | | | | | | |
| agents-list | 1 | | | | | | |
| agent-detail cold | 1 | | | | | | |
| agent-detail warm | 1 | | | | | | |

Cold-cache and warm-cache rows must come from separate application runs with a fixed cache mode and identical seed; one initial cold request is not a cold-cache throughput test.

Report the median and range across at least three measurement runs. Do not select only the best result.

## Server-side correlation

- HTTP throughput/P95/5xx:
- `enjoy.auth.membership.lookup` P95:
- Hibernate prepared statements per successful request:
- Hikari active/pending/timeouts:
- Process CPU and JVM heap/GC:
- Redis hit/miss, latency and memory:
- RabbitMQ ready/unacked/publish/consume:
- Synchronous document ingest total and phase P95:

## Correctness and interpretation

- Cross-tenant responses observed:
- Cold-cache regression:
- Cache invalidation verification:
- Load generator and application resource contention:
- Bottleneck supported by metrics:
- Changes allowed into resume bullets:
