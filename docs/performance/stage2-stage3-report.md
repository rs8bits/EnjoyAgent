# EnjoyAgent 阶段 2/3 性能与可靠性验证报告

测试时间：2026-08-03 至 2026-08-04（Asia/Shanghai）

## 1. 结论

- 阶段 2 的认证上下文两级缓存将 `/api/auth/me` 的 P95 从 **108 ms** 降至 **1 ms**，降低 **99.1%**；平均延迟从 **34.545 ms** 降至 **0.460 ms**，降低 **98.7%**。
- 在 6,000 RPS 的给定负载上限附近，实测吞吐从 **5,824.664 RPS** 增至 **6,011.956 RPS**，提升 **3.2%**；由于优化后已经触及施压上限，该数字不能解释为系统吞吐上限。
- 同一测量窗口内，每请求 Hibernate prepared statement 从约 **1.000394** 降至 **0.000388**，降低 **99.96%**。缓存开启组每轮剩余的 45–46 条语句包含后台定时任务，不能归因于认证请求。
- 阶段 3 将文档重处理从上传请求中解耦后，64 KiB 文档上传接口的 P95 从 **941 ms** 降至 **38 ms**，降低 **96.0%**，60/60 个异步上传均最终进入 `READY`。
- 异步消费者把 Embedding 最大并发从同步模式的 **4** 限制为 **2**，验证了背压有效。代价是本次突发负载下最终 `READY` 的 P95 从 **1.182 s** 增至 **2.190 s**；阶段 3 优化的是用户请求延迟、故障隔离和系统稳定性，不是单份文档的最短完成时间。

## 2. 测试边界

- 应用：同一份 Spring Boot 可执行 JAR，固定 `-Xms1g -Xmx1g -XX:+UseG1GC`。
- 主机：Apple M4、24 GiB RAM、macOS 26.5.2。
- Java：Amazon Corretto 21.0.10；JMeter：5.6.3，非 GUI 模式。
- 数据库、Redis、RabbitMQ、MinIO、Elasticsearch 均使用本机 Docker Compose 环境。
- 工作树在测试时包含尚未提交的功能修改，因此结果用 commit 和 dirty count 标识；所有正式 A/B 组使用同一份打包产物和同一份数据集。
- 负载生成器与应用运行在同一主机，绝对吞吐只用于本机 A/B，不代表生产容量规划结果。
- 每组使用三轮有效测量并报告中位数及范围；错误率均为 0。运行器会拒绝样本不足、吞吐未达门槛或测量窗口因系统时钟跳变而失真的结果。

## 3. 阶段 2：Caffeine + Redis + Redisson

### 3.1 实现

- 使用 Caffeine 作为进程内 L1、Redis 作为共享 L2；L2 物理 key 携带全局/key generation，提交后失效推进 generation，使在途旧 loader 只能写入不可达的旧代。
- 对认证成员关系快照和 Agent 配置分别缓存，JWT 的签名、过期时间及 Token 类型仍然逐请求校验。
- Agent 配置冷 miss 使用 Redisson `RLock` 合并跨实例回源；通过 Redisson `RTopic` 广播 L1 失效。
- 写操作在事务提交后推进 generation 和本地 epoch，不等待 Caffeine 在途计算；跨实例 L1 采用 Pub/Sub 加 5/30 秒短 TTL 最终收敛。
- Redis 运行期故障时认证、Agent 和会话摘要缓存均 fail-open 回源；关闭全部缓存开关时不创建 Redis/Redisson 缓存基础设施。
- 增加命中、miss、回源耗时、锁等待、失效和错误等 Micrometer 指标。

### 3.2 A/B 配置

场景固定为 `/api/auth/me`：600 线程、目标 360,000 RPM、10 秒预热、20 秒测量、测量 ramp 为 0。关闭组使用：

```text
APP_CACHE_AUTH_CONTEXT_ENABLED=false
APP_CACHE_AUTH_CONTEXT_LOCAL_ENABLED=false
```

开启组把上述两个开关均设为 `true`。其余 JVM、JAR、数据库、用户及 JMeter 参数完全相同。

### 3.3 三轮结果中位数

| 指标 | 缓存关闭 | 缓存开启 | 变化 |
|---|---:|---:|---:|
| 实际吞吐 | 5,824.664 RPS（5,779.725–5,830.179） | 6,011.956 RPS（6,005.616–6,018.112） | +3.2% |
| 平均延迟 | 34.545 ms（24.949–38.517） | 0.460 ms（0.460–0.515） | -98.7% |
| P95 | 108 ms（106–123） | 1 ms（1–1） | -99.1% |
| P99 | 179 ms（169–188） | 2 ms（2–3） | -98.9% |
| prepared statement / 请求 | 1.000394 | 0.000388 | -99.96% |
| 错误率 | 0% | 0% | 持平 |

原始聚合结果：

- `performance/results/stage2-final-off-auth-me-aggregate.json`
- `performance/results/stage2-final-on-auth-me-aggregate.json`

## 4. 阶段 3：RabbitMQ + Transactional Outbox 异步导入

### 4.1 实现

- 上传请求先完成 MinIO 文件落盘，并在同一数据库事务中写入 `QUEUED` 文档和 Outbox，返回 HTTP 202。
- Outbox Relay 使用持久化 RabbitMQ 消息、Publisher Confirm 和 Mandatory Return，避免数据库提交成功但消息静默丢失。
- 消费端固定 2 个 worker、prefetch 1，以显式背压保护解析、Embedding 和索引下游。
- 状态机为 `QUEUED → PROCESSING → INDEXING → READY`；每次 delivery 使用持久化 token、单调 fence 和 lease，数据库变更及外部索引调用前后均校验所有权。
- 异步路径不做破坏性 ES 预清理；`INDEXING` 崩溃或普通故障重投均基于已提交 chunk 恢复索引，不重复解析和向量生成。
- 瞬时故障有限重试 3 次，耗尽后进入 parking queue；永久错误直接停车；`FAILED` 文档支持手动发起新一轮有限重试。
- MinIO 补偿绑定最外层事务的 rollback 回调且只执行一次；Outbox Relay 逐条 claim、publish、confirm，避免批次尾部 claim 过期。

### 4.2 A/B 配置

每轮上传 20 份固定的 64 KiB 文本文档（4 线程 × 5 次），文件 SHA-256：

```text
1aeca7128d93552ed525b68e29b973b90c2dbaed4c4fc218c9d47e7bb1599562
```

Embedding 使用确定性 OpenAI-compatible Mock：1024 维、每请求固定 50 ms 延迟。同步组设置 `APP_KNOWLEDGE_ASYNC_INGEST_ENABLED=false` 并断言 HTTP 200；异步组设为 `true`，断言 HTTP 202，并每 100 ms 轮询至 `READY` 或 `FAILED`。

### 4.3 三轮结果中位数

| 指标 | 同步处理 | 异步处理 | 变化 |
|---|---:|---:|---:|
| 上传平均延迟 | 887.70 ms（845.75–982.45） | 18.65 ms（18.35–77.35） | -97.9% |
| 上传 P50 | 883 ms（840–897） | 14 ms（13–16） | -98.4% |
| 上传 P95 | 941 ms（909–1,323） | 38 ms（35–321） | -96.0% |
| 最终 READY P95 | 1.182 s（1.119–1.574） | 2.190 s（2.082–2.537） | +85.3% |
| Embedding 最大并发 | 4 | 2 | 被 worker 上限约束 |
| 文档失败 | 0 / 60 | 0 / 60 | 持平 |
| Embedding 错误 | 0 / 600 请求 | 0 / 600 请求 | 持平 |

异步组的“上传吞吐”没有用于结论，因为当前 JMeter 线程在一次上传后会等待该文档到终态再开始下一次循环；这个数同时受轮询和 worker 队列耗时影响，不能代表 202 接口的独立接收能力。

三轮结束后的数据库与队列核验：

- 同步与异步共 120 份正式测试文档全部为 `READY`，共生成 11,280 个 chunk。
- 60 条异步 Outbox 记录全部为 `PUBLISHED`。
- RabbitMQ 主队列 `ready=0`、`unacked=0`、消费者数为 2；parking queue 为 0。
- 每轮同步与异步均产生 200 个 Embedding batch 请求、1,880 个输入 chunk；异步组最大并发稳定为 2。
- 所有终态文档的 processing token/lease 均已清空，未遗留 Worker 所有权。

聚合结果：`performance/results/stage3-final-sync-vs-async.json`。

## 5. 可复现入口

- 通用基线：`performance/jmeter/run-baseline.sh`
- 文档导入：`performance/jmeter/run-document-upload.sh`
- 认证基线聚合：`performance/jmeter/aggregate-summaries.py`
- 文档 A/B 聚合：`performance/jmeter/aggregate-document-summaries.py`
- 固定文档生成：`performance/fixtures/generate-document.py`
- Embedding Mock：`performance/mock/openai_embedding_server.py`
- 数据集：`performance/seed/agent-dataset.sql`、`performance/seed/knowledge-ingest-dataset.sql`

## 6. 简历表述建议

可以基于本报告写成以下两条；面试时需要同时说明数据规模、同机 A/B 条件及异步端到端延迟的取舍：

- 设计 Caffeine + Redis 两级缓存，结合 generation fencing、Redisson 分布式锁与失效广播治理热点数据并发回源；JMeter 在 6,000 RPS 给定负载下三轮压测，认证接口 P95 由 108 ms 降至 1 ms，数据库 prepared statement / 请求降低 99.96%。
- 基于 RabbitMQ + Transactional Outbox 重构知识库文档异步导入，落地 processing fence、幂等状态机、Publisher Confirm、有限重试与 parking queue；64 KiB 文档三轮压测中上传 P95 由 941 ms 降至 38 ms，并将 Embedding 并发稳定限制为 2，60/60 个任务最终成功。
