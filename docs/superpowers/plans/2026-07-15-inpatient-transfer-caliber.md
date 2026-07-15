# 患者入院 48 小时内转科指标实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `hospital_001` 可以基于公司 SQL Server 真实表生成并试运行 `MQSI2025_001`，且汇总、分母明细、分子明细严格复用同一口径。

**Architecture:** 沿用急会诊的“医院字段映射 + 专用 SQL Server 查询配置”模式。规则导入将医院范围、转科类型代码、ICU 机构范围和真实字段写入运行库；汇总模板与明细构建器使用相同的住院范围、有效转科筛选和最早事件规则，现有快照服务继续负责汇总/明细数量一致性校验。

**Tech Stack:** Python 3.11、FastAPI、SQLAlchemy、Pydantic、Jinja2、pytest、SQL Server 2016、DBHub MCP。

## Global Constraints

- 医院业务库只允许 `SELECT`，不得创建视图、临时业务表或修改患者数据。
- 统计区间统一为 `[start_time, end_time)`。
- 及时条件统一为 `0 <= DATEDIFF(MINUTE, ADMITTED_AT, INPAT_TRANSFER_AT) <= transfer_minutes_threshold`。
- 每个住院人次仅取按 `INPAT_TRANSFER_AT, INPAT_TRANSFER_ID` 排序的第一条有效转科。
- ICU 机构范围必须来自医院配置，不使用 Excel 中未匹配当前数据库的硬编码。
- 不提交数据库密码、患者明细、运行日志、Excel 临时分析目录或本地真实配置。

---

### Task 1: 固化医院参数和正式字段映射

**Files:**
- Modify: `app/config.py`
- Modify: `app/api/main.py`
- Modify: `scripts/import_four_indicator_rules.py`
- Modify: `app/rules/importer.py`
- Modify: `app/rules/repository.py`
- Modify: `config.example.yaml`
- Test: `tests/test_rule_importer.py`
- Test: `tests/test_rule_repository.py`

**Interfaces:**
- Consumes: `hospital_scope_value: int`、`transfer_department_code: int`、`transfer_ward_code: int`、`icu_org_ids_csv: str`。
- Produces: `MQSI2025_001` 已确认字段映射、两表关联、医院参数、SQL Server 专用 SQL 和 `inpatient_transfer_48h_sqlserver` 查询配置。

- [ ] **Step 1: Write the failing importer test**

```python
result = import_four_indicator_rules(
    engine,
    Path("core-rules-wiki"),
    business_source_id="win60_qa_991827",
    business_dialect="sqlserver",
    hospital_scope_value=991827,
    urgent_level_code=977578,
    transfer_department_code=399549991,
    transfer_ward_code=399549990,
    icu_org_ids_csv="360896232048246943,360915701134999568",
)
mapping = MySQLRuleRepository(engine).get_field_mapping(
    "MQSI2025_001", "hospital_001"
)
assert mapping["query_profile"] == "inpatient_transfer_48h_sqlserver"
assert mapping["fields"]["admit_time"] == "INPATIENT_ENCOUNTER.ADMITTED_AT"
assert mapping["fields"]["transfer_time"] == "INPAT_TRANSFER.INPAT_TRANSFER_AT"
```

- [ ] **Step 2: Run the importer test and verify RED**

Run: `python -m pytest tests/test_rule_importer.py::FourIndicatorRuleImporterTest::test_formal_sqlserver_import_replaces_demo_mapping_safely -q`

Expected: FAIL because the importer does not accept transfer parameters and only confirms `MQSI2025_005`.

- [ ] **Step 3: Implement formal mappings and parameter loading**

Add `get_int_list_csv()` to normalize comma-separated hospital configuration without exposing list interpolation to SQL. Add importer helpers that upsert the `INPATIENT_ENCOUNTER`/`INPAT_TRANSFER` mappings and their `ENCOUNTER_ID` relation, then store the hospital parameters and SQL Server template as the approved custom implementation.

- [ ] **Step 4: Run importer and repository tests**

Run: `python -m pytest tests/test_rule_importer.py tests/test_rule_repository.py -q`

Expected: PASS.

### Task 2: 生成统一口径的 SQL Server 汇总 SQL

**Files:**
- Create: `core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/templates/sqlserver.sql.j2`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/rule_sql_spec.yaml`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/field_contract.yaml`
- Modify: `core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_001.yaml`
- Test: `tests/test_company_inpatient_transfer.py`

**Interfaces:**
- Consumes: `hospital_soid`、`transfer_department_code`、`transfer_ward_code`、`icu_org_ids_csv`、`transfer_minutes_threshold`、`start_time`、`end_time`。
- Produces: `index_value`、`numerator_count`、`denominator_count`、`sample_count`。

- [ ] **Step 1: Write failing SQL contract tests**

The tests must assert the template contains both real tables, half-open admission period, minute-level inclusive boundary, ICU organization exclusion, `ROW_NUMBER()` ordered by actual transfer time, and all four result aliases.

- [ ] **Step 2: Run the SQL contract tests and verify RED**

Run: `python -m pytest tests/test_company_inpatient_transfer.py -q`

Expected: FAIL because the SQL Server template and formal mapping are missing.

- [ ] **Step 3: Implement the aggregate template and structured rule metadata**

The template must construct `eligible_encounter`, `valid_transfer`, and `base` CTEs. The denominator counts one row per eligible encounter; the numerator sums rows whose transfer minutes are in `[0, threshold]`; the ratio uses a zero-denominator guard.

- [ ] **Step 4: Run SQL contract and four-indicator regression tests**

Run: `python -m pytest tests/test_company_inpatient_transfer.py tests/test_four_indicator_sql.py -q`

Expected: PASS.

### Task 3: 让明细查询复用汇总统计范围

**Files:**
- Modify: `app/indicator_details/sql_builder.py`
- Modify: `app/indicator_details/lineage.py`
- Modify: `tests/test_company_inpatient_transfer.py`
- Test: `tests/test_indicator_detail_snapshot.py`

**Interfaces:**
- Consumes: Task 1 产生的 `RunContext` 和医院参数。
- Produces: 每个住院人次一行的明细，包含 `__meets_numerator` 和 `__evidence_row_count`。

- [ ] **Step 1: Write a failing detail reuse test**

```python
query = build_detail_query(inpatient_transfer_context)
assert "SELECT TOP 20001" in query.sql
assert "ROW_NUMBER() OVER" in query.sql
assert "ORDER BY transfer.INPAT_TRANSFER_AT" in query.sql
assert "DATEDIFF(MINUTE" in query.sql
assert "BETWEEN 0 AND :transfer_minutes_threshold" in query.sql
```

Also assert that database lineage lists `WINDBA.INPATIENT_ENCOUNTER` and `WINDBA.INPAT_TRANSFER` once each.

- [ ] **Step 2: Run the detail tests and verify RED**

Run: `python -m pytest tests/test_company_inpatient_transfer.py -q`

Expected: FAIL because the detail builder has no inpatient-transfer SQL Server profile.

- [ ] **Step 3: Implement the dedicated detail query**

Add `_build_inpatient_transfer_sqlserver_query()` and dispatch it only when `query_profile == "inpatient_transfer_48h_sqlserver"`. Keep the same filters and ordering as the aggregate query; output denominator rows and mark numerator rows instead of running a separate numerator query.

- [ ] **Step 4: Run detail and snapshot tests**

Run: `python -m pytest tests/test_company_inpatient_transfer.py tests/test_indicator_detail_snapshot.py tests/test_indicator_detail_lineage.py -q`

Expected: PASS, including the existing snapshot mismatch rejection.

### Task 4: 导入、只读试运行和文档验证

**Files:**
- Modify: `README.md`
- Local-only: `config.yaml`

**Interfaces:**
- Consumes: 当前 QA 库只读连接和 Task 1-3 的实现。
- Produces: 可在前端复现的正式映射、汇总结果和详情入口。

- [ ] **Step 1: Run the focused test suite**

Run: `python -m pytest tests/test_company_inpatient_transfer.py tests/test_rule_importer.py tests/test_rule_repository.py tests/test_four_indicator_sql.py tests/test_indicator_detail_snapshot.py tests/test_indicator_detail_lineage.py -q`

Expected: PASS.

- [ ] **Step 2: Import the rules into the local runtime database**

Run: `python scripts/import_four_indicator_rules.py`

Expected: `MQSI2025_001` and `MQSI2025_005` have confirmed SQL Server mappings; the other indicators remain pending rather than retaining Demo mappings.

- [ ] **Step 3: Execute the generated SQL read-only against QA**

Use `2026-06-01 00:00:00` to `2026-08-01 00:00:00`. Expected current QA candidate result: denominator `158`, numerator `2`, result `1.27`.

- [ ] **Step 4: Verify aggregate/detail consistency**

Execute the detail SQL through the same read-only DB client. Expected: 158 detail rows, exactly 2 rows with `__meets_numerator = 1`.

- [ ] **Step 5: Run the full relevant suite and inspect Git diff**

Run: `python -m pytest tests -q`

Expected: PASS with no new warnings or failures. Then run `git diff --check` and confirm no local secret or temporary artifact is staged.

- [ ] **Step 6: Commit and push**

```powershell
git add <本批相关文件>
git commit -m "feat: 落地入院48小时转科真实口径"
git push
```
