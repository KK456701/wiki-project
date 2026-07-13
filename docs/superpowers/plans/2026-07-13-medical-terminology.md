# 35 个核心制度指标医学术语库实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为现有 35 个核心制度指标建立可审核、可版本化、支持本院值映射的医学术语库，并同时接入指标召回和参数化 SQL 链路。

**Architecture:** 新增独立 `app/terminology` 领域模块，MySQL 保存当前生效投影、医院映射和不可变发布快照，Wiki 保存可读术语页与只读兜底索引。`CoreIndicatorOrchestrator` 在意图识别后、规则检索前调用确定性术语标准化器；SQL 生成仅接收已审核、无歧义、`sql_safe=true` 且存在有效医院映射的值绑定。

**Tech Stack:** Python 3.12、FastAPI、Pydantic v2、SQLAlchemy、MySQL 8、PyYAML、原生 HTML/CSS/JavaScript、unittest。

## Global Constraints

- 完整范围仅指现有 `MQSI2025_001` 至 `MQSI2025_035` 指标语料，不宣称覆盖全部医学领域。
- MySQL 是运行时权威记录，Wiki 是解释、导入来源和只读故障兜底。
- 医院只能增加本地别名、编码和值映射，不能修改公司标准概念。
- `related` 和 `forbidden` 关系永远不能进入 SQL 值替换。
- LLM 生成内容只能进入待审核状态，不能自动发布。
- SQL 只使用参数绑定，不拼接用户原文或未经审核的术语值。
- 歧义或缺少本院映射时停止生成可执行 SQL，并返回通俗中文原因。
- 执行链路不记录患者明细，只记录术语概念、来源、版本和使用决策。
- 本地术语标准化目标耗时不超过 100ms。
- 每一批完成后运行该批回归测试，使用规范中文 Commit，并自动推送到当前远端分支。

## File Structure

### 第一批：术语数据基础

- Create: `app/terminology/__init__.py` — 对外导出术语契约、仓储和服务。
- Create: `app/terminology/contracts.py` — Pydantic 输入输出契约。
- Create: `app/terminology/repository.py` — 当前投影、医院映射、审批和版本查询。
- Create: `app/terminology/importer.py` — 从审核后的 YAML 幂等导入 MySQL。
- Create: `app/terminology/release.py` — 发布快照、原子切换和回退。
- Create: `app/terminology/wiki_sync.py` — 生成 Wiki 术语页和兜底索引。
- Create: `core-rules-wiki/terminology/core_indicator_terms.yaml` — 35 指标审核后的结构化术语语料。
- Create: `scripts/import_core_indicator_terms.py` — 预览、校验和显式执行入口。
- Modify: `scripts/init_runtime_db.sql` — 术语当前投影、医院映射、版本和审计表。
- Modify: `app/db/migrations.py` — 老安装的幂等建表迁移。

### 第二批：运行链路接入

- Create: `app/terminology/normalizer.py` — 确定性最长词优先匹配、歧义识别和查询改写。
- Create: `app/terminology/sql_binding.py` — SQL 安全映射和阻断结果。
- Modify: `app/agents/contracts.py` — 增加术语标准化契约。
- Modify: `app/agents/orchestrator.py` — 在检索前标准化，在 SQL 前解析安全值。
- Modify: `app/api/main.py` — 构造并注入术语服务。
- Modify: `app/sqlgen/agent.py` — 只接收结构化术语值绑定并加入参数。
- Modify: `app/agent/graph.py` — 流式与非流式路径记录同一术语结果。
- Modify: `app/workflows/core_indicator_chat.yaml` — 增加 `term_normalize` 节点和边。

### 第三批：维护工作台和知识包

- Create: `app/api/terminology.py` — 查询、测试、审核、发布、回退和医院映射 API。
- Create: `web/terminology.js` — 术语工作台交互。
- Create: `web/terminology.css` — 术语工作台桌面与移动布局。
- Modify: `web/index.html` — 在数据库与元数据页面增加术语标签页和编辑区域。
- Modify: `web/workbench.js` — 页面激活和权限控制。
- Modify: `app/kb/export.py` — 医院回收包加入本院术语映射与候选词。
- Modify: `app/kb/merge.py` — 术语差异、歧义和 SQL 安全变化报告。
- Modify: `app/kb/company_repository.py` — 公司发布包加入术语版本和校验和。
- Modify: `README.md` — 使用、审核、验证和故障处理说明。

---

## 第一批：术语数据基础

### Task 1: 术语数据库结构与契约

**Files:**
- Create: `app/terminology/__init__.py`
- Create: `app/terminology/contracts.py`
- Modify: `scripts/init_runtime_db.sql`
- Modify: `app/db/migrations.py`
- Modify: `app/api/main.py`
- Test: `tests/test_terminology_schema.py`
- Test: `tests/test_terminology_contracts.py`

**Interfaces:**
- Produces: `TermConcept`、`TermAlias`、`TermRuleLink`、`HospitalTermMapping`、`TermMatch`、`TermNormalizationResult`。
- Produces: `ensure_terminology_schema(engine: Engine) -> dict[str, list[str]]`。

- [ ] **Step 1: 写数据库和契约失败测试**

```python
class TerminologySchemaTest(unittest.TestCase):
    def test_terminology_schema_is_idempotent(self):
        engine = _runtime_engine()
        first = ensure_terminology_schema(engine)
        second = ensure_terminology_schema(engine)
        self.assertGreaterEqual(set(inspect(engine).get_table_names()), {
            "med_term_concept", "med_term_alias", "med_term_rule_link",
            "med_hospital_term_mapping", "med_hospital_term_mapping_version",
            "med_term_release", "med_term_audit_log",
        })
        self.assertTrue(first["created_tables"])
        self.assertEqual(second["created_tables"], [])

class TerminologyContractsTest(unittest.TestCase):
    def test_related_term_is_not_sql_safe(self):
        with self.assertRaises(ValidationError):
            TermAlias(
                concept_code="DIAG_URI", alias_text="感冒",
                relation_type="related", retrieval_enabled=True,
                sql_safe=True, approval_status="approved", version=1,
                source_reference="术语安全测试",
            )
```

- [ ] **Step 2: 运行测试并确认缺少模块而失败**

Run: `python -m unittest tests.test_terminology_schema tests.test_terminology_contracts -v`

Expected: `ModuleNotFoundError: No module named 'app.terminology'`。

- [ ] **Step 3: 定义严格契约**

```python
RelationType = Literal[
    "exact", "abbreviation", "colloquial", "related", "value_mapping", "forbidden"
]

class TermAlias(BaseModel):
    concept_code: str
    alias_text: str
    relation_type: RelationType
    retrieval_enabled: bool = True
    sql_safe: bool = False
    ambiguity_group: str | None = None
    source_reference: str
    approval_status: Literal["pending", "approved", "rejected"]
    version: int

    @model_validator(mode="after")
    def reject_unsafe_relations(self):
        if self.sql_safe and self.relation_type in {"related", "forbidden"}:
            raise ValueError("相关词或禁止替换词不能用于 SQL")
        return self
```

`TermNormalizationResult` 固定包含 `original_text`、`normalized_text`、`matches`、`ambiguities`、`release_version`、`duration_ms` 和 `sql_eligible`，后续 Agent 和 Trace 只传递该契约。

- [ ] **Step 4: 建立当前投影、版本和审计表**

在 `scripts/init_runtime_db.sql` 与 `ensure_terminology_schema` 中建立七张表。`med_term_rule_link` 保存概念到指标、文档段落和业务字段键的关联；当前投影表使用以下唯一约束：

```sql
UNIQUE KEY uk_term_concept_code (concept_code),
UNIQUE KEY uk_term_alias_scope (concept_code, alias_text, version),
UNIQUE KEY uk_term_rule_link (concept_code, index_code, usage_section, version),
UNIQUE KEY uk_hospital_term_current
  (hospital_id, concept_code, code_system, local_code, version)
```

`med_term_release.snapshot_json` 保存发布时的完整概念和别名快照；`med_hospital_term_mapping_version.snapshot_json` 保存医院映射历史。`med_term_audit_log` 保存动作、对象、操作者、版本和不含患者数据的差异摘要。

- [ ] **Step 5: 在应用启动时执行幂等迁移**

在 `app/api/main.py` 现有启动迁移附近调用：

```python
ensure_terminology_schema(engine)
```

迁移失败沿用现有健康检查降级策略，不静默吞掉错误。

- [ ] **Step 6: 运行测试**

Run: `python -m unittest tests.test_terminology_schema tests.test_terminology_contracts tests.test_runtime_migrations -v`

Expected: 全部通过。

### Task 2: 术语仓储、审批和医院映射版本

**Files:**
- Create: `app/terminology/repository.py`
- Test: `tests/test_terminology_repository.py`

**Interfaces:**
- Produces: `TerminologyRepository(engine)`。
- Produces: `list_active_terms(hospital_id)`、`create_alias_candidate(payload)`、`approve_alias(alias_id, approver_id)`、`upsert_hospital_mapping_candidate(payload)`、`approve_hospital_mapping(mapping_id, approver_id)`、`restore_hospital_mapping(hospital_id, version_id, approver_id)`。

- [ ] **Step 1: 写失败测试覆盖隔离、审核和不可变版本**

```python
def test_hospital_mapping_is_scoped_and_requires_approval():
    pending = repository.upsert_hospital_mapping_candidate({
        "hospital_id": "hospital_001", "concept_code": "DIAG_URI",
        "code_system": "hospital_diagnosis", "local_code": "J06.9-H1",
        "local_name": "急性上呼吸道感染", "local_value": "J06.9-H1",
        "created_by": "user_001",
    })
    assert repository.active_hospital_mappings("hospital_001") == []
    repository.approve_hospital_mapping(pending["id"], "admin")
    assert len(repository.active_hospital_mappings("hospital_001")) == 1
    assert repository.active_hospital_mappings("hospital_002") == []
```

- [ ] **Step 2: 运行并确认仓储不存在**

Run: `python -m unittest tests.test_terminology_repository -v`

Expected: import failure。

- [ ] **Step 3: 实现事务化仓储**

所有写操作使用 `engine.begin()`；审批时写当前投影、不可变版本和审计日志。公司概念写接口拒绝 `source_level=hospital`，医院接口拒绝更改 `canonical_name` 和 `definition`。

公开查询签名固定为 `active_hospital_mappings(hospital_id: str, concept_codes: list[str] | None = None, now: datetime | None = None) -> list[dict[str, Any]]`。

- [ ] **Step 4: 增加冲突与歧义校验**

同一作用域内，一个已审批别名指向多个概念时必须具有同一非空 `ambiguity_group`；否则审批返回 `TERM_ALIAS_CONFLICT`。`related`、`forbidden` 或未审批记录不能被 `list_sql_safe_terms` 返回。

- [ ] **Step 5: 运行仓储和 Schema 测试**

Run: `python -m unittest tests.test_terminology_repository tests.test_terminology_schema -v`

Expected: 全部通过。

### Task 3: 35 指标审核语料与幂等导入

**Files:**
- Create: `core-rules-wiki/terminology/core_indicator_terms.yaml`
- Create: `app/terminology/importer.py`
- Create: `scripts/import_core_indicator_terms.py`
- Test: `tests/test_terminology_corpus.py`
- Test: `tests/test_terminology_importer.py`

**Interfaces:**
- Produces: `load_term_corpus(path) -> TermCorpus`。
- Produces: `validate_term_corpus(corpus) -> CoverageReport`。
- Produces: `import_term_corpus(engine, corpus, actor_id) -> ImportSummary`。
- CLI: `python scripts/import_core_indicator_terms.py [--apply]`，默认只预览。

- [ ] **Step 1: 写 35 指标覆盖和安全分类失败测试**

```python
def test_corpus_covers_all_35_rules_and_sections():
    corpus = load_term_corpus(CORPUS_PATH)
    assert set(corpus.rule_coverage) == {
        f"MQSI2025_{number:03d}" for number in range(1, 36)
    }
    for coverage in corpus.rule_coverage.values():
        assert set(coverage.covered_sections) | set(coverage.not_applicable_sections) == {
            "rule_name", "definition", "numerator", "denominator", "filter", "exclude"
        }
        assert coverage.review_status == "approved"

def test_corpus_never_marks_related_or_forbidden_as_sql_safe():
    for concept in load_term_corpus(CORPUS_PATH).concepts:
        for alias in concept.aliases:
            assert not (
                alias.relation_type in {"related", "forbidden"} and alias.sql_safe
            )
```

- [ ] **Step 2: 运行并确认语料文件不存在**

Run: `python -m unittest tests.test_terminology_corpus tests.test_terminology_importer -v`

Expected: corpus path missing。

- [ ] **Step 3: 建立可审计 YAML 格式并整理 35 指标**

每个概念采用固定结构：

```yaml
schema_version: term-corpus-v1
concepts:
  - concept_code: CONSULT_URGENT
    canonical_name: 急会诊
    concept_type: business_concept
    definition: 需要在规定紧急时限内响应的会诊业务概念。
    source_level: national
    source_references: [MQSI2025_005]
    aliases:
      - {text: 紧急会诊, relation_type: exact, retrieval_enabled: true, sql_safe: false}
      - {text: 急诊会诊, relation_type: related, retrieval_enabled: true, sql_safe: false}
    confused_with:
      - {text: 普通会诊, relation_type: forbidden, reason: 响应时限和统计口径不同}
rule_coverage:
  MQSI2025_005:
    concept_links:
      - {concept_code: IND_MQSI2025_005, usage_section: rule_name}
      - {concept_code: CONSULT_URGENT, usage_section: numerator, business_field_key: consult_type}
      - {concept_code: CONSULT_ARRIVAL, usage_section: numerator, business_field_key: arrive_time}
      - {concept_code: TIME_MINUTE, usage_section: numerator}
    covered_sections: [rule_name, definition, numerator, denominator, filter, exclude]
    not_applicable_sections: []
    review_status: approved
    reviewed_by: terminology_seed_review
```

逐项阅读 `core-rules-wiki/wiki/standards/national/` 下 `MQSI2025_001` 至 `MQSI2025_035` 的页面，将指标名称、定义、分子、分母、筛选、剔除、统计对象、时间、人员、科室、状态和业务动作归入概念，并为每个概念建立 `concept_links`。只有需要参与 SQL 条件的链接填写 `business_field_key`，该键必须能由现有 `med_field_mapping` 解析。对“上感/感冒”“急会诊/急诊会诊”“抢救成功/治愈”“再次住院/非计划再次住院”“危急值/异常值”建立明确的 `related` 或 `forbidden` 关系，不将其默认标记为 SQL 安全。

- [ ] **Step 4: 实现严格加载、预览和幂等导入**

导入前校验 35 个规则覆盖、来源存在、概念编码唯一、别名冲突已标歧义、危险关系不具备 SQL 权限。`--apply` 才写 MySQL；重复导入相同内容只返回 `unchanged_count`，不增加版本。

- [ ] **Step 5: 运行语料与导入测试**

Run: `python -m unittest tests.test_terminology_corpus tests.test_terminology_importer -v`

Expected: 全部通过，覆盖规则数为 35，待审核条目为 0。

### Task 4: 发布、回退、缓存和 Wiki 同步

**Files:**
- Create: `app/terminology/release.py`
- Create: `app/terminology/wiki_sync.py`
- Modify: `app/kb/tools.py`
- Test: `tests/test_terminology_release.py`
- Test: `tests/test_terminology_wiki.py`

**Interfaces:**
- Produces: `TerminologyReleaseService.publish(actor_id) -> ReleaseResult`。
- Produces: `TerminologyReleaseService.restore(release_id, actor_id) -> ReleaseResult`。
- Produces: `write_terminology_wiki(snapshot, kb_root) -> WikiSyncResult`。
- Produces: `KnowledgeBaseTools.search_terms(query, limit=10)` 只读兜底。

- [ ] **Step 1: 写发布、回退和 Wiki 失败测试**

```python
def test_restore_switches_projection_without_mutating_history():
    first = service.publish("admin")
    repository.create_alias_candidate(_new_alias())
    repository.approve_alias(_alias_id(), "admin")
    second = service.publish("admin")
    restored = service.restore(first.release_id, "admin")
    assert restored.active_release_id == first.release_id
    assert repository.get_release(second.release_id)["status"] == "history"

def test_wiki_fallback_never_returns_sql_values():
    result = KnowledgeBaseTools(root).search_terms("感冒")
    assert result["matches"]
    assert all("local_value" not in item for item in result["matches"])
```

- [ ] **Step 2: 运行并确认服务不存在**

Run: `python -m unittest tests.test_terminology_release tests.test_terminology_wiki -v`

Expected: import failure。

- [ ] **Step 3: 实现校验和快照与原子发布**

发布事务内完成：检查无待审核冲突、生成排序稳定 JSON、计算 SHA-256、写 `med_term_release`、切换当前投影版本、记录审计。相同校验和重复发布返回现有版本。

- [ ] **Step 4: 生成 Wiki 术语页和索引**

生成路径 `core-rules-wiki/wiki/terminology/<concept_code>_<canonical_name>.md` 和 `core-rules-wiki/indexes/term_index.json`。页面展示定义、别名关系、SQL 安全标志、混淆说明、来源和关联指标，不写医院本地编码。

- [ ] **Step 5: 实现版本感知缓存**

缓存键为当前 `release_id + hospital_id`。发布、回退或医院映射审批成功后递增数据库缓存代号；服务下一次请求检测到代号变化即重载，不要求重启。

- [ ] **Step 6: 运行第一批回归、真实迁移并提交推送**

Run:

```powershell
python -m unittest tests.test_terminology_schema tests.test_terminology_contracts tests.test_terminology_repository tests.test_terminology_corpus tests.test_terminology_importer tests.test_terminology_release tests.test_terminology_wiki -v
python scripts\migrate_runtime_schema.py
python scripts\import_core_indicator_terms.py
python scripts\import_core_indicator_terms.py --apply
```

Expected: 测试通过；预览显示 35 个指标全部覆盖；执行后存在一个生效术语版本，重复执行不增加版本。

Commit: `feat: 建立核心制度医学术语库`

Push: `git push origin main`

---

## 第二批：运行链路接入

### Task 5: 确定性术语标准化与歧义识别

**Files:**
- Create: `app/terminology/normalizer.py`
- Modify: `app/agents/contracts.py`
- Test: `tests/test_terminology_normalizer.py`
- Test: `tests/test_agent_contracts.py`

**Interfaces:**
- Produces: `TerminologyNormalizer.normalize(text, hospital_id) -> TermNormalizationResult`。
- `PreparedRequest` 增加 `term_normalization: TermNormalizationResult | None`。

- [ ] **Step 1: 写最长词、医院优先和歧义失败测试**

```python
def test_longest_match_wins_without_overlapping_replacement():
    result = normalizer.normalize("统计非计划再次住院患者", "hospital_001")
    assert result.matches[0].canonical_name == "非计划再次住院"
    assert "再次住院" not in [item.matched_text for item in result.matches[1:]]

def test_ambiguous_term_requires_confirmation():
    result = normalizer.normalize("查房率", "hospital_001")
    assert result.ambiguities
    assert result.sql_eligible is False
```

- [ ] **Step 2: 运行并确认标准化器不存在**

Run: `python -m unittest tests.test_terminology_normalizer -v`

Expected: import failure。

- [ ] **Step 3: 实现标准化算法**

算法顺序固定为：本院 `local_name/local_value`、公司标准名、已审核别名；同层级按最长文本优先；重叠区间只保留优先匹配。`forbidden` 产生阻断说明，`related` 只将标准概念加入召回扩展，不改写原始医学条件。

```python
def normalize(self, text: str, hospital_id: str | None) -> TermNormalizationResult:
    candidates = self.repository.list_active_terms(hospital_id)
    matches = longest_non_overlapping_matches(text, candidates)
    return build_normalization_result(text, matches, self.repository.active_release())
```

- [ ] **Step 4: 加入性能测试**

在完整 35 指标语料下连续标准化 100 次，测试断言单次中位数小于 100ms；测试不调用 LLM、网络或 DBHub。

- [ ] **Step 5: 运行标准化和契约测试**

Run: `python -m unittest tests.test_terminology_normalizer tests.test_agent_contracts -v`

Expected: 全部通过。

### Task 6: 接入 Agent 检索与 SQL 安全值映射

**Files:**
- Create: `app/terminology/sql_binding.py`
- Modify: `app/agents/orchestrator.py`
- Modify: `app/api/main.py`
- Modify: `app/sqlgen/agent.py`
- Test: `tests/test_agent_orchestrator.py`
- Test: `tests/test_terminology_sql_binding.py`
- Test: `tests/test_sqlgen.py`

**Interfaces:**
- Produces: `resolve_sql_bindings(normalization, hospital_id) -> TermSQLBindingResult`。
- `CoreIndicatorOrchestrator.__init__` 增加 `terminology: TerminologyNormalizer | None`。
- `SQLGenerationAgent.generate` 增加 `term_bindings: list[dict[str, Any]] | None = None`。

- [ ] **Step 1: 写召回改写和 SQL 阻断失败测试**

```python
def test_synonym_expands_rule_search_but_keeps_original_query():
    prepared = orchestrator.prepare("急诊会诊到位怎么样", "hospital_001")
    assert prepared.term_normalization.original_text == "急诊会诊到位怎么样"
    assert "急会诊" in prepared.retrieval_query

def test_missing_hospital_value_blocks_executable_sql():
    result = resolve_sql_bindings(urgent_consult_normalization, "hospital_001")
    assert result.ok is False
    assert result.problem_code == "TERM_LOCAL_MAPPING_REQUIRED"
    assert result.missing_concepts == ["CONSULT_URGENT"]
```

- [ ] **Step 2: 运行并确认接口缺失**

Run: `python -m unittest tests.test_agent_orchestrator tests.test_terminology_sql_binding -v`

Expected: failing assertions or missing module。

- [ ] **Step 3: 在编排器中增加术语阶段**

`understand_request` 后调用标准化器；无歧义时用标准概念和指标别名构造 `retrieval_query`，但保留 `PreparedRequest.query`。普通聊天不强制术语检索。标准化器故障时记录 `TERM_NORMALIZATION_UNAVAILABLE` 并使用原查询继续规则召回，不能伪造 SQL 值。

- [ ] **Step 4: SQL 前解析医院值**

仅处理 `sql_safe=true`、`approval_status=approved`、当前生效版本且无歧义的概念。映射结果使用独立参数名：

```python
TermSQLBinding(
    concept_code="DIAG_URI",
    field_key="diagnosis_code",
    parameter_name="term_diag_uri_0",
    values=["J06.9-H1"],
    source="hospital",
)
```

字段键必须存在于现有 `med_field_mapping`，否则返回 `TERM_FIELD_MAPPING_REQUIRED`。SQL Agent 只把绑定加入 `params`，模板使用命名占位符，不将值写入 SQL 文本。

- [ ] **Step 5: 返回通俗阻断信息**

歧义时回答“‘查房率’可能指多个指标，请选择”；缺映射时回答“已识别为急性上呼吸道感染，但 hospital_001 尚未配置本院诊断编码，暂不能生成可执行 SQL”。内部问题码保留在结构化结果中。

- [ ] **Step 6: 运行 Agent 与 SQL 回归**

Run: `python -m unittest tests.test_agent_orchestrator tests.test_terminology_sql_binding tests.test_sqlgen tests.test_specialized_agents -v`

Expected: 全部通过；现有四指标 SQL 不受影响。

### Task 7: 工作流 Manifest、Trace 和流式链路一致性

**Files:**
- Modify: `app/workflows/core_indicator_chat.yaml`
- Modify: `app/agent/graph.py`
- Modify: `app/observability/workflow_nodes.py`
- Test: `tests/test_workflow_manifest.py`
- Test: `tests/test_agent_workflow.py`
- Test: `tests/test_trace_ui.py`

**Interfaces:**
- Produces Trace node `term_normalize`。
- Safe outputs: `matched_count`、`canonical_terms`、`ambiguity_count`、`release_version`、`sql_eligible`、`duration_ms`。

- [ ] **Step 1: 写 Manifest 和 Trace 失败测试**

```python
def test_term_normalize_node_precedes_rule_search():
    manifest = load_workflow_manifest("core_indicator_chat")
    assert edge_exists(manifest, "intent_detect", "term_normalize")
    assert edge_exists(manifest, "term_normalize", "rule_search")

def test_trace_does_not_store_local_values_or_patient_data():
    node = trace_node_for("term_normalize")
    payload = json.dumps(node, ensure_ascii=False)
    assert "J06.9-H1" not in payload
    assert "patient_id" not in payload
```

- [ ] **Step 2: 运行并确认节点缺失**

Run: `python -m unittest tests.test_workflow_manifest tests.test_agent_workflow -v`

Expected: `term_normalize` assertions fail。

- [ ] **Step 3: 更新 Manifest**

节点位于 `intent_detect` 和 `rule_search` 之间，`chat` 仍可直接进入 `final_response`。节点失败策略为 `continue`，但输出 `sql_eligible=false`，确保 SQL 意图不会因降级而绕过安全映射。

- [ ] **Step 4: 统一流式与非流式记录**

提取 `_record_term_normalize_node(recorder, trace_id, normalization, duration_ms)`，两条执行路径都调用同一函数。摘要显示“识别 3 个术语 · 无歧义”或“1 个表达需要确认”；详情才展示原词到标准词的关系、来源和版本。

- [ ] **Step 5: 运行第二批回归并提交推送**

Run:

```powershell
python -m unittest tests.test_terminology_normalizer tests.test_terminology_sql_binding tests.test_agent_contracts tests.test_agent_orchestrator tests.test_agent_workflow tests.test_sqlgen tests.test_workflow_manifest tests.test_trace_ui -v
```

Expected: 全部通过；执行链路出现“术语标准化”节点，详情不含医院本地值和患者数据。

Commit: `feat: 接入医学术语标准化链路`

Push: `git push origin main`

---

## 第三批：维护工作台与知识包

### Task 8: 术语管理 API

**Files:**
- Create: `app/api/terminology.py`
- Modify: `app/api/main.py`
- Test: `tests/test_terminology_api.py`

**Interfaces:**
- `GET /api/terminology/concepts`
- `GET /api/terminology/concepts/{concept_code}`
- `POST /api/terminology/test`
- `POST /api/terminology/aliases`
- `POST /api/terminology/aliases/{alias_id}/approve`
- `POST /api/terminology/hospital-mappings`
- `POST /api/terminology/hospital-mappings/{mapping_id}/approve`
- `GET /api/terminology/releases`
- `POST /api/terminology/releases/publish`
- `POST /api/terminology/releases/{release_id}/restore`

- [ ] **Step 1: 写权限、隔离和识别测试 API 失败测试**

```python
def test_read_test_is_available_but_mutation_requires_admin():
    preview = client.post("/api/terminology/test", json={
        "hospital_id": "hospital_001", "text": "统计上感患者"
    })
    assert preview.status_code == 200
    create = client.post("/api/terminology/aliases", json=_alias_payload())
    assert create.status_code == 401

def test_hospital_mapping_list_is_scoped():
    result = client.get(
        "/api/terminology/concepts/DIAG_URI?hospital_id=hospital_002",
        headers=admin_headers,
    )
    assert result.json()["hospital_mappings"] == []
```

- [ ] **Step 2: 运行并确认路由不存在**

Run: `python -m unittest tests.test_terminology_api -v`

Expected: 404。

- [ ] **Step 3: 实现薄 API 边界**

API 只做鉴权、Pydantic 校验、医院作用域检查和错误码到中文提示转换；业务事务全部委托仓储、发布服务和标准化器。所有写接口复用现有管理员 token。

- [ ] **Step 4: 运行 API 和既有鉴权回归**

Run: `python -m unittest tests.test_terminology_api tests.test_api -v`

Expected: 全部通过。

### Task 9: 医学术语维护工作台

**Files:**
- Create: `web/terminology.js`
- Create: `web/terminology.css`
- Modify: `web/index.html`
- Modify: `web/workbench.js`
- Test: `tests/test_terminology_ui.py`
- Test: `tests/test_workbench_ui.py`

**Interfaces:**
- Produces: `window.activateTerminologyWorkspace()`。
- Consumes: Task 8 API。

- [ ] **Step 1: 写页面结构、路由和可访问性失败测试**

```python
def test_terminology_workspace_has_complete_business_controls():
    html = INDEX.read_text(encoding="utf-8")
    for element_id in (
        "terminologyWorkspace", "terminologyConceptList", "terminologyAliasList",
        "terminologyHospitalMappings", "terminologyReviewQueue",
        "terminologyReleaseList", "terminologyTestInput",
    ):
        assert f'id="{element_id}"' in html
```

- [ ] **Step 2: 运行并确认页面不存在**

Run: `python -m unittest tests.test_terminology_ui tests.test_workbench_ui -v`

Expected: missing element assertions。

- [ ] **Step 3: 在元数据页面增加“数据库结构 / 医学术语库”标签**

术语库首屏为紧凑工作台，不做营销式 Hero。左侧概念列表支持名称、类型、指标和状态筛选；右侧使用标签展示“同义词”“本院映射”“来源与关联指标”“版本”。不嵌套卡片，状态使用文本加颜色，不只依赖颜色。

- [ ] **Step 4: 完成审核、版本和识别测试交互**

管理员可新增候选、审批、发布和回退；医院人员可查看和测试，但写按钮隐藏。识别测试展示原始词、标准词、关系、歧义和“可用于检索/可用于 SQL”，不执行 SQL。发布和回退必须二次确认。

- [ ] **Step 5: 完成响应式与键盘操作**

桌面使用稳定双栏布局，移动端改为列表和详情顺序堆叠。所有标签、弹窗、按钮和表单具备明确 label、focus 状态和关闭操作；长术语允许换行，不出现水平溢出。

- [ ] **Step 6: 运行 UI 测试并使用浏览器核验**

Run: `python -m unittest tests.test_terminology_ui tests.test_workbench_ui -v`

Expected: 全部通过。

Browser verification:

1. 打开 `http://127.0.0.1:8765/#/metadata`。
2. 进入“医学术语库”，搜索“急会诊”。
3. 输入“统计上感患者”，确认显示相关概念但没有本院映射时标记为不可生成 SQL。
4. 使用管理员身份新增并审核一条本院映射，重新测试后显示映射已就绪。
5. 在 1440×900、1024×768 和 390×844 三个视口确认无重叠和横向滚动。

### Task 10: 公司知识包与医院回收包扩展

**Files:**
- Modify: `app/kb/export.py`
- Modify: `app/kb/merge.py`
- Modify: `app/kb/company_repository.py`
- Test: `tests/test_kb_merge.py`
- Test: `tests/test_company_kb_repository.py`

**Interfaces:**
- Hospital package format: `kb-exchange-v3`，新增 `terminology/mappings/*.yaml` 和 `terminology/candidates/*.yaml`。
- Company package format: `company-release-v2`，新增 `terminology/release.json`、`terminology/concepts.json`、`terminology/aliases.json`。

- [ ] **Step 1: 写包格式、校验和和隐私失败测试**

```python
def test_hospital_package_contains_terms_but_no_patient_data():
    package = export_hospital_kb_zip(engine, "hospital_001")
    with ZipFile(BytesIO(package)) as archive:
        names = set(archive.namelist())
        assert "terminology/mappings/DIAG_URI.yaml" in names
        assert "checksums.json" in names
        payload = b"".join(archive.read(name) for name in names)
        assert b"patient_id" not in payload
```

- [ ] **Step 2: 运行并确认格式仍为旧版本**

Run: `python -m unittest tests.test_kb_merge tests.test_company_kb_repository -v`

Expected: format/version assertions fail。

- [ ] **Step 3: 扩展导出与公司发布包**

只导出已审批本院映射和待回收候选词，不导出 SQL 运行日志、患者数据或业务明细。所有新增文件加入现有 SHA-256 校验清单。

- [ ] **Step 4: 扩展合并报告**

报告新增 `term_candidate`、`term_conflict`、`term_ambiguity`、`term_sql_safety_change` 类型。公司审核选择仍为“纳入公司候选”或“保留医院本地”，不能直接发布。

- [ ] **Step 5: 兼容读取旧包**

继续接受 `kb-exchange-v2` 和 `company-release-v1`，缺少术语目录时按空集合处理；新导出一律使用新格式。

- [ ] **Step 6: 运行知识包测试**

Run: `python -m unittest tests.test_kb_merge tests.test_company_kb_repository tests.test_api -v`

Expected: 新旧包测试全部通过。

### Task 11: 文档、真实数据库和端到端验收

**Files:**
- Modify: `README.md`
- Test: `tests/test_terminology_end_to_end.py`

**Interfaces:**
- End-to-end scenarios: 指标别名召回、相关医学词召回、歧义确认、缺映射阻断、已审批值参数化、版本回退。

- [ ] **Step 1: 写端到端失败测试**

```python
def test_medical_related_term_is_retrievable_but_not_sql_safe():
    retrieval = system.normalize("统计上感患者", "hospital_001")
    assert retrieval.matches
    assert retrieval.matches[0].retrieval_enabled is True
    assert system.resolve_sql(retrieval, "hospital_001").ok is False

def test_approved_business_value_is_bound_for_sql():
    normalized = system.normalize("统计紧急会诊", "hospital_001")
    system.approve_demo_mapping("hospital_001", "CONSULT_URGENT", "urgent")
    binding = system.resolve_sql(normalized, "hospital_001")
    assert binding.ok is True
    assert binding.bindings[0].parameter_name == "term_consult_urgent_0"
```

- [ ] **Step 2: 运行并确认端到端场景尚未完成**

Run: `python -m unittest tests.test_terminology_end_to_end -v`

Expected: failure。

- [ ] **Step 3: 更新 README**

说明术语分类、审核安全边界、公司与医院职责、导入命令、前端维护入口、版本发布回退、知识包格式和以下验证问题：

- “急会诊响应率怎么算？”应召回 `MQSI2025_005`。
- “统计上感患者”应识别相关诊断概念；无本院编码时阻断 SQL。
- “查房率”命中多个指标时应要求确认。
- “抢救成功患者”不能被改写成“治愈患者”。

- [ ] **Step 4: 在真实 MySQL 执行迁移、导入和 API 验证**

Run:

```powershell
python scripts\migrate_runtime_schema.py
python scripts\import_core_indicator_terms.py --apply
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8765/api/terminology/test `
  -ContentType "application/json" `
  -Body '{"hospital_id":"hospital_001","text":"急会诊响应率怎么算"}'
```

Expected: 返回标准概念、`MQSI2025_005` 关联和当前术语版本；不返回患者数据。

- [ ] **Step 5: 运行全量回归**

Run: `python -m unittest discover -s tests -v`

Expected: 全部测试通过。

- [ ] **Step 6: 提交并推送第三批**

```powershell
git add README.md app web scripts core-rules-wiki tests
git commit -m "feat: 增加医学术语维护工作台"
git push origin main
```

Expected: 当前分支推送成功，`git status --short` 无未提交文件。
