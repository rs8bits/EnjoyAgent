# 文件处理与成果交付本地验收

验证时间：2026-11-02，Asia/Shanghai。

本次使用真实 Docker Engine、PostgreSQL/pgvector、MinIO 和 RabbitMQ。数据库/API 验收在随机隔离 schema 与独立 Redis 中完成，未向业务 schema 写入测试用户或任务。Agent 协议测试使用仅监听回环地址的 OpenAI 兼容模拟服务，不调用付费模型。

| 验证 | 结果 |
| --- | --- |
| 后端 Maven 测试（包括真实 PostgreSQL 集成） | 336 通过，0 失败，0 跳过 |
| Manager 测试（包括真实容器和 LibreOffice） | 19 通过，0 失败，0 跳过 |
| 前端 Vitest | 96 通过 |
| 前端生产构建 / TypeScript / ESLint | 通过 |
| Compose Manager 健康检查 | 通过 |

## 覆盖

- 文件上传、大小/MIME/哈希、权限、流式下载、结果 JSON 预览。
- 真实 Python stdout/stderr、非零退出码、超时、OOM、取消、非 root、断网、只读输入、无 Docker socket 和模型凭证。
- 日志保存上限与持续排空，包括启动后立即输出大量日志的任务，保留最初输出。
- 输出目录限制、符号链接/硬链接拒绝、文件完整性重检、成果入库。
- 重复提交、代码冻结、租约抢占和旧 worker 状态写入拒绝。
- 成果上传失败后停留在收集阶段，恢复后不重新提交容器、不重复产物、不重复计算存储额度。
- Manager 重启时清理自身失效容器，保留执行失败事实，不重放代码。
- PDF 文本提取、范围拆分、按输入顺序合并、真实 PNG 渲染、加密 PDF/非法页码拒绝。
- XLSX Sheet/行列检查、CSV 分组求和、生成 CSV/XLSX 并重新读取验证。
- DuckDB 查询已注册输入表；拒绝读取 /etc/passwd、安装扩展和重新开启外部访问。
- 包含中文的 DOCX 经 LibreOffice 生成 PDF，并再次提取验证文本。
- Agent 内置工具规划、附件允许列表、能力关闭拒绝、消息历史中的成果引用、聊天幂等和 SSE 成果事件。
- 持久化 SANDBOX 工作流：文件复制 → SQL 聚合 → 最终成果；检查点恢复复用原任务 ID，不重新执行。

## 命令

在后端目录：

```bash
ENJOY_TEST_DB_URL=jdbc:postgresql://localhost:5432/enjoy_agent \
ENJOY_TEST_DB_USER=enjoy ENJOY_TEST_DB_PASSWORD=enjoy123 ./mvnw test

SANDBOX_DOCKER_TEST=true SANDBOX_DOCUMENT_TEST=true \
./mvnw -f sandbox/manager/pom.xml test

SANDBOX_TEST_BASE_URL=http://127.0.0.1:18080 python3 scripts/sandbox-smoke.py
SANDBOX_TEST_BASE_URL=http://127.0.0.1:18080 python3 scripts/sandbox-runtime-smoke.py
```

上述数据库账号是 `.env.example` 的本地示例。集成测试自动创建、删除随机 schema；API smoke 脚本必须指向单独启动的隔离测试应用，详见架构文档，不能直接指向生产环境。

前端：

```bash
npm test
npm run build
npm run lint
```

## 本次修复的恢复问题

真实持久化工作流验收发现并修复了两项原有问题：异步执行时连线端点未预加载；检查点快照通过实体对象身份查找节点位置，跨持久化上下文会得到 -1。现在预加载端点，并按稳定 nodeKey 定位快照端点；增加了独立数据库回归测试。

## 范围

预热池、OCR、业务数据库连接、在线安装 Python 包未包含在本版。磁盘总量检查是周期检查；生产执行盘仍需配置文件系统硬配额，执行面应与业务服务隔离部署。文件和业务执行记录持久保存，Manager 暂存及日志按保留期限清理。
