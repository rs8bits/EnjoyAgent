# AI 客服第一阶段实现说明

本文解释第一阶段代码为什么这样拆分，便于阅读演进过程。它是一份历史阶段说明；
轻量摘要、LLM 工具规划和参数级写操作确认已经在
[`support-phase2.md`](support-phase2.md) 中实现。语义历史召回仍暂不加入学习版范围。

## 1. 已确认的业务边界

- 会话唯一键是 `tenant_id + user_id`。同一用户切换租户后使用不同客服记忆。
- Agent、知识库按当前租户查询；Credential、钱包和充值申请按当前用户查询。
- 充值是人工审核申请。`PENDING` 只表示等待审核，不代表资金已到账。
- 第一阶段只开放 `READ` Skill，不执行创建、更新、删除、禁用或充值申请创建。
- 客服使用显式配置的平台官方模型，不创建隐藏 Agent，不扣用户钱包。

## 2. 一条消息的执行顺序

```text
认证快照
  -> USER_INPUT Hook
  -> 短事务：幂等 Turn + 用户消息
  -> SSE accepted
  -> 确定性意图路由
  -> 0~3 个白名单只读 Skill
       -> BEFORE_ARCHIVE
       -> Skill 调用记录 RUNNING
       -> BEFORE_SKILL 身份角色/权限/风险 Hook
       -> 执行显式 tenantId/userId 查询
       -> AFTER_SKILL_RAW JSON、提示注入边界与 DLP
       -> BEFORE_MODEL_VISIBLE 压缩
       -> BEFORE_ARCHIVE DLP
       -> Skill 调用记录 SUCCESS/FAILED
  -> 严格上下文预算组装
  -> 官方模型真正流式输出
       -> 每个 delta 先经过 AFTER_MODEL Hook
  -> 短事务：最终助手消息 + Turn 元数据
  -> SSE completed
```

规则问候和固定 FAQ 在意图路由后直接回答，不调用模型。规则使用整句匹配，所以
“你好，帮我查询 Agent”仍会进入 Agent Skill。

## 3. 为什么第一方 Skill 不走本机 HTTP

第一方资产已经在同一个 Spring Boot 进程中。若为了形式统一再请求自己的 REST API，
会增加一次序列化、网络、认证和故障点。当前的 `SupportSkill` 是进程内强类型接口，
同时保留名称、版本、JSON Schema、风险和权限元数据；未来外部 MCP 可以作为另一种适配器
接入相同注册表。

Skill 不能从参数读取 `tenantId` 或 `userId`。这两个值只来自服务端认证快照，并放入
`SupportSkillExecutionContext`。即使模型或浏览器伪造同名字段也不会改变查询范围。

## 4. 数据保存与幂等

- `support_conversation`：唯一长期会话和消息 sequence 分配器。
- `support_turn`：幂等键、请求 hash、运行租约、owner token、版本、路由和模型用量元数据。
- `support_message`：完整用户消息和最终助手消息。
- `support_skill_call`：每次 Skill 尝试的脱敏请求、结果、状态和耗时。

数据库同时使用悲观会话锁和“每会话最多一个 RUNNING Turn”的部分唯一索引。进程崩溃
留下的 RUNNING Turn 带有租约；租约过期后，同一请求可复用原用户消息继续执行，不会
在历史中重复插入问题。每次接管都会更换 `owner_token`，所有晚到写入必须同时匹配
owner token 和乐观锁版本，旧工作线程不能覆盖新结果。Skill 调用序号会从旧审计记录
之后继续递增；浏览器断连后用相同文本重试时，前端也会复用原幂等键。

Agent、知识库、Credential 和充值申请使用构造器投影，SQL 只选择模型确实需要的字段。
API Key 密文与历史掩码、Agent system prompt、模型凭证、用户邮箱和审核人实体都不会被
客服查询加载。业务文本还会递归检查伪造边界标签和提示注入，再进入归档或模型上下文。

## 5. 上下文控制

客服不会沿用普通 Agent 的大窗口。`SupportContextAssembler` 只组装：

1. 固定核心安全提示；
2. 最近若干条“已完成 Turn”消息和当前用户问题（失败 Turn 只归档、不注入）；
3. 本轮被选中 Skill 的最小字段投影。

先按每个 Skill 的结果字符上限生成合法 JSON，再用模型网关的保守 token 上界估算。
超预算时先按完整 Turn 删除最旧问答，再从低优先级 Skill 结果开始移除；当前用户消息
始终保留，也不会留下缺少用户问题的孤立助手回答。

## 6. 本地启用

先在管理员官方模型页面创建并启用一个 `CHAT` 模型，然后设置：

```bash
APP_SUPPORT_OFFICIAL_MODEL_CONFIG_ID=你的官方模型配置ID
```

启动后访问前端 `/app/support`。后端接口为：

- `GET /api/support/conversation`
- `GET /api/support/conversation/messages?beforeSequence=&limit=`
- `POST /api/support/conversation/messages/stream`

POST 必须携带 `Idempotency-Key`。SSE 事件包含 `accepted`、`intent_routed`、
`skill_started`、`skill_completed`、`heartbeat`、`delta`、`completed` 和 `error`。

## 7. 后续阶段

- 可靠异步历史摘要、`covered_through_sequence` 水位线和结构化实体记忆；
- 脱敏后的全文/向量召回与 `history.recall` Skill；
- 绑定会话、Turn、Skill 版本、规范化参数、资源版本和金额的确认状态机；
- 幂等的人工充值申请创建，以及 revoke/disable 优先的资产变更；
- 持久化 SSE 事件与断线续传、限流、指标、告警和故障演练。
