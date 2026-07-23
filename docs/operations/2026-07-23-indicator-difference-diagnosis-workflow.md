# 2026-07-23 指标差异分层诊断 Workflow

## 目标

将“我们100、系统98”“上传文件为什么与本院计算不同”等请求从通用异常诊断中分离，
在现有 Compiled Plan Agent 内新增一条确定性业务分支。没有增加 Agent 框架、数据库、
中间件、页面或部署服务。

## 触发和边界

- 新意图：`indicator_difference_diagnosis`
- 新输出事实：`difference_diagnosis_report`
- 新能力：`DIAGNOSE_INDICATOR_DIFFERENCE`
- 新受控工具：`diagnose_indicator_difference`

服务端会对明确的“不一致、差异、为什么我们、与系统核对、上传文件对比”等表达做兜底
归类，降低本地小模型误判率。纯 Excel 内容分析仍走 `upload_analysis`；没有外部比较对象
的“指标为什么偏低”仍走 `indicator_diagnosis`。

## 固定执行顺序

1. 诊断范围预检：指标、医院、统计区间、文件类型、用户声称值。
2. 实时结构核验：Wiki 字段契约、医院映射与 DBHub 实时元数据。
3. 执行当前口径：受控 SQL 生成、安全校验和只读试运行。
4. 候选口径反事实：同医院、同时间、同数据源，最多 5 个已审批候选。
5. 记录集合核对：双方都有、仅系统有、仅文件有、字段差异、达标判定差异。
6. 数据质量：只执行 Wiki 允许列表 DSL，不接受用户或模型 SQL。
7. 诊断结论：保存报告、安全 Evidence 和 Trace。

一层确认全部原因后停止；只能解释部分差异时保留已解释数量并继续。候选结果数值相同但
没有口径描述或逐条记录证据时，不确认因果关系。

## 结论代码

- `STRUCTURE_BLOCKING`
- `STRUCTURE_CAUSE_CONFIRMED`
- `CALIBER_CAUSE_CONFIRMED`
- `RECORD_SET_DIFF_CONFIRMED`
- `DATA_QUALITY_CAUSE_CONFIRMED`
- `SYSTEM_RESULT_VERIFIED`
- `INSUFFICIENT_EXTERNAL_EVIDENCE`

## Wiki 配置

每个支持反事实诊断的指标可在 SQL 规格目录增加 `diagnosis_profiles.yaml`。只有
`status: approved` 且当前医院可见、统计周期有效的条目可执行。参数覆盖必须属于原 SQL
规格的参数允许列表；历史模板不完整时标记不可执行，不回退为模型生成 SQL。

数据质量规则保存在 `rule_sql_spec.yaml` 的 `quality_checks`，当前允许：

- `required_not_null`
- `duplicate_key`
- `timestamp_order`

这些规则只引用 Wiki 中已确认的业务字段，Java 将其编译为只读计数查询。

## Trace、报告和导出

前端状态槽与“查看链路”显示：

```text
诊断范围预检 → 实时结构核验 → 执行当前口径 → 试运行候选口径
→ 核对记录集合 → 检查数据质量 → 生成诊断结论
```

诊断报告只保存汇总、对象编号、结论和证据限制。患者行仍保存在原有短期明细对象中。

```http
POST /api/diagnosis-reports/{report_id}/exports
Authorization: Bearer <hospital_token>
Content-Type: application/json

{"confirmed": true}
```

有逐条文件时，Excel 包含诊断摘要、对比摘要、双方都有、仅系统有、仅文件有和数据质量
异常汇总；下载继续使用 `/api/indicator-exports/{export_id}/download`。

## 验证

```powershell
cd .\backend-java
mvn '-Dtest=IndicatorDifferenceDiagnosisWorkflowTest,CapabilityPlanningTest,PlanValidatorTest,AgentRunnerTest,IndicatorDetailServiceTest' test

cd ..\frontend-vue
npm.cmd run type-check
npm.cmd run build
```
