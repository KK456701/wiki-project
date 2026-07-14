# 医生友好的指标计算说明实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让医生在不理解数据库字段和 SQL 的情况下，看懂指标分母、分子、本院口径和最终结果，同时保留供信息科排障的折叠技术详情。

**Architecture:** 后端继续以结构化字段血缘为唯一事实来源，将回答拆成“医生说明”和“技术详情”两层。技术详情使用固定分段标记传给前端，聊天 Markdown 渲染器只解析该标记并生成原生折叠控件，分段内部仍走既有 HTML 转义逻辑。

**Tech Stack:** Python 3.12、FastAPI 现有回答链路、原生 JavaScript、HTML/CSS、`unittest`/`pytest`、Node.js 渲染测试。

## Global Constraints

- 默认可见内容必须使用医生能理解的中文，不出现数据库表名、字段名或 SQL。
- 技术详情默认折叠，展开后保留 SQL、安全校验、字段血缘和医院数据库位置。
- 自然语言说明和技术详情必须来自同一份 `lineage` 与 `effective_rule`，不得由大模型自由补写。
- 试运行只解释聚合数量，不展示患者明细或绑定后的 SQL。
- 不放开任意 HTML；折叠内容仍使用现有转义渲染器。
- 不改变指标计算、医院口径合成、SQL 安全校验和只读执行行为。

---

### Task 1: 后端生成医生说明和折叠技术分段

**Files:**
- Modify: `tests/test_sql_explanation.py`
- Modify: `tests/test_agent_workflow.py`
- Modify: `app/sqlgen/explanation.py`

**Interfaces:**
- Consumes: `format_generation_explanation(...)` 和 `format_trial_explanation(...)` 的现有参数。
- Produces: 默认医生说明，以及由 `:::details 查看技术详情（供信息科和实施人员）` 和 `:::` 包裹的技术 Markdown。

- [ ] **Step 1: 添加默认医生视图的失败测试**

在 `tests/test_sql_explanation.py` 增加辅助函数和断言：

```python
def visible_part(answer: str) -> str:
    return answer.split(":::details", 1)[0]

visible = visible_part(self._generation())
self.assertIn("本次统计哪些急会诊", visible)
self.assertIn("哪些急会诊算作及时到位", visible)
self.assertIn("20分钟", visible)
self.assertNotIn("consult_record.", visible)
self.assertNotIn("```sql", visible)
self.assertIn(":::details 查看技术详情（供信息科和实施人员）", answer)
```

试运行测试必须断言默认区域包含“本期共有10次急会诊进入统计范围”“其中8次在本院规定的20分钟内到位”“另有2次未在规定时间内到位”和 `8 / 10 x 100% = 80%`。

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -B -m pytest tests/test_sql_explanation.py -q`

Expected: 新断言失败，因为当前回答直接显示 `consult_record.*` 和 SQL。

- [ ] **Step 3: 实现医生说明与技术分段**

在 `app/sqlgen/explanation.py` 新增固定分段函数：

```python
DETAILS_START = ":::details 查看技术详情（供信息科和实施人员）"
DETAILS_END = ":::"

def _details_section(sections: Iterable[str]) -> str:
    body = "\n\n".join(section for section in sections if section)
    return f"{DETAILS_START}\n{body}\n{DETAILS_END}"
```

新增 `_doctor_denominator_section`、`_doctor_numerator_section` 和 `_doctor_caliber_section`。这些函数只读取业务名称、`condition_text`、`derivation_text`、口径值和影响范围，不读取 `physical_fields`。默认区域顺序为：计算方法已准备好、当前采用什么规则、本次统计哪些记录（分母）、哪些记录算作达标（分子）、最终怎么计算。

技术分段继续调用现有字段表格和 SQL 代码块，表头改为：

```python
["步骤", "系统统一名称", "本院数据库位置", "系统如何判断", "规则来源", effect_header]
```

- [ ] **Step 4: 实现试运行的自然语言结论和降级文案**

试运行用聚合值确定性生成：

```text
本期共有10次急会诊进入统计范围，其中8次在本院规定的20分钟内到位，另有2次未在规定时间内到位。因此，急会诊及时到位率为 8 / 10 x 100% = 80%。
```

字段关系缺失时显示“当前指标的取数关系尚未配置完整，请联系信息科或实施人员完善后再生成”；分母为零、分子大于分母及旧版 SQL 的既有安全降级语义保持不变。

- [ ] **Step 5: 运行后端说明测试**

Run: `python -B -m pytest tests/test_sql_explanation.py tests/test_agent_workflow.py -q`

Expected: 全部通过；默认区域没有物理字段或 SQL，技术分段仍有完整信息。

- [ ] **Step 6: 提交并推送后端改动**

```powershell
git add app/sqlgen/explanation.py tests/test_sql_explanation.py tests/test_agent_workflow.py
git commit -m "feat: 用医生语言解释指标分子分母"
git push
```

---

### Task 2: 前端安全渲染折叠技术详情

**Files:**
- Modify: `tests/test_chat_markdown_ui.py`
- Modify: `web/chat-markdown.js`
- Modify: `web/index.html`

**Interfaces:**
- Consumes: Task 1 输出的固定 `:::details` 分段。
- Produces: 默认关闭的 `<details class="message-details">`；内部表格和代码继续使用 `renderAssistantMarkdown`。

- [ ] **Step 1: 添加折叠和转义的失败测试**

构造同时包含表格、SQL 和 `<script>alert(1)</script>` 的技术分段，断言：

```python
self.assertIn('<details class="message-details">', result.stdout)
self.assertIn("<summary>查看技术详情（供信息科和实施人员）</summary>", result.stdout)
self.assertNotIn('<details class="message-details" open', result.stdout)
self.assertIn('<table class="message-table">', result.stdout)
self.assertIn('<pre class="message-code">', result.stdout)
self.assertNotIn("<script>", result.stdout)
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -B -m pytest tests/test_chat_markdown_ui.py -q`

Expected: FAIL，当前渲染器把折叠标记当作普通段落。

- [ ] **Step 3: 实现受控折叠分段解析**

在 `renderAssistantMarkdown` 主循环中识别 `^:::details`，收集到独占一行的 `:::`，再递归渲染分段内容：

```javascript
output.push(
  '<details class="message-details"><summary>' + renderInline(label) +
  '</summary><div class="message-details-body">' +
  renderAssistantMarkdown(detailLines.join("\n")) + '</div></details>'
);
```

不得解析回答中的任意原始 HTML，技术内容必须继续经过 `escapeHtml`。

- [ ] **Step 4: 添加折叠区域样式**

在 `web/index.html` 的聊天样式区域增加 `.message-details`、`.message-details > summary` 和 `.message-details-body`。使用 6px 圆角、现有边框色和青绿色文字，默认关闭，不引入新的卡片嵌套。

- [ ] **Step 5: 运行前端渲染测试**

Run: `python -B -m pytest tests/test_chat_markdown_ui.py tests/test_indicator_ui.py -q`

Expected: 全部通过；技术详情默认关闭，展开内容保留表格和代码，恶意 HTML 被转义。

- [ ] **Step 6: 提交并推送前端改动**

```powershell
git add web/chat-markdown.js web/index.html tests/test_chat_markdown_ui.py
git commit -m "feat: 折叠展示指标技术详情"
git push
```

---

### Task 3: 文档、全量回归与前端验收

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1 的医生说明和 Task 2 的折叠渲染。
- Produces: README 验收步骤和完整回归证据。

- [ ] **Step 1: 更新 README 验收说明**

写明默认区域应显示统计范围、分母、分子、本院20分钟规则和结果计算；表名、字段名与 SQL 只有点击“查看技术详情（供信息科和实施人员）”后才显示。

- [ ] **Step 2: 运行聚焦回归测试**

Run: `python -B -m pytest tests/test_sql_explanation.py tests/test_agent_workflow.py tests/test_chat_markdown_ui.py tests/test_indicator_ui.py -q`

Expected: 全部通过。

- [ ] **Step 3: 运行全量测试**

Run: `python -B -m pytest -q`

Expected: 全部通过，无新增失败。

- [ ] **Step 4: 真实前端验收**

在 `http://127.0.0.1:8765/` 依次发送“急会诊及时到位率怎么算？”、“生成 SQL”、“试运行”。确认默认医生说明、折叠技术详情、展开后的字段表格、试运行比例解释均正确，并检查桌面布局无重叠。

- [ ] **Step 5: 检查差异并提交文档**

```powershell
git diff --check
git add README.md
git commit -m "docs: 补充医生版指标说明验收步骤"
git push
```
