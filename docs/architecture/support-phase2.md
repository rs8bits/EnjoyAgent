# AI 客服第二阶段实现说明

第二阶段以“先把学习项目闭环”为目标，没有引入复杂 Agent 框架、向量化历史召回、
多级审批或完整评测平台。它补齐四件核心能力：模型识别意图并选择 Skill、最近消息加
历史摘要记忆、删除与充值的显式确认、前端确认卡片。

## 1. 当前功能边界

- 每个 `tenant_id + user_id` 仍然只有一个长期客服会话。
- 模型可以查询 Agent、知识库、API Key 元数据、钱包和充值申请。
- 模型可以提出删除 Agent、删除空知识库、删除当前用户 API Key 的操作。
- 模型可以提出创建充值申请；确认后订单状态为 `PENDING`，等待管理员人工审核，
  不会直接增加余额。
- Agent 和知识库删除要求当前成员为租户 `OWNER`；API Key 删除及充值申请绑定当前用户。
- 本阶段不实现任意资产创建/修改、工具历史语义召回、多会话、自动充值到账或多级审批。

## 2. 一条普通消息如何执行

```text
认证快照 + USER_INPUT Hook
  -> 保存用户消息和 RUNNING Turn
  -> 规则只匹配纯问候、致谢、告别和极简单项目介绍
  -> 其余问题组装“历史摘要 + 最近消息”的小上下文
  -> 官方客服模型进行一次原生 Tool Calling 规划
       -> 无工具：直接把规划模型文本作为答案
       -> 有工具：白名单、数量、JSON、身份字段检查
  -> 查询 Skill：执行并只把必要结果注入最终回答上下文
  -> 写 Skill：只生成 confirmation 提案，绝不在本轮执行
  -> 最终回答经过输出 Hook，保存消息并结束 Turn
  -> 异步检查是否需要刷新历史摘要
```

入口是 `SupportApplicationService`。`SupportIntentRouter` 只负责便宜且确定的规则；
`SupportToolPlanningService` 负责一次模型规划；`SupportSkillRegistry` 是模型可调用能力的
服务端白名单。即使模型生成了一个不存在的工具名或偷偷加入 `tenantId`、`userId`，规划
结果也会在执行前被拒绝。

模型只负责“建议调用什么”，无权决定权限、租户范围和是否跳过确认。真正的身份来自
`AuthenticatedUser`，执行器再把它构造成 `SupportSkillExecutionContext`。

## 3. 为什么写操作分成提案和执行

写 Skill 实现 `ConfirmableSupportSkill`，它故意没有一个可以被普通编排器直接调用的写
入口，而是拆成：

1. `preview(...)`：重新查询当前用户可见的目标，验证参数，生成安全标题、摘要和规范化参数；
2. `executeConfirmed(...)`：确认接口完成全部校验后才调用，真正写业务数据。

`SupportActionConfirmationService.createProposal(...)` 把规范化参数保存到
`support_action_confirmation`，同时保存 SHA-256、Skill 版本、当前用户/租户、来源 Turn、
过期时间以及删除目标的 `updated_at`。浏览器只收到确认 ID 和安全摘要，看不到或回传
原始参数。

用户点击确认时，浏览器只调用：

```text
POST /api/support/confirmations/{confirmationId}/confirm
```

服务端随后执行以下检查：

```text
当前登录身份拥有该确认记录
  -> 行级悲观锁，阻止双击和多标签重复执行
  -> 状态仍是 PENDING 且未过期
  -> Skill 仍在白名单且版本未变化
  -> 数据库参数的 SHA-256 未变化
  -> 再跑 BEFORE_SKILL 权限与安全 Hook
  -> 删除目标 updated_at 与提案时一致
  -> 在同一事务内执行领域服务、保存 Skill 审计和两条会话消息
  -> 状态改为 EXECUTED
```

同一个确认 ID 重复提交不会再次删除或再次创建充值申请。取消接口为
`POST /api/support/confirmations/{confirmationId}/cancel`；取消或过期后不能再执行。

页面刷新时会调用 `GET /api/support/confirmations/pending`，按当前租户和用户恢复仍未过期
的确认卡片。该接口同样只返回确认 ID 与安全摘要，不返回冻结参数。

四个确认型 Skill 位于 `support/skill/builtin`：

- `AgentDeleteSupportSkill`
- `KnowledgeBaseDeleteSupportSkill`
- `ApiKeyDeleteSupportSkill`
- `RechargeOrderCreateSupportSkill`

它们复用原有领域服务完成最终写入，因此原领域中的归属检查、引用限制、缓存清理和
充值状态规则不会在客服模块里复制一套。

## 4. 轻量记忆如何控制上下文

`SupportConversationMemoryService` 使用“旧历史摘要 + 最近完整消息”的简单策略：

- 最近 2 个已完成 Turn 保留原文；
- 累积至少 3 个更老 Turn 才异步更新摘要；
- 一次最多压缩 3 个 Turn；
- 单条摘要输入最多 300 字符，最终摘要最多 1500 字符；
- `covered_through_sequence` 表示摘要已覆盖到哪条消息。

`SupportContextAssembler` 读取摘要以后，只注入水位线之后的最近消息，避免同一历史既以
摘要又以原文出现。摘要也被标记为不可信数据，并依次经过上下文、模型可见输出和归档
Hook。若总体超过客服的 Token 硬上限，依次删除最旧完整 Turn、低优先级工具结果，最后
临时放弃摘要；当前用户问题永远保留。

摘要失败不会影响用户当前回答，也不会错误推进水位。完整聊天和 Skill 调用仍保存在
数据库中；本阶段只是没有再做向量化工具历史召回。

## 5. 安全 Hook 放在哪里

- `USER_INPUT`：用户消息写库和处理前；
- `BEFORE_CONTEXT`：系统指令与摘要系统提示进入模型前；
- `BEFORE_SKILL`：每次查询、提案和确认执行前校验身份、角色、风险；
- `AFTER_SKILL_RAW`：业务工具原始输出离开 Skill 后；
- `BEFORE_MODEL_VISIBLE`：摘要、工具结果和确认提案注入模型或页面前；
- `BEFORE_ARCHIVE`：参数、工具结果和摘要写审计存储前；
- `AFTER_MODEL`：规则文本、规划直接回答、最终回答和摘要模型输出后。

工具的完整请求与结果进入 `support_skill_call`，普通会话消息只保存用户输入和最终回复。
因此聊天上下文不会因为历史工具 JSON 越积越大，也不会把工具协议伪装成普通用户文本。

## 6. 前端确认卡片

后端在写 Skill 提案成功后发送 `confirmation_required` SSE 事件，随后仍发送普通的
`delta` 与 `completed` 终态。`SupportPage.vue` 将确认卡片与聊天消息分开呈现。

卡片只展示后端给出的标题、摘要、过期时间和状态。确认或取消期间按钮会禁用；确认
成功后，接口返回的正式“用户确认消息 + 客服执行结果消息”按 `sequence` 合并进唯一
会话，而不是由浏览器伪造一条成功消息。

## 7. 数据库迁移与配置

- `V32__create_support_customer_service_tables.sql`：会话、Turn、消息和 Skill 审计；
- `V33__create_support_conversation_memory.sql`：历史摘要和覆盖水位；
- `V34__create_support_action_confirmation.sql`：不可篡改确认提案和状态机。

至少配置一个已启用的官方 `CHAT` 模型：

```bash
APP_SUPPORT_OFFICIAL_MODEL_CONFIG_ID=你的官方模型配置ID
APP_SUPPORT_CONFIRMATION_TTL_MINUTES=10
```

其余上下文、输出和 Skill 数量上限在 `application.yml` 的 `app.support` 下，并可通过
对应 `APP_SUPPORT_*` 环境变量覆盖。Flyway 会在应用启动时自动执行三条迁移。

## 8. 建议的后续改进顺序

当前版本已经形成可学习、可运行的最小闭环。后续按实际问题渐进补充即可：先增加写
Skill 与确认状态机的集成测试，再补限流和指标；只有当真实长会话证明摘要不够用时，
再考虑脱敏全文或向量召回。这样不会为了尚未出现的问题提前引入复杂架构。
