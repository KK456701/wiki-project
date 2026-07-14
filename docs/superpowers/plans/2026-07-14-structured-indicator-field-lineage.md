# 结构化指标字段血缘 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让四个已实现指标的分子、分母、派生字段、本院参数和医院物理字段形成可校验的结构化血缘，并让 SQL 生成与业务解释使用同一份 MySQL 生效定义。

**Architecture:** 新增独立的计算定义领域模型和字段血缘解析器；标准定义随规则导入 MySQL，医院定制通过 JSON 补丁合成生效定义。现有 `med_field_mapping` 继续负责业务字段到医院字段的映射，DBHub 元数据快照负责存在性和类型校验，SQL 模板继续处理数据库方言，回答格式化器只消费已解析的血缘结果。

**Tech Stack:** Python 3.12、Pydantic 2、SQLAlchemy 2、MySQL 8、SQLite 测试、PyYAML、Jinja2、FastAPI、pytest/unittest。

## Global Constraints

- 运行时以 MySQL 为权威记录；Wiki/YAML 只作为导入来源和只读故障兜底。
- `information_schema` 和 DBHub 只验证物理结构，不能自动决定指标字段。
- 未确认字段映射、缺失字段、类型不兼容或缺少跨表关联时必须阻断可执行 SQL。
- LLM 只能提出字段候选，不能自动写入 `confirmed` 映射。
- 分子必须继承分母，医院20分钟定制只影响急会诊分子，不改变分母。
- 前端和执行链路不得展示患者明细或绑定参数后的 SQL。
- 当前计划只迁移 `MQSI2025_001`、`MQSI2025_005`、`MQSI2025_014`、`MQSI2025_035`。
- Excel 口径导入属于后续独立工作台计划；本计划先稳定其最终要转换到的统一计算定义结构。
- 每个任务严格执行 RED-GREEN-REFACTOR，验证后使用中文 Conventional Commit 并推送当前分支。

---

## 文件职责

- `app/rules/calculation.py`：计算定义类型、结构校验、医院补丁合成和业务字段依赖收集。
- `app/rules/lineage.py`：把计算定义、实际参数和医院字段映射解析为业务可读字段血缘。
- `app/rules/schema.py`：结构化定义列和医院表关联表的幂等数据库迁移。
- `app/rules/importer.py`：从四个 YAML 规格导入标准定义、字段契约和医院映射。
- `app/rules/repository.py`：读取标准定义、合成医院补丁、返回 MySQL 生效定义与字段映射。
- `app/metadata/precheck.py`：根据结构化依赖校验映射、物理字段、类型和跨表关联。
- `app/sqlgen/agent.py`：使用已准备好的生效定义和字段映射生成 SQL，避免二次读取产生漂移。
- `app/sqlgen/explanation.py`：将血缘解析结果渲染成分母、分子和本院口径落点表格。
- `app/agent/graph.py`：只传递解释上下文，不再自行从 YAML 拼装规则事实。

---

### Task 1: 计算定义领域模型与校验

**Files:**
- Create: `app/rules/calculation.py`
- Create: `tests/test_calculation_definition.py`

**Interfaces:**
- Produces: `parse_calculation_definition(payload: Any) -> CalculationDefinition`
- Produces: `validate_calculation_definition(definition: CalculationDefinition, business_fields: dict[str, Any], params: dict[str, Any]) -> list[str]`
- Produces: `collect_business_dependencies(definition: CalculationDefinition) -> set[str]`
- Produces: `merge_calculation_patch(base: dict[str, Any], patch: dict[str, Any] | None) -> dict[str, Any]`

- [ ] **Step 1: 写计算定义解析和依赖收集失败测试**

```python
def test_urgent_consult_definition_collects_derived_source_fields():
    definition = parse_calculation_definition(URGENT_CONSULT_DEFINITION)
    assert collect_business_dependencies(definition) == {
        "hospital_id", "consult_type", "request_time", "arrive_time"
    }

def test_unknown_field_and_derived_cycle_are_rejected():
    errors = validate_calculation_definition(
        parse_calculation_definition(INVALID_DEFINITION),
        {"hospital_id": {"type": "string"}},
        {"hospital_id": "hospital_001"},
    )
    assert any("未定义字段" in item for item in errors)
    assert any("循环依赖" in item for item in errors)
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `python -B -m pytest tests/test_calculation_definition.py -q`

Expected: FAIL，提示 `app.rules.calculation` 不存在。

- [ ] **Step 3: 实现最小 Pydantic 模型和确定性校验**

```python
class ConditionDefinition(BaseModel):
    id: str
    field: str
    operator: str
    parameter: str | None = None
    parameters: list[str] = Field(default_factory=list)
    values: list[Any] = Field(default_factory=list)

class DerivedFieldDefinition(BaseModel):
    name: str
    operation: str
    source_fields: list[str]

class AggregateDefinition(BaseModel):
    method: Literal["count_rows", "count_distinct"]
    field: str | None = None

class ScopeDefinition(BaseModel):
    conditions: list[ConditionDefinition] = Field(default_factory=list)

class CalculationBranchDefinition(BaseModel):
    name: str
    inherits: Literal["scope", "denominator"]
    conditions: list[ConditionDefinition] = Field(default_factory=list)
    aggregate: AggregateDefinition

class ResultDefinition(BaseModel):
    operation: Literal["ratio_percent"]
    numerator: Literal["numerator"]
    denominator: Literal["denominator"]

class CalculationDefinition(BaseModel):
    schema_version: Literal[1]
    scope: ScopeDefinition
    derived_fields: dict[str, DerivedFieldDefinition] = Field(default_factory=dict)
    denominator: CalculationBranchDefinition
    numerator: CalculationBranchDefinition
    result: ResultDefinition
```

使用 DFS 检查派生字段循环；条件参数必须存在；`numerator.inherits` 必须为 `denominator`；仅允许设计文档列出的运算符和聚合方法。`merge_calculation_patch` 使用受限递归合并，拒绝修改 `schema_version` 和删除必需节点。

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `python -B -m pytest tests/test_calculation_definition.py -q`

Expected: PASS。

- [ ] **Step 5: 提交并推送**

```powershell
git add app/rules/calculation.py tests/test_calculation_definition.py
git commit -m "feat: 增加指标结构化计算定义"
git push origin main
```

---

### Task 2: 字段血缘解析器

**Files:**
- Create: `app/rules/lineage.py`
- Create: `tests/test_indicator_lineage.py`

**Interfaces:**
- Consumes: Task 1 的 `CalculationDefinition` 和 `collect_business_dependencies`
- Produces: `build_indicator_lineage(definition: CalculationDefinition, mapping: dict[str, Any], params: dict[str, Any], effective_rule: dict[str, Any], stat_start: str, stat_end: str) -> dict[str, Any]`
- Output keys: `denominator_rows`、`numerator_rows`、`caliber_rows`、`required_business_fields`、`physical_tables`

- [ ] **Step 1: 写急会诊字段关系失败测试**

```python
def test_lineage_links_denominator_numerator_and_hospital_caliber():
    lineage = build_indicator_lineage(
        parse_calculation_definition(URGENT_CONSULT_DEFINITION),
        HOSPITAL_MAPPING,
        {"hospital_id": "hospital_001", "consult_type_value": "急会诊", "arrive_minutes_threshold": 20},
        HOSPITAL_RULE,
        "2026-07-01 00:00:00",
        "2026-08-01 00:00:00",
    )
    assert any("consult_record.request_time" in row["physical_fields"] for row in lineage["denominator_rows"])
    timely = next(row for row in lineage["numerator_rows"] if row["condition_id"] == "timely_arrival")
    assert timely["physical_fields"] == [
        "consult_record.request_time", "consult_record.arrive_time"
    ]
    assert timely["condition_text"] == "到位时间减申请时间为0至20分钟"
    assert lineage["caliber_rows"][0]["effect_scope"] == "只改变分子，不改变分母"
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `python -B -m pytest tests/test_indicator_lineage.py -q`

Expected: FAIL，提示 `build_indicator_lineage` 不存在。

- [ ] **Step 3: 实现血缘解析**

解析规则：

```python
def _resolve_field(field_name, definition, mapping):
    if field_name in definition.derived_fields:
        source_names = definition.derived_fields[field_name].source_fields
    else:
        source_names = [field_name]
    return [mapping["fields"][name] for name in source_names]
```

`scope + denominator.conditions` 组成分母行；分子第一行固定说明“继承全部分母条件”，后续只列 `numerator.conditions`。医院参数差异通过 `effective_rule.overridden_fields` 与 `national_params` 比对，并关联到引用该参数的条件。

- [ ] **Step 4: 覆盖去重指标和多表字段**

新增测试确认 `count_distinct admission_id` 显示为“按入院流水号去重”，且不同物理表会进入 `physical_tables`，但解析器不自行猜测 JOIN。

- [ ] **Step 5: 运行测试确认 GREEN**

Run: `python -B -m pytest tests/test_indicator_lineage.py tests/test_calculation_definition.py -q`

Expected: PASS。

- [ ] **Step 6: 提交并推送**

```powershell
git add app/rules/lineage.py tests/test_indicator_lineage.py
git commit -m "feat: 解析指标分子分母字段血缘"
git push origin main
```

---

### Task 3: MySQL 存储和幂等迁移

**Files:**
- Create: `app/rules/schema.py`
- Modify: `app/db/migrations.py`
- Modify: `app/api/main.py`
- Modify: `scripts/migrate_runtime_schema.py`
- Modify: `scripts/init_runtime_db.sql`
- Modify: `tests/test_runtime_migrations.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- Produces: `ensure_rule_lineage_schema(engine: Engine) -> dict[str, list[str]]`
- Creates columns: `med_index_standard.calculation_definition`、`med_index_hospital_custom.custom_calculation_patch`
- Creates table: `med_table_relation`

- [ ] **Step 1: 写 SQLite 幂等迁移失败测试**

```python
def test_rule_lineage_migration_is_idempotent(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'runtime.db'}")
    create_legacy_rule_tables(engine)
    first = ensure_rule_lineage_schema(engine)
    second = ensure_rule_lineage_schema(engine)
    assert first["added_columns"] == ["calculation_definition", "custom_calculation_patch"]
    assert first["created_tables"] == ["med_table_relation"]
    assert second == {"added_columns": [], "created_tables": []}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `python -B -m pytest tests/test_runtime_migrations.py::RuntimeMigrationTest::test_rule_lineage_migration_is_idempotent -q`

Expected: FAIL，提示迁移函数不存在。

- [ ] **Step 3: 实现 SQLite/MySQL 双方言迁移**

MySQL 初始化定义：

```sql
ALTER TABLE med_index_standard
  ADD COLUMN calculation_definition JSON NULL;
ALTER TABLE med_index_hospital_custom
  ADD COLUMN custom_calculation_patch JSON NULL;
CREATE TABLE IF NOT EXISTS med_table_relation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  left_table VARCHAR(128) NOT NULL,
  left_column VARCHAR(128) NOT NULL,
  right_table VARCHAR(128) NOT NULL,
  right_column VARCHAR(128) NOT NULL,
  join_type VARCHAR(16) NOT NULL,
  relation_source VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'confirmed',
  updated_by VARCHAR(64),
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_table_relation (
    hospital_id, db_name, left_table, left_column, right_table, right_column
  )
);
```

SQLite 使用 `TEXT` 保存 JSON，并使用 `PRAGMA table_info`；MySQL 使用 SQLAlchemy inspector。迁移脚本输出新增列和表；API 启动时调用该函数，并沿用现有“迁移失败只记录健康告警”的边界。

- [ ] **Step 4: 运行迁移测试确认 GREEN**

Run: `python -B -m pytest tests/test_runtime_migrations.py tests/test_api.py -q`

Expected: PASS。

- [ ] **Step 5: 提交并推送**

```powershell
git add app/rules/schema.py app/db/migrations.py app/api/main.py scripts/migrate_runtime_schema.py scripts/init_runtime_db.sql tests/test_runtime_migrations.py tests/test_api.py
git commit -m "feat: 增加指标字段血缘存储结构"
git push origin main
```

---

### Task 4: 四个指标 YAML 与 MySQL 导入

**Files:**
- Modify: `core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/rule_sql_spec.yaml`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_005_急会诊及时到位率/rule_sql_spec.yaml`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_014_急危重症患者抢救成功率/rule_sql_spec.yaml`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_035_术中自体血回输率/rule_sql_spec.yaml`
- Modify: `app/rules/importer.py`
- Modify: `tests/test_rule_importer.py`
- Modify: `tests/test_four_indicator_sql.py`

**Interfaces:**
- Consumes: Task 1 的解析和校验函数
- Produces: 四条非空 `med_index_standard.calculation_definition`

- [ ] **Step 1: 写导入结果失败测试**

```python
def test_import_persists_valid_calculation_definitions():
    import_four_indicator_rules(engine, KB_ROOT)
    rows = connection.execute(text(
        "SELECT index_code, calculation_definition FROM med_index_standard ORDER BY index_code"
    )).mappings().all()
    assert len(rows) == 4
    urgent = json.loads(next(row for row in rows if row["index_code"] == "MQSI2025_005")["calculation_definition"])
    assert urgent["numerator"]["inherits"] == "denominator"
    assert urgent["derived_fields"]["arrive_minutes"]["source_fields"] == ["request_time", "arrive_time"]
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `python -B -m pytest tests/test_rule_importer.py -q`

Expected: FAIL，旧表或旧导入结果没有 `calculation_definition`。

- [ ] **Step 3: 为四个规格补齐结构化定义**

每个 YAML 明确写入：

- `schema_version: 1`
- `scope.conditions`：医院与半开统计区间。
- `derived_fields`：急会诊到位分钟数、转科分钟数；其余指标为空。
- `denominator`：条件和 `count_rows`/`count_distinct`。
- `numerator`：继承分母后的追加条件。
- `result.operation: ratio_percent`。

- [ ] **Step 4: 改造导入器从 YAML 读取并校验**

```python
spec = load_rule_sql_spec(kb_root, index_code)
definition = parse_calculation_definition(spec["calculation"])
errors = validate_calculation_definition(definition, field_contract, seed["rule_params"])
if errors:
    raise ValueError("；".join(errors))
seed["calculation_definition"] = definition.model_dump(mode="json")
```

`_upsert_standard` 的插入和更新同时写入 JSON。测试数据库建表补充该列，旧文本规则和 SQL 模板保持不变。

- [ ] **Step 5: 校验结构化定义与 SQL 模板依赖一致**

测试四个模板渲染后包含 `collect_business_dependencies` 解析出的所有物理字段；允许业务字段作为聚合键或条件字段出现，不允许结构化定义引用模板完全未使用的必需字段。

- [ ] **Step 6: 运行测试确认 GREEN**

Run: `python -B -m pytest tests/test_rule_importer.py tests/test_four_indicator_sql.py tests/test_calculation_definition.py -q`

Expected: PASS。

- [ ] **Step 7: 提交并推送**

```powershell
git add core-rules-wiki/sql-specs app/rules/importer.py tests/test_rule_importer.py tests/test_four_indicator_sql.py
git commit -m "feat: 迁移四个指标结构化计算规则"
git push origin main
```

---

### Task 5: 生效口径合成和医院字段映射

**Files:**
- Modify: `app/rules/repository.py`
- Modify: `app/agents/contracts.py`
- Modify: `app/agents/caliber_adaptation.py`
- Modify: `tests/test_rule_repository.py`
- Modify: `tests/test_agent_orchestrator.py`

**Interfaces:**
- Consumes: `merge_calculation_patch`
- Extends `EffectiveRule.calculation_definition: dict[str, Any]`
- Extends `EffectiveRule.national_calculation_definition: dict[str, Any]`
- Extends `FieldMapping.items: list[dict[str, Any]]` and `FieldMapping.status: str`

- [ ] **Step 1: 写标准定义和医院补丁合成失败测试**

```python
def test_effective_rule_returns_mysql_calculation_definition():
    effective = repository.get_effective_rule("MQSI2025_005", "hospital_001")
    assert effective["calculation_definition"]["numerator"]["inherits"] == "denominator"
    assert effective["national_calculation_definition"]["schema_version"] == 1

def test_hospital_patch_is_versioned_and_merged_without_mutating_standard():
    effective = repository.get_effective_rule("MQSI2025_005", "hospital_001")
    assert effective["effective_params"]["arrive_minutes_threshold"] == 20
    assert effective["calculation_definition"]["denominator"] == effective["national_calculation_definition"]["denominator"]
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `python -B -m pytest tests/test_rule_repository.py -q`

Expected: FAIL，返回结果缺少计算定义。

- [ ] **Step 3: 返回标准和生效计算定义**

`get_effective_rule` 解析标准 JSON，读取可选 `custom_calculation_patch`，调用受限合并后返回两个定义。提交、审批、版本快照和恢复路径同步携带 `custom_calculation_patch`，确保回退不会丢失计算规则。

- [ ] **Step 4: 保留字段映射的逐字段来源信息**

`get_field_mapping` 继续返回兼容 `fields` 字典，同时返回 `items` 中每个字段的数据库、表、列、类型和状态。若任一必需映射不是 `confirmed`，整体状态不得返回 `confirmed`。

- [ ] **Step 5: 运行测试确认 GREEN**

Run: `python -B -m pytest tests/test_rule_repository.py tests/test_agent_orchestrator.py -q`

Expected: PASS。

- [ ] **Step 6: 提交并推送**

```powershell
git add app/rules/repository.py app/agents/contracts.py app/agents/caliber_adaptation.py tests/test_rule_repository.py tests/test_agent_orchestrator.py
git commit -m "feat: 合成本院生效计算定义"
git push origin main
```

---

### Task 6: 元数据预校验和 SQL 生成共用上下文

**Files:**
- Modify: `app/metadata/precheck.py`
- Modify: `app/agents/metadata_parsing.py`
- Modify: `app/agents/orchestrator.py`
- Modify: `app/agents/contracts.py`
- Modify: `app/sqlgen/agent.py`
- Modify: `tests/test_metadata_precheck.py`
- Modify: `tests/test_sqlgen.py`
- Modify: `tests/test_agent_orchestrator.py`

**Interfaces:**
- Changes: `MetadataParsingAgent.precheck(hospital_id, rule_id, calculation_definition=None, field_mapping=None) -> dict[str, Any]`
- Changes: `SQLGenerationAgent.generate(query: str, hospital_id: str, rule_id: str, effective_rule: dict[str, Any], stat_start_time: str, stat_end_time: str, precheck: dict[str, Any], trial_run: bool = False, generated_by: str = "agent", custom_filters: list[dict[str, str]] | None = None, term_bindings: list[dict[str, Any]] | None = None, persist_run_result: bool = True, field_mapping: dict[str, Any] | None = None) -> dict[str, Any]`
- Extends: `MetadataPrecheckResult.required_business_fields`、`unconfirmed_mappings`、`type_mismatches`、`missing_relations`
- Extends: `SQLGenerationResult.calculation_definition`、`field_mapping`、`lineage`
- Produces in precheck: `required_business_fields`、`missing_mappings`、`unconfirmed_mappings`、`missing_columns`、`type_mismatches`、`missing_relations`

- [ ] **Step 1: 写映射和元数据阻断失败测试**

```python
def test_precheck_uses_definition_dependencies_not_all_contract_fields():
    result = precheck_rule_fields(
        kb_root, engine, "hospital_001", "MQSI2025_005",
        calculation_definition=URGENT_CONSULT_DEFINITION,
        field_mapping=MYSQL_MAPPING,
    )
    assert result["required_business_fields"] == [
        "arrive_time", "consult_type", "hospital_id", "request_time"
    ]

def test_precheck_blocks_unconfirmed_mapping_and_missing_relation():
    result = precheck_rule_fields(
        kb_root,
        engine,
        "hospital_001",
        "MQSI2025_005",
        calculation_definition=MULTI_TABLE_DEFINITION,
        field_mapping=UNCONFIRMED_MULTI_TABLE_MAPPING,
    )
    assert result["ok"] is False
    assert result["unconfirmed_mappings"] == ["arrive_time"]
    assert result["missing_relations"] == ["consult_record -> staff_directory"]
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `python -B -m pytest tests/test_metadata_precheck.py -q`

Expected: FAIL，现有预校验不接收结构化上下文。

- [ ] **Step 3: 实现结构化依赖校验**

预校验依次执行：定义合法性、映射状态、`med_metadata_column` 表字段存在性、类型兼容、跨表关系确认。类型兼容采用固定类别映射，例如 `varchar/text -> string`、`datetime/timestamp -> datetime`、整数类型 -> `integer`。

- [ ] **Step 4: 让编排器传递同一份生效定义和映射**

```python
precheck = self.metadata.precheck_contract(
    hospital_id,
    rule_id,
    calculation_definition=prepared.effective_rule.calculation_definition,
    field_mapping=prepared.field_mapping.model_dump(),
)
```

SQL 生成器接收 `prepared.field_mapping`，不再在生成过程中重新查询仓库。返回结果增加 `calculation_definition`、`field_mapping` 和由 Task 2 生成的 `lineage`，供回答层直接使用。

- [ ] **Step 5: 改进失败文案**

失败文案必须指出业务影响，例如：“分子条件需要‘急会诊到达现场时间’，本院映射尚未确认，暂不能生成可执行 SQL。”不向普通用户只展示 Python 列表。

- [ ] **Step 6: 运行测试确认 GREEN**

Run: `python -B -m pytest tests/test_metadata_precheck.py tests/test_sqlgen.py tests/test_agent_orchestrator.py -q`

Expected: PASS。

- [ ] **Step 7: 提交并推送**

```powershell
git add app/metadata/precheck.py app/agents/metadata_parsing.py app/agents/orchestrator.py app/agents/contracts.py app/sqlgen/agent.py tests/test_metadata_precheck.py tests/test_sqlgen.py tests/test_agent_orchestrator.py
git commit -m "feat: 按结构化血缘校验SQL字段"
git push origin main
```

---

### Task 7: 分子、分母和口径字段说明

**Files:**
- Modify: `app/sqlgen/explanation.py`
- Modify: `app/agent/graph.py`
- Modify: `tests/test_sql_explanation.py`
- Modify: `tests/test_agent_workflow.py`

**Interfaces:**
- Consumes: SQL 生成结果中的 `lineage`
- Removes: `graph.py` 对 SQL 规格 YAML 的业务事实拼装依赖

- [ ] **Step 1: 写最终业务说明失败测试**

```python
def test_generation_explains_denominator_fields_numerator_inheritance_and_caliber_target():
    answer = format_generation_explanation(
        result=GENERATION_RESULT,
        effective_rule=HOSPITAL_RULE,
        lineage=URGENT_LINEAGE,
        hospital_id="hospital_001",
        stat_start="2026-07-01 00:00:00",
        stat_end="2026-08-01 00:00:00",
    )
    assert "## 分母如何取数" in answer
    assert "consult_record.hospital_id" in answer
    assert "consult_record.consult_type" in answer
    assert "consult_record.request_time" in answer
    assert "## 分子如何从分母中筛选" in answer
    assert "分子一定是分母的子集" in answer
    assert "consult_record.arrive_time" in answer
    assert "到位时间减申请时间" in answer
    assert "## 本院口径作用在哪" in answer
    assert "只改变分子，不改变分母" in answer
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `python -B -m pytest tests/test_sql_explanation.py -q`

Expected: FAIL，旧说明没有三张字段关系表。

- [ ] **Step 3: 渲染三层业务表格**

`explanation.py` 按设计文档渲染：

1. 分母如何取数：条件、数据库表字段、判断方式、条件来源、对分母的作用。
2. 分子如何从分母中筛选：继承关系、派生字段来源、追加条件、对分子的作用。
3. 本院口径作用在哪：本院值、标准值、条件节点、对应物理字段和影响范围。

保留原来的计算结果、运行信息和技术 SQL，但删除重复且无法体现关系的“业务字段平铺表”。旧规则没有 `lineage` 时显示“字段关系尚未结构化”，不猜测关联。

- [ ] **Step 4: 精简 `graph.py` 上下文组装**

生成和试运行都直接把 `result["lineage"]` 交给格式化器。Wiki 兜底规则仅在 MySQL 结果明确标记 `wiki_fallback` 时加载旧规格，不能覆盖 MySQL 生效定义。

- [ ] **Step 5: 运行回答和链路测试确认 GREEN**

Run: `python -B -m pytest tests/test_sql_explanation.py tests/test_agent_workflow.py tests/test_chat_markdown_ui.py -q`

Expected: PASS；执行链路不包含患者明细和绑定后的 SQL。

- [ ] **Step 6: 提交并推送**

```powershell
git add app/sqlgen/explanation.py app/agent/graph.py tests/test_sql_explanation.py tests/test_agent_workflow.py
git commit -m "feat: 展示分子分母字段关系与口径落点"
git push origin main
```

---

### Task 8: 运行时迁移、端到端验收与文档

**Files:**
- Modify: `README.md`
- Modify: `tests/test_agent_workflow.py`
- Modify: `tests/test_rule_importer.py`

**Interfaces:**
- No new public API.
- Runtime commands: `python -B scripts/migrate_runtime_schema.py`、`python -B scripts/import_four_indicator_rules.py`

- [ ] **Step 1: 运行完整迁移和导入**

```powershell
python -B scripts/migrate_runtime_schema.py
python -B scripts/import_four_indicator_rules.py
```

Expected: 迁移返回 `status=success`；四个指标全部为 `inserted` 或 `updated`，无 `failed`。

- [ ] **Step 2: 查询运行时 MySQL 验证权威数据**

验证四条 `calculation_definition` 非空，`MQSI2025_005` 派生字段来源为 `request_time`、`arrive_time`，`hospital_001` 的参数仍为20分钟，标准参数仍为10分钟。

- [ ] **Step 3: 重启 API 并执行真实对话闭环**

依次输入：

```text
急会诊及时到位率怎么算？
生成 SQL
试运行
```

Expected: 回答明确列出分母使用 `hospital_id`、`consult_type`、`request_time`；分子继承分母并额外使用 `request_time`、`arrive_time`；20分钟只作用于分子。

- [ ] **Step 4: 检查执行链路脱敏**

调用 `/api/traces/{trace_id}`，确认 `sql_trial_mcp.output_data` 只包含分子、分母、指标值、数据源、统计区间、耗时和运行 ID，不包含 `rows`、患者字段或 `bound_sql`。

- [ ] **Step 5: 更新 README**

补充“指标字段为什么可信”章节，说明指标规则、业务字段、`med_field_mapping`、表关联和 DBHub 元数据的职责，并给出前端验证步骤；命令行只作为实施排障补充。

- [ ] **Step 6: 运行全量验证**

Run: `python -B -m pytest -q`

Expected: 全部测试通过。

Run: `git diff --check`

Expected: 无输出且退出码为0。

- [ ] **Step 7: 提交并推送**

```powershell
git add README.md tests/test_agent_workflow.py tests/test_rule_importer.py
git commit -m "docs: 补充指标字段血缘验证说明"
git push origin main
```
