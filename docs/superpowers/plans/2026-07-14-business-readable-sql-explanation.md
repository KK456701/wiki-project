# Business-Readable SQL Explanation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make generated SQL and trial-run answers understandable to doctors and hospital implementation staff by showing the active hospital caliber, numerator, denominator, source fields, aggregate counts, and the exact percentage calculation.

**Architecture:** Extend every supported aggregate SQL to return a stable numerator/denominator contract, preserve those aggregates in the trial runner, and format them through a deterministic `app.sqlgen.explanation` module. Keep `graph.py` as orchestration only, and add a small HTML-escaping chat renderer that supports the limited Markdown constructs emitted by the formatter.

**Tech Stack:** Python 3.12, FastAPI, SQLAlchemy, Jinja2 SQL templates, unittest, vanilla JavaScript, MySQL, DBHub MCP.

## Global Constraints

- The explanation, generated SQL, and trial result must use the same effective hospital rule and parameters.
- One read-only aggregate query must return `index_value`, `numerator_count`, `denominator_count`, and compatibility field `sample_count`.
- No patient names, medical record numbers, row-level records, or bound SQL may appear in the answer or trace.
- A zero denominator means “no eligible data”, not a business result of zero percent.
- Existing SQL without numerator/denominator aliases remains readable but must be labeled as a legacy result that cannot explain the calculation fully.
- Raw HTML must be escaped before rendering Markdown tables or code blocks.
- Each task follows red-green-refactor and ends with a Chinese Conventional Commit message.
- After each independently verified task, push the current `main` branch according to `agent.md`.

---

## File Structure

- Modify `app/indicators/sql_plan.py`: emit the aggregate output contract for indicator drafts.
- Modify `app/rules/importer.py`: keep MySQL standard-rule SQL stored in the runtime database aligned with template SQL.
- Modify the four `core-rules-wiki/sql-specs/*/templates/mysql.sql.j2` files: expose numerator and denominator counts.
- Modify `app/sqlgen/runner.py`: return aggregate counts, source, and statistical period.
- Create `app/sqlgen/explanation.py`: deterministic business-readable Markdown formatter.
- Modify `app/agent/graph.py`: load explanation context, delegate formatting, and record aggregate-only trace output.
- Create `web/chat-markdown.js`: safe limited Markdown renderer for tables and SQL blocks.
- Modify `web/index.html`: load the renderer and add table/code presentation styles.
- Modify focused tests under `tests/`: lock SQL aliases, runner contract, answer copy, trace privacy, and browser rendering.
- Modify `README.md`: document the new SQL and trial-run output.

---

### Task 1: Add numerator and denominator to the SQL output contract

**Files:**
- Modify: `tests/test_four_indicator_sql.py`
- Modify: `tests/test_indicator_sql_plan.py`
- Modify: `app/indicators/sql_plan.py`
- Modify: `app/rules/importer.py`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/templates/mysql.sql.j2`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_005_急会诊及时到位率/templates/mysql.sql.j2`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_014_急危重症患者抢救成功率/templates/mysql.sql.j2`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_035_术中自体血回输率/templates/mysql.sql.j2`

**Interfaces:**
- Consumes: existing SQL templates, `render_indicator_sql(plan, mappings)`.
- Produces: result rows with `index_value`, `numerator_count`, `denominator_count`, and `sample_count`.

- [ ] **Step 1: Write failing assertions for the four supported SQL templates**

Add to `test_all_four_specs_render_and_pass_safety_validation`:

```python
for alias in (
    "numerator_count",
    "denominator_count",
    "sample_count",
):
    self.assertIn(alias, sql)
```

Extend `test_demo_rows_produce_expected_hospital_results` with exact aggregate expectations:

```python
expected_counts = {
    "MQSI2025_001": (1, 4),
    "MQSI2025_005": (2, 3),
    "MQSI2025_014": (3, 4),
    "MQSI2025_035": (2, 4),
}
self.assertEqual(
    (int(row["numerator_count"]), int(row["denominator_count"])),
    expected_counts[code],
)
self.assertEqual(int(row["sample_count"]), int(row["denominator_count"]))
```

Add to `IndicatorSQLPlanRendererTest`:

```python
def test_ratio_sql_returns_explainable_aggregate_columns(self) -> None:
    rendered = render_indicator_sql(_plan(), _confirmed_mappings())
    self.assertIn("AS numerator_count", rendered["sql_text"])
    self.assertIn("AS denominator_count", rendered["sql_text"])
    self.assertIn("AS sample_count", rendered["sql_text"])
```

- [ ] **Step 2: Run tests and verify the missing-column failure**

Run:

```powershell
python -m unittest tests.test_four_indicator_sql tests.test_indicator_sql_plan -v
```

Expected: FAIL because the generated SQL does not contain `numerator_count` or `denominator_count`.

- [ ] **Step 3: Add aggregate aliases to all four MySQL templates and importer seeds**

For `MQSI2025_005`, use the same expressions already used by the percentage:

```sql
  SUM(CASE
    WHEN TIMESTAMPDIFF(MINUTE, {{ fields.request_time }}, {{ fields.arrive_time }}) BETWEEN 0 AND :arrive_minutes_threshold
    THEN 1 ELSE 0
  END) AS numerator_count,
  COUNT(*) AS denominator_count,
  COUNT(*) AS sample_count
```

For `MQSI2025_001` and `MQSI2025_035`, reuse their existing conditional distinct-count expression as `numerator_count` and the existing distinct denominator as both `denominator_count` and `sample_count`. For `MQSI2025_014`, reuse its existing conditional success sum as `numerator_count` and `COUNT(*)` as both denominator fields. Apply the same complete expressions in `_SEEDS[*]["standard_sql"]` so the MySQL projection and Wiki templates cannot diverge.

- [ ] **Step 4: Extend indicator-draft SQL rendering**

Change the ratio branch in `render_indicator_sql` to emit:

```python
sql_text = (
    "SELECT\n"
    f"  CASE WHEN {denominator_count} = 0 THEN 0\n"
    f"       ELSE ROUND({numerator_count} / {denominator_count} * 100, 2)\n"
    "  END AS index_value,\n"
    f"  {numerator_count} AS numerator_count,\n"
    f"  {denominator_count} AS denominator_count,\n"
    f"  {denominator_count} AS sample_count\n"
    f"FROM `{table}`\n"
    f"WHERE {where}"
)
```

In the count branch, emit `count_expression` under all three count aliases so downstream code has a uniform contract.

- [ ] **Step 5: Run focused tests and commit**

Run:

```powershell
python -m unittest tests.test_four_indicator_sql tests.test_indicator_sql_plan -v
git diff --check
```

Expected: all tests PASS and no whitespace errors.

Commit and push:

```powershell
git add app/indicators/sql_plan.py app/rules/importer.py core-rules-wiki/sql-specs tests/test_four_indicator_sql.py tests/test_indicator_sql_plan.py
git commit -m "feat: 扩展指标SQL分子分母输出"
git push
```

---

### Task 2: Preserve explainable aggregates in trial-run results

**Files:**
- Modify: `tests/test_sqlgen.py`
- Modify: `app/sqlgen/runner.py`

**Interfaces:**
- Consumes: query result row aliases from Task 1 and `QueryResult.source`.
- Produces: `run_sql_trial -> dict[str, Any]` with `numerator_count`, `denominator_count`, `source`, `stat_start`, and `stat_end`.

- [ ] **Step 1: Write the failing trial-result contract test**

Add a fake business query returning an 80% result:

```python
def test_trial_run_returns_counts_source_and_period(self) -> None:
    class FakeBusinessDB:
        def execute_select(self, sql):
            return QueryResult(
                rows=[{
                    "index_value": "80.00",
                    "numerator_count": 8,
                    "denominator_count": 10,
                    "sample_count": 10,
                }],
                row_count=1,
                source="hospital_demo_data",
                tool_name="execute_sql_hospital_demo_data",
                duration_ms=2,
            )

    with patch("app.sqlgen.runner.insert_sql_run_log"):
        result = run_sql_trial(
            object(), FakeBusinessDB(), "SQL_80", "SELECT 80 AS index_value",
            "hospital_001", "MQSI2025_005",
            "2026-07-01 00:00:00", "2026-08-01 00:00:00", {}, "tester",
        )

    self.assertEqual(result["numerator_count"], 8)
    self.assertEqual(result["denominator_count"], 10)
    self.assertEqual(result["source"], "hospital_demo_data")
    self.assertEqual(result["stat_start"], "2026-07-01 00:00:00")
    self.assertEqual(result["stat_end"], "2026-08-01 00:00:00")
```

- [ ] **Step 2: Run the new test and verify it fails with missing keys**

Run:

```powershell
python -m unittest tests.test_sqlgen.SqlGenerationSafetyTest.test_trial_run_returns_counts_source_and_period -v
```

Expected: FAIL with `KeyError: 'numerator_count'`.

- [ ] **Step 3: Read aggregates with legacy fallback**

Initialize the new fields before the query and populate them after execution:

```python
numerator_count: int | None = None
denominator_count: int | None = None
source: str | None = None

source = query_result.source
if first_row.get("numerator_count") is not None:
    numerator_count = int(first_row["numerator_count"] or 0)
if first_row.get("denominator_count") is not None:
    denominator_count = int(first_row["denominator_count"] or 0)
elif first_row.get("sample_count") is not None:
    denominator_count = int(first_row["sample_count"] or 0)
no_sample = denominator_count == 0 if denominator_count is not None else False
```

Return the new fields without changing `insert_sql_run_log` or storing row-level data:

```python
"numerator_count": numerator_count,
"denominator_count": denominator_count,
"source": source,
"stat_start": stat_start,
"stat_end": stat_end,
```

- [ ] **Step 4: Verify success, zero denominator, legacy fallback, and failure paths**

Run:

```powershell
python -m unittest tests.test_sqlgen -v
git diff --check
```

Expected: all SQL generation and runner tests PASS.

Commit and push:

```powershell
git add app/sqlgen/runner.py tests/test_sqlgen.py
git commit -m "feat: 返回试运行分子分母聚合结果"
git push
```

---

### Task 3: Add a deterministic business explanation formatter

**Files:**
- Create: `app/sqlgen/explanation.py`
- Create: `tests/test_sql_explanation.py`

**Interfaces:**
- Consumes: generation result, effective rule, SQL spec, field contract, hospital mapping, hospital ID, start/end times.
- Produces: `format_generation_explanation -> str` and `format_trial_explanation -> str`.

- [ ] **Step 1: Write failing tests for hospital caliber, 80% explanation, and edge cases**

Create fixtures for acute consultation with standard threshold 10 and effective hospital threshold 20. Assert:

```python
answer = format_generation_explanation(
    result=generation_result,
    effective_rule=effective_hospital_rule,
    spec=rule_spec,
    field_contract=field_contract,
    mapping=hospital_mapping,
    hospital_id="hospital_001",
    stat_start="2026-07-01 00:00:00",
    stat_end="2026-08-01 00:00:00",
)
self.assertIn("本院生效口径", answer)
self.assertIn("20分钟", answer)
self.assertIn("标准值：10分钟", answer)
self.assertIn("hospital_demo_data", answer)
self.assertIn("consult_record", answer)
self.assertIn("申请时间", answer)
self.assertLess(answer.index("| 计算项 |"), answer.index("```sql"))
```

For trial output:

```python
self.assertIn("8 / 10 x 100% = 80%", answer)
self.assertIn("未进入分子", answer)
self.assertIn("2", answer)
```

Add separate tests that assert:

- denominator zero contains “本期没有符合分母条件的数据” and does not claim a calculated 0%;
- numerator 11 and denominator 10 contains “结果异常”;
- missing aggregate aliases contains “旧版 SQL 未返回分子分母”;
- standard-level effective rule does not contain “本院定制”.

- [ ] **Step 2: Run tests and verify the module-import failure**

Run:

```powershell
python -m unittest tests.test_sql_explanation -v
```

Expected: ERROR because `app.sqlgen.explanation` does not exist.

- [ ] **Step 3: Implement formatter interfaces and safe value formatting**

Create these public signatures:

```python
def format_generation_explanation(
    *, result: dict[str, Any], effective_rule: dict[str, Any],
    spec: dict[str, Any], field_contract: dict[str, Any],
    mapping: dict[str, Any], hospital_id: str,
    stat_start: str, stat_end: str,
) -> str:
    sections = [
        "## SQL 已生成",
        _caliber_lines(effective_rule, spec, result.get("params", {})),
        _definition_table(spec, result.get("params", {})),
        _field_table(spec, field_contract, mapping),
        _parameter_table(result.get("params", {}), hospital_id, stat_start, stat_end),
        f"```sql\n{result.get('sql_text', '')}\n```",
        "如需验证本期结果，请输入「试运行」。",
    ]
    return "\n\n".join(section for section in sections if section)

def format_trial_explanation(
    *, result: dict[str, Any], effective_rule: dict[str, Any],
    spec: dict[str, Any], field_contract: dict[str, Any],
    mapping: dict[str, Any], hospital_id: str,
    stat_start: str, stat_end: str,
) -> str:
    trial = result.get("trial_run", {})
    sections = [
        "## 试运行完成",
        _caliber_lines(effective_rule, spec, result.get("params", {})),
        _trial_conclusion(trial),
        _trial_table(trial),
        _field_table(spec, field_contract, mapping),
        _run_metadata_table(trial, hospital_id, stat_start, stat_end),
        f"```sql\n{result.get('sql_text', '')}\n```",
    ]
    return "\n\n".join(section for section in sections if section)
```

Private helpers must include `_markdown_table`, `_definition_table`, `_field_table`, `_parameter_table`, `_run_metadata_table`, `_trial_table`, `_trial_conclusion`, `_caliber_lines`, `_display_value`, and `_format_period`. Escape table cell pipe characters as `\|`; never interpolate patient rows because no row collection is accepted by either public interface.

Build trial arithmetic only from returned aggregates:

```python
if denominator == 0:
    conclusion = "本期没有符合分母条件的数据，指标暂不可计算。"
elif numerator is None or denominator is None:
    conclusion = "旧版 SQL 未返回分子分母，暂无法展开计算过程，请重新生成 SQL。"
elif numerator > denominator:
    conclusion = "分子大于分母，结果异常，请检查本院口径或 SQL。"
else:
    excluded = denominator - numerator
    conclusion = (
        f"本期共有{denominator}条记录进入分母，其中{numerator}条进入分子，"
        f"因此 {numerator} / {denominator} x 100% = {_display_value(result_value)}%。"
    )
```

The field table obtains Chinese descriptions from `field_contract["business_fields"][key]["desc"]`, and hospital columns from `mapping["fields"][key]`. The caliber section uses `effective_level`, `overridden_fields`, `effective_params`, and `spec["default_params"]`; it must not infer hospital customization from the threshold value alone.

- [ ] **Step 4: Run formatter tests and commit**

Run:

```powershell
python -m unittest tests.test_sql_explanation -v
git diff --check
```

Expected: all formatter tests PASS.

Commit and push:

```powershell
git add app/sqlgen/explanation.py tests/test_sql_explanation.py
git commit -m "feat: 增加指标业务解释器"
git push
```

---

### Task 4: Render explanation tables safely in the chat UI

**Files:**
- Create: `web/chat-markdown.js`
- Create: `tests/test_chat_markdown_ui.py`
- Modify: `web/index.html`

**Interfaces:**
- Consumes: Markdown emitted by Task 3.
- Produces: `window.renderAssistantMarkdown(text) -> safe HTML` and CommonJS export for Node-based tests.

- [ ] **Step 1: Write failing UI and renderer tests**

The static test must assert that `/static/chat-markdown.js` is loaded before the inline chat script and that `_renderAssHtml` delegates to `renderAssistantMarkdown`.

Execute the renderer in Node and assert:

```javascript
const {renderAssistantMarkdown} = require('./web/chat-markdown.js');
const html = renderAssistantMarkdown(
  '| 统计项 | 数量 |\n|---|---:|\n| 分子 | 8 |\n\n```sql\nSELECT 1\n```\n<script>alert(1)</script>'
);
if (!html.includes('<table class="message-table">')) process.exit(1);
if (!html.includes('<pre class="message-code"><code class="language-sql">')) process.exit(1);
if (html.includes('<script>')) process.exit(1);
```

- [ ] **Step 2: Run tests and verify the missing-script failure**

Run:

```powershell
python -m unittest tests.test_chat_markdown_ui -v
```

Expected: FAIL because `web/chat-markdown.js` and its script tag do not exist.

- [ ] **Step 3: Implement a limited, HTML-escaping Markdown renderer**

Implement these stages in order. `escapeHtml` must use the exact escaping map below; the other three functions receive only escaped text and must generate the documented table, heading, paragraph, and fenced-code elements:

```javascript
function escapeHtml(value) {
  return String(value == null ? "" : value).replace(/[&<>"']/g, function (char) {
    return {"&":"&amp;", "<":"&lt;", ">":"&gt;", "\"":"&quot;", "'":"&#39;"}[char];
  });
}

function renderInline(value) {
  return escapeHtml(value)
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
}
```

Export with:

```javascript
if (typeof module !== "undefined" && module.exports) {
  module.exports = {renderAssistantMarkdown: renderAssistantMarkdown};
}
root.renderAssistantMarkdown = renderAssistantMarkdown;
```

Do not support arbitrary HTML, links, images, or event attributes. Replace `_renderAssHtml` with a direct call to `renderAssistantMarkdown(text || "")`.

Add responsive styles:

```css
.message-table { width: 100%; border-collapse: collapse; margin: 12px 0; font-size: 14px; }
.message-table th, .message-table td { border: 1px solid var(--line); padding: 9px 10px; text-align: left; vertical-align: top; }
.message-table th { background: #edf4f2; }
.message-code { overflow-x: auto; padding: 14px; background: #17231f; color: #f5f7f6; }
```

- [ ] **Step 4: Run Node syntax, UI tests, and commit**

Run:

```powershell
node --check web/chat-markdown.js
python -m unittest tests.test_chat_markdown_ui tests.test_workbench_ui -v
git diff --check
```

Expected: all tests PASS and unsafe HTML remains escaped.

Commit and push:

```powershell
git add web/chat-markdown.js web/index.html tests/test_chat_markdown_ui.py
git commit -m "feat: 支持对话业务表格安全渲染"
git push
```

---

### Task 5: Integrate explanations into the chat workflow and trace

**Files:**
- Modify: `tests/test_agent_workflow.py`
- Modify: `app/agent/graph.py`

**Interfaces:**
- Consumes: formatter functions from Task 3, runner fields from Task 2.
- Produces: business-readable generation/trial answers and aggregate-only `sql_trial_mcp.output_data`.

- [ ] **Step 1: Extend fake SQL output and add failing chat assertions**

Update `FakeSQLGenerationAgent` trial result:

```python
"result_value": 80.0,
"numerator_count": 8,
"denominator_count": 10,
"source": "hospital_demo_data",
"stat_start": "2026-07-01 00:00:00",
"stat_end": "2026-08-01 00:00:00",
```

Add assertions for generated SQL and trial-run streams:

```python
self.assertIn("当前采用口径", answer)
self.assertIn("| 计算项 |", answer)
self.assertIn("| 业务字段 | 医院字段 |", answer)
self.assertLess(answer.index("| 计算项 |"), answer.index("```sql"))
self.assertIn("8 / 10 x 100% = 80%", trial_answer)
self.assertNotIn("patient_id", trial_answer)
```

Extend the trace assertion:

```python
self.assertEqual(by_name["sql_trial_mcp"]["output_data"]["numerator_count"], 8)
self.assertEqual(by_name["sql_trial_mcp"]["output_data"]["denominator_count"], 10)
self.assertNotIn("rows", by_name["sql_trial_mcp"]["output_data"])
self.assertNotIn("bound_sql", by_name["sql_trial_mcp"]["output_data"])
```

- [ ] **Step 2: Run workflow tests and verify old answer-format failures**

Run:

```powershell
python -m unittest tests.test_agent_workflow -v
```

Expected: FAIL because `graph.py` still builds the old field/parameter text and trace lacks aggregate counts.

- [ ] **Step 3: Load all explanation inputs once and delegate formatting**

Add a helper in `graph.py` that loads `rule_sql_spec.yaml`, `field_contract.yaml`, and the current hospital mapping. Reuse it for both `generate_sql` and `trial_run`; do not duplicate file-loading blocks.

Replace the old answer string construction with:

```python
answer = format_generation_explanation(
    result=result,
    effective_rule=effective,
    spec=spec,
    field_contract=field_contract,
    mapping=mapping,
    hospital_id=str(state.get("hospital_id") or ""),
    stat_start=start,
    stat_end=end,
)
```

Use `format_trial_explanation` in the trial branch. Preserve existing failed-precheck and exception responses.

- [ ] **Step 4: Record aggregate trace output without sensitive data**

Extend `sql_trial_mcp.output_data` with:

```python
"numerator_count": trial.get("numerator_count"),
"denominator_count": trial.get("denominator_count"),
"source": trial.get("source"),
"stat_start": trial.get("stat_start"),
"stat_end": trial.get("stat_end"),
```

Keep `sql_preview` behavior unchanged for generated, unbound SQL and never add query rows or executable bound SQL.

- [ ] **Step 5: Run workflow and formatter tests, then commit**

Run:

```powershell
python -m unittest tests.test_agent_workflow tests.test_sql_explanation -v
git diff --check
```

Expected: all tests PASS.

Commit and push:

```powershell
git add app/agent/graph.py tests/test_agent_workflow.py
git commit -m "feat: 输出业务可读SQL与试运行说明"
git push
```

---

### Task 6: Update runtime rule projection, documentation, and end-to-end verification

**Files:**
- Modify: `README.md`
- Verify: MySQL `wiki_agent_runtime.med_index_standard`
- Verify: running frontend at `http://127.0.0.1:8765`

**Interfaces:**
- Consumes: all previous tasks.
- Produces: updated local MySQL rule SQL and a user-verifiable frontend workflow.

- [ ] **Step 1: Document the new output and privacy boundary**

Add a README section explaining that generated SQL and trial results show:

```text
本院生效口径 -> 分子/分母业务定义 -> 数据库表和字段 -> 技术 SQL
试运行 -> 分子数/分母数 -> 算式 -> 指标结果
```

State that only aggregates are displayed and stored in the trace.

- [ ] **Step 2: Re-import the four standards into the runtime MySQL projection**

Run:

```powershell
python -B scripts/import_four_indicator_rules.py
```

Verify:

```sql
SELECT index_code,
       standard_sql LIKE '%numerator_count%' AS has_numerator,
       standard_sql LIKE '%denominator_count%' AS has_denominator
FROM med_index_standard
WHERE index_code IN ('MQSI2025_001','MQSI2025_005','MQSI2025_014','MQSI2025_035');
```

Expected: all four rows return `has_numerator=1` and `has_denominator=1`.

- [ ] **Step 3: Run focused and complete verification**

Run:

```powershell
node --check web/chat-markdown.js
python -B -m unittest tests.test_four_indicator_sql tests.test_indicator_sql_plan tests.test_sqlgen tests.test_sql_explanation tests.test_chat_markdown_ui tests.test_agent_workflow -v
python -B -m unittest discover -s tests -q
git diff --check
```

Expected: focused tests and the complete suite PASS.

- [ ] **Step 4: Restart services and verify the real conversation**

Generate SQL for “急会诊及时到位率”, then send “试运行”. Verify the browser shows actual tables and that the answer includes:

```text
本院生效口径
20分钟（标准值：10分钟）
hospital_demo_data.consult_record
分子数量 / 分母数量 x 100%
```

Verify the execution trace contains aggregate counts and no patient rows.

- [ ] **Step 5: Commit documentation and push the completed batch**

```powershell
git add README.md docs/superpowers/specs/2026-07-14-business-readable-sql-explanation-design.md
git commit -m "docs: 补充指标计算解释与验证说明"
git push
git status --short
```

Expected: working tree is clean and `main` is synchronized with the remote.
