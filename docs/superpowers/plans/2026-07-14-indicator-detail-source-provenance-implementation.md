# 指标明细数据来源展示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在指标明细页面和三个 Excel 工作表中展示来源数据库、数据表及直接/派生字段来源，同时保持患者数据安全和现有数量结果不变。

**Architecture:** 从试运行 `RunContext` 的医院字段映射和结构化派生字段定义生成统一的 `DetailFieldLineage`，由 `DetailSnapshotSummary` 同时提供给前端和 Excel。短期患者快照格式不变，来源元数据在读取摘要时动态生成，因此旧快照无需迁移。

**Tech Stack:** Python 3.11、Pydantic v2、FastAPI、openpyxl、原生 JavaScript/CSS、pytest。

## Global Constraints

- 页面默认直接显示逻辑数据库名和取数表，字段来源使用原生折叠区按需展开。
- 直接字段展示完整 `table.column`；派生字段展示“由中文源字段计算”，不能伪装为数据库原始列。
- Excel 继续只有“统计范围、达到要求、未达到要求”三个工作表。
- 不返回或展示主机、端口、账号、密码、连接串、SQL、服务器绝对路径或患者字段值。
- 来源数据库优先使用 `field_mapping.db_name`，缺失时回退 `RunContext.db_source`。
- 现有 576/488/88 数量、预览脱敏、授权导出和 24 小时清理行为必须保持不变。
- 每个任务按 TDD 完成，验证后使用中文 Conventional Commit 并立即推送 `main`。

---

## File Map

- Create: `app/indicator_details/lineage.py`：把运行上下文和明细列转换为统一来源契约。
- Modify: `app/indicator_details/models.py`：增加 `DetailFieldLineage` 和摘要来源字段。
- Modify: `app/indicator_details/snapshot.py`：创建/复用快照时补全来源摘要。
- Modify: `app/indicator_details/exporter.py`：在三个工作表顶部写来源信息并动态定位表头。
- Modify: `web/indicator-details.js`：渲染数据库、数据表和折叠字段来源。
- Modify: `web/indicator-details.css`：增加响应式来源区域样式。
- Modify: `README.md`：补充明细来源验收说明。
- Test: `tests/test_indicator_detail_lineage.py`：覆盖直接字段、派生字段、回退和非法来源。
- Modify: `tests/test_indicator_detail_snapshot.py`：验证摘要来源。
- Modify: `tests/test_indicator_detail_export.py`：验证三个工作表来源和动态表头。
- Modify: `tests/test_indicator_detail_ui.py`：验证来源区域和安全 DOM 构造。
- Modify: `tests/test_indicator_detail_e2e.py`：验证 API、预览和 Excel 来源一致。

---

### Task 1: 统一后端字段来源契约

**Files:**
- Create: `app/indicator_details/lineage.py`
- Modify: `app/indicator_details/models.py`
- Modify: `app/indicator_details/snapshot.py`
- Create: `tests/test_indicator_detail_lineage.py`
- Modify: `tests/test_indicator_detail_snapshot.py`

**Interfaces:**
- Consumes: `RunContext`、`list[DetailColumn]`。
- Produces: `DetailFieldLineage(field, label, kind, sources, explanation)`。
- Produces: `build_detail_lineage(context, columns) -> tuple[str, list[str], list[DetailFieldLineage]]`。
- Extends: `DetailSnapshotSummary.source_database`、`source_tables`、`field_lineage`。

- [ ] **Step 1: 写直接字段、派生字段和数据库回退失败测试**

```python
def test_lineage_distinguishes_database_columns_and_derived_fields():
    context = make_context("MQSI2025_005")
    query = build_detail_query(context)

    database, tables, lineage = build_detail_lineage(context, query.columns)

    assert database == "hospital_demo_data"
    assert tables == ["consult_record"]
    assert lineage[0].explanation == "来自 consult_record.patient_id"
    arrive = next(item for item in lineage if item.field == "arrive_minutes")
    assert arrive.kind == "derived"
    assert arrive.sources == [
        "consult_record.request_time",
        "consult_record.arrive_time",
    ]
    assert arrive.explanation == "由申请时间、到位时间计算"


def test_lineage_falls_back_to_run_context_database_source():
    context = make_context("MQSI2025_005")
    context.field_mapping.pop("db_name", None)
    database, _, _ = build_detail_lineage(context, build_detail_query(context).columns)
    assert database == "hospital_demo_data"
```

- [ ] **Step 2: 运行测试并确认因来源模块不存在而失败**

Run: `python -m pytest tests/test_indicator_detail_lineage.py -q`

Expected: FAIL，错误包含 `ModuleNotFoundError: No module named 'app.indicator_details.lineage'`。

- [ ] **Step 3: 增加 Pydantic 来源模型和摘要字段**

在 `app/indicator_details/models.py` 增加：

```python
class DetailFieldLineage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    field: str
    label: str
    kind: Literal["column", "derived"]
    sources: list[str] = Field(default_factory=list)
    explanation: str


class DetailSnapshotSummary(BaseModel):
    # 保留现有字段
    source_database: str = ""
    source_tables: list[str] = Field(default_factory=list)
    field_lineage: list[DetailFieldLineage] = Field(default_factory=list)
```

- [ ] **Step 4: 实现结构化来源解析器**

`app/indicator_details/lineage.py` 的核心逻辑固定为：

```python
def build_detail_lineage(
    context: RunContext,
    columns: list[DetailColumn],
) -> tuple[str, list[str], list[DetailFieldLineage]]:
    mappings = dict(context.field_mapping.get("fields") or {})
    derived = dict(context.calculation_definition.get("derived_fields") or {})
    labels = {column.field: column.label for column in columns}
    database = str(context.field_mapping.get("db_name") or context.db_source or "")
    tables: list[str] = [context.main_table]
    result: list[DetailFieldLineage] = []

    for column in columns:
        if column.field in derived:
            source_fields = list(derived[column.field].get("source_fields") or [])
            sources = [_mapped_column(mappings, field) for field in source_fields]
            source_labels = [labels.get(field, _business_label(field)) for field in source_fields]
            result.append(DetailFieldLineage(
                field=column.field,
                label=column.label,
                kind="derived",
                sources=sources,
                explanation=f"由{'、'.join(source_labels)}计算",
            ))
        else:
            source = _mapped_column(mappings, column.field)
            result.append(DetailFieldLineage(
                field=column.field,
                label=column.label,
                kind="column",
                sources=[source],
                explanation=f"来自 {source}",
            ))
        _append_source_tables(tables, result[-1].sources)
    return database, tables, result
```

`_mapped_column()` 只接受 `table.column`，缺失时抛出 `ValueError("明细字段尚未完成本院映射：{field}")`；`_append_source_tables()` 按首次出现顺序去重。

- [ ] **Step 5: 在快照摘要中接入来源契约**

`DetailSnapshotStore._summary()` 先构造 `columns`，再调用：

```python
source_database, source_tables, field_lineage = build_detail_lineage(
    context, columns
)
return DetailSnapshotSummary(
    # 现有参数
    source_database=source_database,
    source_tables=source_tables,
    field_lineage=field_lineage,
)
```

复用旧快照时也走同一 `_summary()`，不得修改 `jsonl.gz` 患者行格式。

- [ ] **Step 6: 运行来源与快照测试**

Run: `python -m pytest tests/test_indicator_detail_lineage.py tests/test_indicator_detail_snapshot.py tests/test_indicator_detail_sql.py -q`

Expected: PASS；急会诊返回 `hospital_demo_data`、`consult_record`、五个直接来源和一个派生来源。

- [ ] **Step 7: 提交并推送后端契约**

```powershell
git add app/indicator_details/models.py app/indicator_details/lineage.py app/indicator_details/snapshot.py tests/test_indicator_detail_lineage.py tests/test_indicator_detail_snapshot.py
git commit -m "feat(details): 增加指标明细字段来源契约"
git push origin main
```

---

### Task 2: 页面与 Excel 展示数据来源

**Files:**
- Modify: `app/indicator_details/exporter.py`
- Modify: `web/indicator-details.js`
- Modify: `web/indicator-details.css`
- Modify: `tests/test_indicator_detail_export.py`
- Modify: `tests/test_indicator_detail_ui.py`

**Interfaces:**
- Consumes: Task 1 的 `DetailSnapshotSummary.source_database`、`source_tables`、`field_lineage`。
- Produces: 页面 `#indicatorDetailSource` 和 `#indicatorDetailLineageList`。
- Produces: 三个 Excel 工作表中的“来源数据库”“取数表”“字段来源”。

- [ ] **Step 1: 写 Excel 和前端失败测试**

```python
def test_each_excel_sheet_contains_source_database_table_and_field_lineage(tmp_path):
    path = create_export(tmp_path, summary_with_lineage())
    workbook = load_workbook(path, read_only=True)
    for sheet in workbook.worksheets:
        metadata = {sheet.cell(row, 1).value: sheet.cell(row, 2).value for row in range(1, 13)}
        assert metadata["来源数据库"] == "hospital_demo_data"
        assert metadata["取数表"] == "consult_record"
        assert "患者标识 → consult_record.patient_id" in metadata["字段来源"]
        assert "到位耗时（分钟） → 由申请时间、到位时间计算" in metadata["字段来源"]
```

`tests/test_indicator_detail_ui.py` 增加静态断言：

```python
assert 'id="indicatorDetailSource"' in source
assert 'id="indicatorDetailLineageList"' in source
assert "summary.source_database" in source
assert "lineage.explanation" in source
assert "innerHTML" not in render_source_function_body
```

- [ ] **Step 2: 运行定向测试并确认来源展示缺失**

Run: `python -m pytest tests/test_indicator_detail_export.py tests/test_indicator_detail_ui.py -q`

Expected: FAIL，Excel 没有“来源数据库”，前端没有 `indicatorDetailSource`。

- [ ] **Step 3: 让 Excel 元数据和表头位置动态化**

`app/indicator_details/exporter.py` 将元数据扩展为：

```python
field_source_text = "\n".join(
    f"{item.label} → {item.explanation.removeprefix('来自 ')}"
    if item.kind == "column"
    else f"{item.label} → {item.explanation}"
    for item in summary.field_lineage
)
metadata = (
    ("指标名称", summary.rule_name),
    ("适用医院", summary.hospital_id),
    ("口径来源与版本", _version_text(summary)),
    ("来源数据库", summary.source_database or "未记录"),
    ("取数表", "、".join(summary.source_tables) or "未记录"),
    ("字段来源", field_source_text or "未记录"),
    ("统计区间", ...),
    ("明细快照时间", ...),
    ("导出人", actor_id),
    ("本表说明", description),
    ("记录总数", len(rows)),
)
header_row = len(metadata) + 2
data_start_row = header_row + 1
```

“字段来源”值单元格启用 `wrap_text=True`，行高按来源条数增加；冻结窗格、筛选范围和数据写入统一使用 `header_row`、`data_start_row`。

- [ ] **Step 4: 在明细窗口增加来源区域和折叠字段列表**

`web/indicator-details.js` 在标题元数据之后插入：

```html
<section id="indicatorDetailSource" class="indicator-detail-source" aria-label="数据来源">
  <div class="indicator-detail-source-summary"></div>
  <details class="indicator-detail-lineage">
    <summary>查看字段来源</summary>
    <dl id="indicatorDetailLineageList"></dl>
  </details>
</section>
```

`renderSummary()` 使用 `document.createElement`、`textContent` 和 `replaceChildren`：

```javascript
sourceSummary.replaceChildren(
  sourceItem("来源数据库", summary.source_database || "未记录"),
  sourceItem("取数表", (summary.source_tables || []).join("、") || "未记录")
);
lineageList.replaceChildren();
(summary.field_lineage || []).forEach(function (lineage) {
  var term = document.createElement("dt");
  term.textContent = lineage.label;
  var detail = document.createElement("dd");
  detail.textContent = lineage.explanation;
  lineageList.append(term, detail);
});
```

- [ ] **Step 5: 增加响应式样式**

`web/indicator-details.css` 使用无嵌套卡片的分隔带：

```css
.indicator-detail-source {
  padding: 12px 20px;
  border-top: 1px solid var(--line);
  background: #f7fbfa;
}
.indicator-detail-source-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
}
.indicator-detail-lineage dl {
  display: grid;
  grid-template-columns: minmax(120px, 0.35fr) minmax(0, 1fr);
}
@media (max-width: 640px) {
  .indicator-detail-lineage dl { grid-template-columns: 1fr; }
}
```

- [ ] **Step 6: 运行 Excel 与前端测试**

Run: `python -m pytest tests/test_indicator_detail_export.py tests/test_indicator_detail_ui.py tests/test_indicator_detail_api.py -q`

Expected: PASS；三个工作表数量不变，来源元数据存在，页面不使用不受控 HTML 拼接来源值。

- [ ] **Step 7: 提交并推送展示层**

```powershell
git add app/indicator_details/exporter.py web/indicator-details.js web/indicator-details.css tests/test_indicator_detail_export.py tests/test_indicator_detail_ui.py
git commit -m "feat(ui): 展示指标明细数据来源"
git push origin main
```

---

### Task 3: 端到端、文档和浏览器验收

**Files:**
- Modify: `tests/test_indicator_detail_e2e.py`
- Modify: `README.md`

**Interfaces:**
- Consumes: Tasks 1-2 的 API、页面和 Excel 来源展示。
- Produces: 可复跑的 576/488/88 来源一致性验收。

- [ ] **Step 1: 扩展端到端测试**

在 `test_urgent_consult_preview_and_excel_are_consistent` 中增加：

```python
payload = snapshot.json()
assert payload["source_database"] == "hospital_demo_data"
assert payload["source_tables"] == ["consult_record"]
assert next(
    item for item in payload["field_lineage"] if item["field"] == "arrive_minutes"
)["explanation"] == "由申请时间、到位时间计算"

for sheet in workbook.worksheets:
    metadata = {sheet.cell(row, 1).value: sheet.cell(row, 2).value for row in range(1, 13)}
    assert metadata["来源数据库"] == payload["source_database"]
    assert metadata["取数表"] == "、".join(payload["source_tables"])
```

- [ ] **Step 2: 运行端到端测试并确认完整链路通过**

Run: `python -m pytest tests/test_indicator_detail_e2e.py -q`

Expected: PASS，576/488/88、脱敏预览、三工作表和来源信息一致。

- [ ] **Step 3: 补充 README 验收说明**

在“指标明细预览与短期导出验收”中增加：

```markdown
明细窗口会直接显示“来源数据库”和“取数表”；展开“查看字段来源”可核对每个中文业务列对应的医院字段。派生列会说明由哪些原始字段计算。Excel 三个工作表顶部保存同一份来源说明，但不包含数据库连接串或 SQL。
```

- [ ] **Step 4: 运行全量测试和安全扫描**

Run:

```powershell
python -m pytest -q
git diff --check
rg -n "password|mysql\+pymysql|SELECT |127\.0\.0\.1:3306" app/indicator_details web/indicator-details.js
```

Expected: 全部测试 PASS；差异检查无错误；新增 API/页面/Excel 来源代码不包含连接串、密码或 SQL 文本。

- [ ] **Step 5: 重启 `8765` 并完成浏览器验收**

验证：

1. 1440 像素：数据库、表、字段来源折叠区可见且不挤压三个数量标签。
2. 768 像素：来源摘要自动换行，字段来源两列仍可读。
3. 390 像素：字段来源改为单列，页面无横向整体溢出。
4. Excel 仍为三个工作表，每个工作表顶部来源一致。

- [ ] **Step 6: 提交并推送验收文档**

```powershell
git add README.md tests/test_indicator_detail_e2e.py
git commit -m "docs: 补充指标明细来源验收说明"
git push origin main
```

---

## Final Verification

- [ ] `python -m pytest -q` 全部通过。
- [ ] 急会诊 API 返回 `hospital_demo_data` 和 `consult_record`。
- [ ] 五个直接字段显示完整表字段，到位耗时显示由申请时间和到位时间计算。
- [ ] 页面展开前后在 1440、768、390 像素均无重叠和整体溢出。
- [ ] Excel 仍只有三个工作表，三个表的来源信息一致。
- [ ] 页面和 Excel 不出现连接串、密码、SQL 或绝对路径。
- [ ] 576/488/88、预览脱敏、完整授权导出和 24 小时清理保持不变。

## Self-Review Record

- Spec coverage: 数据库、表、直接字段、派生字段、页面、Excel、安全、旧快照兼容和三档响应式验收均有对应任务。
- Placeholder scan: 无 `TBD`、`TODO`、“后续实现”或未定义错误处理。
- Type consistency: `DetailFieldLineage`、`source_database`、`source_tables`、`field_lineage` 在后端、前端、Excel 和端到端测试中命名一致。
