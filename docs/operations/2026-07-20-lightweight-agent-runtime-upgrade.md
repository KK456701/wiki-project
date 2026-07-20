# 2026-07-20 轻量化 Agent Runtime 升级

本次升级保持原有单体部署：FastAPI、现有 MySQL、SQLite/JSONL、DBHub、Ollama 或 OpenAI 兼容 API，以及原生 HTML/CSS/JavaScript。没有增加 Docker、PostgreSQL、消息队列、向量数据库、独立可观测服务、前端 CDN 或新的生产依赖。

## 已实施批次

### 1. 轻量 Eval、命名与失败分类

- 新增 `evals/cases.yaml`、结构测试和显式模型矩阵脚本。没有传 `--models` 时不会调用任何模型。
- 新 Trace 统一使用 `final_answer_llm`；`executor_llm` 只保留历史记录展示兼容。
- `FailureClass` 统一分类执行失败。只允许语义计划错误、任务类型错误、用户改变目标及存在合法替代方向触发一次 Replan；数据库、权限、缺时间、对象过期、Evidence 冲突和工具失败不触发 Replan。

### 2. 版本化 CompiledPlan IR

- `CapabilitySpecRegistry` 成为能力 ID、依赖事实、产出事实、工具、策略动作、参数编译器、Verifier、Retry 和 Answer Mode 的单一来源。
- Compiler 递归解析 Fact Producer 并生成拓扑有序 `CompiledPlanIR`；Controller 只查缺失事实；Dispatch 和 Verifier 只读取 CapabilitySpec。
- Agent 启动时拒绝能力环、重复 Fact Producer、未知工具和未知 Verifier。
- IR 同时记录 RequestPlan、Capability Registry、Prompt、Model Adapter 和 Verifier 版本。

### 3. 轻量 Evidence Ledger

- 复用现有 MySQL 表 `med_agent_evidence` 与 `med_agent_evidence_verification`；MySQL 不可用时自动写 `runtime/agent_evidence.jsonl`。
- ToolGateway 写未验证 Evidence；PlanVerifier 写独立验证记录；Final Answer 只能消费已验证 Evidence ID。
- Evidence 校验医院、子任务、过期时间、规则、统计周期和 SQL 对象链。
- SQL、运行结果、短期快照和患者级对象只保存安全引用；患者行和 SQL 原文不进入 Ledger。

### 4. 类型化依赖和轻量 PDP/PEP

- `ToolExecutionContext` 显式携带登录主体、Agent 上下文、`subtask_id`、RunState 与 PolicyDecision。
- `PolicyDecisionService` 只负责 allow/deny、原因码、展示说明和策略版本。
- ToolGateway 仍是 Policy Enforcement Point，并继续承担 Pydantic 参数校验、超时、重复调用控制和缓存。

### 5. 内置 Trace 可视化与运行观察

- Trace 节点增加父子关系、泳道、序号、真实开始偏移、独占耗时、能力、工具、模型、FailureClass、Token、缓存和重试字段。
- 单轮“查看链路”增加耗时分类、横向瀑布图、父子树、多指标泳道、筛选、最慢节点、版本和 Evidence 定位。
- 新增当前医院授权接口 `/api/agent/runs`、`/api/agent/runs/metrics` 和“Agent 运行观察”页面。
- Trace 仍写现有 MySQL 和 JSONL；数据库记录按 `agent_trace_retention_days` 进行小时级机会式清理。

### 6. 复合任务自适应并行

- 各指标使用独立 child state、Evidence namespace、Trace 泳道和 `subtask_id`，子任务结束前不修改父状态。
- API 模型默认最大并发 2，本地 Ollama 默认串行，DBHub 只读工具默认最大并发 2。
- 上传对比、规则变更、发布和审批保持串行。
- 使用 `asyncio.Semaphore` 和 `asyncio.gather`；结果按用户输入顺序合并，支持局部失败和取消传播。

## 配置

```yaml
agent_trace_retention_days: 30
agent_trace_slow_request_ms: 120000
agent_trace_slow_llm_ms: 60000
agent_trace_tool_failure_warning_rate: 0.05
agent_trace_timeout_warning_rate: 0.05

compound_api_concurrency: 2
compound_ollama_concurrency: 1
compound_db_concurrency: 2
```

## 数据库升级

新建环境直接执行 `scripts/init_runtime_db.sql`。已有环境执行幂等迁移：

```powershell
python -B scripts\migrate_runtime_schema.py
```

应用启动也会尝试补齐 Evidence 表与 Trace 列；权限不足或 MySQL 暂时不可用时不会阻止 Agent 启动，Evidence 自动使用 JSONL 兜底。生产环境仍建议由数据库管理员提前执行迁移。

## 验收命令

本次交付只执行静态语法、YAML 与差异检查，不代替使用者运行完整测试。建议按以下顺序验证：

```powershell
pytest -q tests\test_agent_plan_compiler.py tests\test_agent_replan_policy.py
pytest -q tests\test_agent_evidence_ledger.py tests\test_agent_plan_verifier.py
pytest -q tests\test_agent_trace_manifest.py tests\test_agent_trace_bridge.py
pytest -q tests\test_agent_frontend_ui.py tests\test_agent_api.py
pytest -q evals\test_eval_dataset.py
```

需要比较模型语义表现时再显式运行：

```powershell
python evals\run_model_matrix.py --models ollama-qwen3 ollama-qwen3-8b-thinking deepseek-v4-flash
```

模型矩阵结果写入已忽略的 `evals/results/`，不会在后台自动调用模型。
