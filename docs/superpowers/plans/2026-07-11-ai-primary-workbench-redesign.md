# AI 主入口工作台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 AI 指标助手改为登录后的默认完整首页，同时保留专业业务页中的同会话辅助抽屉，并修复监控页面顶部大面积空白。

**Architecture:** 在现有原生 HTML/CSS/JavaScript 工作台中新增 `assistant` 路由和 AI 首页挂载点。现有聊天 DOM 只保留一份，由 `workbench.js` 在 AI 首页与右侧抽屉之间移动；路由表显式声明页面权限，只有 `monitoring` 需要管理员验证。现有 SSE、会话记忆、Trace、监控 API 和后端 Agent 编排保持不变。

**Tech Stack:** 原生 HTML/CSS/JavaScript、FastAPI 静态资源、Python `unittest`、Node.js 语法检查、应用内浏览器验收。

## Global Constraints

- 默认路由和未知 Hash 回退目标必须是 `#/assistant`。
- 普通医院人员登录后不得自动弹出管理员登录。
- `#/monitoring` 必须在进入时验证管理员权限，验证成功后仍停留在该路由。
- AI 首页和业务页抽屉必须复用唯一一套 `#messages`、`#chatForm`、会话 ID、SSE 和 Trace 状态。
- 不重写聊天、会话记忆、Trace、监控 API 或后端 Agent 编排。
- 当前只开放已完成的 AI 首页和指标运算监控，不创建其余四页空壳。
- 桌面端和 `390x844` 移动端不得出现横向溢出、遮挡、按钮文字换行或大面积加载占位。
- 每个任务遵守 RED -> GREEN -> 验证 -> 中文 Conventional Commit -> 推送 `main`。

---

### Task 1: 建立 AI 默认路由和页面权限

**Files:**

- Modify: `tests/test_workbench_ui.py`
- Modify: `web/index.html`
- Modify: `web/workbench.js`

**Interfaces:**

- Consumes: 全局 `currentUser`、`adminToken`、`requireAdminThenOpen(area)`、`window.activateMonitoringPage()`。
- Produces: `WORKBENCH_ROUTES`、`currentWorkbenchRoute()`、`navigateWorkbench(route)`、`applyWorkbenchRoute()`；新增 `assistant` 和 `monitoring` 两个路由。

- [ ] **Step 1: 编写 AI 默认路由失败测试**

在 `tests/test_workbench_ui.py` 增加：

```python
def test_ai_is_default_route_and_monitoring_alone_requires_admin(self) -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
    js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

    self.assertIn('id="assistantPage"', html)
    self.assertIn('data-workbench-route="assistant"', html)
    self.assertIn('data-workbench-route="monitoring"', html)
    self.assertIn('assistant: {requiresAdmin: false}', js)
    self.assertIn('monitoring: {requiresAdmin: true}', js)
    self.assertIn('return WORKBENCH_ROUTES[route] ? route : "assistant"', js)
    self.assertIn('var target = "#/" + (WORKBENCH_ROUTES[route] ? route : "assistant")', js)
    self.assertIn('if (definition.requiresAdmin && !adminToken)', js)
```

同时把旧测试中默认只接受 `monitoring` 的断言改为同时检查 `"#/assistant"` 和 `"#/monitoring"`。

- [ ] **Step 2: 运行测试确认 RED**

```powershell
python -B -m unittest tests.test_workbench_ui.WorkbenchUiTest.test_ai_is_default_route_and_monitoring_alone_requires_admin -v
```

Expected: FAIL，因为 `assistantPage` 和路由权限表尚不存在。

- [ ] **Step 3: 增加 AI 页面和导航入口**

在 `web/index.html` 的左侧导航中把 AI 放在第一位：

```html
<div class="workbench-nav-heading">智能入口</div>
<button class="workbench-nav-item active" type="button" data-workbench-route="assistant" aria-current="page">
  <span class="workbench-nav-mark">AI</span>
  <span>AI 指标助手</span>
</button>
<div class="workbench-nav-heading workbench-nav-heading-secondary">业务工作台</div>
<button class="workbench-nav-item" type="button" data-workbench-route="monitoring">
  <span class="workbench-nav-mark">监控</span>
  <span>指标运算监控</span>
</button>
```

在 `#workbenchContent` 中增加默认页面挂载点：

```html
<section id="assistantPage" class="workbench-page assistant-page" data-route="assistant" aria-labelledby="assistantPageTitle">
  <header class="assistant-page-heading">
    <span>AI 指标助手</span>
    <h1 id="assistantPageTitle">今天需要处理哪个指标？</h1>
    <p>问口径、生成 SQL、试运行或诊断异常，结果可以继续进入对应业务页面处理。</p>
  </header>
  <div id="assistantHomeMount" class="assistant-home-mount"></div>
</section>
```

- [ ] **Step 4: 实现路由权限表**

将 `web/workbench.js` 的单路由判断替换为：

```javascript
var WORKBENCH_ROUTES = {
  assistant: {requiresAdmin: false},
  monitoring: {requiresAdmin: true},
};

function currentWorkbenchRoute() {
  var route = window.location.hash.replace(/^#\/?/, "");
  return WORKBENCH_ROUTES[route] ? route : "assistant";
}

function navigateWorkbench(route) {
  var target = "#/" + (WORKBENCH_ROUTES[route] ? route : "assistant");
  if (window.location.hash === target) {
    applyWorkbenchRoute();
    return;
  }
  window.location.hash = target;
}
```

`applyWorkbenchRoute()` 按路由显示页面，只有权限路由才验证管理员：

```javascript
function applyWorkbenchRoute() {
  var route = currentWorkbenchRoute();
  var definition = WORKBENCH_ROUTES[route];
  mountWorkbenchPages();
  document.querySelectorAll(".workbench-page").forEach(function(page) {
    page.hidden = page.dataset.route !== route;
  });
  updateWorkbenchNavigation(route);
  workbenchLoading.hidden = true;
  if (!currentUser) return;
  if (definition.requiresAdmin && !adminToken) {
    requireAdminThenOpen(route);
    return;
  }
  if (route === "monitoring") window.activateMonitoringPage();
}
```

`initializeWorkbench()` 在已登录时进入当前合法 Hash；空 Hash 自动进入 AI：

```javascript
function initializeWorkbench() {
  mountWorkbenchPages();
  if (!currentUser) return;
  navigateWorkbench(currentWorkbenchRoute());
}
```

- [ ] **Step 5: 验证并提交**

```powershell
python -B -m unittest tests.test_workbench_ui tests.test_api.ApiTest.test_admin_login_returns_to_requested_admin_area -v
node --check web/workbench.js
git diff --check
git add tests/test_workbench_ui.py web/index.html web/workbench.js
git commit -m "feat: 将 AI 助手设为工作台默认首页"
git push origin main
```

Expected: 测试通过；普通登录不会调用 `requireAdminThenOpen("monitoring")`。

---

### Task 2: 在首页与业务抽屉间共享聊天工作区

**Files:**

- Modify: `tests/test_workbench_ui.py`
- Modify: `web/index.html`
- Modify: `web/workbench.js`

**Interfaces:**

- Consumes: Task 1 的 `currentWorkbenchRoute()` 和 `applyWorkbenchRoute()`；现有 `messages`、`queryInput`、`addWelcomeMessage()`。
- Produces: `mountAssistantWorkspace(target)`、`showAssistantPage()`、`openAssistantDrawer()`、`closeAssistantDrawer()`、`ensureAssistantWelcome()`。

- [ ] **Step 1: 编写唯一聊天工作区失败测试**

在 `tests/test_workbench_ui.py` 增加：

```python
def test_single_chat_workspace_moves_between_home_and_drawer(self) -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
    js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

    self.assertEqual(html.count('id="assistantWorkspace"'), 1)
    self.assertEqual(html.count('id="messages"'), 1)
    self.assertEqual(html.count('id="chatForm"'), 1)
    self.assertIn('id="assistantHomeMount"', html)
    self.assertIn('id="assistantDrawerMount"', html)
    self.assertIn("function mountAssistantWorkspace", js)
    self.assertIn("function ensureAssistantWelcome", js)
    self.assertIn('mountAssistantWorkspace("home")', js)
    self.assertIn('mountAssistantWorkspace("drawer")', js)
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
python -B -m unittest tests.test_workbench_ui.WorkbenchUiTest.test_single_chat_workspace_moves_between_home_and_drawer -v
```

Expected: FAIL，因为共享工作区和两个挂载点尚未建立。

- [ ] **Step 3: 重组唯一聊天 DOM**

将现有聊天区包装为唯一工作区，并默认放入 `#assistantHomeMount`：

```html
<div id="assistantHomeMount" class="assistant-home-mount">
  <div id="assistantWorkspace" class="assistant-workspace">
    <header class="assistant-workspace-header">
      <div><strong>当前会话</strong><span id="sessionLabel"></span></div>
      <button id="newSessionButton" class="ghost" type="button">新会话</button>
    </header>
    <div class="chat-layout assistant-chat-layout">
      <section id="messages" class="messages" aria-live="polite"></section>
    </div>
    <footer class="composer-wrapper assistant-composer-wrapper">
      <form id="chatForm" class="composer">
        <textarea id="queryInput" rows="1" placeholder="输入指标问题或任务，例如：生成急会诊及时到位率 SQL"></textarea>
        <button id="sendButton" type="submit">发送</button>
      </form>
      <div class="composer-hint"><span>回答会优先使用本院生效口径。</span></div>
    </footer>
  </div>
</div>
```

抽屉只保留外壳和目标挂载点：

```html
<aside id="assistantDrawer" class="assistant-drawer" hidden aria-label="AI 助手">
  <header class="assistant-drawer-header">
    <strong>AI 指标助手</strong>
    <button id="assistantCloseButton" class="ghost assistant-close" type="button" aria-label="关闭 AI 助手">关闭</button>
  </header>
  <div id="assistantDrawerMount" class="assistant-drawer-mount"></div>
</aside>
```

- [ ] **Step 4: 实现工作区迁移和欢迎态去重**

在 `web/workbench.js` 增加：

```javascript
var assistantWorkspace = document.getElementById("assistantWorkspace");
var assistantHomeMount = document.getElementById("assistantHomeMount");
var assistantDrawerMount = document.getElementById("assistantDrawerMount");

function mountAssistantWorkspace(target) {
  var mount = target === "drawer" ? assistantDrawerMount : assistantHomeMount;
  if (assistantWorkspace.parentElement !== mount) mount.appendChild(assistantWorkspace);
  assistantWorkspace.classList.toggle("compact", target === "drawer");
}

function ensureAssistantWelcome() {
  if (!messages.children.length) addWelcomeMessage();
}

function showAssistantPage() {
  assistantDrawer.hidden = true;
  assistantToggleButton.hidden = true;
  assistantToggleButton.setAttribute("aria-expanded", "false");
  mountAssistantWorkspace("home");
  ensureAssistantWelcome();
}

function openAssistantDrawer() {
  if (currentWorkbenchRoute() === "assistant") return;
  mountAssistantWorkspace("drawer");
  assistantDrawer.hidden = false;
  assistantToggleButton.setAttribute("aria-expanded", "true");
  messages.scrollTop = messages.scrollHeight;
  queryInput.focus();
}

function closeAssistantDrawer() {
  assistantDrawer.hidden = true;
  assistantToggleButton.setAttribute("aria-expanded", "false");
  assistantToggleButton.focus();
}
```

`applyWorkbenchRoute()` 在 `assistant` 路由调用 `showAssistantPage()`；业务路由显示 `assistantToggleButton` 并默认关闭抽屉。删除登录点击处理器中直接调用 `addWelcomeMessage()` 的语句，统一由 `ensureAssistantWelcome()` 保证首次只创建一次欢迎消息。

- [ ] **Step 5: 验证并提交**

```powershell
python -B -m unittest tests.test_workbench_ui tests.test_api.ApiTest.test_chat_stream_returns_sse_events -v
node --check web/workbench.js
git diff --check
git add tests/test_workbench_ui.py web/index.html web/workbench.js
git commit -m "refactor: 复用 AI 首页与业务页聊天会话"
git push origin main
```

Expected: HTML 中聊天相关 ID 均唯一；切换挂载点不清空 DOM。

---

### Task 3: 重塑视觉层级并修复监控首屏空白

**Files:**

- Modify: `tests/test_workbench_ui.py`
- Modify: `tests/test_monitoring_ui.py`
- Modify: `web/index.html`
- Modify: `web/workbench.css`

**Interfaces:**

- Consumes: Task 2 的 `.assistant-workspace`、`.compact`、`.assistant-home-mount`、`.assistant-drawer-mount`。
- Produces: AI 首页完整布局、业务页紧凑抽屉、系统工具菜单、无残留加载占位的监控首屏。

- [ ] **Step 1: 编写布局和空白修复失败测试**

在 `tests/test_workbench_ui.py` 增加：

```python
def test_ai_home_is_primary_and_loading_placeholder_collapses(self) -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
    css = (ROOT / "web" / "workbench.css").read_text(encoding="utf-8")

    self.assertIn('class="assistant-page-heading"', html)
    self.assertIn('id="systemToolsMenu"', html)
    self.assertIn(".assistant-page", css)
    self.assertIn(".assistant-home-mount", css)
    self.assertIn(".assistant-workspace.compact", css)
    self.assertIn(".workbench-loading[hidden]", css)
    self.assertIn("display: none", css[css.index(".workbench-loading[hidden]"):])
```

在 `tests/test_monitoring_ui.py` 增加：

```python
def test_monitoring_page_starts_at_top_of_workbench(self) -> None:
    css = (ROOT / "web" / "workbench.css").read_text(encoding="utf-8")
    self.assertIn(".workbench-page[hidden]", css)
    self.assertIn(".monitoring-page-surface", css)
    self.assertNotIn("min-height: 420px", css)
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
python -B -m unittest tests.test_workbench_ui.WorkbenchUiTest.test_ai_home_is_primary_and_loading_placeholder_collapses tests.test_monitoring_ui.MonitoringUiTest.test_monitoring_page_starts_at_top_of_workbench -v
```

Expected: FAIL，因为 AI 首页样式、系统工具菜单和显式 `[hidden]` 规则尚未实现。

- [ ] **Step 3: 收拢顶栏系统工具**

在 `web/index.html` 中用原生 `details` 保持零依赖：

```html
<details id="systemToolsMenu" class="system-tools-menu">
  <summary>系统工具</summary>
  <div class="system-tools-popover">
    <button id="selfCheckButton" class="workbench-tool-action" type="button">系统自检</button>
    <button id="recoveryButton" class="workbench-tool-action" type="button">恢复中心</button>
  </div>
</details>
<button id="assistantToggleButton" class="btn btn-secondary" type="button" aria-expanded="false" aria-controls="assistantDrawer" hidden>AI 助手</button>
```

保留原按钮 ID，既有事件监听无需改写。

- [ ] **Step 4: 实现 AI 主页面和紧凑抽屉样式**

在 `web/workbench.css` 中增加明确的三段布局：

```css
.workbench-page[hidden],
.workbench-loading[hidden],
.assistant-drawer[hidden] {
  display: none;
}

.assistant-page {
  min-height: 100%;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  max-width: 1040px;
}

.assistant-page-heading {
  padding: 18px 8px 20px;
}

.assistant-page-heading h1 {
  margin: 6px 0;
  font-size: 28px;
  letter-spacing: 0;
}

.assistant-home-mount,
.assistant-workspace {
  min-height: 0;
  height: 100%;
}

.assistant-workspace {
  display: grid;
  grid-template-rows: 52px minmax(0, 1fr) auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
}

.assistant-workspace.compact {
  border: 0;
  border-radius: 0;
}
```

首页消息区保持舒适宽度；`.compact` 下复用现有 400px 抽屉字号和间距。移除旧 `.workbench-loading` 的 `min-height: 420px`，让加载节点隐藏后不占布局。监控页保持 `margin: 0 auto` 且从主内容顶部开始。

- [ ] **Step 5: 完成移动端规则**

在现有 `@media (max-width: 760px)` 中加入：

```css
.assistant-page {
  min-height: calc(100vh - 150px);
}

.assistant-page-heading h1 {
  font-size: 22px;
}

.assistant-home-mount {
  min-height: 0;
}

.assistant-drawer {
  width: 100vw;
}

.system-tools-popover {
  position: fixed;
  right: 10px;
}
```

确保主区、消息区和输入区只有一个可达的垂直滚动区域；导航横向滚动但页面本身不横向溢出。

- [ ] **Step 6: 验证并提交**

```powershell
python -B -m unittest tests.test_workbench_ui tests.test_monitoring_ui -v
node --check web/workbench.js
node --check web/monitoring.js
git diff --check
git add tests/test_workbench_ui.py tests/test_monitoring_ui.py web/index.html web/workbench.css
git commit -m "feat: 重塑 AI 主入口工作台布局"
git push origin main
```

Expected: 布局测试通过；显式 `[hidden]` 规则消除截图中的加载占位空白。

---

### Task 4: 更新文档并完成端到端回归

**Files:**

- Modify: `README.md`
- Modify: implementation files only when acceptance exposes a regression.

**Interfaces:**

- Consumes: 完整 AI 首页、监控业务页和共享 AI 抽屉。
- Produces: 最终用户说明、桌面/移动验收证据、干净且已推送的 `main`。

- [ ] **Step 1: 更新 README 使用说明**

将“五页面业务工作台”改为以下事实：

```markdown
- 登录后默认进入 `#/assistant` AI 指标助手首页，普通问答不需要管理员权限。
- 从左侧进入“指标运算监控”时才验证管理员权限。
- 专业页面右上角可打开 AI 助手，返回 AI 首页后继续同一会话。
- 系统自检和恢复中心位于顶部“系统工具”菜单。
```

删除“登录后直接进入 `#/monitoring`”的旧说明。

- [ ] **Step 2: 桌面浏览器验收**

在 `http://127.0.0.1:8765/` 验证：

1. 清除当前登录状态后使用医院人员登录。
2. URL 为 `#/assistant`，不弹管理员登录。
3. 发送“急会诊及时到位率怎么算？”，流式回答和执行链路入口正常。
4. 点击“指标运算监控”，管理员验证后 URL 保持 `#/monitoring`。
5. 监控内容从主区顶部开始，无“正在打开业务工作台”残留和大面积空白。
6. 打开 AI 抽屉，确认原会话仍在；关闭后监控筛选和选中计划不丢失。
7. 返回 AI 首页，确认同一会话继续显示。
8. 控制台没有新增错误。

- [ ] **Step 3: 移动端浏览器验收**

使用 `390x844` 视口验证：

1. AI 首页、消息区和输入框均可见且可滚动。
2. 横向导航可达 AI 和监控入口。
3. 页面无横向溢出，按钮文字不换行。
4. 监控计划列表与详情单列展示。
5. AI 抽屉全屏且可关闭。

- [ ] **Step 4: 运行完整自动化回归**

```powershell
python -B -m unittest tests.test_workbench_ui tests.test_monitoring_ui tests.test_monitoring_api tests.test_api -v
python -B -m unittest discover -s tests
node --check web/workbench.js
node --check web/monitoring.js
git diff --check
```

Expected: 定向测试和全量测试均为 `OK`；Node 语法检查及差异检查退出码为 `0`。

- [ ] **Step 5: 运行服务自检**

```powershell
$health = Invoke-RestMethod -Uri http://127.0.0.1:8765/api/health/summary
$health.status
$health.items | Select-Object key,status,problem_code
```

Expected: `status` 为 `ok`，FastAPI、运行数据库、DBHub、业务数据库 MCP、指标调度器和 LangGraph 均为 `ok`。

- [ ] **Step 6: 提交并推送文档或验收修复**

```powershell
git add README.md
git commit -m "docs: 更新 AI 主入口工作台使用说明"
git push origin main
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
```

Expected: 工作区干净，`HEAD` 与 `origin/main` 一致，服务继续运行在 `http://127.0.0.1:8765/`。
