# EnjoyAgent 阶段 4：故障演练方案与验证报告

## 1. 目标

阶段 4 不继续堆叠中间件，而是验证阶段 2/3 的可靠性承诺是否能在真实进程、真实 PostgreSQL/Redis/RabbitMQ/MinIO 环境中成立：

- Redis 运行期不可用时，业务请求能够 fail-open 回源数据库，并在 Redis 恢复后继续工作。
- 两个应用实例之间的 Agent 配置失效消息能够传播，实例 B 不会持续返回实例 A 更新前的 Caffeine 旧值。
- RabbitMQ 不可用时，已经进入数据库事务的 Outbox 不丢失，Broker 恢复后能够补发。
- 消费者在持有任务期间崩溃时，RabbitMQ redelivery 能重新取得所有权，旧 worker 不能覆盖新结果。
- Embedding 上游返回超时响应时只执行有限次数，耗尽后进入 parking queue，并支持人工恢复。

## 2. 可复现入口

```bash
./performance/fault-injection/run-fault-drills.sh all
```

脚本默认使用：

| 资源 | 默认值 | 隔离方式 |
|---|---:|---|
| 主应用 | `127.0.0.1:18081` | 脚本独立启动的打包 JAR |
| 第二实例 | `127.0.0.1:18083` | 仅跨实例缓存场景启动 |
| Embedding Mock | `127.0.0.1:18082` | 独立 Python 进程，可动态切换模式 |
| RabbitMQ 拓扑 | 每次运行唯一 | exchange、主队列、parking queue 均带 run ID |
| 测试身份 | 每次运行唯一 | 独立用户、租户、Agent 和文档记录 |

每次运行结果写入 `performance/results/fault-injection-<UTC>-<PID>/`：

- `report.md`：人类可读的 PASS/FAIL 总结。
- `summary.json`：机器可读的场景结果和运行元数据。
- `application-*.log`：实例启动、断连、重连和消费日志。
- `*.prom`：缓存请求、错误和回源指标快照。
- `*-document.json` / `*-outbox.json`：数据库状态证据。
- `*-queue.json`：RabbitMQ ready、unacked、consumer 等队列状态。

## 3. 场景与通过条件

### 3.1 双实例缓存失效传播

1. 实例 A 创建 Agent，实例 B 连续读取两次并确认缓存的是 `v1`。
2. 实例 A 通过正常 API/事务把描述更新为 `v2`，触发 generation 推进和 Redisson Pub/Sub。
3. 每 20 ms 从实例 B 读取一次，记录首次读到 `v2` 的单调时钟耗时。

通过条件：B 在 5 秒内读到 `v2`。这个阈值小于 30 秒 L1 TTL，因此通过不能只归因于 TTL 自然过期。

### 3.2 Redis 宕机与恢复

1. 先访问 `/api/auth/me` 预热认证缓存。
2. 停止 Redis，并等待 3 秒，使测试配置的 2 秒 Caffeine TTL 过期。
3. 再次访问接口并保存 Prometheus 指标，然后恢复 Redis 并重新访问。

通过条件：故障中和恢复后均为 HTTP 200、响应 `success=true`，且故障窗口出现 `auth-context-v1` 的 L2 error 指标。

### 3.3 RabbitMQ 中断与 Outbox 补发

1. 停止 RabbitMQ 后上传文档。
2. 验证接口立即返回 HTTP 202；Outbox 至少尝试发布一次并回到带错误信息的 `PENDING`。
3. 恢复 RabbitMQ，等待 Outbox 变为 `PUBLISHED`，文档变为 `READY`。

通过条件：最终 Outbox `publish_attempts >= 2`、状态 `PUBLISHED`，文档 `READY`。

### 3.4 消费者处理中崩溃

1. 将 Mock 设置为 5 秒延迟，上传文档并等待 `PROCESSING`、token 和 lease 已写入。
2. 对主应用发送 `SIGKILL`，等待未 ACK 消息回到主队列。
3. 将 Mock 恢复为快速成功并重启同配置应用。

通过条件：文档最终 `READY`，processing fence 严格增加，`processing_attempts` 仍为 1，终态 token/lease 均已清空。

### 3.5 Embedding 超时、有限重试与 parking queue

1. Mock 对 Embedding 返回确定性 HTTP 408，自动重试间隔缩短为 1 秒、1 秒。选择 4xx 超时响应是为了避开 Spring AI 对瞬时 5xx 的内部重试，从而单独验证应用层 delivery 次数。
2. 等待文档进入 `FAILED` 并保存 Outbox、parking queue 和 Mock 统计。
3. Mock 恢复成功模式，通过公开 API 手动重试。

通过条件：失败时恰好 3 次处理尝试、3 条 Outbox delivery、parking ready 至少 1；手工重试使用 attempt 4 并最终 `READY`。

HTTP 408 表示明确的上游超时响应。本场景验证有限重试和停车语义，不宣称验证 TCP 黑洞条件下客户端 read-timeout 的具体秒数。

## 4. 安全边界

- 脚本不会执行 `docker compose down`、`docker volume rm`、共享队列 purge 或数据库清库。
- Redis/RabbitMQ 停止动作由 `EXIT` trap 恢复；脚本结束后服务状态与执行前保持一致。
- 只删除本次 run ID 创建的 RabbitMQ topology；专用测试租户和业务记录保留，便于复核数据库证据。
- 若运行在任务中途异常退出，只清理该专用租户尚未发布的 Outbox，并把其活动文档标为 `FAILED/FAULT_DRILL_ABORTED`，避免遗留消息被后续实例投递到其他拓扑。
- 若端口已占用、JAR/fixture 缺失或基础设施未能在超时内恢复，脚本会在注入故障前或当前场景立即失败。

## 5. 实测结果

最终有效运行：`performance/results/fault-injection-20260808T091354Z-11020/`

- 时间：2026-08-08 17:13–17:14（Asia/Shanghai）
- 结果：**5/5 PASS**
- Git commit：`ab45b1435bc1e5d8b67766251c068473b56dfc03`
- JAR SHA-256：`04a1b475960e8688f7706af27fa37a205896b450ec0ff5a4521c5d025477c78c`
- Fixture：65,536 bytes，SHA-256 `1aeca7128d93552ed525b68e29b973b90c2dbaed4c4fc218c9d47e7bb1599562`
- Java：Amazon Corretto 21.0.10；Docker Compose：5.1.1；平台：Darwin arm64

该 JAR 来自上述 commit 的独立 detached worktree；构建前工作树为空，Maven 107/107 项测试通过。
演练运行器位于主工作树，因此原始 metadata 的 `git_dirty_count=19` 只描述运行器所在目录，不影响被测 JAR 的来源。

| 场景 | 实测结果 | 关键量化证据 |
|---|---|---|
| 双实例缓存失效 | PASS | B 已确认一次 `agent-config-v4` L1 hit；A 提交 `v2` 后，B 在 **56 ms** 读到新值，显著小于 30 秒 TTL |
| Redis 宕机/恢复 | PASS | L1 过期且 Redis 停止后 `/api/auth/me` 仍为 HTTP 200；curl 实际耗时 **14.938 ms**（Runner 包络 41 ms），L2 error 计数为 **2**；Redis 恢复后耗时 **8.907 ms**、仍为 200 |
| RabbitMQ/Outbox | PASS | Broker 停止时上传以 **118.214 ms** 返回 HTTP 202；Outbox 在故障中为 `PENDING` 且记录 `Connection refused`，恢复后第 9 次发布尝试变为 `PUBLISHED`，文档 658 为 `READY` |
| Worker 崩溃恢复 | PASS | 文档 659 为 `PROCESSING` 时对 JVM 执行真实 `SIGKILL`，主队列出现 `ready=1/consumers=0`；重启后 fence **1→2**、逻辑 attempt 保持 **1**、94 个 chunk，终态 token/lease 清空 |
| Embedding 超时 | PASS | 连续 **3** 个 HTTP 408 对应 attempt 1–3 和 3 条已发布 Outbox，耗尽后 parking `ready=1`；Mock 恢复后人工 attempt 4 生成 94 个 chunk 并进入 `READY` |

最终数据核验：文档 658–660 对应 6 条 Outbox 均为 `PUBLISHED`，全库 `PENDING/CLAIMED=0`；脚本专用主队列和 parking queue 均已删除，18081/18082/18083 均已释放，PostgreSQL、Redis、RabbitMQ、MinIO、Elasticsearch 均恢复运行。

完整摘要见 `performance/results/fault-injection-20260808T091354Z-11020/report.md` 和同目录 `summary.json`。

### 5.1 Redis 快速失败改造对比

旧版 JAR 的完整通过运行位于 `performance/results/fault-injection-20260808T084822Z-26668/`，JAR SHA-256 为 `a06f30c75232f344953479406ba64bbee5141024522b511d2fffb3c44b461268`。该运行证明可靠性语义成立，但 Redis 故障请求耗时 9.860105 秒，不再作为最终结果。

最终 JAR 将 Redis 命令超时、Redisson connect/command timeout 和 retryAttempts 调整为快速失败配置。历史修复前演练的 Redis 故障请求为 **9.860105 s**，clean-commit 最终演练为 **0.014938 s**，相差 **99.849%**、约 **660.1 倍**；HTTP 状态和响应业务语义均保持成功。两次不是严格隔离所有依赖版本的 A/B，因此该数字只作为问题定位和快速失败生效的诊断证据，不应解释为稳定延迟分位数。

### 5.2 无效试跑说明

- 第一次试跑在故障注入前发现 seed 邮箱大小写归一化不一致，只有 bootstrap 失败，不纳入结果。
- 第二次试跑前三项通过，但 worker 场景发现演练工具杀掉的是 shell 包装进程而非 JVM，未真正制造消费者崩溃，因此整轮不作为证据。Runner 改为 `exec` 后，旧版与最终版 JAR 的两轮有效运行均完成真实 `SIGKILL`、RabbitMQ redelivery 和 fence 递增断言。

这两次均由脚本判为 FAIL，未把未满足故障前提的结果计入正式结论。
