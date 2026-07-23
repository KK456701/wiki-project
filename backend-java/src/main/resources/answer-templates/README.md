# Final Answer 回答模板

这里保存 Final Answer LLM 按需使用的 Markdown 回答与报告模板。模板不属于基础 Prompt；
`AnswerTemplateRegistry` 根据已经校验的 `RequestPlan.intent` 和 `requested_outputs`
每轮只选择一份模板。

| 模板 | 适用意图 | 视觉结构 |
|---|---|---|
| `general-chat.md` | 普通对话 | 直接回答，不强制标题 |
| `rule-explanation.md` | 指标定义、公式和当前口径 | 口径速览、计算口径、实施信息 |
| `indicator-trial-result.md` | 指标实际结果 | 结论速览、结果表、口径、数据依据 |
| `indicator-sql-report.md` | 受控 SQL | SQL 口径、代码块、参数、安全状态 |
| `caliber-simulation-report.md` | 候选口径模拟 | 模拟结果、候选口径、差异、限制 |
| `indicator-diagnosis-report.md` | 普通异常诊断 | 诊断摘要、确认事实、处理建议 |
| `difference-diagnosis-report.md` | 双方结果差异诊断 | 双方结果、候选试算、结论、证据限制 |
| `rule-change-preview.md` | 规则变更预览 | 变更摘要、影响、后续操作 |
| `upload-analysis-report.md` | Excel 分析或核对 | 文件概览、分析结果、数据限制 |
| `implementation-validation-report.md` | 实施验收 | 验收结论、阶段表、整改建议 |
| `clarification.md` | 信息不足或未知意图 | 已确认信息、缺失信息、下一步 |

模板使用规则：

- 占位符只表示内容位置，模型输出中不得保留占位符。
- 模板不能决定工具、SQL、口径、权限或数值，只能组织 `VerifiedEvidence`。
- `AnswerContractValidator` 检查必需章节、工具协议泄漏和试运行数值完整性。
- 校验失败最多修复一次；再次失败时使用确定性 Evidence 模板。
- SQL 正文、患者级数据和敏感对象仍遵守现有安全边界。
