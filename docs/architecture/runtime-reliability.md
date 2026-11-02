# Agent 执行、确认与恢复

## 操作确认

HIGH 风险 MCP 调用不再接受工具级预授权。旧 `/api/mcp/tools/{id}/approvals` 返回 410。
聊天和工作流在调用前生成数据库提案；参数以 AES-GCM 加密保存，同时记录规范化参数摘要、工具定义摘要、
用户、租户、会话与执行范围。页面显示冻结参数（敏感字段隐藏），只以确认 UUID 提交确认或取消。
确认时重新检查身份、会话、工具可运行状态、Agent 绑定、参数与工具摘要。工作流测试操作重新要求 OWNER。

`PENDING -> EXECUTING` 是单条数据库条件更新；网络调用发生在提交之后，不占用数据库事务。
成功保存结果后变为 EXECUTED，重复确认或原请求重试只使用结果副本。失败、取消、过期不能重放。
网络异常或进程在远端调用期间崩溃可能产生 UNKNOWN / EXECUTING，必须在外部系统核对；平台不宣称外部 exactly-once。
确认有效期为十分钟。工具定义或绑定变化后必须生成新的提案；外部业务对象的版本约束仍由远端工具负责。

端点：
- `GET /api/mcp/confirmations?sessionId=...`
- `GET /api/mcp/confirmations/workflow/{executionId}`
- `POST /api/mcp/confirmations/{id}/confirm`
- `POST /api/mcp/confirmations/{id}/cancel`

普通聊天在确认后使用原 Idempotency-Key 继续该轮；浏览器 sessionStorage 保存待继续请求，不保存授权令牌。
MCP 多轮重规划可能再次查询只读工具；同一轮中相同工具和相同参数的高风险操作复用已确认结果。

## 执行事件与上下文

聊天 SSE 增加 `execution` 事件，包含 runId、sequence、type、timestamp 和脱敏状态数据。
检索、模型轮次、工具、工作流节点、确认和终态均使用此契约。并行节点共享 runId；sequence 用于去重，
并发到达顺序可能不同。每十五秒发送 heartbeat；断开连接中断执行并释放定时器。
检索在 SSE 建立之后执行。工具及工作流回答仍在整轮结束时返回最终文本，进度事件实时返回。
工作流测试原有节点事件继续保留，并增加同构的确认事件。

摘要和检索资料以带 JSON 数据边界的 UserMessage 进入模型，系统指令声明其不可信；不允许资料改变授权。
预算估计包含这些数据边界产生的转义与字节。服务端权限及参数冻结是执行安全边界，提示词不能代替授权。

## 工作流检查点

`workflow_run_state` 保存执行者、加密输入、加密画布快照、流程指纹及租约。
`workflow_node_checkpoint` 保存节点/循环迭代状态、尝试次数、分支句柄、加密输出和错误码。
成功节点在恢复时直接重放保存的输出，重新构建 DAG 上下文与分支状态，不重复调用模型或工具。
循环体按 `loopKey/iteration/bodyKey` 保存。普通节点最多三次尝试，恢复由用户显式触发。

租约为三十秒，每五秒续期；检查点写入使用短事务锁定所属执行并核验 owner_token。
失去租约的旧 Worker 禁止更新检查点及终态。没有租约的 RUNNING 执行在租约到期后可恢复。
TOOL 或 LOOP 在未知失败时停止自动重试；等待确认时可以恢复。进程崩溃后不重放结果不确定的写节点。
测试运行使用原始画布快照；聊天重试验证现有流程指纹，流程改变则拒绝恢复。
已完成的聊天流程可在幂等重试时复用全部节点结果，以应对最终消息落库失败。

- `POST /api/workflows/executions/{id}/resume`：恢复当前用户的测试运行，返回 SSE。
- `GET /api/workflows/executions/{id}/nodes`：查看当前用户的节点状态。
- 聊天流程在原聊天中重试，沿用原 Idempotency-Key。

## RAG 与来源

切片优先选择段落、行和 Markdown 结构边界；尽量保留可放入单片的代码块和表格。
超过硬上限的大块仍按长度切分，保留 overlap 且不截断 UTF-16 代理对。
新导入切片记录规范化提取文本的字符区间、PDF 页码及 Markdown 章节。字符区间不是原 PDF 文件字节偏移。
检索继续使用 Rewrite、混合召回与 Rerank；仅跳过确定的寒暄，不按主观置信度屏蔽知识问题。
为命中片段补充最多两个有界邻近段落，总检索上下文最多 24,000 字符。

回答引用 `[来源:chunkId]`。前端将该格式解析成按钮，不渲染任意模型 HTML。
`GET /api/knowledge/chunks/{id}` 按当前租户与 READY 文档过滤，返回准确片段、位置及相邻段落。
旧文档仍可引用，但页码、章节和字符区间为空；需要重新导入后才生成完整定位信息。
扫描 PDF 的 OCR、模型效果评测与自动二次检索不在本次实现范围，资料不足时由回答明确说明或请求澄清。

## 迁移与验证

新增 Flyway V35–V37。升级应同时发布前后端，以切换 HIGH 风险工具确认协议。
数据库恢复涉及加密检查点，因此数据库备份和 AES 密钥轮换/历史密钥保留需要配套管理。

默认 `./mvnw verify` 执行单元测试；数据库集成测试仅在提供专用测试库变量时启用：

```bash
ENJOY_TEST_DB_URL=jdbc:postgresql://localhost:55439/postgres \
ENJOY_TEST_DB_USER=postgres ENJOY_TEST_DB_PASSWORD=你的测试库密码 ./mvnw verify
```

集成测试在随机独立 schema 中运行所有迁移，结束时清理 schema。只使用专用测试库。
GitHub Actions 模板位于 `docs/ci/runtime-integration.yml`，使用临时 pgvector 服务运行相同验证。
当前推送凭据缺少 workflow 权限，因此模板未激活；具备该权限后将其放入 `.github/workflows/` 即可启用。
