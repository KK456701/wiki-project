# 指标监控工作台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将指标计划、手工运行、结果、预警和执行链路接入现有前端，使医院管理员无需命令行即可完成日常监控操作。

**Architecture:** 保留 `web/index.html` 的单页和弹窗模式，仅在其中增加监控入口与语义化 DOM；新增 `web/monitoring.css` 和 `web/monitoring.js` 分别承载监控样式与交互。JavaScript 复用全局管理员令牌、当前医院 ID 和 `showTrace()`，通过现有 `/api/monitoring` 接口完成操作，不复制后端规则或 Trace 渲染逻辑。

**Tech Stack:** 原生 HTML/CSS/JavaScript、FastAPI 静态文件、Python `unittest`、Codex in-app Browser

## Global Constraints

- 日常业务操作必须前端可达，PowerShell 和原始 JSON 只用于开发与实施排障。
- 当前医院必须读取顶部 `hospitalId`，所有计划、结果和预警操作显式传递 `hospital_id`。
- 继续复用管理员登录、`requireAdminThenOpen()` 和 `showTrace()`。
- 历史结果没有 `trace_id` 时不显示无效的链路按钮；本次手工运行返回 `trace_id` 时必须可直接打开链路。
- 用户可见状态、校验和错误使用中文，不直接暴露 `run_key`、manifest 等内部术语。
- 桌面与移动端不得出现文字溢出、按钮重叠或无法滚动到的操作。
- 每个任务先观察测试失败，再实现最小代码，验证后使用中文 Conventional Commit 并推送 `main`。

---

### Task 1: 将可用性、可维护性和易上手写入 Agent 约束

**Files:**
- Modify: `agent.md`
- Create: `tests/test_agent_guidance.py`

**Interfaces:**
- Produces: 后续 Agent 必须遵守的三类工程硬约束。
- Consumes: 根目录 `agent.md` 现有中文、测试和提交规范。

- [ ] **Step 1: 写失败测试**

创建 `tests/test_agent_guidance.py`：

```python
import unittest
from pathlib import Path


class AgentGuidanceTest(unittest.TestCase):
    def test_agent_guidance_requires_product_usability(self) -> None:
        text = (Path(__file__).resolve().parents[1] / "agent.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("## 产品工程原则", text)
        self.assertIn("### 可用性", text)
        self.assertIn("### 可维护性", text)
        self.assertIn("### 易上手", text)
        self.assertIn("不得把命令行作为普通用户的主要操作入口", text)
        self.assertIn("加载中、空数据、成功、失败和权限失效", text)
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```powershell
python -B -m unittest tests.test_agent_guidance -v
```

Expected: FAIL，因为 `agent.md` 还没有“产品工程原则”。

- [ ] **Step 3: 写入工程原则**

在 `agent.md` 的“代码生成约束”后新增：

```markdown
## 产品工程原则

### 可用性

1. 用户日常业务操作必须在前端完成，不得把命令行作为普通用户的主要操作入口。
2. 页面必须覆盖加载中、空数据、成功、失败和权限失效状态，并给出可以执行的下一步。
3. 关键操作必须防止重复提交；失败后保留用户已经填写的内容。

### 可维护性

1. 代码按数据请求、状态、渲染和业务动作拆分，避免大函数和跨模块隐式修改状态。
2. 优先复用已有 API、组件和状态映射；同一概念不得出现多套实现和同义状态。
3. 功能变更必须同步自动化测试、用户文档和故障定位信息。

### 易上手

1. 使用医院人员理解的业务语言和合理默认值，不要求用户理解 MCP、manifest、run_key 等内部术语。
2. 高频流程应在少量明确步骤内完成，并在当前操作位置提供说明和错误处理建议。
3. 命令行、原始 JSON 和技术接口只作为开发或实施排障补充，不能替代产品界面。
```

- [ ] **Step 4: 验证并提交**

```powershell
python -B -m unittest tests.test_agent_guidance -v
git diff --check
git add agent.md tests/test_agent_guidance.py
git commit -m "docs: 增加产品工程原则"
git push origin main
```

Expected: 1 test passes.

---

### Task 2: 增加监控工作台骨架与响应式样式

**Files:**
- Modify: `web/index.html`
- Create: `web/monitoring.css`
- Create: `tests/test_monitoring_ui.py`

**Interfaces:**
- Produces: `#monitoringButton`、`#monitoringModal`、三个标签、计划表单和各列表容器。
- Consumes: 现有 `.modal`、`.dialog`、`.btn` 和管理员登录交互。

- [ ] **Step 1: 写失败的结构测试**

创建 `tests/test_monitoring_ui.py`：

```python
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class MonitoringUiTest(unittest.TestCase):
    def test_page_exposes_monitoring_workspace_structure(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        for marker in (
            'id="monitoringButton"',
            'id="monitoringModal"',
            'id="monitoringPlansTab"',
            'id="monitoringResultsTab"',
            'id="monitoringAlertsTab"',
            'id="monitoringPlanList"',
            'id="monitoringPlanDetail"',
            'id="monitoringResultsList"',
            'id="monitoringAlertsList"',
            'id="monitoringPlanForm"',
            'id="monitoringResultRuleFilter"',
            'id="monitoringAlertStatusFilter"',
        ):
            self.assertIn(marker, html)
        self.assertIn('/static/monitoring.css', html)
        self.assertIn('/static/monitoring.js', html)

    def test_monitoring_styles_include_mobile_layout(self) -> None:
        css = (ROOT / "web" / "monitoring.css").read_text(encoding="utf-8")
        self.assertIn(".monitoring-workbench", css)
        self.assertIn(".monitoring-plan-layout", css)
        self.assertIn("@media (max-width: 760px)", css)
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
python -B -m unittest tests.test_monitoring_ui -v
```

Expected: FAIL，因为监控 DOM 和 CSS 不存在。

- [ ] **Step 3: 增加 HTML 骨架**

在顶部操作区增加：

```html
<button id="monitoringButton" class="btn btn-ghost">指标监控</button>
```

在执行链路弹窗前增加 `#monitoringModal`，内容包含：

```html
<div id="monitoringModal" class="modal" hidden>
  <section class="dialog monitoring-dialog" role="dialog" aria-modal="true" aria-labelledby="monitoringTitle">
    <header>
      <div><h2 id="monitoringTitle">指标监控</h2><div id="monitoringMeta">查看运行计划、结果和预警。</div></div>
      <button class="ghost" data-close="monitoringModal">关闭</button>
    </header>
    <div class="monitoring-tabs" role="tablist">
      <button id="monitoringPlansTab" class="monitoring-tab active" type="button">运行计划</button>
      <button id="monitoringResultsTab" class="monitoring-tab" type="button">运行结果</button>
      <button id="monitoringAlertsTab" class="monitoring-tab" type="button">预警处理 <span id="monitoringAlertCount"></span></button>
    </div>
    <div class="monitoring-workbench">
      <section id="monitoringPlansPanel">
        <div class="monitoring-plan-layout">
          <aside><button id="newMonitoringPlanButton" type="button">新建计划</button><div id="monitoringPlanList"></div></aside>
          <div id="monitoringPlanDetail"></div>
        </div>
      </section>
      <section id="monitoringResultsPanel" hidden><input id="monitoringResultRuleFilter" placeholder="指标编码" /><div id="monitoringResultsList"></div></section>
      <section id="monitoringAlertsPanel" hidden><select id="monitoringAlertStatusFilter"><option value="">全部状态</option></select><div id="monitoringAlertsList"></div></section>
    </div>
    <form id="monitoringPlanForm" hidden>
      <input id="monitoringPlanId" type="hidden" />
      <label>指标编码<input id="monitoringRuleId" required /></label>
      <label>计划名称<input id="monitoringPlanName" required /></label>
      <label>运行频率<select id="monitoringFrequency"><option value="daily">每日</option><option value="monthly">每月</option></select></label>
      <label>运行时间<input id="monitoringRunTime" type="time" value="02:00" required /></label>
      <label id="monitoringDayField">每月执行日<input id="monitoringDayOfMonth" type="number" min="1" max="28" value="1" /></label>
      <label><input id="monitoringMomEnabled" type="checkbox" checked />启用环比</label>
      <label>环比阈值<input id="monitoringMomThreshold" type="number" min="0.01" value="20" /></label>
      <label><input id="monitoringYoyEnabled" type="checkbox" checked />启用同比</label>
      <label>同比阈值<input id="monitoringYoyThreshold" type="number" min="0.01" value="30" /></label>
      <button id="cancelMonitoringPlanButton" type="button">取消</button>
      <button id="saveMonitoringPlanButton" type="submit">保存计划</button>
    </form>
  </section>
</div>
<link rel="stylesheet" href="/static/monitoring.css" />
<script src="/static/monitoring.js"></script>
```

表单必须使用 `select`、`input type="time"`、checkbox 和 number 输入控件，取消和保存按钮名称固定为“取消”“保存计划”。

- [ ] **Step 4: 实现响应式 CSS**

创建 `web/monitoring.css`。桌面宽度 `min(1180px, 100%)`，计划布局为 `280px minmax(0, 1fr)`；卡片圆角不超过 8px。`760px` 以下改为单列，标签允许横向滚动，操作按钮换行但不覆盖文本。

关键规则：

```css
.monitoring-dialog { width: min(1180px, 100%); }
.monitoring-plan-layout { display: grid; grid-template-columns: 280px minmax(0, 1fr); min-height: 520px; }
.monitoring-summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); }
@media (max-width: 760px) {
  .monitoring-plan-layout, .monitoring-summary-grid { grid-template-columns: 1fr; }
  .monitoring-dialog { max-height: calc(100vh - 20px); }
}
```

- [ ] **Step 5: 验证并提交**

```powershell
python -B -m unittest tests.test_monitoring_ui -v
git diff --check
git add web/index.html web/monitoring.css tests/test_monitoring_ui.py
git commit -m "feat: 增加指标监控工作台骨架"
git push origin main
```

Expected: structure and responsive tests pass.

---

### Task 3: 实现运行计划管理与手工运行

**Files:**
- Create: `web/monitoring.js`
- Modify: `web/index.html`
- Modify: `tests/test_monitoring_ui.py`

**Interfaces:**
- Produces: `window.openMonitoringWorkbench()`、`loadMonitoringPlans()`、`saveMonitoringPlan()`、`runMonitoringPlan()`。
- Consumes: `/api/monitoring/plans`、全局 `adminToken`、`hospitalIdInput`、`requireAdminThenOpen()`、`showTrace()`。

- [ ] **Step 1: 写失败的计划交互测试**

在 `tests/test_monitoring_ui.py` 增加：

```python
def test_monitoring_script_manages_plans_and_manual_runs(self) -> None:
    js = (ROOT / "web" / "monitoring.js").read_text(encoding="utf-8")
    for marker in (
        "function openMonitoringWorkbench",
        "function loadMonitoringPlans",
        "function loadMonitoringHealth",
        "function saveMonitoringPlan",
        "function setMonitoringPlanStatus",
        "function runMonitoringPlan",
        '"/api/monitoring/plans"',
        '"/enable"',
        '"/disable"',
        '"/run"',
        '"/api/health/summary"',
        "showTrace(data.trace_id)",
    ):
        self.assertIn(marker, js)
    self.assertIn('requireAdminThenOpen("monitoring")', (ROOT / "web" / "index.html").read_text(encoding="utf-8"))
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
python -B -m unittest tests.test_monitoring_ui.MonitoringUiTest.test_monitoring_script_manages_plans_and_manual_runs -v
```

Expected: FAIL，因为 `monitoring.js` 不存在。

- [ ] **Step 3: 实现统一 API 请求与权限回跳**

创建 `web/monitoring.js`，定义：

```javascript
async function monitoringRequest(path, options) {
  var config = options || {};
  config.headers = Object.assign({}, config.headers || {}, {
    "Authorization": "Bearer " + adminToken,
  });
  var response = await fetch(path, config);
  var data = await response.json();
  if (response.status === 401 || response.status === 403) {
    adminToken = "";
    sessionStorage.removeItem("adminToken");
    updateAdminUI();
    requireAdminThenOpen("monitoring");
    throw new Error("管理员登录已失效，请重新登录。");
  }
  if (!response.ok) throw new Error(data.detail || "监控请求失败");
  return data;
}
```

在 `openAdminArea()` 增加 `monitoring` 分支；`#monitoringButton` 点击时调用 `requireAdminThenOpen("monitoring")`。

- [ ] **Step 4: 实现计划状态与渲染**

状态对象只保存：

```javascript
var monitoringState = {
  tab: "plans",
  plans: [],
  selectedPlanId: "",
  results: [],
  alerts: [],
  latestTraceByResultId: {},
};
```

实现计划加载、列表、详情、中文频率与状态映射。无计划时显示“当前医院还没有运行计划”和“新建计划”按钮。选择计划后，详情必须展示频率、时间、环比/同比开关和阈值、启停按钮、编辑和立即运行。

`loadMonitoringHealth()` 读取 `/api/health/summary` 的 `monitoring_scheduler` 项，在工作台标题下显示正常、未启用或异常状态；异常时提供打开系统自检的按钮。监听顶部 `hospitalId` 的 `change` 事件，监控弹窗打开时清空已选计划并重新加载当前标签，防止跨医院保留旧数据。

- [ ] **Step 5: 实现表单、新建、编辑和启停**

表单提交使用 `POST /api/monitoring/plans` 或 `PUT /api/monitoring/plans/{plan_id}`。编辑请求必须包含当前 `hospital_id`。`frequency=monthly` 时显示每月执行日；每日时隐藏。保存和启停期间禁用对应按钮，失败时保留表单和值。

- [ ] **Step 6: 实现手工运行和链路入口**

运行区域提供可空的统计范围输入，留空表示最近完整周期。请求：

```javascript
var data = await monitoringRequest(
  "/api/monitoring/plans/" + encodeURIComponent(planId) + "/run",
  {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({hospital_id: currentHospitalId(), stat_period: period || null}),
  }
);
if (data.trace_id) {
  monitoringState.latestTraceByResultId[String(data.id)] = data.trace_id;
  showTrace(data.trace_id);
}
```

运行成功后刷新计划详情和结果列表；失败时显示“前往恢复中心”按钮，调用 `requireAdminThenOpen("recovery")`。

- [ ] **Step 7: 验证并提交**

```powershell
python -B -m unittest tests.test_monitoring_ui tests.test_api.ApiTest.test_admin_login_returns_to_requested_admin_area -v
git diff --check
git add web/index.html web/monitoring.js tests/test_monitoring_ui.py
git commit -m "feat: 实现指标运行计划前端管理"
git push origin main
```

Expected: plan interactions and admin return tests pass.

---

### Task 4: 实现结果查看和预警处理

**Files:**
- Modify: `web/monitoring.js`
- Modify: `web/monitoring.css`
- Modify: `tests/test_monitoring_ui.py`

**Interfaces:**
- Produces: `loadMonitoringResults()`、`loadMonitoringAlerts()`、`acknowledgeMonitoringAlert()`、`closeMonitoringAlert()`、`diagnoseMonitoringAlert()`。
- Consumes: `/api/monitoring/results`、`/api/monitoring/alerts`、Task 3 `monitoringRequest()` 和 `monitoringState`。

- [ ] **Step 1: 写失败的结果与预警测试**

```python
def test_monitoring_script_handles_results_alerts_and_readable_states(self) -> None:
    js = (ROOT / "web" / "monitoring.js").read_text(encoding="utf-8")
    for marker in (
        "function loadMonitoringResults",
        "function loadMonitoringAlerts",
        "function acknowledgeMonitoringAlert",
        "function closeMonitoringAlert",
        "function diagnoseMonitoringAlert",
        '"/api/monitoring/results?hospital_id="',
        '"/api/monitoring/alerts?hospital_id="',
        '"/acknowledge"',
        '"/close"',
        '"/diagnose"',
        'success: "成功"',
        'no_sample: "无有效样本"',
        'failed: "运行失败"',
        'baseline_insufficient: "缺少历史基线"',
    ):
        self.assertIn(marker, js)
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
python -B -m unittest tests.test_monitoring_ui.MonitoringUiTest.test_monitoring_script_handles_results_alerts_and_readable_states -v
```

Expected: FAIL，因为结果和预警函数不存在。

- [ ] **Step 3: 实现标签切换和独立加载状态**

三个标签只切换各自面板；第一次进入结果或预警标签时加载对应数据，刷新按钮只刷新当前标签。每个容器分别显示“正在读取运行结果”“当前没有运行结果”“正在读取预警”“当前没有预警”。结果标签的指标编码输入变化后重新请求 `rule_id` 筛选；预警标签的状态选项为全部、未处理、已确认、已关闭，变化后重新请求 `status` 筛选。

- [ ] **Step 4: 渲染结果列表**

结果项展示规则编码、统计周期、结果值、环比、同比、触发方式、中文状态、DBHub 耗时和创建时间。变化率为空显示“暂无基线”，不得显示 `null%`。仅当 `latestTraceByResultId[result.id]` 存在时显示“查看执行链路”。

- [ ] **Step 5: 渲染并处理预警**

预警项展示指标、类型、当前值、环比/同比变化率、诊断状态和处理状态。操作规则：

- `open`：显示“确认”和“关闭”；
- `acknowledged`：显示“关闭”；
- `diagnose_status=failed`：显示“重新诊断”；
- `closed`：无写操作按钮。

请求体统一为：

```javascript
JSON.stringify({
  hospital_id: currentHospitalId(),
  actor_id: currentUser ? currentUser.accountId : "admin",
})
```

完成后刷新预警列表和未关闭数量。

- [ ] **Step 6: 验证并提交**

```powershell
python -B -m unittest tests.test_monitoring_ui tests.test_monitoring_api -v
git diff --check
git add web/monitoring.js web/monitoring.css tests/test_monitoring_ui.py
git commit -m "feat: 增加指标结果与预警前端处理"
git push origin main
```

Expected: UI source tests and monitoring API tests pass.

---

### Task 5: 浏览器验收、文档同步和最终回归

**Files:**
- Modify: `README.md`
- Modify: `tests/test_monitoring_ui.py` only if browser acceptance exposes a regression.

**Interfaces:**
- Consumes: Tasks 1-4 complete workbench and the running FastAPI/DBHub services.
- Produces: verified desktop/mobile workflow and user-facing README instructions.

- [ ] **Step 1: 更新 README 前端验证说明**

将命令行示例保留在“实施排障”下；日常验证改为：登录页面 → 指标监控 → 新建计划 → 立即运行 → 运行结果 → 查看执行链路 → 预警处理。明确完整工作台已经可用，不再写“并入第六批”。

- [ ] **Step 2: 运行自动化回归**

```powershell
python -B -m unittest tests.test_agent_guidance tests.test_monitoring_ui tests.test_monitoring_api tests.test_api -v
python -B -m unittest discover -s tests
```

Expected: targeted tests and all tests pass.

- [ ] **Step 3: 使用 in-app Browser 验收桌面端**

打开 `http://127.0.0.1:8765/`，使用医院人员身份进入，点击“指标监控”。验证管理员登录回跳、三个标签、计划新建/编辑/启停、手工运行、结果和预警操作。运行使用带 `UI_ACCEPT_20260711` 标识的临时计划。

- [ ] **Step 4: 验收移动端与视觉稳定性**

使用约 `390x844` 视口检查：弹窗可滚动、计划列表与详情为单列、标签可用、按钮和文字不重叠。使用截图和页面文本检查桌面与移动端；确认浏览器控制台无新增错误。

- [ ] **Step 5: 验证 Trace 安全和清理数据**

手工运行后打开七节点链路，确认真实耗时可见，页面文本不包含 `SELECT` 或 `patient_id`。删除仅由 `UI_ACCEPT_20260711` 标识创建的临时计划、结果、预警和诊断报告，并确认调度器计划数恢复。

- [ ] **Step 6: 最终检查并提交**

```powershell
git diff --check
git status --short
git add README.md
git commit -m "docs: 完善指标监控前端使用说明"
git push origin main
git status --short
git log -5 --oneline
```

Expected: worktree clean, `HEAD == origin/main`, local service remains available at `http://127.0.0.1:8765/`.

## Final Verification Checklist

- [ ] 普通用户无需 PowerShell 即可完成日常监控流程。
- [ ] 管理员登录后自动返回指标监控工作台。
- [ ] 计划新建、编辑、启停和手工运行可用。
- [ ] 结果正确显示聚合值、基线状态和耗时。
- [ ] 预警可以确认、关闭和重新诊断。
- [ ] 本次手工运行可以直接打开七节点执行链路。
- [ ] SQL 和患者明细未出现在页面或 Trace。
- [ ] 桌面和移动端无溢出、重叠和不可达操作。
- [ ] `agent.md` 已包含可用性、可维护性和易上手硬约束。
- [ ] 全量自动化测试通过，验收临时数据已删除，提交已推送。
