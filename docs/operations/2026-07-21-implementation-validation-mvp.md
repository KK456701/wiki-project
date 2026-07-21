# 2026-07-21 全面实施验收 MVP

## 范围

第一批只实现单指标、明确统计周期下的四段验收：

- L1：字段映射、主表和来源完整性。
- L4：指标身份、定义、公式和生效版本对齐。
- L5：字段预检、确定性 SQL、安全校验和医院业务库只读试运行。
- L6：当前会话存在上传 Excel 时执行汇总或逐条数据核对；没有文件时标记为 `skipped`。

不在本批范围：L2、L3、扩展 L5、正式 Excel/PDF 验收报告、报告列表页和审批发布联动。

## 触发方式

用户必须明确使用“全面实施验收、全面实施验证、上线验收、迁移核对、全链路验收”等表达，并提供指标和统计周期，例如：

```text
对患者入院48小时内转科的比例做全面实施验收，统计2026年1月到3月
```

普通公式解释、查询具体结果、生成 SQL、异常诊断和单独查看字段映射不会进入该工作流。

## 执行边界

`RequestPlan` 输出 `implementation_validation` 和 `implementation_validation_report`。`CapabilitySpecRegistry` 将其编译为：

```text
resolve_indicator
-> resolve_effective_rule
-> resolve_time_range
-> inspect_implementation
-> validate_implementation
-> compose_answer
```

`validate_implementation` 只映射到 `validate_indicator_implementation`。L1/L4/L5/L6 是该工具内部确定性阶段，不出现在 Planner 输出中，也不由模型选择。

## 结果和审计

- 报告编号前缀：`IVR_`。
- 总结论：`passed`、`warning` 或 `failed`。
- L6 未提供文件时：`skipped`，不降低总结果。
- 每个阶段在“查看链路”中有独立节点、耗时、输入摘要、配置、结论和失败码。
- Evidence 只保存安全字段以及 `SQL_`、`RUN_`、`IVR_` 对象引用，不保存 SQL 原文和患者行。
- 业务验收失败仍返回完整报告；只有系统错误、权限错误和超时才作为工具执行错误处理。

## 定向验证

```powershell
python -m pytest -q `
  tests/test_agent_plan_compiler.py `
  tests/test_agent_deterministic_dispatch.py `
  tests/test_agent_plan_validator.py `
  tests/test_agent_plan_controller.py `
  tests/test_agent_implementation_validation.py `
  tests/test_agent_planned_runner.py `
  tests/test_agent_execution_loop.py
```
