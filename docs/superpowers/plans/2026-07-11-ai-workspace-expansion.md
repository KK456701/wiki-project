# AI 对话工作区扩展实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 AI 首页在桌面宽屏中占满可用主区域，并增加对话高度与回答气泡宽度。

**Architecture:** 只调整 `web/workbench.css` 中 AI 首页的尺寸、标题排列和消息宽度；通过 `.assistant-workspace:not(.compact)` 限定首页规则，确保业务页抽屉维持紧凑模式。聊天 DOM、路由、权限和后端接口保持不变。

**Tech Stack:** 原生 CSS、Python `unittest`、Node.js 语法检查。

## Global Constraints

- AI 页面不得继续使用 `max-width: 1040px`。
- 首页使用完整可用宽高，主内容区保留 `20px` 至 `24px` 边距。
- 首页回答气泡最大宽度约为 `960px` 或主区域的 `82%`。
- `.compact` 抽屉的现有宽度、字号和间距不得变化。
- `760px` 以下保持单列且无横向溢出。
- 完成后自动创建中文 Conventional Commit 并推送 `main`。

---

### Task 1: 扩大 AI 首页对话区域

**Files:**

- Modify: `tests/test_workbench_ui.py`
- Modify: `web/workbench.css`

**Interfaces:**

- Consumes: `.assistant-page`、`.assistant-page-heading`、`.assistant-workspace`、`.compact`。
- Produces: 全宽 AI 首页、紧凑横向标题和仅首页生效的宽消息气泡。

- [ ] **Step 1: 编写失败测试**

在 `tests/test_workbench_ui.py` 增加：

```python
def test_ai_home_uses_full_available_workspace(self) -> None:
    css = (ROOT / "web" / "workbench.css").read_text(encoding="utf-8")

    self.assertIn(".assistant-page-heading", css)
    self.assertIn("display: flex", css[css.index(".assistant-page-heading") :])
    self.assertIn(".assistant-workspace:not(.compact) .message-bubble", css)
    self.assertIn("max-width: min(960px, 82%)", css)
    assistant_page = css[css.index(".assistant-page {") : css.index(".assistant-page-heading")]
    self.assertNotIn("max-width: 1040px", assistant_page)
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
python -B -m unittest tests.test_workbench_ui.WorkbenchUiTest.test_ai_home_uses_full_available_workspace -v
```

Expected: FAIL，因为首页仍有 `1040px` 限制且消息宽度为 `720px`。

- [ ] **Step 3: 实现桌面全宽布局**

修改 `web/workbench.css`：

```css
.assistant-page {
  width: 100%;
  max-width: none;
}

.assistant-page-heading {
  min-height: 58px;
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 2px 4px 12px;
}

.assistant-page-heading h1 {
  flex: 0 0 auto;
  margin: 0;
  font-size: 24px;
}

.assistant-page-heading p {
  min-width: 0;
  margin: 0;
}

.assistant-workspace:not(.compact) .message-bubble {
  max-width: min(960px, 82%);
}
```

保留 `.assistant-workspace.compact` 和 `.assistant-drawer` 现有规则不变。

- [ ] **Step 4: 保持中小屏可读性**

在 `@media (max-width: 1200px)` 中让标题恢复两行：

```css
@media (max-width: 1200px) {
  .assistant-page-heading {
    display: grid;
    gap: 4px;
  }
}
```

现有 `760px` 以下规则继续控制标题字号、消息内边距和全屏抽屉。

- [ ] **Step 5: 验证并提交**

```powershell
python -B -m unittest tests.test_workbench_ui tests.test_monitoring_ui -v
node --check web/workbench.js
node --check web/monitoring.js
git diff --check
git add tests/test_workbench_ui.py web/workbench.css
git commit -m "feat: 扩大 AI 首页对话工作区"
git push origin main
```

---

### Task 2: 完整回归和运行态核对

**Files:**

- Modify: implementation files only when regression exposes a defect.

- [ ] **Step 1: 运行完整测试**

```powershell
python -B -m unittest discover -s tests
node --check web/workbench.js
node --check web/monitoring.js
git diff --check
```

Expected: 全量测试为 `OK`，脚本和差异检查退出码为 `0`。

- [ ] **Step 2: 检查页面和依赖**

```powershell
$page = (Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8765/).Content
$health = Invoke-RestMethod http://127.0.0.1:8765/api/health/summary
$health.status
```

Expected: 页面包含 `assistantPage`，系统自检为 `ok`。

- [ ] **Step 3: 核对远端一致性**

```powershell
git fetch origin main
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
```

Expected: 工作区干净，`HEAD` 与 `origin/main` 一致。
