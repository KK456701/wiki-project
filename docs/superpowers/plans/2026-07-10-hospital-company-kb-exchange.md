# Hospital-Company Knowledge Exchange Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make hospital MySQL the source of exported knowledge packages and add a separate company MySQL workflow for package intake, candidate review, version publication, and release export.

**Architecture:** Hospital exports are immutable `kb-exchange-v2` ZIP snapshots generated from active approved MySQL rows. Company APIs use a dedicated `company_db_url` repository; uploaded packages are staged and reviewed before approved candidates can be promoted into a published company release. Wiki remains readable source material and a read-only rule fallback.

**Tech Stack:** Python 3, FastAPI, SQLAlchemy, PyMySQL, MySQL 8, YAML/JSON ZIP packages, `unittest`.

## Global Constraints

- Hospital and company schemas are separate and communicate only through ZIP packages.
- Packages must never contain patient rows, credentials, tokens, session data, or runtime logs.
- Hospital exports include only active approved rules and confirmed field mappings.
- Restoring a hospital rule creates a new immutable version and must appear in the next export.
- Upload, review, candidate, and release writes fail closed when company MySQL is unavailable.
- Existing merge API response fields remain compatible where practical.
- Production behavior follows RED-GREEN-REFACTOR; every independently verified batch is committed and pushed with a Chinese Conventional Commit subject.

---

### Task 1: MySQL-backed hospital package export

**Files:**
- Modify: `app/kb/export.py`
- Modify: `app/api/main.py`
- Modify: `tests/test_kb_merge.py`

**Interfaces:**
- `export_hospital_kb_zip(runtime_engine: Engine, hospital_id: str) -> bytes`
- Produces `manifest.yaml`, `overrides/<rule_id>.yaml`, `mappings/<rule_id>.yaml`, and `checksums.json`.

- [ ] Write a failing SQLite-backed test with one active approved override, one expired override, one pending version, and confirmed field mappings. Assert only the active current projection is exported.
- [ ] Run `python -B -m unittest tests.test_kb_merge.KnowledgeBaseMergeTest.test_export_hospital_kb_zip_reads_current_mysql_projection -v` and confirm failure because export still expects Wiki files.
- [ ] Query `med_index_hospital_custom` joined with `med_index_standard`, serialize only approved active rows, group mappings from `med_field_mapping`, and calculate SHA-256 for every payload file.
- [ ] Update the API to pass `create_runtime_engine()` and fail with a clear HTTP error when runtime storage is unavailable.
- [ ] Run `python -B -m unittest tests.test_kb_merge -v` and confirm all export and ZIP safety tests pass.
- [ ] Commit and push with `feat: 从医院MySQL导出知识交换包`.

### Task 2: Company knowledge-center persistence

**Files:**
- Create: `scripts/init_company_kb_db.sql`
- Create: `app/kb/company_repository.py`
- Modify: `app/db/engine.py`
- Modify: `app/kb/merge.py`
- Modify: `app/api/main.py`
- Modify: `config.example.yaml`
- Modify: `tests/test_kb_merge.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- `create_company_engine() -> Engine`
- `CompanyKnowledgeRepository.create_merge_report(zip_bytes: bytes, uploaded_by: str) -> dict`
- Repository methods for report listing/detail and item approval/rejection.

- [ ] Write failing repository tests that upload a valid v2 package, reject a checksum mismatch, and read the report after constructing a new repository instance.
- [ ] Run the focused tests and confirm failure because company tables and repository do not exist.
- [ ] Add company standard, package, package item, candidate, release, and release item DDL with immutable version keys.
- [ ] Persist package manifest, item payloads, statuses, decisions, and audit metadata in transactions; use the existing safe ZIP extraction limits and allowlist.
- [ ] Change merge APIs to use `company_db_url`; keep response shapes used by the current frontend.
- [ ] Run `python -B -m unittest tests.test_kb_merge tests.test_api -v`.
- [ ] Commit and push with `feat: 增加公司知识中心回收存储`.

### Task 3: Candidate promotion and company release package

**Files:**
- Modify: `app/kb/company_repository.py`
- Modify: `app/api/main.py`
- Modify: `tests/test_kb_merge.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- `create_release(candidate_ids: list[str], created_by: str, notes: str = "") -> dict`
- `publish_release(release_id: str, approver_id: str) -> dict`
- `export_release_zip(release_id: str) -> bytes`
- New admin endpoints under `/api/kb/company/releases`.

- [ ] Write failing lifecycle tests proving that approving a merge item creates only a candidate, not a standard; publishing a release creates a new immutable standard version and release ZIP.
- [ ] Run focused tests and confirm failure on missing release methods.
- [ ] Implement draft creation from approved candidates, transactional publication, current standard projection update, and deterministic `company-release-v1` ZIP export.
- [ ] Add list, create, publish, and download endpoints protected by the existing admin token.
- [ ] Run `python -B -m unittest tests.test_kb_merge tests.test_api -v`.
- [ ] Commit and push with `feat: 支持公司知识版本发布与下发`.

### Task 4: Documentation and acceptance

**Files:**
- Modify: `README.md`
- Modify: `tests/test_kb_merge.py`

**Interfaces:**
- Documents separate hospital/company initialization and the complete offline exchange workflow.

- [ ] Add a regression test restoring a hospital rule and asserting the next export carries the new version while excluding all historical and pending snapshots.
- [ ] Document `wiki_agent_runtime`, `wiki_company_kb`, package contents, security exclusions, company review, release download, and the fact that company releases do not auto-apply at hospitals.
- [ ] Run `python -B -m unittest discover -s tests -v`, `python -B -m compileall -q app`, and `git diff --check`.
- [ ] Against local MySQL, initialize both schemas, export `hospital_001`, upload it to the company endpoint, approve one candidate, publish a release, and inspect both ZIP manifests.
- [ ] Commit and push with `docs: 补充医院与公司知识交换说明`.

## Completion Gate

- Hospital export has no Wiki runtime dependency and carries valid checksums.
- Company report/candidate/release state survives repository and process recreation.
- Candidate approval does not mutate company standard before publication.
- Hospital restore remains append-only and the restored current version is exported.
- Existing chat, approval, SQL generation, diagnosis, and trace tests remain green.
