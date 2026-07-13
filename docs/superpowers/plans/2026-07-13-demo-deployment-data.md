# 真实部署模拟数据实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为四个已实现指标提供可重复生成、可安全重置、可触发边界与异常诊断的 18 个月本地模拟数据。

**Architecture:** 纯 Python 生成器负责产生确定性业务记录，命令行脚本只负责校验目标演示库并批量写入。元数据漂移通过不参与指标计算的可选字段模拟；监控历史基线通过现有监控服务生成，避免直接伪造预警和审计记录。

**Tech Stack:** Python 3.12、SQLAlchemy、MySQL 8、unittest。

## Global Constraints

- 只允许写入名称以 `_demo_data` 结尾的业务数据库。
- 默认只预览，执行清空和写入必须显式传入 `--apply`。
- 所有患者、住院、会诊、抢救和手术标识均为确定性虚构编号。
- 不直接写入预警、诊断报告、审批版本和元数据快照等系统生成表。
- 默认生成 2025-01 至 2026-07 共 19 个月，覆盖环比与同比基线。

---

### Task 1: 业务数据生成器

**Files:**
- Create: `app/demo_data/__init__.py`
- Create: `app/demo_data/generator.py`
- Create: `scripts/seed_demo_hospital_data.py`
- Create: `tests/test_demo_data_generator.py`
- Modify: `README.md`

**Interfaces:**
- Produces: `DemoDataOptions`、`generate_demo_rows(options)`、`summarize_demo_rows(rows)`。
- CLI: `python scripts/seed_demo_hospital_data.py --profile realistic --apply`。

- [ ] 先写测试，验证数据量、月份范围、四指标边界值、质量异常和确定性。
- [ ] 运行测试，确认因模块不存在而失败。
- [ ] 实现纯生成器和只允许演示库的批量写入脚本。
- [ ] 运行生成器测试及四指标 SQL 回归测试。
- [ ] 更新 README，提交并推送第一批。

### Task 2: 元数据漂移场景

**Files:**
- Create: `app/demo_data/metadata_drift.py`
- Create: `scripts/simulate_metadata_drift.py`
- Create: `tests/test_demo_metadata_drift.py`
- Modify: `README.md`

**Interfaces:**
- Produces: `metadata_drift_sql(action, dialect="mysql")`。
- CLI actions: `add`、`modify`、`remove`、`restore`，仅操作 `consult_priority` 可选字段。

- [ ] 先写测试，验证四种动作的 SQL 和演示库保护。
- [ ] 运行测试，确认失败。
- [ ] 实现可逆 DDL 和命令行执行器。
- [ ] 运行测试并在本地库执行 add/modify/remove/restore 后同步元数据验证。
- [ ] 更新 README，提交并推送第二批。

### Task 3: 监控历史基线

**Files:**
- Create: `app/demo_data/monitoring_baseline.py`
- Create: `scripts/seed_monitoring_baseline.py`
- Create: `tests/test_demo_monitoring_baseline.py`
- Modify: `README.md`

**Interfaces:**
- Produces: `build_monitoring_periods(start_month, month_count)` 和通过 `MonitoringService` 执行历史周期的入口。
- CLI: `python scripts/seed_monitoring_baseline.py --apply`。

- [ ] 先写测试，验证上期、去年同期、低样本和无样本周期均被覆盖。
- [ ] 运行测试，确认失败。
- [ ] 复用现有监控服务生成历史运行结果，不直接插入预警表。
- [ ] 运行监控、诊断和完整测试。
- [ ] 更新 README，提交并推送第三批。

