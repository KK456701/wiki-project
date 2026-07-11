# 五页面业务工作台第一批实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将现有聊天首页改造成业务工作台外壳，把指标监控迁为首个正式页面，并把聊天改为可收起 AI 助手，使用户无需打开监控弹窗即可完成计划、结果和预警操作。

**Architecture:** 保留 `web/index.html` 的登录、现有弹窗和全局脚本，新增 `web/workbench.css` 与 `web/workbench.js` 承担工作台布局、Hash 路由和 AI 助手状态。监控 DOM 从 `#monitoringModal` 弹窗迁入 `#monitoringPage`，`web/monitoring.js` 继续负责原业务逻辑，只把显示/隐藏和管理员回跳改为页面语义。第一批导航只开放已经完整可用的指标监控；其他四页在各自批次完成时再开放，避免空白占位页面。

**Tech Stack:** 原生 HTML/CSS/JavaScript、FastAPI 静态资源、Python `unittest`、Codex in-app Browser。

## 全局约束

- 工作台第一屏必须是可操作的指标监控，不显示营销说明或空白页面。
- 页面路由使用 `#/monitoring`；第一批遇到未知 Hash 统一回到该页面。
- 原聊天消息、输入框、SSE、会话记忆和 Trace 逻辑不得重写，只迁移容器和开关。
- 管理员登录失效后重新登录，必须返回 `#/monitoring`，不得跳到审批。
- 监控页面继续显式使用顶部医院 ID；切换医院后重新加载页面数据。
- 不复制监控 DOM，不同时维护页面版和弹窗版两套业务结构。
- 页面和手机端不得出现横向页面溢出、遮挡、按钮文字换行或双重不可达滚动区。
- 每个任务遵守 RED -> GREEN -> 验证 -> 中文 Conventional Commit -> 推送 `main`。

---

### Task 1：增加工作台结构契约和基础骨架

**Files:**

- Create: `tests/test_workbench_ui.py`
- Modify: `web/index.html`
- Create: `web/workbench.css`

**Produces:** `#workbenchShell`、`#workbenchNav`、`#workbenchContent`、`#monitoringPage`、`#assistantDrawer` 和响应式布局。

- [ ] **Step 1：编写失败的结构测试**

创建 `tests/test_workbench_ui.py`，断言：

```python
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class WorkbenchUiTest(unittest.TestCase):
    def test_page_exposes_real_workbench_shell(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        for marker in (
            'id="workbenchShell"',
            'id="workbenchNav"',
            'id="workbenchContent"',
            'id="monitoringPage"',
            'id="assistantToggleButton"',
            'id="assistantDrawer"',
            'id="assistantCloseButton"',
        ):
            self.assertIn(marker, html)
        self.assertIn('/static/workbench.css', html)
        self.assertIn('/static/workbench.js', html)
        self.assertNotIn('id="monitoringModal" class="modal"', html)

    def test_workbench_css_has_desktop_and_mobile_layouts(self) -> None:
        css = (ROOT / "web" / "workbench.css").read_text(encoding="utf-8")
        self.assertIn(".workbench-shell", css)
        self.assertIn("grid-template-columns: 224px minmax(0, 1fr)", css)
        self.assertIn(".assistant-drawer", css)
        self.assertIn("@media (max-width: 760px)", css)
```

- [ ] **Step 2：运行测试确认 RED**

```powershell
python -B -m unittest tests.test_workbench_ui -v
```

Expected: FAIL，因为工作台 DOM 和 CSS 尚不存在。

- [ ] **Step 3：重组 HTML 骨架**

登录页继续保留。登录后的 `.app-shell` 改为：

```html
<div id="workbenchShell" class="workbench-shell">
  <header class="workbench-topbar">...</header>
  <aside id="workbenchNav" class="workbench-nav">...</aside>
  <main id="workbenchContent" class="workbench-content">
    <section id="monitoringPage" class="workbench-page" data-route="monitoring">...</section>
  </main>
  <aside id="assistantDrawer" class="assistant-drawer" hidden>...</aside>
</div>
```

顶部保留品牌、医院、身份、自检、恢复中心和 AI 助手按钮。删除顶部监控入口；MCP、指标设计稿和审批原入口暂保留在兼容区域但默认不作为主导航展示，后续页面迁移后删除。

监控页面移动现有监控标题、三个标签、列表、详情和表单 DOM，移除 `modal/dialog/关闭` 语义，禁止复制节点。

AI 助手抽屉中移动现有：

- `#messages`；
- `#chatForm`；
- `#queryInput`；
- `#sendButton`；
- `#sessionLabel`。

- [ ] **Step 4：实现工作台 CSS**

创建 `web/workbench.css`：

- 桌面：`224px minmax(0, 1fr)` 两列；
- 顶栏固定在工作台顶部；
- 主区独立滚动；
- 监控页为无装饰页面内容，不再有弹窗阴影；
- AI 抽屉从右侧覆盖，宽度 `min(400px, 100vw)`；
- `760px` 以下导航变成顶部选择区，主区单列，AI 抽屉全屏；
- 按钮、页面标题、医院输入框使用稳定尺寸和 `white-space: nowrap`；
- 不添加装饰性渐变、圆球或营销大标题。

- [ ] **Step 5：验证并提交**

```powershell
python -B -m unittest tests.test_workbench_ui tests.test_monitoring_ui -v
git diff --check
git add web/index.html web/workbench.css tests/test_workbench_ui.py tests/test_monitoring_ui.py
git commit -m "feat: 增加五页面工作台基础骨架"
git push origin main
```

监控旧结构测试需要同步从 `monitoringModal` 改为 `monitoringPage`，但监控标签、列表和表单 ID 必须保持不变。

---

### Task 2：实现 Hash 路由与页面状态

**Files:**

- Modify: `tests/test_workbench_ui.py`
- Create: `web/workbench.js`
- Modify: `web/index.html`

**Produces:** `navigateWorkbench()`、`applyWorkbenchRoute()`、`currentWorkbenchRoute()`。

- [ ] **Step 1：编写失败的路由测试**

增加测试断言 `workbench.js` 包含：

```python
for marker in (
    "function currentWorkbenchRoute",
    "function navigateWorkbench",
    "function applyWorkbenchRoute",
    'window.addEventListener("hashchange"',
    '"#/monitoring"',
    "window.openMonitoringWorkbench",
):
    self.assertIn(marker, js)
```

并断言 HTML 的监控导航项使用 `data-workbench-route="monitoring"` 和 `aria-current="page"`。

- [ ] **Step 2：运行测试确认 RED**

```powershell
python -B -m unittest tests.test_workbench_ui.WorkbenchUiTest.test_workbench_script_routes_to_monitoring -v
```

- [ ] **Step 3：实现最小路由**

`web/workbench.js`：

- `currentWorkbenchRoute()` 只接受已注册页面，第一批仅 `monitoring`；
- `navigateWorkbench("monitoring")` 设置 `location.hash = "#/monitoring"`；
- `applyWorkbenchRoute()` 显示匹配页面、更新导航选中状态和页面标题；
- 未登录时不强制改变 Hash；登录成功后进入 `#/monitoring`；
- 监听 `hashchange`；
- 导出 `window.navigateWorkbench` 和 `window.openMonitoringWorkbench`。

- [ ] **Step 4：管理员回跳改为页面语义**

`openAdminArea("monitoring")` 调用 `navigateWorkbench("monitoring")` 并加载监控数据。管理员登录前记录目标路由，登录成功后回到监控页面。

旧 `#monitoringButton` 入口删除后，同步移除对应监听器。兼容测试只验证 `requireAdminThenOpen("monitoring")` 仍能正确路由。

- [ ] **Step 5：验证并提交**

```powershell
python -B -m unittest tests.test_workbench_ui tests.test_monitoring_ui tests.test_api.ApiTest.test_admin_login_returns_to_requested_admin_area -v
node --check web/workbench.js
git diff --check
git add web/workbench.js web/index.html tests/test_workbench_ui.py tests/test_monitoring_ui.py
git commit -m "feat: 实现工作台页面路由"
git push origin main
```

---

### Task 3：将监控逻辑适配为正式页面

**Files:**

- Modify: `web/monitoring.js`
- Modify: `web/monitoring.css`
- Modify: `tests/test_monitoring_ui.py`

**Produces:** 页面打开/刷新/权限失效行为，保持现有计划、结果和预警功能。

- [ ] **Step 1：编写失败的页面适配测试**

断言：

```python
self.assertIn('var monitoringPage = document.getElementById("monitoringPage")', js)
self.assertNotIn('var monitoringModal = document.getElementById("monitoringModal")', js)
self.assertIn("function activateMonitoringPage", js)
self.assertIn('navigateWorkbench("monitoring")', js)
self.assertNotIn("monitoringModal.hidden", js)
```

- [ ] **Step 2：运行测试确认 RED**

```powershell
python -B -m unittest tests.test_monitoring_ui -v
```

- [ ] **Step 3：适配页面生命周期**

- `monitoringModal` 改为 `monitoringPage`；
- `activateMonitoringPage()` 负责健康、计划和预警计数加载；
- 页面已经激活时重复点击导航只刷新，不复制事件监听；
- 401/403 时保留当前 Hash，打开管理员登录，不隐藏整个工作台；
- 恢复中心和系统自检继续使用弹窗；
- 医院切换时仅在当前路由是监控页时刷新数据。

- [ ] **Step 4：调整监控页面样式**

- `.monitoring-dialog` 改为 `.monitoring-page-surface`；
- 移除固定弹窗宽高、居中和阴影；
- 内容使用主区可用高度；
- 表单继续使用右侧抽屉，移动端全宽；
- 复用现有监控卡片、标签和状态色。

- [ ] **Step 5：验证并提交**

```powershell
python -B -m unittest tests.test_monitoring_ui tests.test_monitoring_api tests.test_workbench_ui -v
node --check web/monitoring.js
git diff --check
git add web/monitoring.js web/monitoring.css tests/test_monitoring_ui.py
git commit -m "refactor: 将指标监控迁为正式页面"
git push origin main
```

---

### Task 4：实现可收起 AI 助手

**Files:**

- Modify: `tests/test_workbench_ui.py`
- Modify: `web/workbench.js`
- Modify: `web/workbench.css`
- Modify: `web/index.html`

**Produces:** `openAssistantDrawer()`、`closeAssistantDrawer()`、`toggleAssistantDrawer()`，保留原聊天行为。

- [ ] **Step 1：编写失败的助手测试**

断言脚本包含三个助手函数、`aria-expanded` 同步和 `Escape` 关闭；HTML 中消息与表单只出现一次。

- [ ] **Step 2：运行测试确认 RED**

```powershell
python -B -m unittest tests.test_workbench_ui -v
```

- [ ] **Step 3：实现助手状态**

- 点击顶部 AI 图标按钮打开/关闭抽屉；
- 更新 `hidden`、`.assistant-open` 和 `aria-expanded`；
- `Escape` 关闭；
- 打开后聚焦输入框；
- 切换页面不清空消息、输入草稿或会话 ID；
- 登录后的欢迎消息仍只创建一次；
- 新会话按钮移动到助手头部，并继续复用原逻辑。

- [ ] **Step 4：验证并提交**

```powershell
python -B -m unittest tests.test_workbench_ui tests.test_api.ApiTest.test_chat_stream_returns_sse_events -v
node --check web/workbench.js
git diff --check
git add web/workbench.js web/workbench.css web/index.html tests/test_workbench_ui.py
git commit -m "feat: 增加工作台 AI 助手抽屉"
git push origin main
```

---

### Task 5：浏览器验收、文档和最终回归

**Files:**

- Modify: `README.md`
- Modify: implementation files only when browser acceptance exposes a regression.

- [ ] **Step 1：更新 README**

说明登录后进入指标监控正式页面，顶部可打开 AI 助手；原监控弹窗说明改为工作台导航说明。明确其余四页将在第六批后续子批次逐页开放。

- [ ] **Step 2：桌面浏览器验收**

在 `http://127.0.0.1:8765/` 验证：

- 登录后显示工作台而非全屏聊天；
- Hash 为 `#/monitoring`；
- 管理员登录后返回监控页；
- 新建、编辑、启停和手工运行计划；
- 结果、预警和 Trace；
- AI 助手打开、流式问答、关闭后页面状态不丢；
- 控制台无新增错误。

- [ ] **Step 3：移动端验收**

约 `390x844` 视口验证：

- 顶栏、页面选择区、监控内容和助手可滚动；
- 无页面横向溢出；
- 按钮文字不换行；
- AI 助手全屏且可关闭；
- 监控计划列表和详情单列显示。

- [ ] **Step 4：自动化回归**

```powershell
python -B -m unittest tests.test_workbench_ui tests.test_monitoring_ui tests.test_monitoring_api tests.test_api -v
python -B -m unittest discover -s tests
node --check web/workbench.js
node --check web/monitoring.js
git diff --check
```

- [ ] **Step 5：提交并推送**

```powershell
git add README.md
git commit -m "docs: 增加五页面工作台使用说明"
git push origin main
```

最终确认：

- 工作区干净；
- `HEAD == origin/main`；
- FastAPI、DBHub 和调度器状态正常；
- 浏览器验收临时数据已经删除；
- 服务继续运行在 `http://127.0.0.1:8765/`。
