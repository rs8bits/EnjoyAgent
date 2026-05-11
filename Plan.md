# EnjoyAgent Backend Roadmap

这份文档记录后端当前状态和后续开发路线。项目已经具备第一版核心业务闭环，接下来重点不是继续堆页面或表结构，而是把稳定性、可观测性和可运营能力补扎实。

## 当前状态

EnjoyAgent Backend 已经完成这些主链：

- 认证、多租户与管理员边界
- 凭证加密与用户模型配置
- 官方模型配置与平台托管凭证
- Agent 管理、会话、消息和 SSE 流式聊天
- RAG 文档处理、向量检索、混合检索、Query Rewrite、Rerank
- MCP Server、Tool 同步、OAuth、运行时工具调用
- Workflow 画布保存、测试运行、执行历史
- 用户钱包、充值单、管理员审核、RabbitMQ 异步 token 计费
- 共享市场提交、审核、安装和本地副本复制

一句话概括：

```text
EnjoyAgent Backend 已经是一个具备真实平台闭环的多租户 Agent 后端 MVP。
```

## 已完成阶段

### 阶段 1：基础设施

- Spring Boot 3
- Java 21
- PostgreSQL / Redis / MinIO / RabbitMQ / Elasticsearch
- Flyway
- Swagger / OpenAPI

### 阶段 2：平台基础

- 用户注册和登录
- JWT 鉴权
- 多租户隔离
- 系统管理员角色
- 统一异常响应

### 阶段 3：模型与凭证

- 用户凭证加密存储
- 用户模型配置
- 官方模型配置
- 官方模型托管凭证
- 模型调用日志

### 阶段 4：Agent Runtime

- Agent CRUD
- 会话和消息持久化
- 普通聊天
- SSE 流式聊天
- RAG 注入
- MCP Tool 调用
- 用户模型和官方模型分流

### 阶段 5：RAG

- 文档上传
- MinIO 存储
- 文本抽取
- chunk 切片
- embedding 入库
- pgvector 检索
- Elasticsearch 混合检索
- Query Rewrite
- Rerank
- 检索调试信息

### 阶段 6：MCP

- MCP Server 管理
- Tool 同步
- Agent Tool 绑定
- Tool 调用日志
- OAuth Auth Code + PKCE
- OAuth Client Credentials
- Token refresh

### 阶段 7：Workflow

- Workflow CRUD
- Canvas 节点和连线保存
- 工作流测试运行
- 执行历史
- Workflow 共享市场提交和安装复制

### 阶段 8：Billing

- 用户钱包
- 钱包流水
- 充值单
- 管理员审核充值
- 官方模型价格
- `billing_usage_event`
- RabbitMQ 异步计费

### 阶段 9：Marketplace

- Agent / Knowledge Base / MCP Server / Workflow 提交
- 管理员审核、驳回、下架
- 市场资产列表和详情
- 安装到目标租户
- Agent 安装复制知识库和 MCP 依赖
- Workflow 安装复制节点和连线

## 下一阶段优先级

### P0：集成测试和联调脚本固化

目标：让任何人拉起项目后，都能快速确认核心链路可用。

建议补充：

- 注册登录 smoke test
- 创建模型和凭证 smoke test
- RAG 上传、索引、聊天 smoke test
- MCP Server 和 Tool 调用 smoke test
- Workflow 保存、测试运行 smoke test
- 市场提交、审核、安装 smoke test
- 官方模型计费 smoke test

完成标准：

- 本地一条命令可以验证主链
- CI 可以至少跑单元测试和部分轻量集成测试

### P1：市场安装异步化

当前市场安装已经能复制资产，但知识库文档复制、索引重建、MCP 安装和 Workflow 依赖检查可以继续产品化。

建议补充：

- 安装任务表
- 安装进度状态
- 安装失败原因
- 可重试安装
- 部分成功补偿策略
- 安装结果查询接口

### P1：Workflow Runtime 增强

建议补充：

- 节点级执行日志
- 节点输入输出快照
- 节点重试策略
- 节点超时策略
- 变量面板后端结构
- 条件分支调试信息
- 运行失败恢复

### P2：管理员能力增强

建议补充：

- 用户列表
- 用户禁用
- 系统角色调整
- 管理员操作审计
- 模型调用统计
- 计费事件统计
- 市场安装统计

### P2：权限模型升级

当前 `USER / ADMIN` 已经够 MVP 使用。后续可以加入：

- 平台超级管理员
- 市场审核员
- 充值审核员
- 只读运营
- 租户内成员角色细化

### P3：市场运营能力

建议补充：

- 资产搜索
- 资产分类
- 收藏
- 评分
- 版本更新
- 安装升级
- 发布说明

## 暂不优先

- 过早做复杂风控系统
- 过早拆微服务
- 过早做超复杂 RBAC
- 过早接入大量模型 provider 抽象
- 过早做市场评论和社区功能

当前更有价值的是让已有主链更稳、更可测、更易部署。

## 推荐开发顺序

1. 集成测试脚本固化
2. Workflow Runtime 节点级日志和调试信息
3. 市场安装异步化
4. 管理员用户管理和审计
5. 计费、模型、市场统计看板接口
6. 市场版本和升级
7. 更细 RBAC

## 文档维护原则

每次修改这些模块时，都需要同步更新文档：

- `billing`
- `market`
- `workflow`
- `mcp`
- `knowledge`
- `modelgateway`

README 负责吸引用户理解项目价值；本文件负责告诉贡献者下一步该往哪里推进。
