# 离线包安全闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有知识包交换基础上，实现医院白名单元数据、本院口径和聚合验证反馈的签名导出，以及公司发布包在医院端的验签、隔离、预览和兼容检查闭环。

**Architecture:** 继续使用现有 `kb-exchange-v3` 和 `company-release-v2`，分别演进为签名的 `kb-exchange-v4` 与 `company-release-v3`。共用 Ed25519 包签名组件；医院运行库新增导出白名单、公司包导入暂存和审计表。所有包在写入正式规则前只进入暂存区，本批不实现公司包自动应用。

**Tech Stack:** Python 3.12、FastAPI、Pydantic 2、SQLAlchemy 2、MySQL/SQLite 测试兼容、PyYAML、cryptography Ed25519、原生 JavaScript/CSS、pytest。

## Global Constraints

- 公司与医院数据库不得直连，只交换离线签名包。
- 医院反馈包不得包含患者明细、字段样例值、数据库凭据、连接地址和未选中的结构元数据。
- 医院反馈包必须包含当前已审批且处于生效期的本院口径及其标准口径差异。
- 完整医院 SQL 默认不导出；本批 API 不提供开启完整 SQL 导出的入口。
- 新版包必须验签；旧版包只能标记为“旧版未签名”，不得伪装成已验证。
- 公司发布包导入医院后只能进入待适配暂存区，不能自动覆盖当前生效口径。
- 所有数据库访问保持参数化；患者业务库只通过既有 DBHub 只读链路访问。
- 前端必须提供加载、空数据、成功、失败、无权限和重复导入状态，不要求普通用户执行命令行。
- 每项功能按 TDD 顺序执行：先观察测试按预期失败，再写最小实现并观察通过。
- 每个任务形成独立中文 Conventional Commit，并推送当前分支。

---

## 文件结构

- Create `app/kb/signing.py`：Ed25519 密钥加载、签名文件生成和可信公钥验签。
- Create `app/kb/exchange_schema.py`：导出白名单、医院端公司包暂存、包条目和审计表。
- Create `app/kb/scope.py`：白名单读取、替换、预览和允许导出字段集合。
- Modify `app/kb/export.py`：生成签名 `kb-exchange-v4`，加入白名单元数据、表关系和聚合反馈。
- Modify `app/kb/company_repository.py`：验证 v4 医院包、幂等导入、解析元数据与验证反馈；签名导出 v3 公司包。
- Create `app/kb/hospital_import.py`：医院端导入、验签、兼容检查、隔离存储、列表和详情。
- Modify `app/api/main.py`：新增白名单、导出预览、医院反馈包导出和公司发布包院端导入 API。
- Modify `scripts/init_runtime_db.sql`：增加离线交换运行表。
- Modify `scripts/migrate_runtime_schema.py`：执行离线交换表迁移。
- Create `scripts/generate_package_keys.py`：生成本地 Ed25519 密钥和可信公钥目录。
- Modify `requirements.txt`、`config.example.yaml`：声明依赖和密钥路径。
- Modify `web/index.html`、`web/metadata.js`、`web/metadata.css`：在数据基础页面增加离线交换页签。
- Create `web/package-exchange.js`、`web/package-exchange.css`：白名单、预览、导出、导入和包详情交互。
- Modify `README.md`：增加正式部署密钥、离线交换和前端验证说明。
- Create/Modify `tests/test_package_signing.py`、`tests/test_kb_export_scope.py`、`tests/test_company_kb_repository.py`、`tests/test_hospital_release_import.py`、`tests/test_kb_exchange_api.py`、`tests/test_package_exchange_ui.py`、`tests/test_runtime_migrations.py`。

---

### Task 1: Ed25519 签名与离线交换运行表

**Files:**
- Create: `app/kb/signing.py`
- Create: `app/kb/exchange_schema.py`
- Create: `scripts/generate_package_keys.py`
- Modify: `scripts/init_runtime_db.sql`
- Modify: `scripts/migrate_runtime_schema.py`
- Modify: `requirements.txt`
- Modify: `config.example.yaml`
- Test: `tests/test_package_signing.py`
- Test: `tests/test_runtime_migrations.py`

**Interfaces:**
- Produces: `PackageSigner.from_private_pem(path, key_id)`、`sign_checksums(checksums_bytes)`、`verify_checksums(checksums_bytes, signature_payload, trusted_keys_dir)`。
- Produces: `ensure_kb_exchange_schema(engine) -> dict[str, list[str]]`。

- [ ] **Step 1: 写入签名和表迁移失败测试**

```python
def test_ed25519_signature_detects_changed_checksums(tmp_path):
    private_path, trusted_dir = write_test_key_pair(tmp_path, "hospital_001")
    signer = PackageSigner.from_private_pem(private_path, "hospital_001")
    signature = signer.sign_checksums(b'{"manifest.yaml":"abc"}')
    verify_checksums(b'{"manifest.yaml":"abc"}', signature, trusted_dir)
    with pytest.raises(PackageSignatureError, match="PACKAGE_SIGNATURE_INVALID"):
        verify_checksums(b'{"manifest.yaml":"changed"}', signature, trusted_dir)

def test_exchange_schema_is_idempotent():
    engine = sqlite_engine()
    first = ensure_kb_exchange_schema(engine)
    second = ensure_kb_exchange_schema(engine)
    assert "med_metadata_export_scope" in first["created_tables"]
    assert second["created_tables"] == []
```

- [ ] **Step 2: 运行测试并确认因模块缺失失败**

Run: `python -B -m pytest tests/test_package_signing.py tests/test_runtime_migrations.py -q`

Expected: FAIL，提示 `app.kb.signing` 或 `ensure_kb_exchange_schema` 尚不存在。

- [ ] **Step 3: 实现最小签名接口和四张运行表**

```python
class PackageSigner:
    def __init__(self, private_key: Ed25519PrivateKey, key_id: str) -> None:
        self.private_key = private_key
        self.key_id = key_id

    def sign_checksums(self, payload: bytes) -> dict[str, str]:
        return {
            "algorithm": "Ed25519",
            "key_id": self.key_id,
            "signed_file": "checksums.json",
            "signature": base64.b64encode(self.private_key.sign(payload)).decode("ascii"),
        }
```

运行表固定为：

- `med_metadata_export_scope`：医院、数据库、表、字段、选择人、更新时间；
- `med_company_package_import`：发布包、校验状态、兼容状态、清单和包摘要；
- `med_company_package_item`：规则、术语和其他包内条目快照；
- `med_package_audit`：导入、导出、验签、重复导入和拒绝事件。

- [ ] **Step 4: 增加密钥生成脚本和示例配置**

```yaml
hospital_package_signing_key_path: "runtime/package-keys/hospital-private.pem"
hospital_package_signing_key_id: "hospital_001"
trusted_hospital_keys_dir: "runtime/package-keys/trusted-hospitals"
company_package_signing_key_path: "runtime/package-keys/company-private.pem"
company_package_signing_key_id: "company_main"
trusted_company_keys_dir: "runtime/package-keys/trusted-companies"
```

- [ ] **Step 5: 运行测试并提交**

Run: `python -B -m pytest tests/test_package_signing.py tests/test_runtime_migrations.py -q`

Expected: PASS。

Commit: `feat(exchange): 增加离线包签名与运行表`

---

### Task 2: 医院白名单与签名反馈包

**Files:**
- Create: `app/kb/scope.py`
- Modify: `app/kb/export.py`
- Modify: `app/api/main.py`
- Test: `tests/test_kb_export_scope.py`
- Modify: `tests/test_kb_merge.py`

**Interfaces:**
- Produces: `MetadataExportScopeRepository.list_scope(hospital_id, db_name)`。
- Produces: `replace_scope(hospital_id, db_name, selections, actor_id)`。
- Produces: `preview_scope(hospital_id, db_name)`。
- Changes: `export_hospital_kb_zip(engine, hospital_id, db_name, signer) -> bytes` 输出 `kb-exchange-v4`。

- [ ] **Step 1: 写入白名单和导出失败测试**

```python
def test_export_contains_only_selected_metadata_and_no_defaults(scope_engine, signer):
    repository = MetadataExportScopeRepository(scope_engine)
    repository.replace_scope(
        "hospital_001", "hospital_demo_data",
        [{"table_name": "consult_record", "column_name": "request_time"}],
        "admin",
    )
    package = export_hospital_kb_zip(
        scope_engine, "hospital_001", "hospital_demo_data", signer
    )
    with ZipFile(BytesIO(package)) as zf:
        metadata = yaml.safe_load(zf.read("metadata/hospital_demo_data.yaml"))
        assert [item["column_name"] for item in metadata["columns"]] == ["request_time"]
        assert "column_default" not in metadata["columns"][0]
        assert "signature.json" in zf.namelist()
```

- [ ] **Step 2: 运行测试并确认因白名单接口缺失失败**

Run: `python -B -m pytest tests/test_kb_export_scope.py tests/test_kb_merge.py -q`

Expected: FAIL，提示 `MetadataExportScopeRepository` 不存在或导出函数参数不匹配。

- [ ] **Step 3: 实现白名单读取、替换和预览**

预览返回：

```python
{
    "hospital_id": "hospital_001",
    "db_name": "hospital_demo_data",
    "selected_table_count": 1,
    "selected_column_count": 4,
    "tables": [{"table_name": "consult_record", "columns": [...]}],
    "excluded_content": ["患者数据行", "字段样例值", "数据库密码", "连接地址"],
}
```

空白名单不得导出，返回 `METADATA_EXPORT_SCOPE_EMPTY`。

- [ ] **Step 4: 将反馈包升级为 v4**

包内增加：

- `metadata/<db_name>.yaml`：仅选中表字段，省略默认值；
- `metadata/relations.yaml`：仅包含两端都在白名单内的已确认关联；
- `validation/<rule_id>.yaml`：最近一次成功试运行的分子、分母、结果和统计区间；
- `signature.json`：签名 `checksums.json`；
- `manifest.yaml`：元数据、关系、验证反馈和签名计数。

保留 `overrides/` 中当前生效本院口径；`custom_sql` 从默认导出内容删除。字段映射只导出白名单中已授权的表字段。

- [ ] **Step 5: 增加管理员 API**

```text
GET /api/kb/export/scope?hospital_id=...&db_name=...
PUT /api/kb/export/scope
GET /api/kb/export/preview?hospital_id=...&db_name=...
GET /api/kb/export?hospital_id=...&db_name=...
```

保存白名单和正式导出要求管理员 token；读取范围和预览可供已登录本地工作台展示。

- [ ] **Step 6: 运行测试并提交**

Run: `python -B -m pytest tests/test_kb_export_scope.py tests/test_kb_merge.py tests/test_api.py -q`

Expected: PASS。

Commit: `feat(exchange): 导出医院白名单反馈包`

---

### Task 3: 公司端验签、幂等回收与签名发布

**Files:**
- Modify: `app/kb/company_repository.py`
- Modify: `app/api/main.py`
- Modify: `scripts/init_company_kb_db.sql`
- Modify: `tests/test_company_kb_repository.py`

**Interfaces:**
- Changes: `CompanyKnowledgeRepository(engine, trusted_hospital_keys_dir=None, release_signer=None)`。
- Produces: v4 医院包验签结果 `signature_status=verified`。
- Changes: `export_release_zip(release_id)` 输出签名 `company-release-v3`。

- [ ] **Step 1: 写入验签、幂等和签名发布测试**

```python
def test_same_signed_package_is_idempotent(company_repo, signed_hospital_package):
    first = company_repo.create_merge_report(signed_hospital_package, "admin")
    second = company_repo.create_merge_report(signed_hospital_package, "admin")
    assert second["report_id"] == first["report_id"]
    assert second["duplicate"] is True

def test_same_package_id_with_different_content_is_rejected(company_repo):
    with pytest.raises(CompanyKnowledgeError, match="PACKAGE_ID_CONFLICT"):
        company_repo.create_merge_report(changed_signed_package, "admin")
```

- [ ] **Step 2: 运行测试并确认旧逻辑因重复主键或缺少验签失败**

Run: `python -B -m pytest tests/test_company_kb_repository.py -q`

Expected: FAIL，重复包返回 `PACKAGE_ALREADY_IMPORTED` 或 v4 不受支持。

- [ ] **Step 3: 实现 v4 验签和信息类条目解析**

- v4 必须验证 `signature.json` 和可信医院公钥；
- v2/v3 保持可读，但报告标记 `legacy_unsigned`；
- `metadata_schema`、`table_relation` 和 `validation_feedback` 条目标记为 `informational`，不进入公司候选审批；
- 本院口径继续生成 `caliber_conflict` 或 `new_indicator`；
- 报告摘要增加元数据表数、字段数和验证反馈数。

- [ ] **Step 4: 实现幂等导入**

同一 `package_id` 且 SHA-256 相同，返回原报告并增加 `duplicate=true`；同一编号但内容不同，拒绝为 `PACKAGE_ID_CONFLICT`。

- [ ] **Step 5: 公司发布包升级为签名 v3**

`manifest.yaml` 增加 `compatible_system_versions: ["0.1.0"]`、签名者和权限摘要；签名文件覆盖清单及所有包内内容的校验和。

- [ ] **Step 6: 运行测试并提交**

Run: `python -B -m pytest tests/test_company_kb_repository.py tests/test_api.py -q`

Expected: PASS。

Commit: `feat(exchange): 验签回收医院包并签名公司发布`

---

### Task 4: 医院端公司发布包导入隔离

**Files:**
- Create: `app/kb/hospital_import.py`
- Modify: `app/api/main.py`
- Test: `tests/test_hospital_release_import.py`
- Test: `tests/test_kb_exchange_api.py`

**Interfaces:**
- Produces: `HospitalReleaseRepository.import_package(zip_bytes, imported_by)`。
- Produces: `list_imports()`、`read_import(import_id)`。
- Produces API: `/api/kb/hospital/releases/imports`。

- [ ] **Step 1: 写入导入隔离失败测试**

```python
def test_verified_release_is_stored_without_applying_rules(repository, package):
    result = repository.import_package(package, "hospital_admin")
    assert result["signature_status"] == "verified"
    assert result["compatibility_status"] == "compatible"
    assert result["status"] == "ready_for_adaptation"
    assert current_hospital_rule_version(repository.engine, "R001") == 1

def test_unsigned_legacy_release_stays_quarantined(repository, legacy_package):
    result = repository.import_package(legacy_package, "hospital_admin")
    assert result["status"] == "quarantined"
    assert result["signature_status"] == "legacy_unsigned"
```

- [ ] **Step 2: 运行测试并确认模块缺失失败**

Run: `python -B -m pytest tests/test_hospital_release_import.py tests/test_kb_exchange_api.py -q`

Expected: FAIL，提示 `HospitalReleaseRepository` 不存在。

- [ ] **Step 3: 实现解析、验签、兼容检查和暂存**

导入状态规则：

```python
if signature_status != "verified":
    status = "quarantined"
elif compatibility_status != "compatible":
    status = "incompatible"
else:
    status = "ready_for_adaptation"
```

包内规则、术语和清单写入 `med_company_package_item`，不得写入 `med_index_standard`、`med_index_hospital_custom` 或正式术语表。

- [ ] **Step 4: 增加医院端导入 API**

```text
POST /api/kb/hospital/releases/imports
GET  /api/kb/hospital/releases/imports
GET  /api/kb/hospital/releases/imports/{import_id}
```

所有接口要求管理员 token，错误返回中文说明和稳定问题码。

- [ ] **Step 5: 运行测试并提交**

Run: `python -B -m pytest tests/test_hospital_release_import.py tests/test_kb_exchange_api.py tests/test_api.py -q`

Expected: PASS。

Commit: `feat(exchange): 增加公司发布包院内隔离导入`

---

### Task 5: 数据基础页面离线交换工作区

**Files:**
- Modify: `web/index.html`
- Modify: `web/metadata.js`
- Modify: `web/metadata.css`
- Create: `web/package-exchange.js`
- Create: `web/package-exchange.css`
- Test: `tests/test_package_exchange_ui.py`
- Modify: `tests/test_metadata_ui.py`

**Interfaces:**
- Produces: 数据基础第三个页签“离线包交换”。
- Consumes: Task 2 和 Task 4 API。

- [ ] **Step 1: 写入页面结构失败测试**

```python
def test_data_foundation_has_offline_exchange_workspace():
    html = INDEX.read_text(encoding="utf-8")
    assert 'id="packageExchangeTab"' in html
    assert 'id="metadataScopeList"' in html
    assert 'id="hospitalFeedbackExportButton"' in html
    assert 'id="companyReleaseImportInput"' in html
    assert '/static/package-exchange.js' in html
```

- [ ] **Step 2: 运行测试并确认页面元素缺失**

Run: `python -B -m pytest tests/test_package_exchange_ui.py tests/test_metadata_ui.py -q`

Expected: FAIL，提示离线交换页签或静态资源缺失。

- [ ] **Step 3: 实现白名单选择与导出预览**

页面按表分组显示字段复选框，默认不自动全选。保存前展示：选择数据库、表数、字段数、包含内容和明确排除内容。导出按钮只在白名单已保存且管理员已授权时可用。

- [ ] **Step 4: 实现公司包导入和历史列表**

上传后展示签名状态、兼容状态、规则数、术语数和下一步“进入指标适配”，但本批按钮显示“后续阶段开放”，不调用应用接口。

- [ ] **Step 5: 覆盖响应式和失败状态**

桌面使用左右分栏，窄屏改为单列；请求失败时保留已选择白名单，并显示可执行的重试说明。

- [ ] **Step 6: 运行测试并提交**

Run: `python -B -m pytest tests/test_package_exchange_ui.py tests/test_metadata_ui.py tests/test_workbench_ui.py -q`

Expected: PASS。

Commit: `feat(exchange): 增加前端离线包交换工作区`

---

### Task 6: 文档、完整回归与本地可验证配置

**Files:**
- Modify: `README.md`
- Modify: `config.example.yaml`
- Test: all tests

**Interfaces:**
- Produces: 医院管理员不使用命令行即可完成日常导出和导入；密钥初始化作为一次性实施步骤保留脚本说明。

- [ ] **Step 1: 补充部署和验证文档**

README 必须说明：

- 公司端与医院端分别持有哪些私钥和可信公钥；
- 如何生成本地演示密钥；
- 前端如何选择白名单、预览、导出反馈包、导入公司发布包；
- 为什么结构校验不能替代院内真实数据试运行；
- 旧版未签名包为何只能隔离查看。

- [ ] **Step 2: 生成本地演示密钥并配置忽略目录**

Run: `python -B scripts/generate_package_keys.py --output runtime/package-keys --hospital-id hospital_001 --company-id company_main`

Expected: 私钥权限受限，可信公钥分别写入 `trusted-hospitals` 和 `trusted-companies`；`runtime/` 已被 Git 忽略。

- [ ] **Step 3: 运行聚焦回归**

Run: `python -B -m pytest tests/test_package_signing.py tests/test_kb_export_scope.py tests/test_company_kb_repository.py tests/test_hospital_release_import.py tests/test_kb_exchange_api.py tests/test_package_exchange_ui.py -q`

Expected: PASS。

- [ ] **Step 4: 运行全量测试**

Run: `python -B -m pytest -q`

Expected: PASS，无失败和错误。

- [ ] **Step 5: 检查 Git 差异和敏感文件**

Run: `git diff --check`

Run: `git status --short`

Expected: 仅包含本计划相关源码、测试和文档；不包含 `config.yaml`、私钥、运行库、导出包和日志。

- [ ] **Step 6: 提交并推送**

Commit: `docs(exchange): 补充离线包部署与验证说明`

Push: `git push origin main`

---

## 自检结论

- 设计范围覆盖：白名单元数据、本院口径、聚合反馈、双向签名、幂等、隔离、前端入口和审计。
- 明确排除：公司包自动应用、多表映射工作台、口径沙盒和 Skill 执行器，这些属于后续阶段。
- 新旧格式关系唯一：医院包 v3 演进到 v4，公司包 v2 演进到 v3，没有新增平行格式。
- 类型与状态一致：`verified`、`legacy_unsigned`、`compatible`、`quarantined`、`ready_for_adaptation` 在后端、API 和前端共用。
