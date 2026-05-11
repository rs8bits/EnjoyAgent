# EnjoyAgent Backend Architecture

这份文档记录 EnjoyAgent 后端的架构立场、模块边界和核心设计取舍。README 更适合 GitHub 首屏展示；本文件更适合想深入理解代码的人继续阅读。

## 项目定位

EnjoyAgent Backend 的目标是呈现一个真实 AI Agent 平台后端该怎么组织，而不是只演示一次模型调用。

它重点回答这些问题：

- 多租户 Agent 平台如何组织用户、租户、资产和管理员权限
- RAG 如何从文档上传走到聊天运行时
- MCP 工具如何从“配置项”变成“可调用运行时能力”
- BYOK 和平台官方模型如何共存
- 官方模型调用如何进入用户钱包和异步 token 计费
- 市场资产如何从“分享对象”变成“可安装包”
- 可视化 Workflow 如何保存、测试、复制和接入 Agent

## 模块地图

```text
auth / tenant
credential
model / modelgateway
agent / chat
knowledge
mcp
workflow
billing
market
shared
```

核心主链路：

```text
User -> Tenant -> Credential -> Model -> Agent -> Chat
                                  |        |
                                  |        -> RAG
                                  |        -> MCP Tool
                                  |        -> Workflow
                                  |
                                  -> Billing
                                  -> Marketplace
```

## 关键设计取舍

### 1. 租户承载业务资产，用户承载钱包

Agent、知识库、MCP Server、Workflow 这类业务资产挂在租户下；钱包挂在用户下。这样能同时满足团队协作和平台计费：

- 一个用户可以拥有或加入多个租户
- 官方模型扣费主体更接近平台用户
- 充值、审核、人工调账都以用户钱包为核心

### 2. 官方模型独立建模

官方模型没有混进普通用户模型配置里，而是单独建模。这样可以明确区分：

- 用户自己的 BYOK 模型
- 平台托管模型
- 平台官方 key
- 平台价格
- 计费来源

这让模型调用日志、官方模型扣费、市场安装和权限判断更稳定。

### 3. RAG 是运行时能力，不是旁路 Demo

RAG 链路直接进入 Agent 聊天运行时，覆盖：

- 文档上传和对象存储
- 文本抽取
- chunk 切片
- embedding 入库
- pgvector 检索
- Elasticsearch 混合检索
- Query Rewrite
- Rerank
- 检索调试信息

这套设计适合真实排查“为什么没召回、为什么回答不好、为什么 token 变多”这类问题。

### 4. MCP 进入 Agent Runtime

MCP 不是静态配置表。EnjoyAgent 后端会管理 MCP Server、同步 Tool、绑定 Agent，并在聊天运行时把可调用工具注入模型链路。

已支持：

- Tool 调用日志
- OAuth Auth Code + PKCE
- OAuth Client Credentials
- Access Token refresh
- Agent Tool binding

### 5. Workflow 是可保存、可测试、可市场安装的资产

Workflow 不只是前端画布状态，而是后端正式资产：

- Workflow 元数据
- WorkflowNode
- WorkflowEdge
- Canvas 保存
- 测试运行
- 执行历史
- 提交共享市场
- 安装复制节点和连线

跨租户安装时，模型、知识库、工具这类租户级依赖会被重映射或提示用户重新配置，避免把源租户的私有资源直接带到目标租户。

### 6. 市场安装复制副本，不跨租户弱引用

市场资产安装后会在目标租户创建本地副本，而不是直接引用源租户对象。

原因：

- 跨租户引用会让删除、权限和变更传播变得不可控
- MCP 背后可能有私密 credential 和 OAuth 连接态
- 知识库文档如果只引用源对象，安装体验会非常脆弱

因此市场更像“可安装模板系统”，不是“公开引用系统”。

### 7. 异步计费保留数据库事实

RabbitMQ 负责异步消费，但数据库里的 `billing_usage_event` 才是计费事实。

它保存：

- 模型调用来源
- 输入 token
- 输出 token
- 价格快照
- 事件状态
- 错误信息

这样即使消息消费失败，也能做重试、补偿和审计。

## 编码边界

- Controller 保持轻量，只做协议层转换
- Application Service 承载业务编排
- Repository 只处理持久化查询
- 共享异常走统一 `ApiException`
- 所有表结构变化必须通过 Flyway migration
- 敏感 credential 只存加密文本，不在响应中回传明文
- 市场、计费、MCP、Workflow 这类跨模块能力优先写清业务语义，再写代码

## 当前仍需加强

- 集成测试脚本需要固化进仓库
- 市场安装还可以进一步异步化
- Workflow Runtime 需要更强的节点级日志、重试和调试能力
- 管理员权限还可以从系统角色演进到 RBAC
- 审计、统计和可观测性还可以继续增强

## 阅读建议

如果你第一次看代码，建议按下面顺序：

1. `auth / tenant`
2. `model / modelgateway`
3. `agent / chat`
4. `knowledge`
5. `mcp`
6. `workflow`
7. `billing`
8. `market`

这个顺序基本就是 EnjoyAgent 从“用户登录”到“Agent 能运行、能计费、能分享”的主链。
