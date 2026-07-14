# 指标明细预览与短期导出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在指标试运行结果中为分母、分子和未达标数量提供受权限控制的明细预览，并生成可审计、24 小时自动过期的三工作表 Excel 文件。

**Architecture:** 聚合试运行仍只查询并保存指标值与数量，同时把当次生效口径、字段映射、参数和统计区间保存为非患者级运行快照。用户首次查看明细时，系统根据运行快照生成一条确定性只读 SQL，将经数量核对的完整结果写入短期 `jsonl.gz` 快照；脱敏预览和 Excel 都读取这一份快照，避免二次查询造成数量不一致。医院本地账号、医院范围和权限码统一由后端校验，所有查看、导出、下载、拒绝和清理动作写入不含患者值的审计日志。

**Tech Stack:** Python 3.11、FastAPI、Pydantic v2、SQLAlchemy、MySQL、DBHub MCP、openpyxl、原生 JavaScript/CSS、pytest/unittest。

## Global Constraints

- 第一版只支持 `MQSI2025_001`、`MQSI2025_005`、`MQSI2025_014`、`MQSI2025_035` 四个已落地指标和单主表字段映射。
- 明细 SQL 只能是单条 `SELECT`，必须包含医院和半开统计区间限制，禁止 `SELECT *`，最多生成 20,000 条分母明细；超限时整体失败，不截断伪装成完整数据。
- 页面每页默认显示 50 条脱敏数据，`page_size` 只能取 20、50 或 100；完整患者标识只进入受保护短期快照和授权 Excel。
- 快照和 Excel 保存到 `runtime/exports/{hospital_id}/{run_id}/`，统一在创建后 24 小时过期；启动时、创建/列出导出时和调度器每小时执行清理。
- Excel 固定包含“统计范围”“达到要求”“未达到要求”三个工作表，并在表头写明指标、本院口径版本、统计区间、导出人和快照时间。
- `indicator_detail_view` 控制脱敏预览，`indicator_detail_export` 控制 Excel 创建和下载；公司账号和其他医院账号不得访问医院患者明细。
- 本地演示账号为 `user_001` / `123456` / `hospital_001`，首次登录强制修改密码；生产部署不得自动创建演示账号。
- 密码使用 PBKDF2-HMAC-SHA256 和随机盐保存，会话令牌数据库只保存 SHA-256 摘要，会话有效期 8 小时；连续 5 次密码错误锁定 15 分钟。
- 医院人员可见文案使用中文业务语言，不展示 SQL、表字段名、服务器绝对路径或原始异常堆栈。
- 任何患者字段值不得写入聊天记录、执行链路、审计详情、应用日志或 Git；`runtime/` 继续保持 Git 忽略。
- 每个任务按 TDD 完成，验证通过后只提交本任务相关文件，使用中文 Conventional Commit，并立即推送当前分支。

---

## File Map

### 新增后端模块

- `app/hospital_auth/schema.py`：本地医院账号、权限、会话和数据访问审计表及幂等迁移。
- `app/hospital_auth/models.py`：登录结果、医院身份和权限码类型。
- `app/hospital_auth/repository.py`：账号、会话、权限和审计的数据库访问。
- `app/hospital_auth/service.py`：密码散列、登录锁定、改密、令牌认证和退出登录。
- `app/api/hospital_auth.py`：医院登录、首次改密和退出登录 API。
- `app/indicator_details/models.py`：运行上下文、明细字段、快照摘要、分页和导出返回模型。
- `app/indicator_details/schema.py`：试运行日志扩展、短期快照和导出记录表。
- `app/indicator_details/repository.py`：运行、快照和导出记录访问。
- `app/indicator_details/sql_builder.py`：从结构化口径快照生成确定性明细 SQL。
- `app/indicator_details/snapshot.py`：数量核对、`jsonl.gz` 写入、读取、分页和脱敏。
- `app/indicator_details/exporter.py`：从短期快照生成三工作表 `.xlsx`。
- `app/indicator_details/service.py`：权限范围、快照创建/复用、导出和过期清理编排。
- `app/api/indicator_details.py`：明细创建、分页预览、导出列表、创建和下载 API。
- `scripts/seed_demo_hospital_user.py`：显式创建或重置本地演示账号，不在生产启动时自动执行。
- `web/indicator-details.js`：明细窗口、分页、二次确认、带令牌下载和过期状态。
- `web/indicator-details.css`：明细窗口和响应式表格样式。

### 修改现有文件

- `requirements.txt`：加入 `openpyxl>=3.1,<4.0`。
- `scripts/init_runtime_db.sql`：加入正式安装所需的新表和试运行日志字段。
- `app/db/migrations.py`、`scripts/migrate_runtime_schema.py`：接入两组幂等迁移。
- `app/api/main.py`：注册新路由，启动时初始化表和清理过期文件，并把清理回调交给现有调度器。
- `app/tasks/scheduler.py`：增加固定每小时一次的明细文件清理任务。
- `app/rules/calculation.py`：给结构化口径增加带中文标签和脱敏类别的 `detail_fields`。
- 四个 `core-rules-wiki/sql-specs/**/rule_sql_spec.yaml`：声明首批明细证据字段。
- 四个 `core-rules-wiki/hospital-mappings/hospital_001/*.yaml`：补齐患者/业务记录标识字段映射。
- `app/rules/importer.py`：把新增明细定义和字段映射导入 MySQL。
- `app/sqlgen/agent.py`、`app/sqlgen/runner.py`、`app/db/repositories.py`：试运行时保存数量与非患者级运行上下文。
- `app/sqlgen/explanation.py`：在聚合结果表增加“查看详情”操作令牌。
- `web/chat-markdown.js`、`web/index.html`：把安全令牌渲染成按钮，接入真实医院登录和明细窗口。
- `README.md`：补充迁移、演示账号初始化、前端验收、存储和清理说明。

---

### Task 1: 后端医院账号、会话与权限边界

**Files:**
- Create: `app/hospital_auth/__init__.py`
- Create: `app/hospital_auth/models.py`
- Create: `app/hospital_auth/schema.py`
- Create: `app/hospital_auth/repository.py`
- Create: `app/hospital_auth/service.py`
- Create: `app/api/hospital_auth.py`
- Create: `scripts/seed_demo_hospital_user.py`
- Modify: `scripts/init_runtime_db.sql`
- Modify: `app/db/migrations.py`
- Modify: `scripts/migrate_runtime_schema.py`
- Modify: `app/api/main.py`
- Test: `tests/test_hospital_auth.py`
- Test: `tests/test_hospital_auth_api.py`
- Test: `tests/test_runtime_migrations.py`

**Interfaces:**
- Produces: `HospitalPrincipal(user_id, account_id, hospital_id, permissions, must_change_password, session_id)`。
- Produces: `HospitalAuthService.login(account_id: str, password: str) -> LoginResult`。
- Produces: `HospitalAuthService.authenticate(token: str, required_permission: str | None = None) -> HospitalPrincipal`。
- Produces: `HospitalAuthService.change_password(principal: HospitalPrincipal, current_password: str, new_password: str) -> LoginResult`。
- Produces: `HospitalAuthService.logout(principal: HospitalPrincipal) -> None`。
- Produces: FastAPI dependency `require_hospital_principal(required_permission: str | None)`，供 Task 4 的明细接口复用。
- Produces: `DataAccessAudit` 写入接口，只接收元数据和数量，不接收明细行。

- [ ] **Step 1: 写账号、锁定、首登改密、会话过期和跨医院权限的失败测试**

```python
def test_demo_user_must_change_password_before_detail_access():
    service = make_auth_service()
    login = service.login("user_001", "123456")
    assert login.must_change_password is True
    with pytest.raises(PermissionError, match="请先修改初始密码"):
        service.authenticate(login.token, "indicator_detail_view")

def test_five_failed_logins_lock_account_for_fifteen_minutes():
    service, clock = make_auth_service_with_clock()
    for _ in range(5):
        with pytest.raises(PermissionError, match="账号或密码错误"):
            service.login("user_001", "wrong-password")
    with pytest.raises(PermissionError, match="账号已临时锁定"):
        service.login("user_001", "123456")
    clock.advance(minutes=15)
    assert service.login("user_001", "123456").account_id == "user_001"

def test_token_is_scoped_to_users_hospital_and_permission():
    principal = make_authenticated_principal(hospital_id="hospital_001")
    assert principal.can_access_hospital("hospital_001") is True
    assert principal.can_access_hospital("hospital_002") is False
    assert "indicator_detail_export" in principal.permissions
```

- [ ] **Step 2: 运行定向测试并确认因模块不存在而失败**

Run: `python -m pytest tests/test_hospital_auth.py tests/test_hospital_auth_api.py tests/test_runtime_migrations.py -q`

Expected: FAIL，错误包含 `ModuleNotFoundError: No module named 'app.hospital_auth'` 或新表不存在。

- [ ] **Step 3: 建立四张认证/审计表和幂等迁移**

`app/hospital_auth/schema.py` 使用 SQLAlchemy 定义：

```python
AUTH_TABLES = (
    "med_hospital_user",
    "med_hospital_user_permission",
    "med_hospital_session",
    "med_data_access_audit",
)

def ensure_hospital_auth_schema(engine: Engine) -> dict[str, list[str]]:
    before = set(inspect(engine).get_table_names())
    metadata.create_all(engine, checkfirst=True)
    after = set(inspect(engine).get_table_names())
    return {"created_tables": [name for name in AUTH_TABLES if name in after - before]}
```

字段固定如下：

- `med_hospital_user`: `user_id`、`account_id`、`hospital_id`、`password_hash`、`password_salt`、`password_iterations`、`must_change_password`、`status`、`failed_attempts`、`locked_until`、`created_at`、`updated_at`。
- `med_hospital_user_permission`: `user_id`、`permission_code`、`created_at`，唯一键为两列组合。
- `med_hospital_session`: `session_id`、`user_id`、`token_hash`、`expires_at`、`revoked_at`、`created_at`、`last_seen_at`。
- `med_data_access_audit`: `audit_id`、`user_id`、`hospital_id`、`rule_id`、`run_id`、`export_id`、`action`、`result`、`row_count`、`request_id`、`reason`、`created_at`。

同步把等价 MySQL DDL 写入 `scripts/init_runtime_db.sql`，并在 `app/db/migrations.py`、`scripts/migrate_runtime_schema.py` 和应用启动初始化中接入 `ensure_hospital_auth_schema`。

- [ ] **Step 4: 实现 PBKDF2、本地会话和权限服务**

`app/hospital_auth/service.py` 固定使用：

```python
PBKDF2_ITERATIONS = 310_000
SESSION_TTL = timedelta(hours=8)
LOCK_AFTER_FAILURES = 5
LOCK_DURATION = timedelta(minutes=15)

def hash_password(password: str, salt: bytes, iterations: int = PBKDF2_ITERATIONS) -> str:
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    return base64.b64encode(digest).decode("ascii")

def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("ascii")).hexdigest()
```

新密码要求至少 8 位且同时含字母和数字。登录成功返回原始随机令牌一次，数据库只存 `hash_token(token)`；强制改密期间只允许调用改密和退出接口。每次登录成功/失败、退出、权限拒绝均调用 `repository.insert_audit`，传入用户、医院、action、result、request_id 和错误码；`reason` 只存如 `AUTH_BAD_CREDENTIALS` 的错误码，不存密码或请求体。

- [ ] **Step 5: 实现医院认证 API 和显式演示账号脚本**

API 请求/响应固定为：

```python
class HospitalLoginRequest(BaseModel):
    account_id: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=256)

class ChangePasswordRequest(BaseModel):
    current_password: str
    new_password: str

@router.post("/login")
def login(body: HospitalLoginRequest) -> LoginResponse:
    return LoginResponse.model_validate(
        _create_hospital_auth_service().login(body.account_id, body.password)
    )

@router.post("/change-password")
def change_password(
    body: ChangePasswordRequest,
    principal: HospitalPrincipal = Depends(require_hospital_session),
) -> LoginResponse:
    return LoginResponse.model_validate(
        _create_hospital_auth_service().change_password(
            principal, body.current_password, body.new_password
        )
    )

@router.post("/logout", status_code=204)
def logout(
    principal: HospitalPrincipal = Depends(require_hospital_session),
) -> Response:
    _create_hospital_auth_service().logout(principal)
    return Response(status_code=204)
```

`scripts/seed_demo_hospital_user.py` 调用仓储创建/重置 `user_001`，授予 `indicator_detail_view` 和 `indicator_detail_export`，设置 `must_change_password=True`。脚本只在人工执行 `python scripts/seed_demo_hospital_user.py` 时写入账号。

- [ ] **Step 6: 运行认证与迁移测试**

Run: `python -m pytest tests/test_hospital_auth.py tests/test_hospital_auth_api.py tests/test_runtime_migrations.py -q`

Expected: PASS；API 测试覆盖 401 未登录、403 权限不足、403 首登未改密、200 改密、204 退出和过期令牌。

- [ ] **Step 7: 提交并推送认证批次**

```powershell
git add app/hospital_auth app/api/hospital_auth.py app/api/main.py app/db/migrations.py scripts/init_runtime_db.sql scripts/migrate_runtime_schema.py scripts/seed_demo_hospital_user.py tests/test_hospital_auth.py tests/test_hospital_auth_api.py tests/test_runtime_migrations.py
git commit -m "feat(auth): 增加医院账号与明细访问权限"
git push
```

---

### Task 2: 结构化明细字段与试运行口径快照

**Files:**
- Create: `app/indicator_details/__init__.py`
- Create: `app/indicator_details/models.py`
- Create: `app/indicator_details/schema.py`
- Modify: `app/rules/calculation.py`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/rule_sql_spec.yaml`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_005_急会诊及时到位率/rule_sql_spec.yaml`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_014_急危重症患者抢救成功率/rule_sql_spec.yaml`
- Modify: `core-rules-wiki/sql-specs/MQSI2025_035_术中自体血回输率/rule_sql_spec.yaml`
- Modify: `core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_001.yaml`
- Modify: `core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_005.yaml`
- Modify: `core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_014.yaml`
- Modify: `core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_035.yaml`
- Modify: `app/rules/importer.py`
- Modify: `app/sqlgen/agent.py`
- Modify: `app/sqlgen/runner.py`
- Modify: `app/db/repositories.py`
- Modify: `scripts/init_runtime_db.sql`
- Modify: `app/db/migrations.py`
- Modify: `scripts/migrate_runtime_schema.py`
- Test: `tests/test_calculation_definition.py`
- Test: `tests/test_rule_importer.py`
- Test: `tests/test_sqlgen.py`
- Test: `tests/test_indicator_detail_schema.py`

**Interfaces:**
- Consumes: Task 1 的运行时引擎迁移入口。
- Produces: `DetailFieldDefinition(field, label, sensitivity)`。
- Produces: `RunContext`，字段为 `schema_version`、`rule_name`、`effective_level`、`national_version`、`hospital_version`、`calculation_definition`、`field_mapping`、`params`、`stat_start`、`stat_end`、`db_source`、`main_table`。
- Produces: `med_sql_run_log.numerator_count`、`denominator_count`、`run_context_json`，供 Task 3 查询。

- [ ] **Step 1: 写明细定义和运行快照失败测试**

```python
def test_detail_fields_accept_business_and_derived_fields():
    definition = parse_calculation_definition(urgent_consult_definition())
    assert [item.field for item in definition.detail_fields] == [
        "patient_id", "dept_id", "consult_type", "request_time", "arrive_time", "arrive_minutes"
    ]
    assert definition.detail_fields[0].sensitivity == "patient_id"

def test_trial_run_persists_counts_and_non_patient_run_context():
    result = run_successful_urgent_consult_trial()
    row = load_sql_run(result["run_id"])
    assert row["numerator_count"] == 488
    assert row["denominator_count"] == 576
    assert row["run_context_json"]["params"]["arrive_minutes_threshold"] == 20
    assert "rows" not in row["run_context_json"]
    assert "patient_values" not in row["run_context_json"]
```

- [ ] **Step 2: 运行定向测试并确认失败**

Run: `python -m pytest tests/test_calculation_definition.py tests/test_rule_importer.py tests/test_sqlgen.py tests/test_indicator_detail_schema.py -q`

Expected: FAIL，提示 `detail_fields` 为未允许字段，或 `med_sql_run_log` 缺少新列。

- [ ] **Step 3: 扩展结构化口径模型并配置四个指标**

`app/rules/calculation.py` 新增：

```python
class DetailFieldDefinition(_StrictModel):
    field: str
    label: str
    sensitivity: Literal["none", "patient_id", "name", "phone", "id_card"] = "none"

class CalculationDefinition(_StrictModel):
    schema_version: Literal[1]
    scope: ScopeDefinition
    derived_fields: dict[str, DerivedFieldDefinition] = Field(default_factory=dict)
    denominator: CalculationBranchDefinition
    numerator: CalculationBranchDefinition
    result: ResultDefinition
    detail_fields: list[DetailFieldDefinition] = Field(default_factory=list)
```

校验要求：字段必须存在于业务字段或派生字段；字段名不得重复；至少有一个能唯一说明统计对象的业务记录标识。`collect_business_dependencies()` 不把 `detail_fields` 加入聚合 SQL 必需字段，避免旧医院缺少明细映射时影响原有指标计算；明细创建时再严格检查全部映射。

四个指标固定字段：

| 指标 | `detail_fields` |
| --- | --- |
| `MQSI2025_001` | `admission_id` 入院流水号、`admit_time` 入院时间、`transfer_time` 转科时间、`from_dept_id` 转出科室、`to_dept_id` 转入科室、`transfer_minutes` 转科耗时 |
| `MQSI2025_005` | `patient_id` 患者标识、`dept_id` 科室、`consult_type` 会诊类型、`request_time` 申请时间、`arrive_time` 到位时间、`arrive_minutes` 到位耗时 |
| `MQSI2025_014` | `patient_id` 患者标识、`rescue_id` 抢救事件号、`dept_id` 科室、`rescue_time` 抢救时间、`severity_level` 严重程度、`rescue_result` 抢救结果 |
| `MQSI2025_035` | `patient_id` 患者标识、`surgery_id` 手术号、`dept_id` 科室、`surgery_time` 手术时间、`intraoperative_transfusion_flag` 术中输血、`autologous_reinfusion_flag` 自体血回输 |

患者标识、入院流水号、抢救事件号和手术号使用 `patient_id` 脱敏类别；其他字段使用 `none`。同步补齐四份医院映射 YAML 和 `app/rules/importer.py` 的种子映射，其中急会诊新增 `patient_id -> consult_record.patient_id`。

- [ ] **Step 4: 扩展试运行日志并保存当次口径上下文**

`app/indicator_details/schema.py` 为旧安装幂等增加：

```python
SQL_RUN_COLUMNS = {
    "numerator_count": "BIGINT NULL",
    "denominator_count": "BIGINT NULL",
    "run_context_json": "JSON NULL",
}
```

`run_sql_trial` 增加参数 `run_context: dict[str, Any] | None = None`，并把数量和 JSON 传给 `insert_sql_run_log`。`SQLGenerationAgent.generate` 在调用前构造：

```python
run_context = {
    "schema_version": 1,
    "rule_name": effective_rule.get("rule_name"),
    "effective_level": effective_rule.get("effective_level"),
    "national_version": effective_rule.get("national_version"),
    "hospital_version": effective_rule.get("hospital_version"),
    "calculation_definition": result["calculation_definition"],
    "field_mapping": mapping,
    "params": {"hospital_id": hospital_id, "start_time": stat_start_time, "end_time": stat_end_time, **params},
    "stat_start": stat_start_time,
    "stat_end": stat_end_time,
    "db_source": self.business_db.source_id,
    "main_table": mapping.get("main_table"),
}
```

实际实现从已确认映射中计算唯一 `main_table`，不信任客户端传值。`insert_sql_run_log` 使用 SQLAlchemy 参数绑定序列化 JSON，不拼接字符串。

- [ ] **Step 5: 运行四指标导入、口径与试运行回归测试**

Run: `python -m pytest tests/test_calculation_definition.py tests/test_rule_importer.py tests/test_sqlgen.py tests/test_four_indicator_sql.py tests/test_indicator_detail_schema.py -q`

Expected: PASS；原四指标聚合结果不变，运行日志能还原当次 20 分钟本院口径且没有患者行。

- [ ] **Step 6: 提交并推送运行快照批次**

```powershell
git add app/indicator_details app/rules/calculation.py app/rules/importer.py app/sqlgen/agent.py app/sqlgen/runner.py app/db/repositories.py app/db/migrations.py scripts/init_runtime_db.sql scripts/migrate_runtime_schema.py core-rules-wiki/sql-specs core-rules-wiki/hospital-mappings/hospital_001 tests/test_calculation_definition.py tests/test_rule_importer.py tests/test_sqlgen.py tests/test_indicator_detail_schema.py
git commit -m "feat(details): 保存指标试运行口径快照"
git push
```

---

### Task 3: 确定性明细 SQL、数量核对与短期快照

**Files:**
- Create: `app/indicator_details/repository.py`
- Create: `app/indicator_details/sql_builder.py`
- Create: `app/indicator_details/snapshot.py`
- Modify: `app/indicator_details/schema.py`
- Modify: `scripts/init_runtime_db.sql`
- Test: `tests/test_indicator_detail_sql.py`
- Test: `tests/test_indicator_detail_snapshot.py`

**Interfaces:**
- Consumes: `RunContext` 和 `med_sql_run_log` 的分子/分母数量。
- Produces: `build_detail_query(context: RunContext, row_limit: int = 20_001) -> DetailQuery(sql: str, params: dict, columns: list[DetailColumn])`。
- Produces: `DetailSnapshotStore.create(run: SqlRunRecord, actor: HospitalPrincipal) -> DetailSnapshotSummary`。
- Produces: `DetailSnapshotStore.read_page(snapshot_id, group, page, page_size) -> DetailPage`。

- [ ] **Step 1: 写四指标明细 SQL 与计数语义失败测试**

```python
@pytest.mark.parametrize("rule_id", ["MQSI2025_001", "MQSI2025_005", "MQSI2025_014", "MQSI2025_035"])
def test_detail_sql_is_single_select_scoped_and_has_no_star(rule_id):
    query = build_detail_query(load_run_context(rule_id))
    normalized = " ".join(query.sql.upper().split())
    assert normalized.startswith("SELECT")
    assert "SELECT *" not in normalized
    assert ":HOSPITAL_ID" in normalized
    assert ":START_TIME" in normalized
    assert ":END_TIME" in normalized
    assert "LIMIT 20001" in normalized

def test_count_distinct_groups_by_subject_and_uses_any_matching_evidence():
    rows = execute_detail_query_for_autologous_reinfusion()
    assert len(rows) == 3
    assert row_for("patient_001", rows)["__meets_numerator"] == 1
    assert row_for("patient_001", rows)["__evidence_row_count"] == 2
```

- [ ] **Step 2: 运行明细 SQL 测试并确认失败**

Run: `python -m pytest tests/test_indicator_detail_sql.py tests/test_indicator_detail_snapshot.py -q`

Expected: FAIL，提示 `build_detail_query` 或 `DetailSnapshotStore` 尚不存在。

- [ ] **Step 3: 实现从结构化口径渲染条件和证据字段**

`sql_builder.py` 只接受 Task 2 保存并已通过 Pydantic 校验的 `RunContext`。标识符必须匹配 `[A-Za-z_][A-Za-z0-9_]*`，所有值使用命名参数。运算映射固定为：

```python
OPERATORS = {
    "equals": "{field} = :{parameter}",
    "not_equals": "{field} <> :{parameter}",
    "half_open_range": "{field} >= :{start} AND {field} < :{end}",
    "inclusive_range": "{field} BETWEEN :{start} AND :{end}",
    "is_not_null": "{field} IS NOT NULL",
}
```

`timestamp_diff_minutes` 渲染为 `TIMESTAMPDIFF(MINUTE, start_column, end_column)`。查询 `WHERE` 为 `scope AND denominator`，`__meets_numerator` 为只包含 numerator 新增条件的 `CASE WHEN`。

对于 `count_rows`，每个业务行是一条明细。对于 `count_distinct`，按 aggregate field 分组，`MAX(CASE WHEN numerator THEN 1 ELSE 0 END)` 决定该对象是否进入分子，并返回 `COUNT(*) AS __evidence_row_count`；普通证据字段用稳定的 `MIN(mapped_column)` 作为代表值。所有查询最终调用现有 `BusinessDBClient.execute_select()`，继续执行只读校验。

- [ ] **Step 4: 建立快照表并实现原子文件写入**

`med_indicator_detail_snapshot` 字段：`snapshot_id`、`run_id`、`hospital_id`、`rule_id`、`relative_path`、`file_sha256`、`denominator_count`、`numerator_count`、`unmatched_count`、`column_schema_json`、`status`、`created_by`、`created_at`、`expires_at`、`error_message`；`run_id` 唯一，状态只允许 `creating`、`ready`、`expired`、`failed`。

写入流程固定为：

```python
rows = business_db.execute_select(bound_detail_sql).rows
if len(rows) > 20_000:
    raise DetailLimitExceeded("明细超过20,000条，请缩小统计区间后重新试运行")
numerator = sum(int(row["__meets_numerator"]) for row in rows)
denominator = len(rows)
if (numerator, denominator) != (run.numerator_count, run.denominator_count):
    raise DetailCountMismatch("业务数据已经变化，请重新试运行后查看明细")
write_gzip_json_lines(temp_path, rows)
os.replace(temp_path, final_path)
```

文件路径由服务端 `Path(runtime_root) / "exports" / hospital_id / run_id / f"{snapshot_id}.jsonl.gz"` 生成并校验 `resolve()` 后仍位于 `runtime/exports` 下。JSONL 第一行是列定义和运行摘要，后续每行是一条完整明细；数据库只保存相对路径和 SHA-256。

- [ ] **Step 5: 实现脱敏分页和审计安全检查**

```python
def mask_value(value: Any, sensitivity: str) -> Any:
    if value is None or sensitivity == "none":
        return value
    text = str(value)
    if sensitivity == "name":
        return text[:1] + "*" * max(1, len(text) - 1)
    if sensitivity in {"phone", "id_card"}:
        return "*" * max(0, len(text) - 4) + text[-4:]
    return text[:2] + "*" * max(3, len(text) - 4) + text[-2:]
```

分页读取先验证文件哈希，再按 `group` 过滤：`denominator` 返回全部，`numerator` 返回 `__meets_numerator=1`，`unmatched` 返回 `0`。API 返回中移除所有 `__` 内部列，只增加中文“是否达到要求”。测试断言日志和审计参数不包含 `patient_001` 等样例值。

- [ ] **Step 6: 运行 SQL、超限、数量漂移、脱敏和路径穿越测试**

Run: `python -m pytest tests/test_indicator_detail_sql.py tests/test_indicator_detail_snapshot.py tests/test_sqlgen.py -q`

Expected: PASS；覆盖 576/488/88、一条分母查询、20,001 超限、运行后数据变化阻断、快照复用、哈希不一致、过期文件和非法相对路径。

- [ ] **Step 7: 提交并推送明细快照批次**

```powershell
git add app/indicator_details/repository.py app/indicator_details/sql_builder.py app/indicator_details/snapshot.py app/indicator_details/schema.py scripts/init_runtime_db.sql tests/test_indicator_detail_sql.py tests/test_indicator_detail_snapshot.py
git commit -m "feat(details): 增加指标明细快照与数量核对"
git push
```

---

### Task 4: Excel、受保护 API、审计与自动清理

**Files:**
- Create: `app/indicator_details/exporter.py`
- Create: `app/indicator_details/service.py`
- Create: `app/api/indicator_details.py`
- Modify: `app/indicator_details/repository.py`
- Modify: `app/indicator_details/schema.py`
- Modify: `app/api/main.py`
- Modify: `app/tasks/scheduler.py`
- Modify: `requirements.txt`
- Modify: `scripts/init_runtime_db.sql`
- Test: `tests/test_indicator_detail_export.py`
- Test: `tests/test_indicator_detail_api.py`
- Test: `tests/test_monitoring_scheduler.py`

**Interfaces:**
- Consumes: Task 1 的 `HospitalPrincipal`，Task 3 的快照。
- Produces: `IndicatorDetailService.ensure_snapshot(principal, run_id) -> DetailSnapshotSummary`。
- Produces: `IndicatorDetailService.get_page(principal, run_id, group, page, page_size) -> DetailPage`。
- Produces: `IndicatorDetailService.create_export(principal, run_id, confirmed) -> ExportSummary`。
- Produces: `IndicatorDetailService.resolve_download(principal, export_id) -> DownloadFile`。
- Produces: `IndicatorDetailService.cleanup_expired(now: datetime | None = None) -> CleanupResult`。

- [ ] **Step 1: 写 Excel、权限、医院隔离、二次确认和过期下载失败测试**

```python
def test_excel_contains_three_counted_sheets_and_run_metadata(tmp_path):
    export = create_urgent_consult_export(tmp_path, denominator=576, numerator=488)
    workbook = load_workbook(export.path, read_only=True)
    assert workbook.sheetnames == ["统计范围_576", "达到要求_488", "未达到要求_88"]
    assert workbook["统计范围_576"]["A1"].value == "指标名称"
    assert workbook["统计范围_576"]["B1"].value == "急会诊及时到位率"

def test_export_requires_explicit_confirmation_and_export_permission(client):
    assert client.post("/api/sql-runs/RUN_1/exports", json={"confirmed": False}, headers=view_only_headers).status_code == 403
    assert client.post("/api/sql-runs/RUN_1/exports", json={"confirmed": True}, headers=export_headers).status_code == 201

def test_user_cannot_download_other_hospitals_export(client):
    response = client.get("/api/indicator-exports/EXP_HOSPITAL_002/download", headers=hospital_001_headers)
    assert response.status_code == 404
```

- [ ] **Step 2: 运行导出与 API 测试并确认失败**

Run: `python -m pytest tests/test_indicator_detail_export.py tests/test_indicator_detail_api.py tests/test_monitoring_scheduler.py -q`

Expected: FAIL，提示导出器、路由或清理任务不存在。

- [ ] **Step 3: 用 openpyxl 生成医生可核对的三工作表文件**

在 `requirements.txt` 加入 `openpyxl>=3.1,<4.0`。每个工作表第 1 至 8 行固定写：指标名称、医院、口径来源与版本、统计区间、快照时间、导出人、分组说明、总条数；第 10 行写中文列名，第 11 行起写明细。

```python
GROUPS = (
    ("denominator", "统计范围"),
    ("numerator", "达到要求"),
    ("unmatched", "未达到要求"),
)

def safe_excel_value(value: Any) -> Any:
    if isinstance(value, str) and value.startswith(("=", "+", "-", "@")):
        return "'" + value
    return value
```

Excel 不包含 SQL、宏和外部链接。患者标识在 Excel 中保留完整授权值；文件名使用 `指标编码_统计开始日期_统计结束日期_{export_id}.xlsx`，并清除 Windows 非法文件名字符。

- [ ] **Step 4: 建立导出表并实现服务层权限和审计**

`med_indicator_export` 字段：`export_id`、`snapshot_id`、`run_id`、`hospital_id`、`rule_id`、`relative_path`、`file_name`、`file_sha256`、`status`、`row_count`、`created_by`、`created_at`、`expires_at`、`download_count`、`last_downloaded_at`、`error_message`。

服务层每个入口都按顺序校验：令牌有效、已完成首登改密、权限码、医院范围、运行/文件状态、过期时间。其他医院资源统一返回 404，避免暴露资源存在性。审计 action 固定为 `DETAIL_PREVIEW`、`DETAIL_EXPORT_CREATE`、`DETAIL_EXPORT_DOWNLOAD`、`ACCESS_DENIED`、`DETAIL_COUNT_MISMATCH`、`DETAIL_FILE_EXPIRED`，审计只记数量和错误码。

- [ ] **Step 5: 实现 API 和带权限的文件流下载**

```text
POST /api/sql-runs/{run_id}/details
GET  /api/sql-runs/{run_id}/details/{group}?page=1&page_size=50
POST /api/sql-runs/{run_id}/exports            body: {"confirmed": true}
GET  /api/indicator-exports
GET  /api/indicator-exports/{export_id}/download
```

创建快照成功返回 201，复用未过期快照返回 200。下载使用 `FileResponse`，设置 `.xlsx` MIME、`Content-Disposition` 和 `Cache-Control: no-store`，下载前再次校验 SHA-256、令牌、医院和有效期。

- [ ] **Step 6: 接入启动、按需和每小时清理**

`MonitoringScheduler.__init__` 增加可选 `cleanup_callback: Callable[[], Any] | None`。`start()` 在启动 backend 前注册：

```python
self.backend.add_job(
    self.cleanup_callback,
    "interval",
    hours=1,
    id="cleanup:indicator-exports",
    replace_existing=True,
    coalesce=True,
    max_instances=1,
)
```

`app/api/main.py` 启动时先执行一次 `cleanup_expired()`，再把同一回调交给调度器。`ensure_snapshot`、`create_export` 和 `list_exports` 入口也先执行轻量清理。删除成功后快照/导出状态改为 `expired`；删除失败保留原状态、写错误码，下一轮重试。

- [ ] **Step 7: 运行导出、API、调度器和全后端回归测试**

Run: `python -m pytest tests/test_indicator_detail_export.py tests/test_indicator_detail_api.py tests/test_monitoring_scheduler.py tests/test_api.py -q`

Expected: PASS；下载响应可被 openpyxl 打开，24 小时边界、哈希不一致、跨院、无权限和清理重试均被覆盖。

- [ ] **Step 8: 提交并推送导出服务批次**

```powershell
git add app/indicator_details app/api/indicator_details.py app/api/main.py app/tasks/scheduler.py requirements.txt scripts/init_runtime_db.sql tests/test_indicator_detail_export.py tests/test_indicator_detail_api.py tests/test_monitoring_scheduler.py tests/test_api.py
git commit -m "feat(details): 增加受控明细导出与自动清理"
git push
```

---

### Task 5: 前端真实登录、查看详情窗口与下载交互

**Files:**
- Create: `web/indicator-details.js`
- Create: `web/indicator-details.css`
- Modify: `app/sqlgen/explanation.py`
- Modify: `web/chat-markdown.js`
- Modify: `web/index.html`
- Test: `tests/test_sql_explanation.py`
- Test: `tests/test_chat_markdown_ui.py`
- Test: `tests/test_indicator_detail_ui.py`

**Interfaces:**
- Consumes: Task 1 登录 API 和 Task 4 明细 API。
- Produces: `window.IndicatorDetails.open(runId, initialGroup)`。
- Produces: `window.IndicatorDetails.setAuth({token, user})`。
- Produces: 聊天 Markdown 安全令牌 `{{detail:RUN_xxx:denominator|numerator|unmatched}}`。

- [ ] **Step 1: 写聚合结果操作列和安全按钮渲染失败测试**

```python
def test_trial_table_adds_detail_actions_for_three_count_rows():
    text = format_trial_explanation(**urgent_consult_trial_payload())
    assert "| 统计项 | 数量 | 说明 | 操作 |" in text
    assert "{{detail:RUN_80:denominator}}" in text
    assert "{{detail:RUN_80:numerator}}" in text
    assert "{{detail:RUN_80:unmatched}}" in text
    assert "{{detail:RUN_80:result}}" not in text
```

```javascript
const html = renderer.renderAssistantMarkdown("{{detail:RUN_80:denominator}}");
assert(html.includes('data-run-id="RUN_80"'));
assert(html.includes('data-detail-group="denominator"'));
assert(!renderer.renderAssistantMarkdown("{{detail:<script>:denominator}}").includes("button"));
```

- [ ] **Step 2: 运行解释器和前端测试并确认失败**

Run: `python -m pytest tests/test_sql_explanation.py tests/test_chat_markdown_ui.py tests/test_indicator_detail_ui.py -q`

Expected: FAIL，聚合表尚无“操作”列或页面没有明细窗口。

- [ ] **Step 3: 在聚合表内加入不可伪造的详情按钮令牌**

`_trial_table()` 把表头改为 `统计项 | 数量 | 说明 | 操作`，前三行操作列分别输出固定 group 的令牌，指标结果行输出 `-`。只有 `trial.status == success`、`run_id` 匹配 `^RUN_[A-Za-z0-9_]+$` 且数量完整时才输出按钮。

`web/chat-markdown.js` 在 HTML 转义之后只替换严格正则：

```javascript
/\{\{detail:(RUN_[A-Za-z0-9_]+):(denominator|numerator|unmatched)\}\}/g
```

替换时用正则捕获到的 `runId` 和 `group` 构造 `<button type="button" class="indicator-detail-trigger">`，并分别写入经过严格正则校验的 `data-run-id` 与 `data-detail-group`。其他花括号内容保持普通文本。

- [ ] **Step 4: 把医院登录改成后端真实校验和首登改密**

医院角色点击登录时调用 `/api/auth/hospital/login`，成功后在 `sessionStorage` 保存 `hospitalAuthToken` 和服务端返回的 `currentUser`；不再由前端自行构造医院用户。若 `must_change_password=true`，显示不可跳过的改密弹窗；改密成功替换令牌并进入工作台。公司角色保持现有非患者功能入口，但不给 `hospitalAuthToken`，因此不能查看详情。

所有明细请求统一通过：

```javascript
async function detailFetch(url, options) {
  const token = sessionStorage.getItem("hospitalAuthToken") || "";
  const headers = Object.assign({}, options && options.headers, {Authorization: "Bearer " + token});
  const response = await fetch(url, Object.assign({}, options, {headers: headers}));
  if (response.status === 401) throw new Error("登录已过期，请重新登录后继续查看。");
  return response;
}
```

- [ ] **Step 5: 实现明细弹窗的三标签、分页和状态反馈**

弹窗顶部显示指标名称、本院口径、统计区间、快照时间和总数；标签为“统计范围 576”“达到要求 488”“未达到要求 88”。表格使用后端中文列名和脱敏值，每页 50 条。必须覆盖：

- 加载中：保留窗口尺寸并显示“正在读取本次计算明细”。
- 空数据：显示“本组没有记录”，不显示空白大表格。
- 权限不足：说明需要“指标明细查看”权限。
- 数量变化：显示“业务数据已经变化”，提供关闭窗口并重新输入“试运行”的明确说明。
- 过期：显示“明细已过期，请重新生成”，按钮重新调用 `POST /details`。
- 网络失败：保留当前标签和页码，显示“重试”按钮。

- [ ] **Step 6: 实现二次确认和带令牌 Blob 下载**

点击“生成并下载 Excel”先弹出确认框，文案明确包含“文件含完整患者级明细”“仅限授权使用”“24 小时后自动删除”。确认后按钮进入不可重复提交状态，调用 `POST /exports`，再用带 Authorization 的 `fetch` 请求下载地址，转换为 Blob 和临时 `<a download>`；下载触发后立即 `URL.revokeObjectURL()`。

页面只显示服务端返回的相对路径，例如 `runtime/exports/hospital_001/RUN_xxx/文件.xlsx`，不显示磁盘绝对路径。无导出权限时仍可预览，导出按钮禁用并解释“当前账号没有指标明细导出权限”。

- [ ] **Step 7: 运行前端单元测试和浏览器桌面/窄屏验收**

Run: `python -m pytest tests/test_sql_explanation.py tests/test_chat_markdown_ui.py tests/test_indicator_detail_ui.py -q`

Expected: PASS。

Browser checks:

1. 1440x900：聚合表三个“查看详情”按钮可见，弹窗不超出视口，表头和底部操作不重叠。
2. 768x900：表格允许横向滚动，弹窗标题、关闭、标签和下载按钮仍可操作。
3. 键盘：按钮可 Tab 聚焦，Esc 关闭弹窗，焦点回到原“查看详情”按钮。
4. 浏览器 Network：预览响应只有脱敏值；下载请求带 Bearer 令牌。

- [ ] **Step 8: 提交并推送前端批次**

```powershell
git add app/sqlgen/explanation.py web/chat-markdown.js web/indicator-details.js web/indicator-details.css web/index.html tests/test_sql_explanation.py tests/test_chat_markdown_ui.py tests/test_indicator_detail_ui.py
git commit -m "feat(ui): 增加指标明细预览与下载窗口"
git push
```

---

### Task 6: 正式迁移、文档与端到端验收

**Files:**
- Modify: `README.md`
- Modify: `config.example.yaml`
- Modify: `tests/test_api.py`
- Create: `tests/test_indicator_detail_e2e.py`

**Interfaces:**
- Consumes: Tasks 1-5 的完整功能。
- Produces: 可照做的安装、初始化、验收和故障定位说明。

- [ ] **Step 1: 写真实四指标端到端失败测试**

```python
def test_urgent_consult_trial_preview_and_excel_are_consistent(e2e_app, tmp_path):
    token = login_and_change_demo_password(e2e_app)
    run = run_urgent_consult_for_july(e2e_app)
    snapshot = create_detail_snapshot(e2e_app, token, run["run_id"])
    assert snapshot["counts"] == {"denominator": 576, "numerator": 488, "unmatched": 88}
    preview = get_detail_page(e2e_app, token, run["run_id"], "numerator")
    assert "*" in preview["items"][0]["患者标识"]
    export = create_and_download_export(e2e_app, token, run["run_id"], tmp_path)
    workbook = load_workbook(export, read_only=True)
    assert workbook.sheetnames == ["统计范围_576", "达到要求_488", "未达到要求_88"]
```

该测试使用受控的假 DBHub 返回 576 行，不依赖开发者机器当前业务数据；另外保留一个标记为 `integration` 的本地 MySQL 验收测试，只有配置 `RUN_LOCAL_DB_TESTS=1` 时运行。

- [ ] **Step 2: 运行端到端测试并确认尚缺文档或配置时失败**

Run: `python -m pytest tests/test_indicator_detail_e2e.py tests/test_api.py -q`

Expected: 首次执行在 README 验收标记或配置项断言处 FAIL，功能链路测试应已能运行。

- [ ] **Step 3: 补充配置、部署和医生验收说明**

`config.example.yaml` 增加：

```yaml
hospital_auth_session_hours: 8
indicator_detail_export_root: runtime/exports
indicator_detail_expire_hours: 24
indicator_detail_max_rows: 20000
indicator_detail_default_page_size: 50
```

README 按顺序说明：

1. 安装依赖并执行 `python scripts/migrate_runtime_schema.py`。
2. 本地演示环境执行 `python scripts/seed_demo_hospital_user.py`；生产环境使用管理员初始化流程，不使用演示密码。
3. 执行 `python -B scripts/import_four_indicator_rules.py`，让 `detail_fields` 和字段映射进入 MySQL。
4. 登录 `user_001`、强制改密、询问急会诊、生成 SQL、试运行、查看三组明细、确认导出、下载 Excel。
5. 文件位置、24 小时清理、权限码、审计表和常见错误处理。
6. 明确“页面预览脱敏、Excel 完整值”“明细不是长期业务库”“重新试运行后旧快照不自动代表新数据”。

- [ ] **Step 4: 执行迁移、导入和全量自动化测试**

Run:

```powershell
python scripts/migrate_runtime_schema.py
python scripts/seed_demo_hospital_user.py
python -m pytest -q
```

Expected: 迁移输出包含 `hospital_auth` 和 `indicator_details`；演示账号脚本输出 `user_001 / hospital_001 / must_change_password=true` 且不打印密码散列；全部测试 PASS。

- [ ] **Step 5: 启动本地服务并完成真实前端验收**

Run:

```powershell
python -B -m uvicorn app.api.main:app --host 127.0.0.1 --port 8765
```

在 `http://127.0.0.1:8765/` 验证：

1. 初始密码登录后必须改密。
2. 急会诊 20 分钟本院口径试运行结果显示分母、分子、未达标三个详情按钮。
3. 页面患者标识已脱敏，切换三个标签的总数与聚合结果一致。
4. 二次确认后下载 Excel，三个工作表数量、口径和统计区间一致。
5. 文件出现在 `runtime/exports/hospital_001/{run_id}/`，`git status --short` 不出现该文件。
6. 把测试时钟推进 24 小时或在测试环境调用 cleanup 后，下载返回 410，磁盘文件被删除，审计记录存在但不含患者值。

- [ ] **Step 6: 检查差异、安全边界和计划验收覆盖**

Run:

```powershell
git diff --check
git status --short
rg -n "123456|patient_001|SELECT \*|runtime/exports" app web scripts README.md config.example.yaml
```

Expected: `git diff --check` 无输出；`123456` 只出现在显式演示账号脚本和文档；应用日志/审计代码没有患者样例值；明细 SQL 生成器没有 `SELECT *`；运行目录未被 Git 跟踪。

- [ ] **Step 7: 提交并推送文档与端到端验收批次**

```powershell
git add README.md config.example.yaml tests/test_api.py tests/test_indicator_detail_e2e.py
git commit -m "docs: 补充指标明细导出部署与验收说明"
git push
```

---

## Final Verification

- [ ] `python -m pytest -q` 全部通过。
- [ ] `python scripts/migrate_runtime_schema.py` 可重复执行两次，第二次不重复建表或加列。
- [ ] 四个指标原聚合 SQL 和结果不变，只有试运行日志多出数量与口径上下文。
- [ ] 576/488/88 在聊天聚合表、三组预览和 Excel 工作表中一致。
- [ ] `count_distinct` 指标按患者/入院对象去重，不能按原始业务行数错误放大。
- [ ] 未登录、首登未改密、无权限、跨医院、过期和哈希不一致请求均被拒绝并审计。
- [ ] 页面预览只返回脱敏字段；完整值只存在短期快照和授权 Excel。
- [ ] 文件 24 小时后被清理，清理失败可重试且不会把绝对路径返回前端。
- [ ] Excel 无宏、无外链、无公式注入，包含本院口径和统计区间说明。
- [ ] 桌面和窄屏页面无重叠，加载、空数据、成功、失败、权限和过期状态都有可执行下一步。

## Self-Review Record

- Spec coverage: 登录/RBAC、首次改密、三组预览、脱敏、20,000 行限制、同源快照、数量漂移阻断、Excel、24 小时清理、审计、四指标和前端验收均有对应任务。
- Placeholder scan: 计划不包含 `TBD`、`TODO`、“后续实现”或未定义的错误处理要求；每个任务都有具体测试命令、接口和提交范围。
- Type consistency: `HospitalPrincipal` 由 Task 1 产出并被 Tasks 3-4 使用；`RunContext` 由 Task 2 产出并被 Task 3 使用；`DetailSnapshotSummary` 由 Task 3 产出并被 Tasks 4-5 使用；group 名始终为 `denominator`、`numerator`、`unmatched`。
