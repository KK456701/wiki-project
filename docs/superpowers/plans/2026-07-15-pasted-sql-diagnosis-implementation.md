# 粘贴 SQL 与执行证据异常诊断实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让医院人员在现有 AI 对话框中直接粘贴 SQL、参数和执行结果，系统能够安全试运行用户 SQL，并基于实时元数据、国标口径、本院生效口径和原始数据质量给出可理解、可追溯的差异诊断。

**Architecture:** 保留现有 `CoreIndicatorOrchestrator -> RootCauseDiagnosisAgent -> DiagnoseAgent` 边界，在诊断 Agent 内新增“证据提取、SQL 安全归一化、用户 SQL 试运行、SQL 语义画像、三方口径对比、自然语言组织”流水线。模型只提取候选信息和组织表达；SQL 放行、参数绑定、元数据核验、执行结果和差异结论全部由确定性代码产生。未粘贴 SQL 的原有三层诊断保持兼容。

**Tech Stack:** Python 3、Pydantic 2、SQLAlchemy、sqlparse、FastAPI、Ollama、DBHub MCP、现有 SQLite 运行库、unittest/pytest。

## Global Constraints

- 首版仅支持在现有聊天框中粘贴文本，不增加文件上传入口。
- 只允许当前医院已配置的只读业务库；拒绝写操作、动态 SQL、临时表、存储过程和跨库访问。
- 用户 SQL 的患者级结果不得进入模型提示词、普通回复或执行链路摘要；模型只接收表字段、规则差异和聚合值。
- SQL 能执行只代表语法和对象可用，不能据此判定口径正确。
- 三层校验是内部流程，医生可见回答使用“结论、原因、影响、建议、技术依据”结构。
- 不引入新的 SQL AST 依赖；首版基于 `sqlparse` 和受控 SQL Server 语法识别，无法可靠识别时降级为静态诊断并明确说明。
- 每个任务按 TDD 完成，相关测试通过后使用中文 Conventional Commit 提交并推送。

---

## Task 1: 增加粘贴诊断证据契约与确定性提取器

**Files:**
- Create: `app/diagnose/evidence.py`
- Modify: `app/agents/contracts.py`
- Test: `tests/test_diagnosis_evidence.py`

- [ ] **Step 1: 编写失败测试，固定输入证据契约**

  在 `tests/test_diagnosis_evidence.py` 覆盖：

  ```python
  def test_extracts_sqlserver_script_params_period_and_claimed_result():
      evidence = extract_pasted_evidence(RAW_DIAGNOSIS_TEXT, rule_id="MQSI2025_001")
      assert evidence.rule_id == "MQSI2025_001"
      assert evidence.sql_text.startswith("USE [WIN60_QA_991827]")
      assert evidence.declared_params["start_time"] == "2026-06-01 00:00:00"
      assert evidence.declared_params["end_time"] == "2026-08-01 00:00:00"
      assert evidence.claimed_result["numerator_count"] == 2
      assert evidence.claimed_result["denominator_count"] == 158
      assert evidence.stat_period.start == "2026-06-01 00:00:00"
  ```

  同时覆盖无 SQL、多个代码块、中文结果标签、非法模型 JSON 和模型字段与确定性结果冲突。

- [ ] **Step 2: 运行测试并确认失败**

  Run: `python -m pytest tests/test_diagnosis_evidence.py -q`

  Expected: FAIL，提示 `app.diagnose.evidence` 或契约不存在。

- [ ] **Step 3: 在 `app/agents/contracts.py` 增加类型化契约**

  增加以下模型，并为列表、字典使用 `default_factory`：

  ```python
  class DiagnosisStatPeriod(AgentContract):
      start: str | None = None
      end: str | None = None

  class PastedDiagnosisEvidence(AgentContract):
      raw_text: str
      question: str = ""
      rule_id: str | None = None
      sql_text: str = ""
      declared_params: dict[str, Any] = Field(default_factory=dict)
      claimed_result: dict[str, Any] = Field(default_factory=dict)
      stat_period: DiagnosisStatPeriod = Field(default_factory=DiagnosisStatPeriod)
      parse_warnings: list[str] = Field(default_factory=list)
      model_parse_status: str = "not_used"
  ```

  扩展 `DiagnosisResult`，增加可选 `user_summary`、`evidence`、`findings`、`execution_results` 和 `trace_events`，保持旧结果仍可校验。

- [ ] **Step 4: 实现确定性证据提取**

  在 `app/diagnose/evidence.py` 提供：

  ```python
  def extract_pasted_evidence(
      raw_text: str,
      *,
      rule_id: str | None,
      llm_client: Any | None = None,
  ) -> PastedDiagnosisEvidence:
      ...
  ```

  实现顺序：提取 fenced code block 或 `USE/DECLARE/WITH/SELECT` 片段；解析标量 `DECLARE`；解析统计时间；识别分子、分母、比例和样本数；可选调用模型返回固定 JSON；使用 Pydantic 校验模型结果；SQL、参数和数值冲突时以确定性结果为准并写入 `parse_warnings`。

- [ ] **Step 5: 运行测试并确认通过**

  Run: `python -m pytest tests/test_diagnosis_evidence.py tests/test_agent_contracts.py -q`

  Expected: PASS。

- [ ] **Step 6: 提交并推送**

  ```powershell
  git add app/diagnose/evidence.py app/agents/contracts.py tests/test_diagnosis_evidence.py
  git commit -m "feat: 增加粘贴诊断证据提取"
  git push
  ```

---

## Task 2: 增加 SQL Server 粘贴脚本安全归一化与只读试运行

**Files:**
- Create: `app/diagnose/user_sql.py`
- Modify: `app/db_access/business_db.py`
- Test: `tests/test_diagnosis_user_sql.py`
- Test: `tests/test_business_db.py`

- [ ] **Step 1: 编写安全边界失败测试**

  覆盖以下行为：

  ```python
  def test_prepares_current_database_declare_and_cte_for_readonly_execution():
      prepared = prepare_pasted_sql(
          RAW_SQL,
          allowed_database="WIN60_QA_991827",
          allowed_schema="WINDBA",
      )
      assert prepared.safe_to_execute is True
      assert prepared.query_sql.lstrip().upper().startswith("WITH")
      assert "DECLARE" not in prepared.query_sql.upper()
      assert "'2026-06-01T00:00:00'" in prepared.query_sql

  @pytest.mark.parametrize("keyword", ["UPDATE", "DELETE", "MERGE", "EXEC", "CREATE TABLE", "#TMP"])
  def test_rejects_unsafe_scripts(keyword): ...

  def test_rejects_use_of_another_database(): ...
  def test_rejects_unresolved_parameters(): ...
  ```

  再验证 `BusinessDBClient` 可以接受单条 `WITH ... SELECT`，仍拒绝多语句和写操作。

- [ ] **Step 2: 运行测试并确认失败**

  Run: `python -m pytest tests/test_diagnosis_user_sql.py tests/test_business_db.py -q`

- [ ] **Step 3: 实现准备结果和安全归一化**

  在 `app/diagnose/user_sql.py` 增加：

  ```python
  class PreparedPastedSql(BaseModel):
      safe_to_execute: bool = False
      query_sql: str = ""
      declared_params: dict[str, Any] = Field(default_factory=dict)
      referenced_databases: list[str] = Field(default_factory=list)
      referenced_schemas: list[str] = Field(default_factory=list)
      blocked_reasons: list[str] = Field(default_factory=list)

  def prepare_pasted_sql(
      sql_text: str,
      *,
      allowed_database: str,
      allowed_schema: str,
  ) -> PreparedPastedSql:
      ...
  ```

  只允许标量 `DECLARE`；使用现有安全字面量绑定规则替换 `@变量`；移除 `USE`、`DECLARE` 和 CTE 前导分号；最后交给增强后的 `BusinessDBClient._assert_select` 再校验。不得把用户 SQL 拼进系统生成的 SQL。

- [ ] **Step 4: 增强 `BusinessDBClient` 的 CTE 识别与错误信息**

  将“单条只读查询”判断收敛到一个内部函数，支持 `SELECT` 和 `WITH ... SELECT`，显式阻止 `MERGE`、`EXEC/EXECUTE`、`INTO`、临时表及动态 SQL。保持已有调用兼容。

- [ ] **Step 5: 运行测试并确认通过**

  Run: `python -m pytest tests/test_diagnosis_user_sql.py tests/test_business_db.py tests/test_dbhub_client.py -q`

- [ ] **Step 6: 提交并推送**

  ```powershell
  git add app/diagnose/user_sql.py app/db_access/business_db.py tests/test_diagnosis_user_sql.py tests/test_business_db.py
  git commit -m "feat: 增加粘贴 SQL 只读试运行保护"
  git push
  ```

---

## Task 3: 建立 SQL 语义画像与口径差异识别

**Files:**
- Create: `app/diagnose/sql_semantics.py`
- Modify: `app/diagnose/structure_check.py`
- Test: `tests/test_diagnosis_sql_semantics.py`
- Test: `tests/test_diagnose_agent.py`

- [ ] **Step 1: 用当前真实案例编写失败测试**

  将附件案例裁剪成无患者信息的 SQL fixture，断言能够识别：

  ```python
  def test_transfer_indicator_detects_all_material_caliber_differences():
      system = profile_sql(SYSTEM_SQL, dialect="sqlserver")
      user = profile_sql(USER_SQL, dialect="sqlserver")
      findings = compare_sql_profiles(system, user)
      codes = {item.code for item in findings}
      assert "period_field_changed" in codes
      assert "elapsed_start_field_changed" in codes
      assert "upper_boundary_inclusive_changed" in codes
      assert "icu_scope_strategy_changed" in codes
      assert "event_selection_changed" in codes
      assert "null_handling_changed" in codes
      assert user.zero_denominator_guard is True
  ```

  另加结构类型测试：数值型 `*_ID`、`*_CODE` 与规则中的业务编码不应仅因“期望 string”而产生类型不匹配警告。

- [ ] **Step 2: 运行测试并确认失败**

  Run: `python -m pytest tests/test_diagnosis_sql_semantics.py tests/test_diagnose_agent.py -q`

- [ ] **Step 3: 实现 SQL 画像契约和解析器**

  在 `app/diagnose/sql_semantics.py` 增加：

  ```python
  class SqlSemanticProfile(BaseModel):
      tables: list[str] = Field(default_factory=list)
      columns: list[str] = Field(default_factory=list)
      period_fields: list[str] = Field(default_factory=list)
      elapsed_pairs: list[dict[str, str]] = Field(default_factory=list)
      upper_boundary_mode: str = "unknown"
      icu_scope_strategy: str = "unknown"
      event_selection: str = "unknown"
      null_handling: list[str] = Field(default_factory=list)
      zero_denominator_guard: bool = False
      parse_warnings: list[str] = Field(default_factory=list)

  class DiagnosisFinding(BaseModel):
      code: str
      category: str
      severity: str
      title: str
      evidence: str
      impact: str
      suggestion: str
  ```

  提供 `profile_sql(sql, dialect)` 与 `compare_sql_profiles(baseline, candidate)`。解析器只识别已验收的受控模式；不能确认的内容进入 `parse_warnings`，不得猜测。

- [ ] **Step 4: 修正结构类型兼容规则**

  在 `structure_check.py` 提取 `_types_compatible(expected_type, actual_type, field_name)`；兼容 SQL Server 的 `numeric/decimal/bigint` 标识和编码字段，只有真实运算类型不兼容时才提示。

- [ ] **Step 5: 运行测试并确认通过**

  Run: `python -m pytest tests/test_diagnosis_sql_semantics.py tests/test_diagnose_agent.py -q`

- [ ] **Step 6: 提交并推送**

  ```powershell
  git add app/diagnose/sql_semantics.py app/diagnose/structure_check.py tests/test_diagnosis_sql_semantics.py tests/test_diagnose_agent.py
  git commit -m "feat: 增加指标 SQL 口径差异识别"
  git push
  ```

---

## Task 4: 将用户 SQL、国标口径和本院口径接入统一诊断编排

**Files:**
- Create: `app/diagnose/pasted_diagnosis.py`
- Modify: `app/diagnose/agent.py`
- Modify: `app/agents/orchestrator.py`
- Modify: `app/agents/root_cause_diagnosis.py`
- Modify: `app/agents/contracts.py`
- Test: `tests/test_pasted_diagnosis.py`
- Test: `tests/test_agent_orchestrator.py`
- Test: `tests/test_diagnose_agent.py`

- [ ] **Step 1: 编写三方执行和降级行为失败测试**

  覆盖：用户 SQL 执行成功且与本院结果不同；用户 SQL 被安全规则拒绝但仍完成静态分析；国标或本院 SQL 执行失败时不覆盖已确认的用户 SQL 差异；无粘贴 SQL 时继续走旧三层诊断。

  核心断言：

  ```python
  assert result.execution_results["user"]["status"] == "success"
  assert result.execution_results["hospital"]["status"] == "success"
  assert result.execution_results["national"]["status"] == "success"
  assert result.execution_results["user"]["denominator_count"] == 158
  assert result.primary_conclusion == "caliber_difference"
  assert "period_field_changed" in {f["code"] for f in result.findings}
  ```

- [ ] **Step 2: 运行测试并确认失败**

  Run: `python -m pytest tests/test_pasted_diagnosis.py tests/test_agent_orchestrator.py tests/test_diagnose_agent.py -q`

- [ ] **Step 3: 实现粘贴诊断服务**

  在 `app/diagnose/pasted_diagnosis.py` 提供：

  ```python
  class PastedDiagnosisService:
      def run(
          self,
          *,
          evidence: PastedDiagnosisEvidence,
          hospital_id: str,
          effective_rule: dict[str, Any],
          caliber_context: dict[str, Any],
          field_mapping: dict[str, Any],
          stat_period: str | None,
      ) -> dict[str, Any]:
          ...
  ```

  执行顺序固定为：安全准备用户 SQL；实时元数据校验；试运行用户 SQL；复用 `execute_caliber_comparison` 运行国标和本院口径；生成三方 SQL 画像；比较聚合结果；结构无阻断后执行受控数据质量检查。输出只包含聚合结果、差异 finding 和执行状态。

- [ ] **Step 4: 扩展 DiagnoseAgent 而不破坏旧路径**

  为 `DiagnoseAgent.run` 增加可选 `query_text` 与 `llm_client` 输入。仅当 `extract_pasted_evidence(...).sql_text` 非空时调用 `PastedDiagnosisService`；否则保留现有 `structure_check -> rule_check -> data_check`。

  `CoreIndicatorOrchestrator.diagnose` 必须将 `prepared.query` 作为 `query_text` 传入，修复当前“对话有 SQL、诊断器看不到”的断点。

- [ ] **Step 5: 保存完整诊断证据但不保存患者明细**

  扩展 `build_report/save_report` 使用现有 `layer_results` JSON 保存执行状态、聚合结果和 findings；原始 SQL 只保留脱敏摘要或哈希，不把患者行写入报告。

- [ ] **Step 6: 运行测试并确认通过**

  Run: `python -m pytest tests/test_pasted_diagnosis.py tests/test_agent_orchestrator.py tests/test_diagnose_agent.py tests/test_caliber_compare.py -q`

- [ ] **Step 7: 提交并推送**

  ```powershell
  git add app/diagnose/pasted_diagnosis.py app/diagnose/agent.py app/diagnose/report.py app/agents/orchestrator.py app/agents/root_cause_diagnosis.py app/agents/contracts.py tests/test_pasted_diagnosis.py tests/test_agent_orchestrator.py tests/test_diagnose_agent.py
  git commit -m "feat: 接入粘贴 SQL 三方口径诊断"
  git push
  ```

---

## Task 5: 使用本地模型生成医生可读诊断，确定性结论不可被改写

**Files:**
- Create: `app/diagnose/narrator.py`
- Create: `app/prompts/diagnosis_evidence.txt`
- Create: `app/prompts/diagnosis_compose.txt`
- Modify: `app/diagnose/agent.py`
- Modify: `app/agent/graph.py`
- Modify: `app/api/main.py`
- Test: `tests/test_diagnosis_narrator.py`
- Test: `tests/test_agent_workflow.py`

- [ ] **Step 1: 编写模型正常与异常输出失败测试**

  覆盖：合法 JSON 提取；Markdown 包裹 JSON；无效 JSON 回退；模型声称“数据库故障”但确定性 finding 为“口径差异”时拒绝模型结论；模型不可看到患者明细和完整原始查询结果。

  用户可见回答必须包含：

  ```text
  结论
  两段 SQL 都能执行，结果不同主要是统计口径不同。

  为什么不一致
  1. 统计起点不同……
  2. 48 小时边界不同……

  对结果的影响
  用户 SQL / 本院口径 / 国标口径的分子、分母和比例……

  建议怎么处理
  需要业务确认按入院时间还是首次入区时间……
  ```

  普通回答不得出现“第一层、第二层、第三层”。

- [ ] **Step 2: 运行测试并确认失败**

  Run: `python -m pytest tests/test_diagnosis_narrator.py tests/test_agent_workflow.py -q`

- [ ] **Step 3: 实现证据提取和回答组织提示词**

  `diagnosis_evidence.txt` 要求模型仅输出固定 JSON；`diagnosis_compose.txt` 只接收已校验 findings、表字段、聚合值和执行状态。提示词明确禁止补造字段、原因、数值和患者信息。

- [ ] **Step 4: 实现 Narrator 与确定性回退模板**

  在 `app/diagnose/narrator.py` 提供：

  ```python
  class DiagnosisNarrator:
      def compose(self, diagnosis: DiagnosisResult) -> str:
          ...
  ```

  先生成结构化事实清单，再调用 `llm_client.generate`；输出后校验主要结论和关键 finding 是否保留。模型不可用、超时或输出越界时，使用同一事实清单生成中文模板回答。

- [ ] **Step 5: 在图和 API 工厂中注入同一个本地 LLM 客户端**

  `_create_agent_orchestrator` 创建 `DiagnoseAgent` 时传入当前 `llm_client`；API 非流式诊断路径按配置创建 `OllamaClient`。不得在每个诊断节点重复初始化模型客户端。

- [ ] **Step 6: 替换诊断回答格式器**

  `_format_diagnose_answer` 优先使用 `diag_result["user_summary"]`；旧结果继续使用现有三层格式，保证 API 和监控自动诊断兼容。

- [ ] **Step 7: 运行测试并确认通过**

  Run: `python -m pytest tests/test_diagnosis_narrator.py tests/test_agent_workflow.py tests/test_api.py -q`

- [ ] **Step 8: 提交并推送**

  ```powershell
  git add app/diagnose/narrator.py app/prompts/diagnosis_evidence.txt app/prompts/diagnosis_compose.txt app/diagnose/agent.py app/agent/graph.py app/api/main.py tests/test_diagnosis_narrator.py tests/test_agent_workflow.py tests/test_api.py
  git commit -m "feat: 生成医生可读的差异诊断说明"
  git push
  ```

---

## Task 6: 将新诊断阶段接入双层执行链路

**Files:**
- Modify: `app/observability/workflow_nodes.py`
- Modify: `app/workflows/core_indicator_chat.yaml`
- Modify: `web/index.html`
- Test: `tests/test_workflow_manifest.py`
- Test: `tests/test_trace_ui.py`
- Test: `tests/test_agent_workflow.py`

- [ ] **Step 1: 编写 7 个诊断节点的失败测试**

  Manifest 和实际 trace 必须包含：

  ```text
  evidence_extract
  user_sql_guard
  user_sql_trial
  structure_compare
  caliber_semantic_compare
  data_quality_profile
  diagnosis_compose
  ```

  摘要只展示状态、耗时和一句话结论；详情中展示参数名、表字段、finding、聚合值和错误码，但不展示患者行。被安全拦截的 SQL 显示“未执行，已完成静态分析”，不显示笼统“失败”。

- [ ] **Step 2: 运行测试并确认失败**

  Run: `python -m pytest tests/test_workflow_manifest.py tests/test_trace_ui.py tests/test_agent_workflow.py -q`

- [ ] **Step 3: 扩展 trace 记录器**

  `record_diagnose_trace_nodes` 优先读取 `diag_result.trace_events` 并记录新节点；旧三层 `layers` 继续兼容。增加统一脱敏函数，移除 `raw_text`、完整 SQL、患者标识和 `rows`。

- [ ] **Step 4: 更新 manifest 和链路显示名称**

  使用面向业务的标题：识别排查材料、检查 SQL 安全、试运行用户 SQL、核对表字段、比较计算口径、检查数据质量、生成诊断结论。技术 ID 仅在展开详情显示。

- [ ] **Step 5: 运行测试并确认通过**

  Run: `python -m pytest tests/test_workflow_manifest.py tests/test_trace_ui.py tests/test_agent_workflow.py -q`

- [ ] **Step 6: 提交并推送**

  ```powershell
  git add app/observability/workflow_nodes.py app/workflows/core_indicator_chat.yaml web/index.html tests/test_workflow_manifest.py tests/test_trace_ui.py tests/test_agent_workflow.py
  git commit -m "feat: 展示粘贴 SQL 诊断执行链路"
  git push
  ```

---

## Task 7: 完成端到端验收、README 和安全回归

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-07-15-pasted-sql-diagnosis-design.md`（仅在实现与设计存在已确认差异时）
- Create: `tests/fixtures/diagnosis/transfer_ratio_user_sql.sql`
- Create: `tests/fixtures/diagnosis/transfer_ratio_claimed_result.json`
- Test: `tests/test_pasted_diagnosis_e2e.py`

- [ ] **Step 1: 增加真实案例端到端测试**

  使用脱敏 fixture 模拟当前“患者入院 48 小时内转科比例”案例，Fake BusinessDB 按用户、本院、国标 SQL 返回不同聚合结果。断言五类关键差异全部识别、不误报分母保护、不误报 numeric ID 类型、用户回答先给口径差异结论。

- [ ] **Step 2: 增加安全回归矩阵**

  覆盖写操作、跨库、动态 SQL、未解析变量、多语句、临时表、超时、DBHub 不可用、Ollama 不可用。每种情况都必须给出可执行下一步，且不得执行修复 SQL。

- [ ] **Step 3: 更新 README 使用说明**

  写明医生/实施人员的使用方式：先询问指标或沿用当前会话指标；直接粘贴 SQL、参数和聚合结果；系统自动做只读试运行；查看结论和链路；发现口径差异后通过现有审批流程修改，诊断不会直接发布口径。

- [ ] **Step 4: 运行专项和完整测试**

  ```powershell
  python -m pytest tests/test_diagnosis_evidence.py tests/test_diagnosis_user_sql.py tests/test_diagnosis_sql_semantics.py tests/test_pasted_diagnosis.py tests/test_diagnosis_narrator.py tests/test_pasted_diagnosis_e2e.py -q
  python -m pytest -q
  ```

  Expected: 全部通过，无网络依赖测试；真实 DBHub/Ollama 连通性另做本地冒烟验证。

- [ ] **Step 5: 启动项目并完成前端冒烟验证**

  启动现有 API 和 DBHub 服务后，在 AI 对话页：

  1. 询问“患者入院 48 小时内转科的比例怎么算”；
  2. 粘贴验收 SQL、参数和“为什么我们算得不一样”；
  3. 确认回答先说明口径差异，并列出时间字段、边界、ICU 范围、事件选择和空值处理；
  4. 打开执行链路，确认 7 个新节点有真实耗时，详情不含患者明细；
  5. 粘贴一条 `UPDATE`，确认系统拒绝执行但仍给出静态说明。

- [ ] **Step 6: 提交并推送**

  ```powershell
  git add README.md docs/superpowers/specs/2026-07-15-pasted-sql-diagnosis-design.md tests/fixtures/diagnosis tests/test_pasted_diagnosis_e2e.py
  git commit -m "docs: 补充粘贴 SQL 诊断验收说明"
  git push
  ```

## 完成标准

- 当前真实案例的两段 SQL 都可执行时，系统明确判断为“口径差异”，而不是数据库故障。
- 自动识别统计时间字段、计时起点、48 小时边界、ICU 范围、事件选择和空值处理差异。
- 用户 SQL 使用 `NULLIF` 或 `CASE` 时不再误报缺少分母保护。
- numeric 类型的医院 ID、科室 ID、业务编码不再被笼统判定为类型错误。
- 用户 SQL 仅在通过安全规则后由医院只读 DBHub 执行；任何写操作、跨库或动态 SQL 均不会执行。
- Ollama 不可用或输出非法 JSON 时，系统仍可基于确定性证据完成诊断。
- 医生看到自然语言结论，实施人员可在展开详情中查看表字段、参数、执行状态和建议 SQL。
- 原有无粘贴 SQL 的诊断、监控自动诊断、API 和执行链路保持兼容。
