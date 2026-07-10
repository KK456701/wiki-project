# Dual-Caliber Diagnosis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在诊断第二层使用相同医院、字段映射和统计周期分别执行纯国标口径与本院生效口径，并根据执行状态和结果差异形成可审计的口径归因结论。

**Architecture:** `MySQLRuleRepository` 只组装国标/本院双口径上下文，`CaliberAdaptationAgent` 通过类型化契约交给统一编排器；新增 `app/diagnose/caliber_compare.py` 负责周期解析、模板渲染、安全校验、参数绑定和 DBHub 双执行。`DiagnoseAgent` 将比较结果交给 `rule_check` 分类并写入现有诊断报告，API 与 Trace 只负责展示，不直接访问规则仓库或业务库。

**Tech Stack:** Python 3.12、Pydantic、SQLAlchemy、Jinja2、FastAPI、DBHub MCP、unittest

## Global Constraints

- 只覆盖 MySQL 中已迁移且具备 SQL、字段映射的指标，首批验收为 `MQSI2025_001/005/014/035`。
- 业务库访问必须经过 `BusinessDBClient`/DBHub，只允许单条只读 `SELECT`。
- 国标和本院执行必须使用相同 `hospital_id`、`start_time`、`end_time` 和字段映射。
- 不保存患者明细，不向 LLM 发送执行结果或业务数据。
- 结果不同只产生警告并继续第三层；单侧或双侧执行失败才阻断第二层。
- 本院新增指标、无有效本院定制、MySQL 不可用时不得伪造双口径比较。
- 不实现第六批完整工作台中的结构化编辑、模板同屏对照、前端版本恢复、文档导出和专用 Trace 页面。
- 每个任务完成后运行测试、只暂存相关文件、使用中文 Conventional Commit 并推送 `main`。

---

### Task 1: 类型化双口径上下文与规则仓库

**Files:**
- Modify: `app/agents/contracts.py`
- Modify: `app/rules/repository.py`
- Modify: `app/agents/caliber_adaptation.py`
- Test: `tests/test_rule_repository.py`
- Test: `tests/test_specialized_agents.py`

**Interfaces:**
- Produces: `CaliberComparisonContext` Pydantic 契约。
- Produces: `RuleRepository.get_caliber_comparison(rule_id, hospital_id) -> dict[str, Any]`。
- Produces: `CaliberAdaptationAgent.comparison_context_contract(rule_id, hospital_id) -> CaliberComparisonContext`。
- Consumes: 当前有效的 `med_index_standard`、`med_index_hospital_custom` 与 `FallbackRuleRepository`。

- [ ] **Step 1: 为仓库上下文写失败测试**

在 `tests/test_rule_repository.py` 增加：

```python
def test_caliber_comparison_keeps_national_and_hospital_sql_separate(self):
    engine = _rule_engine()
    _seed_standard(engine)
    _seed_custom(engine)
    result = MySQLRuleRepository(engine).get_caliber_comparison(
        "MQSI2025_005", "hospital_001"
    )
    self.assertTrue(result["applicable"])
    self.assertEqual(result["national_params"]["arrive_minutes_threshold"], 10)
    self.assertEqual(result["effective_params"]["arrive_minutes_threshold"], 20)
    self.assertEqual(result["national_version"], "2025")
    self.assertEqual(result["hospital_version"], 1)
```

再覆盖无医院定制、本院新增指标和主库异常：分别断言 `reason` 为 `no_hospital_customization`、`hospital_defined_has_no_national_baseline`、`rule_store_unavailable`。

- [ ] **Step 2: 运行失败测试**

Run:

```powershell
python -B -m unittest tests.test_rule_repository -v
```

Expected: FAIL，提示 `get_caliber_comparison` 或 `CaliberComparisonContext` 不存在。

- [ ] **Step 3: 实现契约与仓库方法**

在 `app/agents/contracts.py` 增加：

```python
class CaliberComparisonContext(AgentContract):
    rule_id: str
    hospital_id: str
    applicable: bool = False
    reason: str = ""
    national_sql_template: str = ""
    national_params: dict[str, Any] = Field(default_factory=dict)
    national_version: str | None = None
    effective_sql_template: str = ""
    effective_params: dict[str, Any] = Field(default_factory=dict)
    hospital_version: int | None = None
    overridden_fields: list[str] = Field(default_factory=list)
```

`MySQLRuleRepository.get_caliber_comparison()` 必须直接读取标准记录和当前有效医院记录，保持国标参数不被医院参数修改；本院 `custom_sql` 为空时复用国标模板。`FallbackRuleRepository` 捕获主库异常并返回不可比较原因，不调用 Wiki 组装医院口径。

- [ ] **Step 4: 通过口径适配 Agent 暴露契约**

```python
def comparison_context_contract(
    self, rule_id: str, hospital_id: str
) -> CaliberComparisonContext:
    return CaliberComparisonContext.model_validate(
        self.rule_repository.get_caliber_comparison(rule_id, hospital_id)
    )
```

- [ ] **Step 5: 运行相关测试并提交**

Run:

```powershell
python -B -m unittest tests.test_rule_repository tests.test_specialized_agents -v
git diff --check
```

Expected: 全部通过。

Commit:

```powershell
git add app/agents/contracts.py app/rules/repository.py app/agents/caliber_adaptation.py tests/test_rule_repository.py tests/test_specialized_agents.py
git commit -m "feat: 增加双口径诊断上下文"
git push origin main
```

---

### Task 2: 统计周期与只读双执行器

**Files:**
- Create: `app/diagnose/caliber_compare.py`
- Test: `tests/test_caliber_compare.py`

**Interfaces:**
- Consumes: `CaliberComparisonContext`、`FieldMapping`、`BusinessDBClient`、运行数据库 Engine。
- Produces: `parse_diagnose_period(stat_period, now=None) -> tuple[str, str, str]`。
- Produces: `execute_caliber_comparison(...) -> dict[str, Any]`，包含两侧结果、差值、结论代码和真实耗时。

- [ ] **Step 1: 写周期解析失败测试**

```python
def test_period_defaults_to_current_month_and_accepts_date_range():
    now = datetime(2026, 7, 10, 12, 0, 0)
    self.assertEqual(
        parse_diagnose_period(None, now),
        ("2026-07-01 00:00:00", "2026-08-01 00:00:00", "2026-07-01 00:00:00~2026-08-01 00:00:00"),
    )
    self.assertEqual(
        parse_diagnose_period("2026-07-01~2026-07-31", now)[:2],
        ("2026-07-01 00:00:00", "2026-08-01 00:00:00"),
    )
```

无效格式和结束时间不晚于开始时间必须在业务库调用前抛出 `CaliberCompareError`。

- [ ] **Step 2: 写双执行失败测试**

使用 SQLite `BusinessDBClient` 和指标五模板构造 3 条急会诊记录，国标阈值 10 分钟得到 `33.33`，本院阈值 20 分钟得到 `66.67`：

```python
result = execute_caliber_comparison(
    runtime_engine=runtime_engine,
    business_db=business_db,
    context=context,
    field_mapping=mapping,
    stat_period="2026-07-01~2026-07-31",
)
self.assertEqual(result["conclusion_code"], "caliber_result_diff")
self.assertEqual(result["national"]["result_value"], 33.33)
self.assertEqual(result["hospital"]["result_value"], 66.67)
self.assertEqual(result["absolute_delta"], 33.34)
self.assertTrue(all(sql.lower().startswith("select") for sql in business_db.sql))
```

同时测试相同结果、无样本、国标成功本院失败、两侧失败、参数缺失和不适用上下文。

- [ ] **Step 3: 运行失败测试**

Run:

```powershell
python -B -m unittest tests.test_caliber_compare -v
```

Expected: FAIL，提示模块或函数不存在。

- [ ] **Step 4: 实现周期解析和单侧执行**

`_execute_side()` 必须依次：

1. 使用 `render_sql(template, fields, main_table, custom_rules)`；
2. 调用 `validate_select_sql()`；
3. 使用 `_bind_sql_params()` 绑定医院、周期和该侧口径参数；
4. 调用 `business_db.execute_select()`；
5. 读取 `index_value`、`sample_count`；
6. 使用 `insert_sql_run_log()` 写入诊断执行日志；
7. 返回 `success/empty/failed` 和稳定错误码，不返回展开后的 SQL。

- [ ] **Step 5: 实现结论分类**

```python
if national_success and hospital_failed:
    code, blocking = "hospital_caliber_execution_failed", True
elif national_failed and hospital_success:
    code, blocking = "national_caliber_execution_failed", True
elif national_failed and hospital_failed:
    code, blocking = "shared_caliber_execution_failed", True
elif both_no_sample:
    code, blocking = "caliber_no_sample", False
elif abs(national_value - hospital_value) > tolerance:
    code, blocking = "caliber_result_diff", False
else:
    code, blocking = "caliber_result_same", False
```

国标为零时 `relative_delta_percent=None`，绝对差值保留两位小数。

- [ ] **Step 6: 运行测试并提交**

Run:

```powershell
python -B -m unittest tests.test_caliber_compare tests.test_sqlgen tests.test_business_db_mcp -v
git diff --check
```

Expected: 全部通过。

Commit:

```powershell
git add app/diagnose/caliber_compare.py tests/test_caliber_compare.py
git commit -m "feat: 实现国标与本院口径双执行"
git push origin main
```

---

### Task 3: 接入三层诊断与统一编排器

**Files:**
- Modify: `app/diagnose/agent.py`
- Modify: `app/diagnose/rule_check.py`
- Modify: `app/agents/orchestrator.py`
- Modify: `app/agents/root_cause_diagnosis.py`
- Test: `tests/test_diagnose_agent.py`
- Test: `tests/test_agent_orchestrator.py`
- Test: `tests/test_specialized_agents.py`

**Interfaces:**
- Consumes: Task 1 的 `comparison_context_contract()` 和 Task 2 的 `execute_caliber_comparison()`。
- Produces: `DiagnoseAgent.run(..., caliber_context=None, field_mapping=None)`。
- Produces: 第二层字段 `caliber_comparison`、`conclusion_code`、`problem_detail`、`repair_suggest`。

- [ ] **Step 1: 将旧的“无双口径比较”测试改成失败测试**

把 `test_rule_check_has_no_three_caliber_compare` 替换为：

```python
def test_layer2_compares_national_and_hospital_results(self):
    report = agent.run(
        "hospital_001",
        "MQSI2025_005",
        _effective_rule(),
        caliber_context=_comparison_context(),
        field_mapping=_comparison_mapping(),
        stat_period="2026-07-01~2026-07-31",
    )
    layer2 = report["layers"][1]
    self.assertEqual(layer2["caliber_comparison"]["conclusion_code"], "caliber_result_diff")
    self.assertTrue(layer2["ok"])
    self.assertTrue(any(check["status"] == "warn" for check in layer2["checks"]))
    self.assertEqual(len(report["layers"]), 3)
```

另加本院执行失败测试，断言 `stopped_at_layer == 2` 且不运行第三层。

- [ ] **Step 2: 运行失败测试**

Run:

```powershell
python -B -m unittest tests.test_diagnose_agent tests.test_agent_orchestrator -v
```

Expected: FAIL，提示新参数或 `caliber_comparison` 不存在。

- [ ] **Step 3: 扩展 `rule_check` 分类**

`rule_check(effective_rule, caliber_comparison=None)` 保留定义、公式、除零和百分比静态检查，并增加一条中文比较检查：

- `caliber_result_diff` -> `warn`，说明结果差异及版本；
- `caliber_result_same/no_sample/not_applicable` -> `pass`；
- 三类执行失败 -> `fail`，生成对应修复建议。

返回值顶层保存完整 `caliber_comparison` 和 `conclusion_code`，`repair_sql` 只允许使用已经安全渲染的模板引用说明，不回显绑定后的 SQL。

- [ ] **Step 4: 调整诊断顺序**

`DiagnoseAgent.run()` 在第一层成功后执行比较，再调用 `rule_check`。比较阻断时第二层 `ok=false` 并停止；差异警告时继续第三层。

当调用方没有提供比较上下文时，生成 `caliber_compare_not_applicable`，保持旧测试和 Wiki 兜底兼容。

- [ ] **Step 5: 由统一编排器注入上下文**

`CoreIndicatorOrchestrator.diagnose()` 调用：

```python
caliber_context = self.caliber.comparison_context_contract(
    prepared.rule_id, prepared.hospital_id or ""
)
result = diagnose(
    ...,
    caliber_context=caliber_context.model_dump(),
    field_mapping=prepared.field_mapping.model_dump(),
)
```

如果 fake/旧适配器没有该方法，传入 `applicable=false` 的兼容上下文，不允许绕过 orchestrator 在 API 中直接读取仓库。

- [ ] **Step 6: 运行测试并提交**

Run:

```powershell
python -B -m unittest tests.test_diagnose_agent tests.test_agent_orchestrator tests.test_specialized_agents tests.test_agent_workflow -v
git diff --check
```

Expected: 全部通过。

Commit:

```powershell
git add app/diagnose/agent.py app/diagnose/rule_check.py app/agents/orchestrator.py app/agents/root_cause_diagnosis.py tests/test_diagnose_agent.py tests/test_agent_orchestrator.py tests/test_specialized_agents.py
git commit -m "feat: 接入双口径根因诊断"
git push origin main
```

---

### Task 4: Trace、README 与真实 MySQL 验收

**Files:**
- Modify: `app/observability/workflow_nodes.py`
- Modify: `app/workflows/core_indicator_chat.yaml`
- Modify: `tests/test_api.py`
- Modify: `tests/test_workflow_manifest.py`
- Modify: `README.md`

**Interfaces:**
- Consumes: 第二层 `caliber_comparison`。
- Produces: `diagnose_rule_check` Trace 节点中的两侧状态、结果、差值、版本和结论。
- Produces: 前端现有执行链路可展开查看的结构化详情。

- [ ] **Step 1: 写 Trace 失败测试**

在 `tests/test_api.py` 构造包含比较结果的诊断响应，断言：

```python
node = next(item for item in trace["nodes"] if item["node_name"] == "diagnose_rule_check")
self.assertEqual(node["output_data"]["caliber_comparison"]["conclusion_code"], "caliber_result_diff")
self.assertEqual(node["output_data"]["caliber_comparison"]["national"]["result_value"], 33.33)
self.assertEqual(node["output_data"]["caliber_comparison"]["hospital"]["result_value"], 66.67)
self.assertEqual(node["status"], "warning")
```

- [ ] **Step 2: 更新 manifest 与 Trace 配置**

`diagnose_rule_check` 的描述改为静态规则检查加双口径 DBHub 执行，输入增加 `caliber_context/field_mapping/stat_period`，输出增加 `caliber_comparison/conclusion_code`，工具标记为 `execute_sql_hospital_demo_data`。

`record_diagnose_trace_nodes()` 对第二层设置 DBHub 工具与数据源，并用比较结论作为 `output_summary`；节点详情保存结构化结果，不保存展开后的 SQL。

- [ ] **Step 3: 更新 README 验证说明**

说明诊断第二层会执行纯国标和本院生效口径；提供指标五 `2026-07-01~2026-07-31` 验证步骤，解释 `33.33/66.67` 只是口径差异风险，不自动判错。

- [ ] **Step 4: 运行自动化与真实环境验收**

Run:

```powershell
python -B -m unittest discover -s tests
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8765/api/diagnose/run `
  -ContentType "application/json" `
  -Body '{"hospital_id":"hospital_001","rule_id":"MQSI2025_005","trigger":"manual","stat_period":"2026-07-01~2026-07-31"}'
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/workflows/core_indicator_chat/validate
git diff --check
```

Expected:

- 全量测试通过；
- 真实诊断第二层返回国标 `33.33`、本院 `66.67`、`caliber_result_diff`；
- 诊断继续第三层；
- manifest 校验 `ok=true`。

- [ ] **Step 5: 提交并推送**

```powershell
git add app/observability/workflow_nodes.py app/workflows/core_indicator_chat.yaml tests/test_api.py tests/test_workflow_manifest.py README.md
git commit -m "feat: 完善双口径诊断可观测性"
git push origin main
```

---

## Final Verification

- [ ] `git status --short` 为空。
- [ ] `git log -4 --oneline` 包含四个第四批提交。
- [ ] `python -B -m unittest discover -s tests` 无失败。
- [ ] `/api/health/summary` 返回 `status=ok`。
- [ ] `/api/workflows/core_indicator_chat/validate` 返回 `ok=true`。
- [ ] 实际报告 `med_index_diagnose_report.layer_results` 包含两侧版本、结果、差值和结论，不包含患者明细。
