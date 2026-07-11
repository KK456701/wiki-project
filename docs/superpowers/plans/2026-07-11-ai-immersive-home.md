# AI 沉浸式首页实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让桌面端 AI 首页隐藏传统顶栏并使用 64px 工具轨道，使对话工作区从窗口顶部约 12px 开始，同时业务页和移动端维持完整工作台。

**Architecture:** `workbench.js` 通过 `assistant-immersive` class 显式切换工作台模式。现有顶栏、医院输入、用户身份和系统工具不复制，桌面沉浸模式使用 CSS 将 `.topbar-actions` 固定到右上角；导航按钮使用 `data-short` 和 `title` 在工具轨道中显示短标识。业务页移除该 class 后恢复原布局。

**Tech Stack:** 原生 HTML/CSS/JavaScript、Python `unittest`、Node.js 语法检查。

## Global Constraints

- 沉浸式布局只用于 `#/assistant` 且只在大于 `760px` 的桌面布局生效。
- 医院输入、用户身份、系统工具、聊天消息和新会话按钮不得复制 DOM。
- `showAssistantPage()` 增加 `assistant-immersive`，`prepareBusinessPage()` 移除。
- AI 首页传统品牌顶栏隐藏；对话工作区与窗口边缘约 `12px`。
- 工具轨道约 `64px`，入口至少 `44px` 高并提供 `title`、悬停提示和焦点样式。
- 业务页恢复完整顶栏和 224px 侧栏；移动端恢复现有顶栏和横向导航。
- 不修改聊天 SSE、会话记忆、Trace、监控 API、权限接口或后端 Agent。
- 每批完成后运行测试、创建中文 Conventional Commit 并推送 `main`。

---

### Task 1: 建立沉浸模式开关和导航语义

**Files:**

- Modify: `tests/test_workbench_ui.py`
- Modify: `web/index.html`
- Modify: `web/workbench.js`

**Interfaces:**

- Consumes: `showAssistantPage()`、`prepareBusinessPage()`、`#workbenchShell`、现有导航和工具按钮。
- Produces: `workbenchShell.classList.add/remove("assistant-immersive")`；导航 `title` 和 `data-short`。

- [ ] **Step 1: 编写失败测试**

```python
def test_assistant_route_toggles_immersive_mode(self) -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
    js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

    self.assertIn('var workbenchShell = document.getElementById("workbenchShell")', js)
    self.assertIn('workbenchShell.classList.add("assistant-immersive")', js)
    self.assertIn('workbenchShell.classList.remove("assistant-immersive")', js)
    self.assertIn('data-short="稿"', html)
    self.assertIn('data-short="审"', html)
    self.assertIn('data-short="库"', html)
    self.assertIn('title="指标设计稿"', html)
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
python -B -m unittest tests.test_workbench_ui.WorkbenchUiTest.test_assistant_route_toggles_immersive_mode -v
```

Expected: FAIL，因为模式 class 和工具短标识尚不存在。

- [ ] **Step 3: 增加工具按钮语义**

修改 `web/index.html`：

```html
<button id="indicatorDraftButton" class="workbench-tool-item" type="button" data-short="稿" title="指标设计稿">指标设计稿</button>
<button id="reviewButton" class="workbench-tool-item" type="button" data-short="审" title="审批与版本">审批与版本</button>
<button id="mcpButton" class="workbench-tool-item" type="button" data-short="库" title="数据库与元数据">数据库与元数据</button>
```

为 AI 和监控导航补 `title="AI 指标助手"` 与 `title="指标运算监控"`。

- [ ] **Step 4: 实现模式 class 切换**

在 `web/workbench.js` 增加 DOM 引用：

```javascript
var workbenchShell = document.getElementById("workbenchShell");
```

修改页面准备函数：

```javascript
function showAssistantPage() {
  workbenchShell.classList.add("assistant-immersive");
  assistantDrawer.hidden = true;
  assistantToggleButton.hidden = true;
  assistantToggleButton.setAttribute("aria-expanded", "false");
  mountAssistantWorkspace("home");
  ensureAssistantWelcome();
}

function prepareBusinessPage() {
  workbenchShell.classList.remove("assistant-immersive");
  assistantDrawer.hidden = true;
  assistantToggleButton.hidden = false;
  assistantToggleButton.setAttribute("aria-expanded", "false");
  mountAssistantWorkspace("drawer");
}
```

- [ ] **Step 5: 验证并提交**

```powershell
python -B -m unittest tests.test_workbench_ui -v
node --check web/workbench.js
git diff --check
git add tests/test_workbench_ui.py web/index.html web/workbench.js
git commit -m "feat: 增加 AI 首页沉浸模式开关"
git push origin main
```

---

### Task 2: 实现桌面工具轨道和全高对话布局

**Files:**

- Modify: `tests/test_workbench_ui.py`
- Modify: `web/workbench.css`

**Interfaces:**

- Consumes: Task 1 的 `.assistant-immersive`、`data-short`、导航 `title`。
- Produces: 桌面沉浸布局、工具提示、浮动上下文控件、移动端完整布局回退。

- [ ] **Step 1: 编写失败测试**

```python
def test_immersive_assistant_uses_rail_and_full_height_canvas(self) -> None:
    css = (ROOT / "web" / "workbench.css").read_text(encoding="utf-8")

    self.assertIn("@media (min-width: 761px)", css)
    self.assertIn(".assistant-immersive", css)
    self.assertIn("grid-template-columns: 64px minmax(0, 1fr)", css)
    self.assertIn(".assistant-immersive .workbench-topbar", css)
    self.assertIn("display: contents", css)
    self.assertIn(".assistant-immersive .topbar-actions", css)
    self.assertIn("position: fixed", css)
    self.assertIn(".assistant-immersive .assistant-page-heading", css)
    self.assertIn("display: none", css)
    self.assertIn("content: attr(data-short)", css)
    self.assertIn("content: attr(title)", css)
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
python -B -m unittest tests.test_workbench_ui.WorkbenchUiTest.test_immersive_assistant_uses_rail_and_full_height_canvas -v
```

Expected: FAIL，因为沉浸模式尚无 CSS。

- [ ] **Step 3: 增加桌面沉浸布局**

在 `web/workbench.css` 增加仅桌面生效的规则：

```css
@media (min-width: 761px) {
  .workbench-shell.assistant-immersive {
    grid-template-columns: 64px minmax(0, 1fr);
    grid-template-rows: minmax(0, 1fr);
  }

  .assistant-immersive .workbench-topbar {
    display: contents;
  }

  .assistant-immersive .workbench-topbar .brand {
    display: none;
  }

  .assistant-immersive .topbar-actions {
    position: fixed;
    top: 12px;
    right: 16px;
    z-index: 70;
    padding: 6px;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.96);
    box-shadow: 0 10px 26px rgba(23, 35, 32, 0.12);
  }

  .assistant-immersive .workbench-nav {
    grid-column: 1;
    grid-row: 1;
    padding: 8px 6px;
    background: var(--ink);
  }

  .assistant-immersive .workbench-content {
    grid-column: 2;
    grid-row: 1;
    padding: 12px;
  }

  .assistant-immersive .assistant-page-heading {
    display: none;
  }
}
```

- [ ] **Step 4: 完成工具轨道和提示**

在同一媒体查询中：

```css
.assistant-immersive .workbench-nav-heading,
.assistant-immersive .workbench-nav-item > span:last-child {
  display: none;
}

.assistant-immersive .workbench-nav-item,
.assistant-immersive .workbench-tool-item {
  min-height: 44px;
  justify-content: center;
  padding: 0;
  color: #fff;
  font-size: 0;
}

.assistant-immersive .workbench-tool-item::before {
  content: attr(data-short);
  font-size: 12px;
  font-weight: 850;
}

.assistant-immersive .workbench-nav [title] {
  position: relative;
}

.assistant-immersive .workbench-nav [title]::after {
  content: attr(title);
  position: absolute;
  left: 58px;
  z-index: 80;
  display: none;
  white-space: nowrap;
}

.assistant-immersive .workbench-nav [title]:hover::after,
.assistant-immersive .workbench-nav [title]:focus-visible::after {
  display: block;
}
```

提示样式使用白底、墨黑文字和轻阴影；选中导航保持医疗青底色。

- [ ] **Step 5: 扩展会话栏品牌语义**

通过 CSS 在首页当前会话栏前增加产品识别，不复制 DOM：

```css
.assistant-immersive .assistant-workspace-header strong::before {
  content: "核心制度指标 Agent · ";
}
```

为右上角浮动控件留出安全区：会话栏右侧增加足够内边距，避免“新会话”被遮挡。

- [ ] **Step 6: 验证并提交**

```powershell
python -B -m unittest tests.test_workbench_ui tests.test_monitoring_ui -v
node --check web/workbench.js
node --check web/monitoring.js
git diff --check
git add tests/test_workbench_ui.py web/workbench.css
git commit -m "feat: 实现 AI 沉浸式工作台布局"
git push origin main
```

---

### Task 3: 文档、全量回归与运行态核对

**Files:**

- Modify: `README.md`
- Modify: implementation files only when regression exposes a defect.

- [ ] **Step 1: 更新 README**

在“五页面业务工作台”中说明：

```markdown
- 桌面端 AI 首页使用沉浸式工具轨道，传统顶栏在该页面隐藏。
- 医院、用户和系统工具固定在 AI 首页右上角；进入业务页后完整顶栏恢复。
- 移动端继续使用横向导航，不采用窄工具轨道。
```

- [ ] **Step 2: 运行完整回归**

```powershell
python -B -m unittest discover -s tests
node --check web/workbench.js
node --check web/monitoring.js
git diff --check
```

Expected: 全量测试 `OK`，语法与差异检查退出码 `0`。

- [ ] **Step 3: 检查真实页面与依赖**

```powershell
$page = (Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8765/).Content
$health = Invoke-RestMethod http://127.0.0.1:8765/api/health/summary
$health.status
```

Expected: 页面包含 `assistantPage`，系统状态为 `ok` 且六项依赖全部正常。

- [ ] **Step 4: 提交文档并核对远端**

```powershell
git add README.md
git commit -m "docs: 补充 AI 沉浸式首页说明"
git push origin main
git fetch origin main
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
```

Expected: 工作区干净，`HEAD` 与 `origin/main` 一致。
