# EnjoyAgent

[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6db33f)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-4169e1)](https://www.postgresql.org/)

EnjoyAgent 是一个基于 Spring Boot 的多租户 AI Agent 平台后端。前端仓库见
[rs8bits/EnjoyAgent-frontend](https://github.com/rs8bits/EnjoyAgent-frontend)。

## 功能

- 用户认证、租户隔离和管理员权限
- 用户凭证、官方凭证和 AES-GCM 密钥保护
- OpenAI 兼容模型与阿里百炼模型配置
- Agent、会话、SSE 流式聊天和对话记忆
- 文档上传、MinIO 存储、pgvector 与 Elasticsearch 混合检索
- MCP Server、工具同步、OAuth、调用审批和调用日志
- 可视化工作流持久化与执行
- 钱包、额度预留、模型调用计费和可靠异步事件
- 共享市场、资产安装和管理员审核
- 用户 + 租户唯一的 AI 客服、LLM 工具规划、轻量摘要记忆、安全确认和调用归档

## 技术栈

- Java 21、Spring Boot 3.5、Spring Security、Spring Data JPA
- Spring AI、JWT、Flyway
- PostgreSQL + pgvector、Redis + Caffeine + Redisson
- RabbitMQ、MinIO、Elasticsearch
- Micrometer、Prometheus、Grafana

## 本地运行

### 环境要求

- JDK 21
- Docker 与 Docker Compose
- macOS、Linux，或支持 Bash 的开发环境

项目包含 Maven Wrapper，不需要预先安装 Maven。

### 1. 获取代码和配置

```bash
git clone https://github.com/rs8bits/EnjoyAgent.git
cd EnjoyAgent
cp .env.example .env
```

`.env.example` 中的凭证只适用于 `local` 或 `perf` profile。不要把这些默认值用于
公网或生产环境。

### 2. 启动基础设施

```bash
docker compose up -d
docker compose ps
```

Compose 默认仅把服务绑定到 `127.0.0.1`：

| 服务 | 地址 |
| --- | --- |
| PostgreSQL | `localhost:5432` |
| Redis | `localhost:6379` |
| MinIO API / Console | `localhost:9000` / `localhost:9001` |
| RabbitMQ / 管理台 | `localhost:5672` / `localhost:15672` |
| Elasticsearch | `localhost:9200` |
| Prometheus / Grafana | `localhost:9090` / `localhost:3000` |

### 3. 启动应用

```bash
set -a
source .env
set +a
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

常用地址：

| 服务 | 地址 |
| --- | --- |
| API | `http://localhost:8080` |
| 健康检查 | `http://localhost:8080/actuator/health` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |

## 测试与构建

```bash
./mvnw test
./mvnw clean package
```

构建产物位于 `target/enjoy-agent-0.0.1-SNAPSHOT.jar`。

## 配置

完整的本地示例见 [.env.example](.env.example)，主要配置分为：

- `DB_*`、`REDIS_*`、`RABBITMQ_*`：数据和消息服务
- `MINIO_*`、`APP_KNOWLEDGE_*`：文件存储和知识库处理
- `APP_JWT_*`、`APP_CREDENTIAL_*`：认证与凭证加密
- `APP_MCP_*`：MCP 运行时、OAuth 和高风险工具审批
- `APP_BILLING_*`：钱包、额度预留和异步计费
- `APP_CACHE_*`：本地缓存、Redis 缓存和失效广播
- `APP_SUPPORT_*`：AI 客服官方模型、最近消息和严格上下文预算

模型供应商密钥由用户或管理员通过 API 写入并加密存储，不应直接提交到仓库。

AI 客服需要把 `APP_SUPPORT_OFFICIAL_MODEL_CONFIG_ID` 显式设置为一个已启用的官方
`CHAT` 模型配置 ID。客服直接使用平台官方凭证，不伪造普通 Agent，也不会从用户钱包
扣除客服调用费用。未配置模型时，纯问候与固定项目介绍仍可由规则快速回答。实现演进
可分别阅读 [`support-phase1.md`](docs/architecture/support-phase1.md) 和
[`support-phase2.md`](docs/architecture/support-phase2.md)。

## 生产安全

部署到公网前至少需要：

- 为 JWT、凭证加密和 MCP 审批分别生成高强度随机密钥
- 更换 PostgreSQL、RabbitMQ、MinIO、Grafana 等默认密码
- 设置 `SERVER_ADDRESS=127.0.0.1`，仅通过 HTTPS 反向代理暴露应用
- 精确配置允许的浏览器 Origin，并启用 `Secure` Cookie
- 关闭 Swagger、公共开发端点和不需要的 Actuator 端点
- 对数据库、对象存储和消息队列建立备份与恢复流程

`local` profile 包含便于开发的默认值，不适合直接暴露到公网。

## 数据库迁移

应用启动时由 Flyway 自动执行 `src/main/resources/db/migration` 中的迁移。升级已有环境前，
请先备份 PostgreSQL 和 MinIO 数据。

## 可观测性与性能

Compose 包含 Prometheus、Grafana、PostgreSQL Exporter 和 Redis Exporter。性能测试、
故障演练及调优说明位于：

- [performance/jmeter/README.md](performance/jmeter/README.md)
- [performance/fault-injection/README.md](performance/fault-injection/README.md)
- [performance/tuning/README.md](performance/tuning/README.md)

## 目录结构

```text
src/main/java/com/enjoy/agent
├── agent          Agent 管理
├── auth           认证与租户
├── billing        钱包与计费
├── chat           会话与聊天
├── credential     凭证
├── knowledge      知识库与检索
├── market         共享市场
├── mcp            MCP 运行时
├── model          模型配置
├── modelgateway   模型调用网关
├── shared         通用安全、缓存和基础设施
├── support        唯一 AI 客服会话、Skill、安全 Hook 与审计
└── workflow       工作流
```

## 相关文档

- [Agent.md](Agent.md)：架构与协作约定
- [docs/architecture/reliability-and-performance.md](docs/architecture/reliability-and-performance.md)：可靠性与性能设计
- [docs/performance/stage4-report.md](docs/performance/stage4-report.md)：故障演练与参数调优结果
