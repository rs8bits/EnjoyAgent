# 文件处理、成果交付与隔离执行

已实现四个阶段：通用文件与成果交付、隔离执行管理、PDF/Office/表格/SQL 工具、Agent 与工作流接入及本地验收。预热池保留在后续阶段。

## 启动

在后端目录运行：

```bash
cp .env.example .env             # 仅首次运行；保留已有配置
./scripts/start-sandbox.sh
docker compose up -d
set -a
source .env
set +a
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

前端按原方式 `npm run dev`，打开 `/app/files`。运行“示例任务”可以生成 `summary.json` 和 `result.txt`，验证完整交付链路；选择 Python 可运行自定义代码，生成 `/output` 下的文件。

脚本创建 `.sandbox/manager-token`，权限为 0600；主应用与 Manager 分别从本机路径、只读 secret 挂载读取相同令牌。不要提交或向用户暴露此文件。Compose 不会把主应用的数据库、MinIO 或模型凭证传给 Manager；执行容器也不会继承 Manager 的环境变量。

Compose 中的 `sandbox-runtime-image` 和 `sandbox-documents-image` 是一次性镜像就绪检查，完成后退出，并非预热池。Manager 通过 Docker API 动态创建实际任务容器。仅 Manager 挂载 Docker socket。

`SANDBOX_WORK_ROOT` 必须是 Docker daemon 宿主机的绝对路径，并以相同绝对路径挂载到 Manager；脚本默认使用 `$PWD/.sandbox/work`。远程 Docker daemon 不能直接使用应用服务器的本地路径，需把 Manager 和暂存盘部署到执行节点。每个 Manager 使用独占的 `SANDBOX_NAMESPACE` 和工作目录，禁止两个实例共享同一个 namespace。

## 责任与协议

- 主应用：当前用户/租户校验、文件额度、任务队列、参数冻结、代码加密、业务状态、成果完整性校验和 MinIO 持久化。
- Manager：输入暂存、容器生命周期、日志、资源采集、超时、取消、输出清单和暂存清理。没有模型或对象存储凭证。
- 执行容器：只能读取本次任务的 `/input`、`/code`；写 `/workspace`、`/output` 和有限容量 `/tmp`。

协议版本为 1。Manager 的内部接口需要 `X-Sandbox-Token`，只在回环/内部网络发布：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/health` | 镜像和 Docker 就绪检查、协议版本 |
| POST | `/internal/executions/{executionId}` | multipart `spec` JSON 与重复 `files` 文件部分 |
| GET | `/internal/executions/{executionId}` | 状态、stdout、stderr、退出码、指标和输出清单 |
| POST | `/internal/executions/{executionId}/cancel` | 取消；未到达的请求也生成取消墓碑 |
| GET | `/internal/executions/{executionId}/outputs/{outputId}` | 流式读取指定产物 |
| POST | `/internal/executions/{executionId}/acknowledge` | 主应用持久化完成后删除暂存目录 |

`spec` 包含 `tenantKey`、`operation`、`code`、`timeoutSeconds`、`arguments` 和 `inputs`。支持 `DEMO`、`PYTHON`、`SQL`、`PDF_EXTRACT`、`PDF_SPLIT`、`PDF_MERGE`、`PDF_RENDER`、`OFFICE_CONVERT`、`TABLE_INSPECT`、`TABLE_ANALYZE`。每个输入包含服务端生成的 `name`、`sizeBytes`、`sha256`，Manager 在执行前重新校验。客户端不能指定镜像、挂载源、Docker flags 或环境变量。同一个执行 ID 的参数哈希不可更改。

`SandboxProvider.acquire/release` 是容器分配边界，当前实现每次创建、销毁。后续预热实现需要保持相同协议并确保租户数据清理。

## 主应用 API

接口沿用现有 Cookie/Bearer 认证及浏览器 Origin 校验。文件和任务属于当前租户、当前用户；同租户其他用户也不能直接访问。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/files` | multipart `file` 上传，返回文件 ID、MIME、大小和 SHA-256 |
| GET | `/api/files?page=0&size=20` | 当前用户文件和成果分页 |
| GET | `/api/files/{id}` | 文件公开元数据；不返回对象存储坐标 |
| GET | `/api/files/{id}/preview` | 限量文本预览或二进制预览类型 |
| GET | `/api/files/{id}/download` | 流式下载；`inline=true` 仅允许 PDF/PNG/JPEG |
| GET | `/api/sandbox/status` | 启用情况、Manager 就绪情况和上传限制 |
| POST | `/api/sandbox/tasks` | 新建任务，必须提供 `Idempotency-Key` |
| GET | `/api/sandbox/tasks?page=0&size=20` | 任务记录分页 |
| GET | `/api/sandbox/tasks/{id}` | 输入别名、状态、日志、资源及成果 |
| POST | `/api/sandbox/tasks/{id}/cancel` | 持久化取消请求 |

任务请求示例：

```json
{
  "operation": "PYTHON",
  "code": "from pathlib import Path\nPath('/output/result.txt').write_text('done')\nprint('completed')",
  "fileIds": [],
  "stepKey": "analysis",
  "timeoutSeconds": 120
}
```

同一 `Idempotency-Key` 和相同参数返回同一任务；改变代码、输入、步骤或超时返回 409。输入既可以是用户上传的文件，也可以是此前产物的文件 ID。输入真实文件名显示为 `/input/{fileId}.{ext}`，原名通过任务详情保留。

文件工作台每 3 秒查询持久化状态，页面关闭不取消任务。文本最多预览 64 KiB；Excel/CSV 通过 TABLE_INSPECT 生成受限表格预览；Office 通过隔离转换生成 PDF 预览。预览任务和结果可复用。HTML/SVG 等主动内容不内联预览。所有文本通过转义绑定展示，二进制预览仅允许经过文件头识别的类型。

## 持久化与恢复

Flyway V38 新增 `file_asset`、`sandbox_task`、`sandbox_execution`、`sandbox_task_input`、`task_artifact`。V39 增加文档操作、结构化参数、输入顺序、聊天/工作流来源、`chat_message_file` 和 `agent_sandbox_capability`。产物记录关联文件哈希、所属任务、执行 ID、生成步骤；输入关系用于追溯来源。

首版直接以 PostgreSQL 任务表作为持久化调度队列，使用 `FOR UPDATE SKIP LOCKED`、两分钟租约、每 20 秒续租和所有权令牌保护状态更新。没有另引入 RabbitMQ 投递状态；后续可增加 RabbitMQ 唤醒通知，同时继续以数据库为事实来源。

状态为 `QUEUED → PREPARING → RUNNING → COLLECTING → SUCCEEDED`，以及 `FAILED / TIMED_OUT / CANCELLED`。执行成功后，收集阶段失败会重试文件传输，使用确定性产物 ID 避免重复登记、重复占用存储额度。已知运行中的执行记录丢失时终止自动重试，不盲目重新运行代码。

Manager 把执行状态原子写入独立 `records/`。重启时仅清理自身 namespace 的容器，将中断的执行标记为 `MANAGER_RESTARTED`，保留日志，不重放代码。周期回收处理失效容器。主应用完成成果入库后发送确认；确认失败由后台重试。

Manager 默认保留暂存结果和日志 7 天；过期未领取成果标记为 `OUTPUTS_EXPIRED`，保留小型幂等墓碑，避免旧请求再次执行。已确认结果的工作目录立即清理。主应用的 MinIO 文件和业务记录持久保存，受用户存储额度约束；生命周期归档/删除策略后续统一扩展。失败或长时间中断的上传会后台清理。

## 隔离与资源

- 固定镜像，记录实际 image ID；通过 argv 启动 Docker，没有 shell 拼接。
- UID/GID 10001、只读根文件系统、只读输入/代码、断网、无 capabilities、no-new-privileges、默认 seccomp，无宿主网络/PID/特权模式。
- CPU、内存/交换、进程数、打开文件数、单文件大小、临时内存空间均设置上限；默认 1 CPU、1 GiB、128 进程、120 秒，任务超时最大 300 秒。
- stdout/stderr 分别读取，默认各保留前 1 MiB；超过限制仍持续排空，避免管道阻塞。Docker 自身日志也轮转限量。
- Docker 原始 stats 采集累计 CPU、采样内存峰值和块 I/O；很短的任务可能来不及取得有效样本。指标是观测数据，不用于本阶段计费。
- 进程退出后扫描输出，拒绝符号链接、硬链接、特殊文件、过深目录，重新计算哈希；主应用再次校验大小和 SHA-256 才发布产物。
- 默认最多 100 个产物、合计 200 MiB；工作目录有周期容量检查。

**磁盘总量检查属于开发环境兜底，不是文件系统硬配额。** bind mount 不自带总量限制，生产必须把 `SANDBOX_WORK_ROOT` 放在配置项目配额或独立容量限制的执行盘上。普通 Docker 共享宿主内核，生产执行面建议放在独立 Linux VM/宿主机，Manager API 不向公网暴露。

## 验证

```bash
./mvnw test
SANDBOX_DOCKER_TEST=true SANDBOX_DOCUMENT_TEST=true ./mvnw -f sandbox/manager/pom.xml test
```

数据库集成测试通过 `ENJOY_TEST_DB_URL`、`ENJOY_TEST_DB_USER`、`ENJOY_TEST_DB_PASSWORD` 启用；创建随机隔离 schema，测试结束只删除该 schema。需要 PostgreSQL/pgvector 和创建 schema 权限，不得指向生产数据库。

`scripts/sandbox-smoke.py` 验证真实 API、Docker 和 MinIO 的上传/哈希/下载、Python 输出、成果预览、幂等、跨用户隔离、示例、非零退出、超时、取消。它会创建两个测试用户和任务，**仅对隔离测试应用/数据库运行**：

```bash
SANDBOX_TEST_BASE_URL=http://127.0.0.1:18080 python3 scripts/sandbox-smoke.py
```

前端运行 `npm test`、`npm run lint`、`npm run build`。


## 文档与数据工具

分析镜像预装固定版本的 pypdf、pypdfium2、pdfplumber、pandas、openpyxl、xlrd、xlsxwriter、DuckDB 和 matplotlib；文档镜像增加 LibreOffice 与 Noto CJK 字体。

| 操作 | 主要参数和限制 |
| --- | --- |
| PDF_EXTRACT | `pages` 可选；最多 500 页和 200 万字符，输出 extracted.txt 和有界摘要。扫描图片 PDF 不执行 OCR。 |
| PDF_SPLIT | `ranges: [[1,2],[3,4]]` 或 `pages: [1,3]`，1 起始页码，最多 99 份。 |
| PDF_MERGE | 严格保留 fileIds 的传入顺序，合并后最多 500 页。 |
| PDF_RENDER | `pages` 默认 [1]，`dpi` 默认 120、范围 36..200，限制总像素，输出 PNG。 |
| OFFICE_CONVERT | 单个 DOC/DOCX/XLS/XLSX/PPT/PPTX/ODF；`format` 为 pdf/docx/xlsx/csv。独立配置目录，宏安全级别最高，断网，校验实际输出。 |
| TABLE_INSPECT | CSV/TSV/XLS/XLSX；`sheet`、`encoding`、`separator` 可选，统计行列、缺失值、Sheet 和限量预览。 |
| TABLE_ANALYZE | `groupBy`、`aggregations`、`filters`、`sortBy`、`ascending`、`limit`，导出 CSV/XLSX；缺省为描述性统计。 |
| SQL | `code` 放 SQL，输入依次注册为 input_1、input_2。仅在内存数据库查询已加载的输入数据。 |

表格限制为 20 万行、500 列；XLSX 检查解压后大小。结构化分析的 CSV/XLSX 导出防止把输入字符串解释为公式。SQL 禁用外部访问、扩展自动安装和加载，并锁定配置；它没有业务数据库凭证。Python 仍可使用预装库进行自定义分析和绘图。

## Agent 与聊天

工作区 OWNER 在 Agent 设置中开启“文件处理与代码执行”。API 为 `GET/PUT /api/agents/{id}/sandbox`，PUT 请求为 `{"enabled":true}`。默认关闭，主应用还须启用全局沙箱配置。

聊天请求增加 `fileIds`（最多 10 个）；其语义进入聊天幂等指纹。已附加或本会话任务生成的文件构成允许列表，模型不能使用其他会话或其他用户的任意文件 ID。开启能力后，显式工具循环同时处理 MCP 与内置 `sandbox_*` 工具，保留逐次模型计费、token 预算、工具轮次、最多 10 次文件操作和时间预算。保留的工具名若与 MCP 冲突会显式拒绝。

沙箱任务在创建时绑定用户消息或工作流执行及步骤。输出在主应用校验、入库后成为消息附件；最终回复保存时把已完成成果关联到助手消息。刷新历史仍可预览下载，不依赖模型生成链接。SSE 发送 sandbox_started、sandbox_status、artifact_created 等运行事件，不发送代码、管理令牌或存储凭证。断开聊天等待不销毁已提交的文件任务，可以从文件工作台继续查看。

## 工作流

新增 SANDBOX 节点和配置面板，使用与任务 API 相同的 operation/code/fileIds/arguments/timeoutSeconds。fileIds 支持类型保留的变量引用，例如 `{{copy.artifact_ids}}`；聊天执行可以使用 `{{input_file_ids}}`。输出字段为 sandbox_result、artifact_ids、artifacts、task_id，供变量选择器和后续节点使用。

执行使用工作流执行 ID 和包含循环迭代的检查点 key 构造稳定幂等键，恢复不会重新运行已完成容器。显式取消工作流测试会同步标记其沙箱任务取消。修复了持久化图的连线关联预加载，以及检查点快照按稳定节点 key 定位端点的问题，使保存后的图和恢复后的图保持一致。

额外运行时验收：

```bash
SANDBOX_TEST_BASE_URL=http://127.0.0.1:18080 python3 scripts/sandbox-runtime-smoke.py
```

该脚本同样只对隔离测试应用/数据库运行。它临时启动回环地址的 OpenAI 兼容模型模拟服务（默认 18092，可用 SANDBOX_TEST_MODEL_PORT 修改），验证真实 Agent 工具协议、附件权限、消息历史、聊天幂等、SSE 成果事件、能力开关、工作流产物传递和检查点恢复，不调用付费模型。
