# 数据库与元数据工作台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将“DBHub MCP 测试”弹窗升级为可重新打开、可查看最近同步与影响范围的正式数据库与元数据工作台。

**Architecture:** 后端新增只读概览查询模块，从最新元数据快照和同步日志组装稳定响应；前端新增独立页面路由、样式和脚本，继续复用现有 DBHub 数据源查询与元数据同步接口。业务页面只展示中文业务状态，连接地址和 MCP 工具收进折叠详情。

**Tech Stack:** Python 3、FastAPI、SQLAlchemy、原生 JavaScript/CSS、`unittest`、Playwright 浏览器验收。

## Global Constraints

- 不引入新的前端框架或运行依赖。
- 业务页面不得展示数据库密码、患者记录或绑定后的 SQL。
- 元数据同步仍只读取 `INFORMATION_SCHEMA.TABLES` 与 `INFORMATION_SCHEMA.COLUMNS`。
- `wiki_agent_runtime` 只作为系统管理库展示，不得成为医院业务元数据的默认同步目标。
- 用户可见文案使用中文；DBHub、MCP 和工具名只出现在折叠的连接详情中。
- 所有新增行为必须先写失败测试，再写最小实现。

---

### Task 1: 最近同步概览查询与 API

**Files:**
- Create: `app/metadata/overview.py`
- Modify: `app/api/main.py`
- Create: `tests/test_metadata_overview.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- Consumes: `find_affected_rules(kb_root, hospital_id, changes)` 和运行库中的 `med_metadata_snapshot`、`med_metadata_sync_log`。
- Produces: `load_metadata_overview(runtime_engine, kb_root, hospital_id, db_name) -> dict[str, Any]`。
- Produces: `GET /api/metadata/overview?hospital_id=...&db_name=...`。

- [ ] **Step 1: 为无同步记录编写失败测试**

```python
def test_overview_returns_empty_business_state_without_snapshot(self) -> None:
    result = load_metadata_overview(
        _metadata_runtime_engine(), Path("core-rules-wiki"),
        "hospital_001", "hospital_demo_data",
    )
    self.assertFalse(result["has_snapshot"])
    self.assertEqual(result["table_count"], 0)
    self.assertEqual(result["column_count"], 0)
    self.assertEqual(result["changes"], [])
    self.assertEqual(result["affected_rules"], [])
```

- [ ] **Step 2: 运行测试并确认因模块不存在而失败**

Run: `python -m unittest tests.test_metadata_overview.MetadataOverviewTest.test_overview_returns_empty_business_state_without_snapshot -v`

Expected: FAIL，提示 `app.metadata.overview` 不存在。

- [ ] **Step 3: 实现最小空状态和最新快照查询**

```python
def empty_metadata_overview(hospital_id: str, db_name: str) -> dict[str, Any]:
    return {
        "hospital_id": hospital_id, "db_name": db_name,
        "has_snapshot": False, "metadata_source": None,
        "batch_id": None, "synced_at": None,
        "table_count": 0, "column_count": 0,
        "changes": [], "affected_rules": [],
    }

def load_metadata_overview(runtime_engine, kb_root, hospital_id, db_name):
    order_column = "rowid" if runtime_engine.dialect.name == "sqlite" else "id"
    with runtime_engine.connect() as conn:
        row = conn.execute(text(f"""
            SELECT metadata_source, sync_batch_id, snapshot_json, created_at
            FROM med_metadata_snapshot
            WHERE hospital_id=:h AND db_name=:d
            ORDER BY {order_column} DESC LIMIT 1
        """), {"h": hospital_id, "d": db_name}).mappings().first()
        if row is None:
            return empty_metadata_overview(hospital_id, db_name)
        changes = [dict(item) for item in conn.execute(text("""
            SELECT table_name, field_name, change_type, change_desc
            FROM med_metadata_sync_log
            WHERE hospital_id=:h AND db_name=:d AND sync_batch_id=:b
              AND change_type <> 'full_sync'
            ORDER BY table_name, field_name, change_type
        """), {"h": hospital_id, "d": db_name, "b": row["sync_batch_id"]}).mappings()]
    snapshot = json.loads(row["snapshot_json"]) if isinstance(row["snapshot_json"], str) else row["snapshot_json"]
    return {
        "hospital_id": hospital_id, "db_name": db_name,
        "has_snapshot": True, "metadata_source": row["metadata_source"],
        "batch_id": row["sync_batch_id"],
        "synced_at": row["created_at"].isoformat() if hasattr(row["created_at"], "isoformat") else str(row["created_at"]),
        "table_count": len(snapshot.get("tables", [])),
        "column_count": len(snapshot.get("columns", [])),
        "changes": changes,
        "affected_rules": find_affected_rules(Path(kb_root), hospital_id, changes),
    }
```

响应固定包含 `hospital_id`、`db_name`、`has_snapshot`、`metadata_source`、`batch_id`、`synced_at`、`table_count`、`column_count`、`changes`、`affected_rules`。

- [ ] **Step 4: 为最近批次和受影响指标编写失败测试**

```python
def test_overview_returns_latest_batch_changes_and_affected_rules(self) -> None:
    engine = _metadata_runtime_engine()
    with TemporaryDirectory() as tmp:
        root = Path(tmp)
        mapping = root / "hospital-mappings" / "hospital_001"
        mapping.mkdir(parents=True)
        (mapping / "MQSI2025_005.yaml").write_text(
            "main_table: consult_record\nfields:\n  arrival_time: consult_record.arrival_time\n",
            encoding="utf-8",
        )
        with engine.begin() as conn:
            conn.execute(text("INSERT INTO med_metadata_snapshot VALUES "
                              "('hospital_001','hospital_demo_data','dbhub','old',:j,'2026-07-12 09:00:00')"), {"j": '{"tables": [], "columns": []}'})
            conn.execute(text("INSERT INTO med_metadata_snapshot VALUES "
                              "('hospital_001','hospital_demo_data','dbhub','new',:j,'2026-07-13 09:00:00')"), {"j": '{"tables": [{"table_name": "consult_record"}], "columns": []}'})
            conn.execute(text("INSERT INTO med_metadata_sync_log VALUES "
                              "('hospital_001','hospital_demo_data','','','full_sync','完成','new','2026-07-13 09:00:00')"))
            conn.execute(text("INSERT INTO med_metadata_sync_log VALUES "
                              "('hospital_001','hospital_demo_data','consult_record','arrival_time','column_deleted','字段删除','new','2026-07-13 09:00:00')"))
        result = load_metadata_overview(engine, root, "hospital_001", "hospital_demo_data")
    self.assertEqual(result["batch_id"], "new")
    self.assertEqual(len(result["changes"]), 1)
    self.assertEqual(result["affected_rules"][0]["rule_id"], "MQSI2025_005")
```

- [ ] **Step 5: 运行测试确认最新批次行为尚未满足**

Run: `python -m unittest tests.test_metadata_overview -v`

Expected: FAIL，显示缺少最新批次变化或受影响指标。

- [ ] **Step 6: 完成变化读取与 API 路由**

```python
@app.get("/api/metadata/overview")
def metadata_overview(hospital_id: str, db_name: str = "hospital_demo_data") -> dict[str, Any]:
    from app.db.engine import create_runtime_engine
    from app.metadata.overview import load_metadata_overview
    return load_metadata_overview(
        create_runtime_engine(), DEFAULT_KB_ROOT, hospital_id, db_name
    )
```

API 测试替换运行库后请求接口，断言状态码 200、默认业务库为 `hospital_demo_data`，且返回结构与服务函数一致。

- [ ] **Step 7: 运行后端相关测试**

Run: `python -m unittest tests.test_metadata_overview tests.test_api.ApiTest.test_metadata_sync_dbhub_uses_mcp_client -v`

Expected: PASS。

- [ ] **Step 8: 提交并推送后端批次**

```powershell
git add app/metadata/overview.py app/api/main.py tests/test_metadata_overview.py tests/test_api.py
git commit -m "feat: 增加元数据同步概览接口"
git push
```

### Task 2: 正式元数据工作台页面

**Files:**
- Create: `web/metadata.css`
- Create: `web/metadata.js`
- Modify: `web/index.html`
- Modify: `web/workbench.js`
- Create: `tests/test_metadata_ui.py`
- Modify: `tests/test_workbench_ui.py`

**Interfaces:**
- Consumes: `GET /api/metadata/overview`、`GET /api/mcp/dbhub/sources`、`POST /api/metadata/sync`。
- Produces: `metadata` 工作台路由和 `activateMetadataPage()` 页面激活函数。

- [ ] **Step 1: 编写正式页面结构的失败测试**

```python
def test_page_exposes_metadata_workspace_instead_of_test_modal(self) -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
    for marker in (
        'data-workbench-route="metadata"', 'id="metadataPage"',
        'id="metadataSyncButton"', 'id="metadataOverview"',
        'id="metadataChanges"', 'id="metadataAffectedRules"',
        'id="metadataConnectionDetails"',
    ):
        self.assertIn(marker, html)
    self.assertNotIn("DBHub MCP 测试", html)
    self.assertNotIn('id="mcpModal"', html)
```

- [ ] **Step 2: 运行测试确认正式页面尚不存在**

Run: `python -m unittest tests.test_metadata_ui.MetadataUiTest.test_page_exposes_metadata_workspace_instead_of_test_modal -v`

Expected: FAIL，缺少 `metadataPage`。

- [ ] **Step 3: 添加页面骨架、样式文件和路由**

在 `index.html` 中把元数据入口移到“业务工作台”，新增 `metadataPage`，移除原 `mcpModal` 和对应内联变量/监听器；引入 `/static/metadata.css` 与 `/static/metadata.js`。

在 `workbench.js` 注册：

```javascript
var workbenchMetadataPage = document.getElementById("metadataPage");
var WORKBENCH_ROUTES = {
  assistant: {requiresAdmin: false},
  monitoring: {requiresAdmin: true},
  metadata: {requiresAdmin: false}
};
```

页面使用稳定布局：标题操作区、四项状态带、结构变化与受影响指标双栏、折叠连接详情。`@media (max-width: 760px)` 下改成单列，按钮和长数据库名称不得溢出。

- [ ] **Step 4: 编写加载、同步和错误状态的失败测试**

测试 `metadata.js` 包含以下职责函数与接口路径：

```python
for marker in (
    "function activateMetadataPage", "function loadMetadataOverview",
    "function loadMetadataSources", "function syncMetadataStructure",
    "function renderMetadataOverview", "function renderMetadataChanges",
    "function renderAffectedRules", '"/api/metadata/overview?hospital_id="',
    '"/api/metadata/sync"', "同步数据库结构", "重新检查连接",
):
    self.assertIn(marker, js)
```

- [ ] **Step 5: 运行测试确认脚本行为尚未实现**

Run: `python -m unittest tests.test_metadata_ui -v`

Expected: FAIL，缺少激活、加载或同步函数。

- [ ] **Step 6: 实现页面状态与同步交互**

`activateMetadataPage()` 并行加载概览和数据源；主数据源只从非 `wiki_agent_runtime` 数据源中选择，优先 `hospital_demo_data`。同步按钮请求期间禁用并显示“正在同步数据库结构”，成功后重新加载概览，失败时保留上一次成功内容。

结构变化按 `change_type` 映射为“新增表、删除表、新增字段、删除字段、字段类型变化、字段可空性变化”。连接失败提示用户检查 DBHub 服务和只读账号；原始工具名仅由 `<details id="metadataConnectionDetails">` 内部渲染。

- [ ] **Step 7: 运行前端静态测试与现有工作台回归**

Run: `python -m unittest tests.test_metadata_ui tests.test_workbench_ui tests.test_monitoring_ui -v`

Expected: PASS。

- [ ] **Step 8: 提交并推送前端批次**

```powershell
git add web/index.html web/workbench.js web/metadata.css web/metadata.js tests/test_metadata_ui.py tests/test_workbench_ui.py
git commit -m "feat: 增加数据库与元数据工作台"
git push
```

### Task 3: 使用说明与端到端验收

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: 已完成的元数据概览接口和工作台。
- Produces: 面向医院人员和实施人员的前端验证说明。

- [ ] **Step 1: 编写 README 内容检查的失败测试**

在 `tests/test_metadata_ui.py` 增加断言，要求 README 包含“数据库与元数据”“同步数据库结构”“不读取患者业务数据”“连接详情”。

- [ ] **Step 2: 运行测试确认说明缺失**

Run: `python -m unittest tests.test_metadata_ui.MetadataUiTest.test_readme_explains_metadata_workspace -v`

Expected: FAIL，缺少正式工作台说明。

- [ ] **Step 3: 更新 README**

说明从左侧进入工作台、选择医院业务库、点击同步、阅读结构变化与受影响指标；明确同步仅采集表字段结构，DBHub/MCP 信息只用于连接详情和实施排障。

- [ ] **Step 4: 运行完整自动化测试**

Run: `python -m unittest discover -s tests -v`

Expected: 全部 PASS。

- [ ] **Step 5: 启动服务并执行浏览器验收**

启动 FastAPI、DBHub 和现有前端，使用浏览器在 `1440x900`、`390x844` 视口验证：正式侧栏入口、首次加载、同步中、同步成功、无变化、连接详情和错误状态。检查控制台无 JavaScript 错误，页面无文字重叠和横向溢出。

- [ ] **Step 6: 提交并推送文档与验收批次**

```powershell
git add README.md tests/test_metadata_ui.py
git commit -m "docs: 补充元数据工作台使用说明"
git push
```
