# EnjoyAgent 阶段 4：故障验证与异步导入调优报告

测试时间：2026-08-08（Asia/Shanghai）

## 1. 结论

- 五类真实故障演练全部通过：跨实例缓存失效、Redis 运行期不可用、RabbitMQ 中断、Worker 在处理中被 `SIGKILL`、Embedding 超时与停车恢复。
- Redis 快速失败配置把历史故障窗口中的认证请求从 **9.860105 s** 收敛到 clean-commit 演练的 **14.938 ms**；接口在故障中仍返回 HTTP 200 并回源数据库。
- 8 组单轮参数矩阵表明，扩大 prefetch 的单独收益有限，较小的 Embedding batch 会增加上游调用并造成积压；因此最终只复验基线 `2/1/10` 与平衡候选 `4/2/20`。
- 最终三轮复验中，候选配置把文档最终 `READY` P95 从 **6.784 s** 降到 **2.818 s**，降低 **58.46%**；完成速率从 **2.358** 提升到 **5.678 doc/s**，提升 **140.80%**。
- 每份文档的 Embedding 请求从 **10** 次降到 **5** 次，最大并发由 2 提升并限制在 4。96/96 份正式文档成功，JMeter、Embedding、Actuator 和 RabbitMQ 采样错误均为 0。
- 候选组 CPU 峰值中位数从 13.4% 升到 16.8%，但 HTTP 202 上传 P95 从 81 ms 降到 70 ms，Heap 峰值中位数从 648.000 MiB 降到 589.321 MiB。后两项只作为观测，不把变化归因于单一参数。因此默认值采用 `worker=4 / prefetch=2 / batch=20`，不采用压力更大的 8-worker 上限组。

## 2. 固定测试边界

- 应用：Git commit `ab45b1435bc1e5d8b67766251c068473b56dfc03` 的独立 detached worktree 构建产物；构建前工作树为空，107/107 项 Maven 测试通过。
- 可执行 JAR：SHA-256 `04a1b475960e8688f7706af27fa37a205896b450ec0ff5a4521c5d025477c78c`。故障与最终调优复验均使用这一份文件。
- Java：Amazon Corretto 21.0.10；JMeter：5.6.3，非 GUI 模式；Docker Compose：5.1.1；平台：Darwin arm64。
- 文档：固定 65,536 bytes 文本，SHA-256 `1aeca7128d93552ed525b68e29b973b90c2dbaed4c4fc218c9d47e7bb1599562`。
- JVM：`-Xms1024m -Xmx1024m -XX:+UseG1GC`。
- Embedding：本机 OpenAI-compatible Mock，1024 维，每次请求固定延迟 50 ms。
- 调优负载：每组预热 4 份文档，正式阶段一次突发 16 份文档；每个配置三轮，报告中位数和范围。
- 运行期压力每 250 ms 从 Actuator Prometheus 和 RabbitMQ Management API 采样一次。采样值不是瞬时绝对峰值。

负载生成器、应用和依赖均运行在同一台机器上，因此这些数字用于同机 A/B 和回归门槛，不代表生产容量。Embedding Mock 也不包含真实供应商的限流、网络抖动和 429 行为。

## 3. 故障演练

最终有效运行：`performance/results/fault-injection-20260808T091354Z-11020/`，结果为 **5/5 PASS**。

| 场景 | 注入方式 | 通过断言 | 最终证据 |
|---|---|---|---|
| 跨实例缓存失效 | B 预热 L1，A 事务更新并广播失效 | B 在 5 秒内看到新值，不能依赖 30 秒 TTL | **56 ms** 收敛 |
| Redis 宕机 | L1 到期后停止 Redis，再访问认证接口 | 故障中 HTTP 200、出现 L2 error、恢复后仍成功 | **14.938 ms** / 200，L2 error=2；恢复后 8.907 ms / 200 |
| RabbitMQ 中断 | Broker 停止时上传文档 | 上传返回 202，Outbox 留在 PENDING，恢复后补发并 READY | **118.214 ms** / 202；第 9 次发布成功，94 chunks |
| Worker 崩溃 | 文档 PROCESSING 时向 JVM 发送真实 `SIGKILL` | 消息 redelivery，新 Worker 取得所有权，旧结果不能覆盖 | fence **1→2**、attempt 保持 1，最终 READY / 94 chunks |
| Embedding 超时 | Mock 连续返回 HTTP 408 | 只尝试 3 次，耗尽进入 parking，人工重试可恢复 | parking ready=1；人工 attempt 4 后 READY / 94 chunks |

Redis 的第一轮有效演练虽然可靠性断言通过，但 Redisson 默认重试让故障请求耗时 9.860105 秒。最终实现把 Redis connect/command timeout 显式化，并让 Redisson 缓存路径 `retryAttempts=0`；clean-commit 演练为 14.938 ms。历史值与最终值相差约 660.1 倍，但并非严格隔离所有依赖版本的 A/B，只作为快速失败配置生效的诊断证据，不是稳定延迟分位数。

Embedding 场景使用 HTTP 408 明确模拟上游超时响应，以隔离应用自身的三次 delivery 语义；它不等同于 TCP 黑洞，也不宣称验证 HTTP 客户端 read-timeout 的具体秒数。

完整场景、断言与原始证据说明见 [`stage4-fault-injection-report.md`](stage4-fault-injection-report.md)，可提交的精简机器摘要见 [`evidence/stage4-fault-summary.json`](evidence/stage4-fault-summary.json)。

## 4. 参数矩阵与选择过程

探索矩阵覆盖 worker 2/4/8、prefetch 1/2/4、Embedding batch 5/10/20 的 8 个受控组合，共 128/128 份正式文档成功，Embedding 错误为 0。主要观察为：

- `2/1/10` 基线单轮 P95 为 7.183 s，完成速率为 2.227 doc/s。
- `4/2/20` 平衡组单轮 P95 为 2.999 s，完成速率为 5.335 doc/s；Embedding 请求/文档由 10 降到 5。
- `8/4/20` 上限组单轮 P95 为 2.488 s、6.431 doc/s，但 Embedding 并发达到 8，理论未确认消息上限达到 32，明显增加下游压力。
- 单独把 prefetch 从 2 提到 4 的单轮差异约 1.8%，不足以优先放大缓冲。
- batch=5 组明显退化，并观察到 `ready=4 / unacked=8`，说明增加上游调用次数会抵消调度收益。

因此三轮确认矩阵只保留基线和最大 Embedding 并发不超过 4 的平衡候选。

## 5. 最终三轮确认

最终运行：`performance/results/ingest-tuning-20260808T091600Z-18667/`。表中为三轮正式样本的 `中位数（最小值–最大值）`。

| 指标 | 基线 `2/1/10` | 候选 `4/2/20` | 变化 |
|---|---:|---:|---:|
| 最终 READY P95 | 6,784 ms（6,778–7,122） | 2,818 ms（2,752–2,923） | **-58.46%** |
| 完成速率 | 2.358 doc/s（2.246–2.360） | 5.678 doc/s（5.474–5.814） | **+140.80%** |
| HTTP 202 上传 P95 | 81 ms（53–100） | 70 ms（65–72） | **-13.58%** |
| Embedding 请求/文档 | 10 | 5 | **-50.00%** |
| Embedding 最大并发 | 2 | 4 | 上限仍为 4 |
| 应用 CPU 峰值 | 13.4%（8.3–15.7） | 16.8%（12.8–29.0） | +3.4 个百分点 |
| JVM Heap 峰值 | 648.000 MiB（633.000–655.846） | 589.321 MiB（547.457–606.809） | 中位数 -9.1% |
| RabbitMQ ready 采样峰值 | 2 | 0 | 仅表示未采到非零值 |
| RabbitMQ unacked 采样峰值 | 2 | 0 | 仅表示未采到非零值 |
| 正式失败 | 0/48 | 0/48 | 无回归 |

六轮正式结果全部通过，96/96 份文档进入 `READY`；包括预热在内为 104/104 `READY`、104/104 Outbox `PUBLISHED`。六轮 Actuator 与 RabbitMQ 采样均为 `ok` 且无采样错误。结束后队列 `ready=0 / unacked=0 / consumers=0`，应用和 Mock 端口均已释放。

精简机器摘要见 [`evidence/stage4-tuning-summary.json`](evidence/stage4-tuning-summary.json)，完整边界说明见 [`performance/tuning/FINAL_RESULTS.md`](../../performance/tuning/FINAL_RESULTS.md)。

## 6. 已采用的默认值

```text
APP_KNOWLEDGE_INGEST_WORKER_CONCURRENCY=4
APP_KNOWLEDGE_INGEST_PREFETCH=2
APP_KNOWLEDGE_EMBEDDING_BATCH_SIZE=20
REDIS_CONNECT_TIMEOUT=1s
REDIS_COMMAND_TIMEOUT=750ms
```

Redisson 同步采用上述 connect/command timeout，并将缓存路径重试次数设为 0；上层缓存捕获运行期异常后回源数据库。这里的快速失败只适用于可安全回源的缓存链路，不能照搬到必须可靠写入的业务操作。

## 7. 可复现入口

完整阶段四入口会构建固定 JAR、准备 64 KiB fixture、启动所需基础设施、依次执行故障演练和参数矩阵，并在结束时恢复执行前的容器启停状态：

```bash
JMETER_BIN=/absolute/path/to/apache-jmeter-5.6.3/bin/jmeter \
MAVEN_BIN=/absolute/path/to/mvn \
./performance/run-stage4.sh all
```

也可以分别执行：

```bash
./performance/fault-injection/run-fault-drills.sh all

JMETER_BIN=/absolute/path/to/apache-jmeter-5.6.3/bin/jmeter \
TUNING_CASES_FILE=performance/tuning/ingest-confirmation.tsv \
TUNING_REPETITIONS=3 \
./performance/tuning/run-ingest-matrix.sh
```

`performance/results/` 保存完整 JTL、应用日志、HTTP/数据库/队列快照，但被 Git 忽略；仓库提交上述精简 JSON、报告、实验定义和运行器。脚本不会删除 Docker volume、共享队列或已有业务数据。
