# Java 迁移服务

这是渐进迁移影子服务，不会替换当前 `8765` 端口上的 FastAPI。当前已完成基础契约、DBHub 客户端、医院认证与规则只读、版本化 Agent IR、Spring AI 模型适配、Evidence、受控 SQL、三层诊断、Excel 汇总分析、试运行结果明细、上传明细与系统明细的逐条差异导出、2 至 3 个指标的隔离执行与自适应并行，以及单轮 Trace 持久化与链路查看。规则写入、审批和跨运行观察工作台仍由 Python 负责。

## 本地运行

```powershell
cd F:\A-wiki-project\backend-java
mvn -s maven-settings.xml test
mvn -s maven-settings.xml spring-boot:run
```

仓库内的 `maven-settings.xml` 修正本机已失效的 HTTP 阿里云镜像，使用 Maven Central HTTPS，并沿用当前 Windows 本地代理 `127.0.0.1:7897`；不包含凭据。若部署机不使用该代理，删除其中 `<proxies>` 段即可。

默认监听 `http://127.0.0.1:8766`：

- `GET /api/health`：与现有 FastAPI 的基础健康契约一致。
- `GET /api/migration/status`：查看当前迁移阶段，明确当前权威运行时仍是 Python。
- `GET /api/mcp/dbhub/sources`：通过 Java 客户端访问现有 DBHub sidecar。
- `POST /api/auth/hospital/login`：使用现有 MySQL 医院账号登录，签发 Python 可识别的共享会话。
- `POST /api/auth/hospital/change-password`、`POST /api/auth/hospital/logout`：兼容现有认证语义。
- `GET /api/kb/rules/search`：按登录主体所在医院搜索规则。
- `GET /api/kb/rules/{rule_id}/effective`：读取本院生效口径；客户端传入其他医院会被拒绝。
- `POST /api/migration/agent/compile`：认证后的影子编译接口，只返回计划校验、版本化 IR 和第一步确定性决策，不执行工具。
- `POST /api/migration/agent/chat`、`POST /api/migration/agent/chat/stream`：执行 Java 影子 Agent，支持规则解释、受控 SQL 试运行、诊断与上传汇总分析。
- `POST /api/sql-runs/{run_id}/details`：基于已成功试运行生成或复用数量一致的短期明细快照。
- `GET /api/sql-runs/{run_id}/details/{denominator|numerator|unmatched}`：分页返回脱敏明细。
- `POST /api/sql-runs/{run_id}/exports`：在有导出权限且显式确认后生成三工作表 `.xlsx`。
- `POST /api/sql-runs/{run_id}/upload-comparison-exports`：把同指标上传明细与系统快照按稳定业务键逐条比较，生成四工作表差异 `.xlsx`。
- `GET /api/indicator-exports/{export_id}/download`：医院隔离、有效期与 SHA-256 校验后的授权下载。
- `GET /api/agent/runs/{trace_id}`：按当前登录医院读取 Java/Python 共用 Trace 表中的安全节点、Evidence 来源和耗时汇总。

配置在 `src/main/resources/application.yml`。运行库凭据通过 `WIKI_RUNTIME_DB_URL`、`WIKI_RUNTIME_DB_USER` 和 `WIKI_RUNTIME_DB_PASSWORD` 提供，真实密码和令牌不得写入本目录；Java 服务不直连医院 SQL Server。

已登录情况下可以运行 Python/Java 双跑：

```powershell
$env:MIGRATION_HOSPITAL_TOKEN = '<当前登录令牌>'
$env:MIGRATION_HOSPITAL_ID = 'hospital_001'
python ..\scripts\compare_java_python_read_api.py
```

脚本只输出安全字段的差异，不打印令牌。跨语言密码算法测试使用 `contracts/migration/v1/auth-crypto-vector.json` 中的非生产测试向量。

Java 已实现版本化 `RequestPlan` / `CompiledPlanIR`、能力注册表、PlanValidator、确定性中文时间解析、StateController、DeterministicDispatch、类型化策略和 ToolGateway。SQL Server 数据始终通过现有 DBHub sidecar 只读访问。上传明细与系统明细按患者/业务标识和关键事件时间执行多重集合比较，重复行不会被静默去重；模型只看到双方都有、仅系统有、仅上传有、字段差异和达标判定差异等安全汇总。患者级明细不会进入 LLM、Evidence、Trace 或会话；页面只取得脱敏分页，原始值只存在于 24 小时短期快照和经确认生成的导出文件。

复合请求由 `CompoundRequestSplitter` 在服务端识别明确并列指标或跨轮“这两个/它们/分别”等复数指代，再由 `CompoundAgentRuntime` fan-out 到相互隔离的单指标 Runner。每个子任务拥有独立会话、请求 ID、RunState、Evidence namespace 与 Trace 子标识，子任务执行期间不修改父状态。OpenAI 兼容 API 最大并发 2，Ollama 最大并发 1，DBHub 只读最大并发 2；上传、规则变更、发布和审批类任务保持串行。整体超时会取消未完成任务，已成功子任务仍按输入顺序返回；Vue 同一回答可展示多个明细或差异导出入口。

Java Trace 复用现有 `med_agent_trace` 和 `med_agent_trace_node`，不部署外部可观测服务。新运行记录 `memory_load`、`planner_llm`、`plan_compile`、`plan_validate`、`state_controller`、`deterministic_tool_dispatch`、`tool_result`、`plan_verify`、`final_answer_llm`、`response_guard`、`memory_save`，复合请求另含拆分、子任务和合并节点。节点包含真实开始偏移、耗时、父子关系、`subtask_id`、工具、模型、能力、FailureClass、缓存及安全输入输出；密码、认证令牌、SQL 正文与患者原始行不会落入 Trace。Vue 链路抽屉以中英文节点名、类型颜色、瀑布条、泳道、筛选和 Evidence 来源显示这些数据，历史 Python Trace 缺少新字段时仍按顺序降级展示。

后续批次会按照 `docs/migration/java-vue-migration.md` 继续迁移跨运行 Trace 指标和 Vue 观察工作台。只有同一接口通过契约对比后，才允许在入口层切流。
