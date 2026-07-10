# Four-Indicator MySQL Rule Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make MySQL the runtime source of truth for four indicators while keeping the Wiki as a read-only fallback, and complete SQL generation and trial-run coverage for `MQSI2025_001`, `005`, `014`, and `035` for `hospital_001`.

**Architecture:** Add a focused `app/rules` package containing a MySQL repository, Wiki fallback adapter, and idempotent importer. Existing chat, SQL generation, approval, and Trace flows consume the repository interface; writes fail closed, while reads may fall back to `KnowledgeBaseTools` with an explicit `wiki_fallback` marker.

**Tech Stack:** Python 3, FastAPI, SQLAlchemy, PyMySQL, Jinja2, MySQL 8, DBHub MCP, `unittest`.

## Global Constraints

- Scope is limited to `MQSI2025_001`, `MQSI2025_005`, `MQSI2025_014`, and `MQSI2025_035` for `hospital_001`.
- Runtime precedence is approved active hospital custom rule, MySQL national rule, then read-only Wiki fallback.
- Company rules are import inputs only and never appear in effective business-caliber precedence.
- `MQSI2025_005` uses 10 minutes nationally and 20 minutes for `hospital_001`; 30 minutes must not remain as a default.
- Generated SQL must be one parameterized read-only `SELECT` and pass the existing validator before DBHub execution.
- Rule writes, approvals, and restores must never fall back to Wiki.
- Existing unrelated worktree changes and generated pending review Markdown must not be staged.
- Every production behavior follows RED-GREEN-REFACTOR and every task ends with a focused commit.

## File Map

- Create `app/rules/repository.py` for repository contracts, MySQL storage, Wiki fallback, and the production factory.
- Create `app/rules/importer.py` and `scripts/import_four_indicator_rules.py` for deterministic idempotent import.
- Create `tests/test_rule_repository.py`, `tests/test_rule_importer.py`, and `tests/test_four_indicator_sql.py`.
- Modify runtime/demo SQL, four SQL specs and mappings, SQL generation, API, workflow Trace, tests, and README.

---

### Task 1: Runtime Rule Schema

**Files:**
- Modify: `scripts/init_runtime_db.sql`
- Create: `tests/test_rule_repository.py`

**Interfaces:**
- Produces `med_index_standard`, `med_index_hospital_custom`, and `med_index_hospital_custom_version`.
- Later tasks rely on unique keys for `index_code`, `hospital_id + index_code`, and `hospital_id + index_code + version`.

- [ ] **Step 1: Write the failing schema test**

```python
class RuntimeRuleSchemaTest(unittest.TestCase):
    def test_runtime_schema_contains_rule_store_tables(self) -> None:
        ddl = Path("scripts/init_runtime_db.sql").read_text(encoding="utf-8")
        for table in (
            "med_index_standard",
            "med_index_hospital_custom",
            "med_index_hospital_custom_version",
        ):
            self.assertIn(f"CREATE TABLE IF NOT EXISTS {table}", ddl)
        for column in (
            "standard_sql LONGTEXT",
            "rule_params JSON",
            "custom_params JSON",
            "approval_status VARCHAR(32)",
            "effective_from DATETIME",
            "effective_to DATETIME",
            "snapshot_json JSON",
        ):
            self.assertIn(column, ddl)
```

- [ ] **Step 2: Run test to verify RED**

Run: `python -B -m unittest tests.test_rule_repository.RuntimeRuleSchemaTest -v`

Expected: FAIL because the three tables are absent.

- [ ] **Step 3: Add minimal MySQL DDL**

Add the exact columns from the approved design. Use `LONGTEXT` for SQL, `JSON` for contracts/params/snapshots, `TINYINT` for enabled status, and these constraints:

```sql
UNIQUE KEY uk_standard_code (index_code)
UNIQUE KEY uk_hospital_index (hospital_id, index_code)
UNIQUE KEY uk_hospital_index_version (hospital_id, index_code, version)
```

The version table also has unique `change_id`, `approval_status`, `source_version`, `change_type`, operator/approver, and created/approved timestamps.

- [ ] **Step 4: Run test to verify GREEN**

Run: `python -B -m unittest tests.test_rule_repository.RuntimeRuleSchemaTest -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add scripts/init_runtime_db.sql tests/test_rule_repository.py
git commit -m "feat: add MySQL indicator rule schema"
```

---

### Task 2: MySQL Repository and Effective Caliber

**Files:**
- Create: `app/rules/__init__.py`
- Create: `app/rules/repository.py`
- Modify: `tests/test_rule_repository.py`

**Interfaces:**
- Produces `RuleRepository` protocol.
- Produces `MySQLRuleRepository(engine: Engine)`.
- Produces `get_effective_rule(index_code_or_name: str, hospital_id: str | None) -> dict[str, Any]`.
- Produces `get_field_mapping(index_code: str, hospital_id: str) -> dict[str, Any]`.

- [ ] **Step 1: Write failing precedence tests**

Create an SQLite fixture with one national `MQSI2025_005` row and one approved hospital row. Assert:

```python
result = repository.get_effective_rule("MQSI2025_005", "hospital_001")
self.assertEqual(result["effective_level"], "hospital")
self.assertEqual(result["rule_source"], "mysql")
self.assertEqual(result["national_params"]["arrive_minutes_threshold"], 10)
self.assertEqual(result["effective_params"]["arrive_minutes_threshold"], 20)
self.assertEqual(result["fallback_chain"], ["hospital", "national"])
```

Add tests for no hospital row, disabled custom, pending custom, future `effective_from`, and expired `effective_to`; each must resolve to national threshold 10.

- [ ] **Step 2: Run test to verify RED**

Run: `python -B -m unittest tests.test_rule_repository.MySQLRuleRepositoryTest -v`

Expected: ERROR because `app.rules.repository` does not exist.

- [ ] **Step 3: Define the repository contract**

```python
class RuleRepository(Protocol):
    def search(self, query: str, limit: int = 5) -> dict[str, Any]: ...
    def get_effective_rule(self, index_code_or_name: str,
                           hospital_id: str | None) -> dict[str, Any]: ...
    def get_field_mapping(self, index_code: str,
                          hospital_id: str) -> dict[str, Any]: ...
    def submit_change_request(self, payload: dict[str, Any]) -> dict[str, Any]: ...
    def approve_change_request(self, change_id: str,
                               approver_id: str) -> dict[str, Any]: ...
    def list_versions(self, index_code: str,
                      hospital_id: str) -> dict[str, Any]: ...
    def restore_version(self, index_code: str, hospital_id: str,
                        version: int, approver_id: str) -> dict[str, Any]: ...
```

- [ ] **Step 4: Implement MySQL reads and field-level merge**

Resolve by exact code, exact name, then name substring. Load only enabled national rules. Apply custom rows only when approved, enabled, and effective now. Merge only non-empty custom fields and merge `custom_params` over `rule_params`. Return national/effective values, versions, overridden fields, and `rule_source="mysql"`.

- [ ] **Step 5: Verify GREEN and Wiki regression**

```powershell
python -B -m unittest tests.test_rule_repository.MySQLRuleRepositoryTest -v
python -B -m unittest tests.test_kb_tools -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/rules tests/test_rule_repository.py
git commit -m "feat: add MySQL rule repository"
```

---

### Task 3: Idempotent Four-Indicator Import

**Files:**
- Create: `app/rules/importer.py`
- Create: `scripts/import_four_indicator_rules.py`
- Create: `tests/test_rule_importer.py`
- Modify: `app/rules/__init__.py`

**Interfaces:**
- Produces `FOUR_INDICATOR_CODES`.
- Produces `import_four_indicator_rules(engine: Engine, kb_root: Path, hospital_id: str = "hospital_001") -> dict[str, Any]`.
- Produces deterministic standards, mappings, templates, and the 005 custom threshold.

- [ ] **Step 1: Write failing idempotency tests**

Run the importer twice against an SQLite rule-store fixture. Assert exactly four standards, one hospital custom (`005`), one initial version, and mappings for all four codes. Assert 005 national threshold is 10 and hospital threshold is 20; no serialized value contains 30.

```python
first = import_four_indicator_rules(engine, Path("core-rules-wiki"))
second = import_four_indicator_rules(engine, Path("core-rules-wiki"))
self.assertEqual(first["failed"], [])
self.assertEqual(second["failed"], [])
self.assertEqual(_count(engine, "med_index_standard"), 4)
self.assertEqual(_count(engine, "med_index_hospital_custom"), 1)
```

- [ ] **Step 2: Run test to verify RED**

Run: `python -B -m unittest tests.test_rule_importer -v`

Expected: ERROR because the importer is absent.

- [ ] **Step 3: Implement one-transaction-per-indicator import**

```python
FOUR_INDICATOR_CODES = (
    "MQSI2025_001", "MQSI2025_005", "MQSI2025_014", "MQSI2025_035",
)

def import_four_indicator_rules(engine: Engine, kb_root: Path,
                                hospital_id: str = "hospital_001") -> dict[str, Any]:
    results = {"inserted": [], "updated": [], "failed": []}
    for seed in build_indicator_seeds(kb_root):
        try:
            with engine.begin() as conn:
                action = upsert_standard(conn, seed)
                upsert_field_mappings(conn, hospital_id, seed)
                if seed["index_code"] == "MQSI2025_005":
                    upsert_initial_hospital_custom(conn, hospital_id, seed)
            results[action].append(seed["index_code"])
        except Exception as exc:
            results["failed"].append({"index_code": seed["index_code"],
                                      "error": str(exc)})
    return results
```

Use MySQL `ON DUPLICATE KEY UPDATE` and SQLite `ON CONFLICT` in dialect-specific helpers.

- [ ] **Step 4: Add the executable command**

```python
if __name__ == "__main__":
    result = import_four_indicator_rules(create_runtime_engine(), DEFAULT_KB_ROOT)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(1 if result["failed"] else 0)
```

- [ ] **Step 5: Run test to verify GREEN**

Run: `python -B -m unittest tests.test_rule_importer -v`

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/rules/importer.py app/rules/__init__.py scripts/import_four_indicator_rules.py tests/test_rule_importer.py
git commit -m "feat: import four indicators into MySQL rule store"
```

---

### Task 4: Read-Only Wiki Fallback

**Files:**
- Modify: `app/rules/repository.py`
- Modify: `app/kb/tools.py`
- Modify: `tests/test_rule_repository.py`

**Interfaces:**
- Produces `WikiRuleSource(tools: KnowledgeBaseTools)`.
- Produces `FallbackRuleRepository(primary: RuleRepository, fallback: WikiRuleSource)`.
- Produces `create_rule_repository(engine: Engine, kb_root: Path) -> RuleRepository`.

- [ ] **Step 1: Write failing fallback tests**

When the primary raises, assert the Wiki result has `rule_source="wiki_fallback"` and warning `rule_store_unavailable`. When primary returns no rule, assert warning `rule_not_migrated`. For `submit_change_request`, assert a primary failure propagates and the Wiki fake receives no write.

- [ ] **Step 2: Run test to verify RED**

Run: `python -B -m unittest tests.test_rule_repository.FallbackRuleRepositoryTest -v`

Expected: ERROR because fallback composition is absent.

- [ ] **Step 3: Implement read fallback and fail-closed writes**

```python
def get_effective_rule(self, code_or_name: str,
                       hospital_id: str | None) -> dict[str, Any]:
    try:
        result = self.primary.get_effective_rule(code_or_name, hospital_id)
        if result:
            return result
        warning = "rule_not_migrated"
    except Exception:
        warning = "rule_store_unavailable"
    result = self.fallback.get_effective_rule(code_or_name, hospital_id)
    result["rule_source"] = "wiki_fallback"
    result["warnings"] = [*result.get("warnings", []), warning]
    result["fallback_chain"] = ["hospital", "national", "wiki_fallback"]
    return result
```

Apply the same pattern to search and mappings. Delegate writes only to `primary` without exception fallback.

- [ ] **Step 4: Verify GREEN**

```powershell
python -B -m unittest tests.test_rule_repository -v
python -B -m unittest tests.test_kb_tools -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/rules/repository.py app/kb/tools.py tests/test_rule_repository.py
git commit -m "feat: add read-only Wiki rule fallback"
```

---

### Task 5: Four Demo Tables and SQL Contracts

**Files:**
- Modify: `scripts/init_demo_hospital_db.sql`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/field_contract.yaml`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/rule_sql_spec.yaml`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/templates/mysql.sql.j2`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/examples.md`
- Modify: `core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_001.yaml`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_005_急会诊及时到位率/rule_sql_spec.yaml`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_005_急会诊及时到位率/templates/mysql.sql.j2`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_005_急会诊及时到位率/examples.md`
- Create: `core-rules-wiki/sql-specs/MQSI2025_014_急危重症患者抢救成功率/field_contract.yaml`
- Create: `core-rules-wiki/sql-specs/MQSI2025_014_急危重症患者抢救成功率/rule_sql_spec.yaml`
- Create: `core-rules-wiki/sql-specs/MQSI2025_014_急危重症患者抢救成功率/templates/mysql.sql.j2`
- Create: `core-rules-wiki/sql-specs/MQSI2025_014_急危重症患者抢救成功率/examples.md`
- Create: `core-rules-wiki/sql-specs/MQSI2025_035_术中自体血回输率/field_contract.yaml`
- Create: `core-rules-wiki/sql-specs/MQSI2025_035_术中自体血回输率/rule_sql_spec.yaml`
- Create: `core-rules-wiki/sql-specs/MQSI2025_035_术中自体血回输率/templates/mysql.sql.j2`
- Create: `core-rules-wiki/sql-specs/MQSI2025_035_术中自体血回输率/examples.md`
- Create: `core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_014.yaml`
- Create: `core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_035.yaml`
- Create: `tests/test_four_indicator_sql.py`

**Interfaces:**
- Produces four renderable MySQL templates using `:hospital_id`, `:start_time`, and `:end_time`.
- Produces exact demo values `25.00`, `66.67`, `75.00`, and `50.00`.

- [ ] **Step 1: Write failing template-contract tests**

Load all four specs and mappings. Render every template, run `validate_select_sql`, assert no literal `hospital_001`, and assert required bind parameters. Assert 005 national threshold is 10 and the imported hospital parameter is 20.

For 001 assert SQL references both department fields, uses a 0-to-48-hour interval, and counts distinct admission IDs. For 035 assert numerator and denominator count distinct patient IDs.

- [ ] **Step 2: Run test to verify RED**

Run: `python -B -m unittest tests.test_four_indicator_sql -v`

Expected: FAIL because 014/035 specs and tables are absent and 005 defaults to 30.

- [ ] **Step 3: Build deterministic `hospital_001` demo data**

Replace the existing `hospital_002` rows and create:

- 001: four admissions: valid within 48 hours, after 48 hours, ICU transfer, and no transfer.
- 005: urgent consult arrivals at 8, 15, and 30 minutes, plus one ordinary consult.
- 014: four `critical_rescue_record` rows with three successes and one failure.
- 035: four transfused patients, two autologous, plus a duplicate detail row for one patient.

Use the exact table fields from the design. Insert only `0/1` flag values and `成功/失败` rescue results.

- [ ] **Step 4: Add and correct SQL templates**

001 numerator includes:

```sql
TIMESTAMPDIFF(MINUTE, {{ fields.admit_time }}, {{ fields.transfer_time }})
  BETWEEN 0 AND :transfer_minutes_threshold
AND COALESCE({{ fields.from_dept_id }}, '') <> :excluded_dept_id
AND COALESCE({{ fields.to_dept_id }}, '') <> :excluded_dept_id
```

Add `from_dept_id` to the 001 field contract and `hospital_001` mapping, remove `count_multiple_transfers`, and update the readable example to `25.00%`.

005 numerator includes:

```sql
TIMESTAMPDIFF(MINUTE, {{ fields.request_time }}, {{ fields.arrive_time }})
  BETWEEN 0 AND :arrive_minutes_threshold
```

014 calculation includes:

```sql
SUM(CASE WHEN {{ fields.rescue_result }} = :success_value THEN 1 ELSE 0 END)
  / NULLIF(COUNT(*), 0) * 100
```

035 calculation includes:

```sql
COUNT(DISTINCT CASE WHEN {{ fields.autologous_reinfusion_flag }} = 1
                    THEN {{ fields.patient_id }} END)
  / NULLIF(COUNT(DISTINCT {{ fields.patient_id }}), 0) * 100
```

Each template also selects denominator count as `sample_count`.

Update all four `examples.md` files with the exact `hospital_001` July 2026 query and expected value. The 005 example must show both national `33.33%` and hospital `66.67%`.

- [ ] **Step 5: Add executable semantic tests**

Register a SQLite `TIMESTAMPDIFF` test function or translate that expression only in the test harness. Execute the rendered templates against in-memory fixtures and assert:

```python
self.assertEqual(results["MQSI2025_001"], 25.0)
self.assertEqual(results["MQSI2025_005"], 66.67)
self.assertEqual(results["MQSI2025_014"], 75.0)
self.assertEqual(results["MQSI2025_035"], 50.0)
```

- [ ] **Step 6: Verify GREEN**

```powershell
python -B -m unittest tests.test_four_indicator_sql -v
python -B -m unittest tests.test_sqlgen -v
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add scripts/init_demo_hospital_db.sql core-rules-wiki/sql-specs core-rules-wiki/hospital-mappings/hospital_001 tests/test_four_indicator_sql.py tests/test_sqlgen.py
git commit -m "feat: add four indicator demo SQL contracts"
```

---

### Task 6: SQL Generation from MySQL and `no_sample`

**Files:**
- Modify: `app/sqlgen/agent.py`
- Modify: `app/sqlgen/runner.py`
- Modify: `tests/test_sqlgen.py`
- Modify: `tests/test_rule_repository.py`

**Interfaces:**
- `SQLGenerationAgent.__init__` gains `rule_repository: RuleRepository | None = None`.
- Repository-backed generation uses `standard_sql`, `effective_params`, and `get_field_mapping`.
- Trial-run output adds `no_sample: bool` without removing `result_value`.

- [ ] **Step 1: Write failing repository-backed generation tests**

Provide a fake repository whose template has a unique marker not present in Wiki. Assert the generated SQL contains the marker and uses effective threshold 20. Add a runner test whose row is `{"index_value": 0, "sample_count": 0}` and assert `result_value == 0.0` and `no_sample is True`.

- [ ] **Step 2: Run test to verify RED**

Run: `python -B -m unittest tests.test_sqlgen -v`

Expected: FAIL because the agent still reads files and runner omits `no_sample`.

- [ ] **Step 3: Implement repository-backed loading**

```python
if self.rule_repository is not None:
    mapping = self.rule_repository.get_field_mapping(rule_id, hospital_id)
    template_str = str(effective_rule["standard_sql"])
    params = dict(effective_rule.get("effective_params") or {})
else:
    mapping = load_hospital_mapping(self.kb_root, hospital_id, rule_id)
    spec = load_rule_sql_spec(self.kb_root, rule_id)
    template_str = load_template(self.kb_root, rule_id,
                                 mapping.get("dialect", "mysql"))
    params = _extract_params(effective_rule, spec)
```

Keep the fallback branch only for isolated legacy tests. Production construction must always provide the repository.

- [ ] **Step 4: Implement `no_sample`**

Read `sample_count` from the first result row. Return `no_sample=True` when it is zero; keep `result_value=0.0` for API compatibility. If the row omits `sample_count`, return `no_sample=False` to preserve old connectors.

- [ ] **Step 5: Verify GREEN**

```powershell
python -B -m unittest tests.test_sqlgen -v
python -B -m unittest tests.test_four_indicator_sql -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/sqlgen/agent.py app/sqlgen/runner.py tests/test_sqlgen.py tests/test_rule_repository.py
git commit -m "feat: generate indicator SQL from MySQL rules"
```

---

### Task 7: MySQL Change Approval and Version Restore

**Files:**
- Modify: `app/rules/repository.py`
- Modify: `app/api/main.py`
- Modify: `tests/test_rule_repository.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- Pending changes are version rows with `approval_status="pending"`.
- Approval updates the current projection and version row in one transaction.
- Restore copies an old snapshot into a new version; history remains immutable.

- [ ] **Step 1: Write failing lifecycle tests**

Submit a 25-minute 005 change, approve it, then restore version 1. Assert pending status, active versions 2 then 3, effective threshold 25 then 20, three version rows, and no new Markdown file.

```python
self.assertEqual(pending["approval_status"], "pending")
self.assertEqual(approved["active_version"], 2)
self.assertEqual(effective_after_approval["effective_params"]
                 ["arrive_minutes_threshold"], 25)
self.assertEqual(restored["active_version"], 3)
self.assertEqual(effective_after_restore["effective_params"]
                 ["arrive_minutes_threshold"], 20)
```

- [ ] **Step 2: Run test to verify RED**

Run: `python -B -m unittest tests.test_rule_repository.MySQLRuleVersionTest -v`

Expected: FAIL because write methods are absent.

- [ ] **Step 3: Implement transactional writes**

- `submit_change_request` allocates `MAX(version)+1`, validates the requested numeric threshold, and inserts a pending snapshot.
- `approve_change_request` locks the pending row, upserts current projection, and records approver/time.
- `list_versions` returns active version and immutable snapshots.
- `restore_version` copies the selected snapshot into a new approved version and updates current projection.
- Missing, rejected, or cross-hospital changes raise a domain error translated to HTTP 400 or 404.

- [ ] **Step 4: Switch review API writes to the repository factory**

Replace direct `KnowledgeBaseTools` writes in create, approve, versions, and restore routes. Leave Wiki zip merge/export routes unchanged because they are outside rule-write scope.

- [ ] **Step 5: Verify GREEN**

```powershell
python -B -m unittest tests.test_rule_repository.MySQLRuleVersionTest -v
python -B -m unittest tests.test_api.ApiTest.test_review_api_creates_and_approves_hospital_change_request -v
python -B -m unittest tests.test_api.ApiTest.test_review_api_lists_and_restores_hospital_override_versions -v
```

Expected: PASS using an injected SQLite repository.

- [ ] **Step 6: Commit**

```powershell
git add app/rules/repository.py app/api/main.py tests/test_rule_repository.py tests/test_api.py
git commit -m "feat: persist hospital caliber versions in MySQL"
```

---

### Task 8: Chat, Trace, Import API, Docs, and Acceptance

**Files:**
- Modify: `app/agent/graph.py`
- Modify: `app/api/main.py`
- Modify: `app/observability/workflow_nodes.py`
- Modify: `tests/test_agent_workflow.py`
- Modify: `tests/test_api.py`
- Modify: `README.md`

**Interfaces:**
- Production chat and SQL routes use `create_rule_repository(create_runtime_engine(), DEFAULT_KB_ROOT)`.
- `POST /api/rules/import-four` is admin-only.
- Effective-rule Trace exposes `rule_source`, `national_version`, `hospital_version`, and `overridden_fields`.

- [ ] **Step 1: Write failing API and Trace tests**

Assert a repository fake is used by `/api/chat`, `/api/sql/generate`, and `/api/kb/rules/{rule_id}/effective`. Assert Trace output contains:

```python
self.assertEqual(node["output_data"]["rule_source"], "mysql")
self.assertEqual(node["output_data"]["national_version"], "2025")
self.assertEqual(node["output_data"]["hospital_version"], 1)
self.assertEqual(node["output_data"]["overridden_fields"],
                 ["arrive_minutes_threshold"])
```

Add import endpoint tests for four successful codes and 401 without admin token.

- [ ] **Step 2: Run tests to verify RED**

```powershell
python -B -m unittest tests.test_agent_workflow -v
python -B -m unittest tests.test_api -v
```

Expected: FAIL on missing wiring, Trace fields, and import route.

- [ ] **Step 3: Wire production repository and import endpoint**

Construct the repository at request boundaries and pass it to `SQLGenerationAgent`. Keep direct `KnowledgeBaseTools` only for Wiki import/export/merge. Add:

```python
@app.post("/api/rules/import-four")
def import_four_rules(
    authorization: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(authorization)
    return import_four_indicator_rules(
        create_runtime_engine(), DEFAULT_KB_ROOT, "hospital_001"
    )
```

Append source/version data to the existing effective-rule Trace node; do not add duplicate nodes.

- [ ] **Step 4: Update README**

Document exact initialization order:

```powershell
mysql -uroot -p123456 < scripts\init_runtime_db.sql
mysql -uroot -p123456 < scripts\init_demo_hospital_db.sql
python -B scripts\import_four_indicator_rules.py
python -B -m uvicorn app.api.main:app --host 127.0.0.1 --port 8765 --reload
```

Document expected values and explain `mysql` versus `wiki_fallback`.

- [ ] **Step 5: Run the full automated suite**

Run: `python -B -m unittest discover -s tests -v`

Expected: all original 87 tests plus new tests PASS with zero failures.

- [ ] **Step 6: Run real MySQL and DBHub acceptance**

Initialize both databases, run the importer twice, and confirm no duplicate rules or versions. With DBHub at `127.0.0.1:8080`, call `/api/sql/generate` with `trial_run=true` for each code and July 2026. Expected values are `25.00`, `66.67`, `75.00`, and `50.00`.

Call the effective-rule endpoint and verify `rule_source=mysql`. Temporarily set one standard row to `status=0`, verify `wiki_fallback`, then restore `status=1`.

- [ ] **Step 7: Commit**

```powershell
git add app/agent/graph.py app/api/main.py app/observability/workflow_nodes.py tests/test_agent_workflow.py tests/test_api.py README.md
git commit -m "feat: complete four indicator MySQL rule workflow"
```

---

## Completion Gate

- Every new behavior has a test observed failing before implementation.
- `git diff --check` reports no errors.
- `python -B -m unittest discover -s tests -v` passes.
- The importer is idempotent against local MySQL.
- All four real DBHub trial runs return the specified values.
- Normal reads show `rule_source=mysql`; a deliberately disabled rule shows `wiki_fallback`.
- Git status contains no accidentally staged runtime files, `tmp/`, or generated pending review Markdown.
