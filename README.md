# Enjoy Agent

Enjoy Agent 是一个以后端为主的多租户 AI Agent 平台项目。它不是单纯的 SDK Demo，也不是只会聊天的玩具项目，而是希望用一套真实可运行的 Spring Boot 代码，把 Agent 平台里几条最关键的能力主线完整串起来：

- 用户与租户体系
- BYOK 与平台官方模型双轨接入
- 聊天、流式输出与模型调用日志
- RAG 检索链路
- MCP 工具集成与 OAuth
- 用户钱包、充值审核与异步 token 计费
- 共享市场、审核发布与安装复制

当前项目以后端为主，前端管理后台仍在继续完善。

## 项目介绍

这个项目最适合两类场景：

- 想系统学习 AI Agent 后端应该怎么设计，而不是只停留在模型 SDK 调用
- 想自己动手实现一个带多租户、RAG、工具、计费和市场能力的 Agent 平台

当前主干已经打通了这条链路：

`认证 -> 凭证 -> 模型 -> Agent -> 聊天 -> RAG -> MCP -> 官方模型 -> 钱包计费 -> 市场安装`

## 核心亮点

### 1. RAG 不是独立 Demo，而是接进了真实聊天运行时

Enjoy Agent 的 RAG 不是“上传文件后做一次检索实验”，而是接到了 Agent 聊天主链里：

- 文档上传后进入 MinIO
- 文本抽取、切片、embedding、pgvector 入库
- 支持 Query Rewrite
- 支持 Rerank
- 支持 PostgreSQL 向量检索
- 支持 Elasticsearch 混合检索
- 支持检索调试信息回传，便于排查“为什么命中/为什么没命中”

这让它更像真实业务系统里的 RAG，而不是单独的实验模块。

### 2. 计费不是按次拍脑袋扣，而是按 token 异步结算

这版计费已经重构成了更接近平台产品的形态：

- 钱包挂在 `user`，而不是挂在 `tenant`
- 官方模型与用户自带模型分离
- 管理员维护官方模型、官方 key 和价格
- 用户充值先创建充值单，再由管理员审核入账
- 官方模型调用后记录 `billing_usage_event`
- 通过 RabbitMQ 异步消费计费事件
- 按输入/输出 token 单价计算费用
- 写入钱包流水并更新余额

也就是说，这里不是简单的“请求成功就减一个固定值”，而是：

`模型调用日志 -> 价格快照 -> 计费事件 -> RabbitMQ -> 钱包流水`

### 3. 共享市场不是弱引用，而是“可安装包”

市场设计上最重要的取舍是：安装后生成本租户自己的副本，而不是跨租户直接引用源对象。

当前已经支持：

- Agent 提交市场
- Knowledge Base 提交市场
- MCP Server 提交市场
- 管理员审核、通过、驳回、下架
- 用户安装市场资产到自己的租户

其中 Agent 市场安装已经支持：

- 复制 Agent 配置
- 打包并复制知识库文档
- 在目标租户创建新的知识库副本
- 复制 MCP Server 与 Tool 定义
- 自动恢复 Agent 的 MCP Tool 绑定
- 对私密认证型 MCP 返回后续配置提示

这样市场里的 Agent 更接近“可运行模板”，而不是装完还缺一堆依赖的空壳。

### 4. MCP 不是静态表结构，而是完整接进运行时

项目已经支持较完整的 MCP 后端能力：

- MCP Server 注册与启停
- Tool 目录同步
- Agent 绑定 Tool
- 聊天运行时注入 Tool 定义
- Tool 调用日志
- OAuth Auth Code + PKCE
- OAuth Client Credentials
- Access Token 刷新

这让项目不仅能“记录有哪些工具”，还能够真正把 MCP 工具拉进 Agent 对话链路里。

### 5. 模型网关同时支持 BYOK 和平台官方模型

模型来源在运行时被显式区分成两类：

- `USER`
- `PLATFORM`

这带来的好处是：

- 用户可以继续使用自己的百炼/OpenAI-compatible key
- 平台可以提供官方托管模型
- 日志、计费、权限和市场安装都能稳定判断这次调用到底走的是谁的模型

## 技术栈

- Java 21
- Spring Boot 3
- Spring Security + JWT
- Spring Data JPA
- PostgreSQL + pgvector
- Redis
- RabbitMQ
- MinIO
- Elasticsearch
- Spring AI
- Flyway

## 当前后端能力

- 用户注册、登录、JWT 鉴权
- 多租户隔离与管理员系统角色
- 凭证管理与 AES 加密存储
- 用户模型配置与官方模型配置
- Agent 管理、会话管理、消息持久化
- 普通聊天与 SSE 流式聊天
- 知识库、文档上传、切片、embedding、检索
- Query Rewrite、Rerank、混合检索、检索调试信息
- MCP Server / Tool / OAuth / 运行时调用
- 用户钱包、充值单、管理员审核入账
- 官方模型按 token 异步计费
- 市场提交、审核、安装与复制

## 设计取舍

这个项目有几条很明确的设计取舍：

- 优先做“真实主链路”，而不是一开始做很重的企业级平台外壳
- 保留多租户、凭证加密、迁移、日志这些最基本的平台边界
- 市场安装优先复制副本，不做跨租户共享运行态对象
- 先把最有学习价值的主链路打通，再做工程化收尾

如果你想看“一个 Agent 平台真正难的地方”，这个项目最值得看的通常不是 Controller，而是这些地方：

- 聊天运行时如何接 RAG 和 MCP
- 用户模型与官方模型如何在同一条链里共存
- 计费如何从同步扣费演进到异步 token 结算
- 市场资产如何从“分享对象”演进成“可安装包”

## 快速启动

### 1. 启动基础依赖

项目自带的 `compose.yml` 当前包含：

- PostgreSQL
- Redis
- MinIO
- RabbitMQ
- Elasticsearch：用于混合检索

启动方式：

```bash
docker compose up -d
```

启动后常用地址：

```text
PostgreSQL: localhost:5432
Redis: localhost:6379
MinIO API: http://localhost:9000
MinIO Console: http://localhost:9001
RabbitMQ: localhost:5672
RabbitMQ Console: http://localhost:15672
Elasticsearch: http://localhost:9200
```

如果你希望本地后端也直接联到这些依赖，建议先复制环境变量模板：

```bash
cp .env.example .env
```

其中已经补好了：

- `RABBITMQ_HOST / RABBITMQ_PORT`
- `APP_BILLING_ENABLED=true`
- `APP_KNOWLEDGE_SEARCH_ENABLED=true`
- `APP_KNOWLEDGE_SEARCH_BASE_URL=http://localhost:9200`

### 2. 启动应用

```bash
mvn spring-boot:run
```

默认端口：

```text
http://localhost:8080
```

Swagger：

```text
http://localhost:8080/swagger-ui.html
```

说明：

- `compose.yml` 现在已经覆盖本地联调需要的主要基础设施
- 应用服务本身仍然建议在本机通过 Maven 启动，调试体验更好
- 如果你后面希望把后端应用本身也一起放进 `docker compose`，可以继续再补一个 `app` 服务

## 当前状态

从后端视角看，项目已经具备一个相对完整的 MVP/第一版闭环，并且已经跑过一轮真实中间件与真实模型参与的集成联调，核心链路包括：

- BYOK 知识库聊天
- 官方模型充值审核与异步扣费
- 市场提交、审核、安装
- 安装后的 Agent 再次命中打包知识库

因此它现在已经不只是“代码能编译”，而是具备了比较完整的后端业务闭环。

## 文档

- [Agent.md](./Agent.md)：项目定位、架构思路、边界与协作原则
- [Plan.md](./Plan.md)：当前完成情况、阶段计划与下一步建议
