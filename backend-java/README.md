# Java 迁移服务

这是渐进迁移影子服务，不会替换当前 `8765` 端口上的 FastAPI。当前已完成基础契约、DBHub 客户端、医院认证与规则只读、版本化 Agent IR、Spring AI 模型适配、Evidence、受控 SQL、三层诊断、Excel 汇总分析、试运行结果明细、上传明细与系统明细的逐条差异导出、2 至 3 个指标的隔离执行与自适应并行、单轮 Trace、跨运行观察、固定 L1/L4/L5/可选 L6 的全面实施验收 MVP、元数据概览与 DBHub 同步、医学术语治理，以及指标监控治理、执行和预警闭环。迁移期 Java 自动调度默认关闭，FastAPI 仍是权威运行时。

## 本地运行

```powershell
cd F:\A-wiki-project\backend-java
$env:WIKI_ADMIN_PASSWORD = '<与当前部署一致的管理员密码>'
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
- `POST /api/migration/agent/chat`、`POST /api/migration/agent/chat/stream`：执行 Java 影子 Agent，支持规则解释、受控 SQL 试运行、诊断、上传汇总分析和全面实施验收。
- `/api/agent/capabilities`、`/api/agent/chat`、`/api/agent/chat/stream`：上述影子接口的前端兼容别名，供 Vue 整体切到 `8766` 时使用。
- `POST /api/sql-runs/{run_id}/details`：基于已成功试运行生成或复用数量一致的短期明细快照。
- `GET /api/sql-runs/{run_id}/details/{denominator|numerator|unmatched}`：分页返回脱敏明细。
- `POST /api/sql-runs/{run_id}/exports`：在有导出权限且显式确认后生成三工作表 `.xlsx`。
- `POST /api/sql-runs/{run_id}/upload-comparison-exports`：把同指标上传明细与系统快照按稳定业务键逐条比较，生成四工作表差异 `.xlsx`。
- `GET /api/indicator-exports/{export_id}/download`：医院隔离、有效期与 SHA-256 校验后的授权下载。
- `GET /api/agent/runs/{trace_id}`：按当前登录医院读取 Java/Python 共用 Trace 表中的安全节点、Evidence 来源和耗时汇总。
- `GET /api/agent/runs`：按时间、状态、模型、工具和 FailureClass 查询当前医院安全运行摘要。
- `GET /api/agent/runs/metrics`：聚合当前医院成功率、p50/p95/p99、工具/模型性能、复合任务和稳定性指标。
- `GET /api/metadata/overview`：按当前登录医院读取最近一次元数据快照、结构变化和受影响指标。
- `POST /api/metadata/sync`：只经 DBHub 读取已配置 SQL Server 的表目录与指标映射依赖字段，保存本院快照并生成 Trace。
- `GET /api/terminology/concepts`：检索已生效标准概念、同义词和指标关联。
- `GET /api/terminology/concepts/{concept_code}`：按登录医院读取概念、同义词、本院映射与指标引用详情。
- `POST /api/terminology/test`：执行不调用 LLM 的确定性术语识别，返回歧义和 SQL 可用性。
- `POST /api/admin/login`、`POST /api/admin/logout`：独立轻量管理员会话；密码只从 `WIKI_ADMIN_PASSWORD` 注入，默认占位值不可登录。
- `POST /api/terminology/aliases` 及其审批接口：创建、校验并审批公司或本院候选词。
- `POST /api/terminology/hospital-mappings` 及其审批接口：仅在管理员会话和当前医院会话同时通过时维护本院编码和值。
- `GET /api/terminology/releases`、`POST /api/terminology/releases/publish`、`POST /api/terminology/releases/{release_id}/restore`：读取、发布和恢复不可变术语版本。
- `GET|POST|PUT /api/monitoring/plans` 及启停接口：在管理员和当前医院双重认证下维护监控计划。
- `POST /api/monitoring/plans/{plan_id}/run`：按显式周期或最近完整日/月手工执行规则读取、受控 SQL 和 DBHub 只读试运行。
- `GET /api/monitoring/results`、`GET /api/monitoring/results/{result_id}`：按当前医院审阅历史聚合结果。
- `GET /api/monitoring/alerts` 及确认/关闭/重新诊断接口：按当前医院处置指标预警并记录操作者。
- `GET /api/monitoring/scheduler/status`、`POST /api/monitoring/scheduler/scan`：查看 Java 调度状态并由管理员扫描到期计划；关闭状态下扫描不会执行计划。

配置在 `src/main/resources/application.yml`。运行库凭据通过 `WIKI_RUNTIME_DB_URL`、`WIKI_RUNTIME_DB_USER` 和 `WIKI_RUNTIME_DB_PASSWORD` 提供，真实密码和令牌不得写入本目录；Java 服务不直连医院 SQL Server。

已登录情况下可以运行 Python/Java 双跑：

```powershell
$env:MIGRATION_HOSPITAL_TOKEN = '<当前登录令牌>'
$env:MIGRATION_HOSPITAL_ID = 'hospital_001'
python ..\scripts\compare_java_python_read_api.py
```

脚本只输出安全字段的差异，不打印令牌。跨语言密码算法测试使用 `contracts/migration/v1/auth-crypto-vector.json` 中的非生产测试向量。

Java 已实现版本化 `RequestPlan` / `CompiledPlanIR`、能力注册表、PlanValidator、确定性中文时间解析、StateController、DeterministicDispatch、类型化策略和 ToolGateway。SQL Server 数据始终通过现有 DBHub sidecar 只读访问。上传明细与系统明细按患者/业务标识和关键事件时间执行多重集合比较，重复行不会被静默去重；模型只看到双方都有、仅系统有、仅上传有、字段差异和达标判定差异等安全汇总。患者级明细不会进入 LLM、Evidence、Trace 或会话；页面只取得脱敏分页，原始值只存在于 24 小时短期快照和经确认生成的导出文件。

用户明确要求“全面实施验收、上线验收、迁移核对或全链路验收”时，服务端把模型计划规范化为 `implementation_validation`，再由一个顶层受控工具固定执行 L1 字段映射与来源、L4 生效规则、L5 SQL 安全校验与 DBHub 只读试运行，以及存在上传文件时的 L6 报表数据核对。任何阶段未通过都会进入同一份结构化报告，而不是触发模型自由重规划；最终报告由 Java 模板生成，不再次调用 LLM。Trace 独立记录 `implementation_validation_l1/l4/l5/l6`，Evidence 只保存报告引用和允许列表字段。

复合请求由 `CompoundRequestSplitter` 在服务端识别明确并列指标或跨轮“这两个/它们/分别”等复数指代，再由 `CompoundAgentRuntime` fan-out 到相互隔离的单指标 Runner。每个子任务拥有独立会话、请求 ID、RunState、Evidence namespace 与 Trace 子标识，子任务执行期间不修改父状态。OpenAI 兼容 API 最大并发 2，Ollama 最大并发 1，DBHub 只读最大并发 2；上传、规则变更、发布和审批类任务保持串行。整体超时会取消未完成任务，已成功子任务仍按输入顺序返回；Vue 同一回答可展示多个明细或差异导出入口。

Java Trace 复用现有 `med_agent_trace` 和 `med_agent_trace_node`，不部署外部可观测服务。新运行记录 `memory_load`、`planner_llm`、`plan_compile`、`plan_validate`、`state_controller`、`deterministic_tool_dispatch`、`tool_result`、`plan_verify`、`final_answer_llm`、`response_guard`、`memory_save`，复合请求另含拆分、子任务和合并节点。节点包含真实开始偏移、耗时、父子关系、`subtask_id`、工具、模型、能力、FailureClass、缓存及安全输入输出；密码、认证令牌、SQL 正文与患者原始行不会落入 Trace。Vue 链路抽屉以中英文节点名、类型颜色、瀑布条、泳道、筛选和 Evidence 来源显示这些数据，历史 Python Trace 缺少新字段时仍按顺序降级展示。

Vue `/runs` 页面直接使用 Trace 汇总接口展示请求量、成功/未完成率、平均与 p50/p95/p99、按日趋势、工具/模型耗时、超时、复合请求、重复调用停止率和 Replan 率。阈值来自 `wiki.agent.trace-*` 配置；页面提示不发送外部通知。Java 每新增 100 次运行最多清理 1000 条超过保留期的 Trace，不增加定时任务或调度中间件。

Vue `/metadata` 页面展示当前医院的快照批次、表数、映射字段数、结构变化和受影响规则。Java 固定查询 `INFORMATION_SCHEMA`，不接受客户端 SQL，也不允许客户端切换到未配置数据库；全库只采集表目录，列信息仅采集 `med_field_mapping` 已确认映射实际依赖的表，避免把无关的大量字段写入运行库。

Vue `/terminology` 页面展示标准概念、已审核同义词、当前医院编码映射、关联指标和发布版本，并提供确定性识别测试。Java 识别链不调用 Spring AI：先做最长词匹配，同跨度多概念直接返回歧义，同长度时优先本院映射；`related`、`forbidden` 或未标记 SQL 安全的临床值会阻止结果直接进入 SQL 条件。独立管理员维护模式支持候选词、本院映射、审批、发布和历史回退；所有医院级写入同时校验医院人员 token 和管理员 token，并记录版本及审计日志。

Vue `/monitoring` 页面复用现有 `med_indicator_run_plan`、`med_index_run_result` 和 `med_indicator_alert` 表，提供计划新增/启停、可选统计周期手工运行、历史结果审阅以及预警确认/关闭/重新诊断。所有医院数据接口同时校验管理员 token 与当前医院 token，查询条件由服务端固定绑定医院。Java 调度使用数据库租约和稳定运行键防止重复执行，并按计划顺序隔离局部失败。

Java 自动扫描默认关闭，避免影子期与 Python APScheduler 同时执行。正式切换监控权威运行时后配置：

```powershell
$env:MONITORING_SCHEDULER_ENABLED = 'true'
$env:MONITORING_SCAN_DELAY_MS = '60000'
$env:MONITORING_LEASE_SECONDS = '600'
```

关闭时仍可从 Vue 或 `POST /api/monitoring/plans/{plan_id}/run` 手工验证单个计划；只有自动到期扫描受开关约束。

Vue 默认代理当前权威 FastAPI。需要完整验证 Java 影子链时，在启动 Vite 前设置 `$env:VITE_API_TARGET='http://127.0.0.1:8766'`；Java 同时提供正式前端路径的兼容别名，因此模型选择、SSE 对话、上传、Trace、明细和元数据页面不会混用两个后端。

后续批次会按照 `docs/migration/java-vue-migration.md` 继续迁移剩余业务工作台与正式切流。只有同一接口通过契约对比后，才允许在入口层切流。

## 单 JAR 构建

在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-java-vue.ps1
```

脚本只在构建机使用现有 Node.js/npm：如果 `frontend-vue/node_modules` 不存在则执行 `npm ci`，随后运行 Vue 生产构建和 `mvn -Pbundle-vue clean package`。输出的 `backend-java/target/wiki-agent-java-*.jar` 已包含 Vue 的 `index.html`、JS 和 CSS，部署机只需 Java 17：

```powershell
$env:WIKI_RUNTIME_DB_PASSWORD = '<MySQL 密码>'
$env:WIKI_ADMIN_PASSWORD = '<管理员密码>'
java -jar .\wiki-agent-java-0.1.0-SNAPSHOT.jar
```

管理员密码没有仓库默认值；未设置时 Java 会拒绝管理员登录，但医院人员只读功能仍可运行。
