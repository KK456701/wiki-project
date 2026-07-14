# Numerator and Denominator Breakdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在生成 SQL 和试运行回答中，确定性展示数据库与表来源、分子分母涉及字段、自然语言运算规则和编号执行步骤。

**Architecture:** 由 `app.rules.lineage` 继续作为指标语义与医院物理字段的唯一连接层，为每个条件输出带中文含义的字段引用；`app.sqlgen.explanation` 只消费该结构，生成医生可读的 Markdown，不解析 SQL 文本。现有折叠技术详情保持不变，新增内容放在默认可见区。

**Tech Stack:** Python 3.12、Pydantic 指标计算定义、FastAPI、原生 Markdown 渲染、`unittest`/`pytest`。

## Global Constraints

- 说明必须来自结构化计算定义、字段血缘、本院生效口径和 SQL 生成结果，禁止调用大模型补写或根据字段名猜测。
- 默认区显示数据库、表和必要字段，但完整 SQL、SQL ID、安全校验与原始配置继续放在技术详情中。
- 未知运算和缺失映射必须明确降级，不得生成看似确定的解释。
- 分母为 0 时显示不可计算，不得显示为 0%。
- 不改变现有 SQL 生成与执行逻辑。

---

### Task 1: Enrich deterministic field lineage

**Files:**
- Modify: `app/rules/lineage.py`
- Test: `tests/test_indicator_lineage.py`

**Interfaces:**
- Consumes: `CalculationDefinition`、医院字段映射中的 `fields`。
- Produces: 每个分母/分子行新增 `field_items: list[dict[str, str]]`，元素固定包含 `business_field`、`label`、`physical_field`；顶层新增去重后的 `field_items`。

- [ ] **Step 1: Write failing tests for labeled field items**

在 `test_links_denominator_numerator_and_hospital_caliber` 中断言：

```python
self.assertEqual(
    timely["field_items"],
    [
        {
            "business_field": "request_time",
            "label": "急会诊申请时间",
            "physical_field": "consult_record.request_time",
        },
        {
            "business_field": "arrive_time",
            "label": "急会诊到位时间",
            "physical_field": "consult_record.arrive_time",
        },
    ],
)
self.assertIn(
    {
        "business_field": "hospital_id",
        "label": "医院",
        "physical_field": "consult_record.hospital_id",
    },
    lineage["field_items"],
)
```

新增缺失映射断言：

```python
def test_field_items_mark_missing_mapping_without_guessing(self) -> None:
    mapping = {**HOSPITAL_MAPPING, "fields": {"hospital_id": "consult_record.hospital_id"}}
    lineage = build_indicator_lineage(
        parse_calculation_definition(URGENT_CONSULT_DEFINITION),
        mapping,
        PARAMS,
        HOSPITAL_RULE,
        PARAMS["start_time"],
        PARAMS["end_time"],
    )
    request = next(item for item in lineage["field_items"] if item["business_field"] == "request_time")
    self.assertEqual(request["physical_field"], "未映射(request_time)")
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run: `python -m pytest tests/test_indicator_lineage.py -q`

Expected: FAIL because `field_items` does not exist.

- [ ] **Step 3: Add one field-item builder and attach it to lineage rows**

在 `app/rules/lineage.py` 增加：

```python
def _field_items(
    business_fields: list[str], mapping: dict[str, Any]
) -> list[dict[str, str]]:
    physical_fields = _physical_fields(business_fields, mapping)
    return [
        {
            "business_field": business_field,
            "label": _field_label(business_field),
            "physical_field": physical_field,
        }
        for business_field, physical_field in zip(
            business_fields, physical_fields, strict=True
        )
    ]
```

`_condition_row` 与 `_aggregate_row` 同时保留现有数组并增加 `field_items`。`build_indicator_lineage` 从所有行按 `business_field + physical_field` 去重生成顶层 `field_items`，不改变 `physical_tables` 和现有调用者。

- [ ] **Step 4: Run lineage tests**

Run: `python -m pytest tests/test_indicator_lineage.py tests/test_indicator_detail_lineage.py -q`

Expected: PASS。

- [ ] **Step 5: Commit and push Task 1**

```powershell
git add app/rules/lineage.py tests/test_indicator_lineage.py
git commit -m "feat(lineage): 补充分子分母字段说明"
git push origin main
```

### Task 2: Generate source, breakdown table, and execution steps

**Files:**
- Modify: `app/sqlgen/explanation.py`
- Test: `tests/test_sql_explanation.py`

**Interfaces:**
- Consumes: Task 1 的 `lineage.field_items` 和行级 `field_items`，以及 `result.dialect`。
- Produces: `_business_calculation_section(lineage, result) -> str`，由数据来源、一句话说明、拆解表和详细步骤组成。

- [ ] **Step 1: Write failing explanation tests**

为 `URGENT_LINEAGE` 补齐 `field_items`，并在默认可见区断言：

```python
self.assertIn("## 数据从哪里来", visible)
self.assertIn("hospital_demo_data", visible)
self.assertIn("consult_record", visible)
self.assertIn("## 分子与分母怎么计算", visible)
self.assertIn("急会诊申请时间：`consult_record.request_time`", visible)
self.assertIn("急会诊到位时间：`consult_record.arrive_time`", visible)
self.assertIn("到位时间减申请时间", visible)
self.assertIn("分子 = SUM(是否及时：是=1，否=0)", visible)
self.assertIn("指标值 = 分子 / 分母 x 100%", visible)
self.assertIn("## 系统实际执行的步骤", visible)
self.assertIn("1. **筛选统计范围**", visible)
self.assertIn("2. **计算时间差**", visible)
self.assertIn("TIMESTAMPDIFF", visible)
```

增加以下场景测试：

- `count_distinct` 显示“按入院流水号去重计数”。
- 无派生字段时不显示“计算时间差”步骤，后续步骤连续编号。
- `physical_tables` 含多表时逐表列出来源，不声称未知 JOIN。
- 空 lineage 显示配置不完整，不出现猜测字段。
- 缺失物理字段显示“尚未映射”。

- [ ] **Step 2: Run focused tests and verify failure**

Run: `python -m pytest tests/test_sql_explanation.py -q`

Expected: FAIL because the new visible sections are absent.

- [ ] **Step 3: Implement formatting helpers**

在 `app/sqlgen/explanation.py` 新增并组合以下职责单一的函数：

```python
def _business_calculation_section(
    lineage: dict[str, Any], result: dict[str, Any]
) -> str:
    if not lineage.get("denominator_rows") or not lineage.get("numerator_rows"):
        return "## 分子与分母怎么计算\n\n" + _missing_lineage_message()
    return "\n\n".join(
        [
            _data_source_section(lineage, result),
            _plain_calculation_summary(lineage),
            _calculation_breakdown_table(lineage),
            _execution_steps_section(lineage, result),
        ]
    )
```

格式约束：

```text
分母 = 符合全部范围条件的记录数
分子 = SUM(满足分子条件：是=1，否=0)
指标值 = 分子 / 分母 x 100%
样本数 = 分母
```

时间差步骤使用：

```text
到位耗时 = 急会诊到位时间 - 急会诊申请时间
MySQL 实际按分钟计算：TIMESTAMPDIFF(MINUTE, request_time, arrive_time)
```

字段显示统一由 `_format_field_items` 生成“中文含义：`table.column`”；当 `physical_field` 以 `未映射(` 开头时输出“中文含义：尚未映射”。表用途按真实物理表分组，列出该表提供的字段中文含义，不推断表间关联。

- [ ] **Step 4: Replace the two visible paragraphs without removing technical details**

在 `format_generation_explanation` 和 `format_trial_explanation` 中，将：

```python
_doctor_denominator_section(lineage),
_doctor_numerator_section(lineage),
```

替换为：

```python
_business_calculation_section(lineage, result),
```

保留折叠区内 `_branch_section("分母如何取数", lineage, "denominator", result)`、`_branch_section("分子如何从分母中筛选", lineage, "numerator", result)`、`_caliber_target_section(effective_rule, lineage)` 和 SQL。删除仅在旧默认段落使用的私有辅助函数前，先用 `rg` 确认没有调用者。

- [ ] **Step 5: Run focused explanation tests**

Run: `python -m pytest tests/test_sql_explanation.py tests/test_agent_workflow.py -q`

Expected: PASS。

- [ ] **Step 6: Commit and push Task 2**

```powershell
git add app/sqlgen/explanation.py tests/test_sql_explanation.py
git commit -m "feat(explanation): 展示分子分母字段与运算步骤"
git push origin main
```

### Task 3: Cross-indicator regression and visual acceptance

**Files:**
- Modify: `tests/test_sql_explanation.py` only if cross-indicator fixture coverage is missing
- Modify: `README.md` only if the user-facing verification section does not mention the new breakdown

**Interfaces:**
- Consumes: Tasks 1-2 completed behavior.
- Produces: Verified output for all four implemented indicators and documented manual acceptance path.

- [ ] **Step 1: Add table-driven coverage for the four calculation shapes**

通过现有四个 `rule_sql_spec.yaml` 和映射加载路径生成 lineage，至少断言：

```python
cases = [
    ("MQSI2025_001", "按入院流水号去重计数"),
    ("MQSI2025_005", "到位时间减申请时间"),
    ("MQSI2025_014", "抢救结果"),
    ("MQSI2025_035", "自体血回输标志"),
]
```

每个案例都必须包含数据库、物理表、分母、分子和最终除乘关系；只有包含 `timestamp_diff_minutes` 的指标显示时间差步骤。

- [ ] **Step 2: Run SQL-generation and workflow regression tests**

Run: `python -m pytest tests/test_calculation_definition.py tests/test_indicator_lineage.py tests/test_sql_explanation.py tests/test_sqlgen.py tests/test_agent_workflow.py -q`

Expected: PASS。

- [ ] **Step 3: Run the full automated suite**

Run: `python -m pytest -q`

Expected: all tests pass; existing SQLite deprecation warnings may remain, with no new warning class introduced.

- [ ] **Step 4: Verify the live UI at desktop and mobile widths**

在已启动的 `http://127.0.0.1:8765` 中登录医院人员账号，询问“急会诊及时到位率怎么算”，再输入“生成 SQL”。确认：

1. 默认区无需展开即可看到数据库、表、字段、分子分母运算和编号步骤。
2. 技术详情仍可展开并显示 SQL。
3. 1440×900、768×900、390×844 下页面无横向溢出；长字段仅在所属单元格换行。
4. 浏览器控制台无新增错误。

- [ ] **Step 5: Update README verification wording if needed**

若 README 仍只描述旧版自然语言说明，增加：

```markdown
- 生成 SQL 后，默认回答会列出数据所在数据库和表、分子分母涉及字段、字段间运算以及系统执行步骤；完整 SQL 仍在“查看技术详情”中。
```

- [ ] **Step 6: Commit and push final verification/docs changes**

```powershell
git add tests/test_sql_explanation.py README.md
git commit -m "test(explanation): 覆盖指标计算拆解场景"
git push origin main
```

若 Step 1 和 Step 5 均无需产生文件变更，则不创建空提交，只记录验证结果。
