# 指标运算监控与波动预警设计

## 1. 背景与目标

第五批对应需求规格说明书 3.6“指标运算与预警功能”：支持定时重算、手动重算、指定周期重算，配置环比和同比波动阈值，在异常波动时自动触发 AI 排查，并完整保留口径版本、数据源、执行结果和错误日志。

当前项目已有以下基础：

- 四个首批指标的 MySQL 国标规则、本院生效口径、SQL 模板和字段映射；
- SQL 安全校验、DBHub 只读试运行和 `med_sql_run_log`；
- `med_index_run_result` 基础结果表；
- 三层根因诊断、诊断报告、Trace 和恢复中心；
- `app/tasks/scheduler.py` 目前仅为 APScheduler 占位文件。

本批目标是形成“计划触发 -> 指标运算 -> 历史比较 -> 生成预警 -> 自动诊断 -> 审计留痕”的后端闭环。完整指标运算监控面板继续并入第六批工作台，本批只提供管理 API、执行链路和本地验收入口。

## 2. 范围

### 2.1 本批实现

- FastAPI 进程内 APScheduler 调度；
- MySQL 持久化运行计划；
- 数据库租约防止多进程重复执行；
- 定时重算、手动重算和指定周期重算；
- 环比阈值和可选同比阈值；
- 波动预警和运算失败预警；
- 波动异常自动触发已有三层诊断；
- 运算结果、口径版本、数据源、耗时和错误审计；
- 计划、运行记录和预警管理 API；
- 启动恢复、系统自检和 Trace 节点；
- 首批四指标支持，真实验收使用 `MQSI2025_005`。

### 2.2 本批不实现

- 完整指标运算监控页面；
- 短信、邮件、企业微信等外部通知渠道；
- Celery、Redis 或独立分布式调度集群；
- 最近 3 个周期滚动平均基线；
- 患者明细保存或外发；
- 自动判定国标与本院口径谁对谁错。

## 3. 方案选择

采用“MySQL 持久计划 + 进程内 APScheduler + 数据库租约”。

没有采用纯内存 APScheduler，因为服务重启后计划状态会丢失，多 worker 还可能重复运行。没有采用 Celery Beat，因为当前四指标 MVP 不值得引入 Redis、独立 Worker 和额外部署复杂度。

APScheduler 只负责按时间提交 `plan_id`，不包含规则读取、SQL 生成、波动判断或诊断逻辑。所有业务流程集中在可独立测试的服务中。

## 4. 组件边界

### 4.1 `MonitoringScheduler`

职责：

- FastAPI 启动时加载启用计划；
- 将简单频率配置转换为 APScheduler Trigger；
- 到点后调用 `IndicatorRunService.run_plan(plan_id)`；
- 计划新增、修改、启停后刷新对应 Job；
- 暴露一次性调度扫描方法用于测试和运维。

不负责数据库查询、SQL 执行、阈值计算和诊断。

### 4.2 `MonitoringRepository`

职责：

- 运行计划 CRUD；
- 获取和释放数据库租约；
- 保存运行结果和预警；
- 查询环比、同比基线；
- 预警确认和关闭；
- 保证计划运行键和预警键幂等。

### 4.3 `IndicatorRunService`

职责：

- 统一处理定时、手动和指定周期重算；
- 通过 `CoreIndicatorOrchestrator` 获取本院生效口径和字段映射；
- 复用元数据预检、确定性 SQL 渲染、安全校验和 DBHub 只读执行；
- 保存本次运行审计结果；
- 调用 `WaveDetector` 和 `AlertService`；
- 对失败运行创建恢复任务。

调度运行不调用 LLM 生成 SQL。LLM 仍只参与自然语言入口和回答组织，正式指标 SQL 来自结构化规则模板。

### 4.4 `WaveDetector`

职责：

- 找到上一个同长度统计周期作为环比基线；
- 找到去年同一统计周期作为同比基线；
- 计算变化率；
- 根据计划阈值生成稳定的判断代码。

判断代码：

- `baseline_insufficient`：没有可比较历史数据；
- `within_threshold`：有基线且未超阈值；
- `mom_threshold_exceeded`：仅环比超阈值；
- `yoy_threshold_exceeded`：仅同比超阈值；
- `mom_yoy_threshold_exceeded`：环比和同比均超阈值；
- `no_sample`：当前周期无样本，不判断波动。

### 4.5 `AlertService`

职责：

- 为超阈值结果创建波动预警；
- 为 SQL、DBHub 或元数据失败创建运算失败预警；
- 对波动预警调用已有三层诊断；
- 将诊断 `report_id` 和状态回写预警；
- 诊断失败时保留运行结果和预警，不回滚已完成运算。

## 5. 数据模型

### 5.1 `med_indicator_run_plan`

| 字段 | 类型 | 说明 |
|---|---|---|
| `plan_id` | varchar(64) | 计划唯一 ID |
| `hospital_id` | varchar(64) | 医院隔离键 |
| `rule_id` | varchar(64) | 指标编码 |
| `plan_name` | varchar(128) | 计划名称 |
| `frequency` | varchar(32) | `daily` 或 `monthly` |
| `run_time` | varchar(8) | `HH:mm` |
| `day_of_month` | int | 月度执行日，默认 1 |
| `timezone` | varchar(64) | 默认 `Asia/Shanghai` |
| `mom_enabled` | tinyint | 是否启用环比 |
| `mom_threshold_pct` | decimal(10,2) | 默认 20.00 |
| `yoy_enabled` | tinyint | 是否启用同比 |
| `yoy_threshold_pct` | decimal(10,2) | 默认 30.00 |
| `status` | varchar(32) | `enabled/disabled` |
| `next_run_at` | datetime | 下次执行时间 |
| `last_run_at` | datetime | 最近执行时间 |
| `locked_until` | datetime | 租约到期时间 |
| `locked_by` | varchar(128) | 执行实例标识 |
| `created_by` | varchar(64) | 创建人 |
| `created_at` | datetime | 创建时间 |
| `updated_at` | datetime | 更新时间 |

唯一键为 `(hospital_id, rule_id, plan_name)`。

### 5.2 扩展 `med_index_run_result`

保留现有字段，并增加：

- `plan_id`、`run_key`、`trigger_type`；
- `retry_of_result_id`，失败重试时关联原运行记录；
- `stat_start_time`、`stat_end_time`；
- `run_status`、`no_sample`；
- `effective_level`、`national_version`、`hospital_version`；
- `data_source`、`duration_ms`、`error_code`、`error_message`；
- `mom_baseline_result_id`、`mom_change_rate`；
- `yoy_baseline_result_id`、`yoy_change_rate`；
- `wave_status`、`is_abnormal`。

`run_key` 唯一。首次定时任务使用 `plan_id + stat_start + stat_end` 生成稳定运行键，重复触发不会重复计算；手动重算使用请求 ID 生成新运行键，允许同周期保留多次审计记录。失败恢复使用新的重试运行键，并通过 `retry_of_result_id` 关联原失败记录，既不会被首次运行的幂等键拦截，也不会覆盖原始失败审计。

### 5.3 `med_indicator_alert`

| 字段 | 类型 | 说明 |
|---|---|---|
| `alert_id` | varchar(64) | 预警 ID |
| `hospital_id` | varchar(64) | 医院隔离键 |
| `rule_id` | varchar(64) | 指标编码 |
| `plan_id` | varchar(64) | 来源计划，可空 |
| `result_id` | bigint | 本次运行结果 |
| `alert_type` | varchar(32) | `wave/execution_failed` |
| `alert_level` | varchar(16) | 首版固定 `warning` 或 `error` |
| `conclusion_code` | varchar(64) | 稳定判断代码 |
| `current_value` | decimal(18,4) | 本期结果 |
| `mom_value` | decimal(18,4) | 上期结果 |
| `mom_change_rate` | decimal(18,4) | 环比变化率 |
| `yoy_value` | decimal(18,4) | 去年同期结果 |
| `yoy_change_rate` | decimal(18,4) | 同比变化率 |
| `diagnose_status` | varchar(32) | `pending/running/completed/failed/not_applicable` |
| `diagnose_report_id` | varchar(64) | 自动诊断报告 ID |
| `status` | varchar(32) | `open/acknowledged/closed` |
| `acknowledged_by` | varchar(64) | 确认人 |
| `acknowledged_at` | datetime | 确认时间 |
| `closed_at` | datetime | 关闭时间 |
| `created_at` | datetime | 创建时间 |

唯一键为 `(result_id, alert_type, conclusion_code)`。

## 6. 周期与基线规则

### 6.1 自动周期

- 日计划计算最近一个完整自然日；
- 月计划计算最近一个完整自然月；
- 所有执行统一使用半开区间 `[start_time, end_time)`；
- 时区默认 `Asia/Shanghai`。

### 6.2 手动周期

手动重算可不传周期，此时按计划频率计算最近完整周期；也可传 `YYYY-MM-DD~YYYY-MM-DD` 或完整时间范围。日期结束值按包含整日处理，内部转换为下一日零点。

### 6.3 环比

日指标与前一自然日比较；月指标与前一自然月比较。默认启用，默认阈值为绝对变化率 20%。

变化率：

```text
(本期值 - 上期值) / abs(上期值) * 100%
```

上期值为零时不计算百分比，标记 `baseline_insufficient`，避免无穷大误报。

只有绝对变化率严格大于阈值时才预警；等于阈值视为未超限。

### 6.4 同比

日指标与去年同一自然日比较；月指标与去年同一自然月比较。可独立启停，默认启用，默认阈值 30%。不足一年历史数据时不判断、不预警。

同比同样只有绝对变化率严格大于阈值时才预警。

## 7. 运行流程

1. 调度器到点提交 `plan_id`；
2. Repository 原子获取租约；
3. 根据频率计算最近完整周期；
4. 生成定时运行键并检查幂等；
5. 读取本院生效口径和字段映射；
6. 执行元数据预检；
7. 确定性渲染 SQL 并安全校验；
8. 通过 DBHub 执行单条只读查询；
9. 保存运行结果、口径版本、数据源和耗时；
10. 查询环比和可选同比基线；
11. 计算变化率并更新运行结果；
12. 超阈值时创建预警；
13. 对波动预警调用三层诊断并回写报告 ID；
14. 更新计划最近/下次运行时间并释放租约。

## 8. 失败与恢复

- 未取得租约：本实例跳过，不记为失败；
- 已存在相同首次定时运行键：返回已有结果，不重复执行；失败恢复创建新的 retry attempt，并关联原失败结果；
- 无样本：保存结果并标记 `no_sample`，不触发波动预警；
- 基线不足：保存 `baseline_insufficient`，不触发预警；
- 元数据、SQL 或 DBHub 失败：保存失败结果和 `execution_failed` 预警，并创建可重试恢复任务；
- 自动诊断失败：预警保留为 `diagnose_status=failed`，可由人工重新触发诊断；
- 服务重启：重新加载启用计划；错过的计划由 APScheduler `coalesce=true` 合并为一次执行；
- 租约超时：其他实例可在 `locked_until` 后接管；首版租约 10 分钟。

所有错误响应使用稳定错误码，数据库错误不向前端回显连接密码或完整 SQL。

## 9. API

### 9.1 运行计划

- `GET /api/monitoring/plans`
- `POST /api/monitoring/plans`
- `PUT /api/monitoring/plans/{plan_id}`
- `POST /api/monitoring/plans/{plan_id}/enable`
- `POST /api/monitoring/plans/{plan_id}/disable`
- `POST /api/monitoring/plans/{plan_id}/run`
- `POST /api/monitoring/scheduler/scan`

创建和修改计划属于管理操作，沿用现有管理员令牌鉴权。手动运行支持可选 `stat_period`。

### 9.2 结果与预警

- `GET /api/monitoring/results`
- `GET /api/monitoring/results/{result_id}`
- `GET /api/monitoring/alerts`
- `POST /api/monitoring/alerts/{alert_id}/acknowledge`
- `POST /api/monitoring/alerts/{alert_id}/close`
- `POST /api/monitoring/alerts/{alert_id}/diagnose`

所有列表接口必须按 `hospital_id` 过滤，禁止跨医院读取。

## 10. FastAPI 生命周期与部署

- 添加 APScheduler 依赖；
- FastAPI startup 创建单例 `MonitoringScheduler` 并加载计划；
- shutdown 优雅关闭调度器，不中断数据库中已持久化的计划；
- 开发模式 `--reload` 可能启动监控子进程，因此数据库租约和运行键幂等仍是最终防线；
- 多 worker 部署允许每个进程加载计划，但只有取得租约的实例执行；
- 系统自检增加“指标调度器”项，展示运行状态、启用计划数和最近扫描时间。

## 11. Trace 与恢复中心

每次指标运行创建 Trace，节点建议为：

```text
monitor_plan_load
-> monitor_lease_acquire
-> monitor_period_resolve
-> monitor_indicator_execute_mcp
-> monitor_wave_detect
-> monitor_alert_create
-> monitor_auto_diagnose
```

未触发预警时不创建后两个节点。执行失败的恢复任务复用现有恢复中心，任务类型为 `indicator_recompute`，安全动作是按同一运行参数重试。

Trace 和运行结果只保存聚合指标值、版本、周期、耗时和错误码，不保存患者明细或绑定后的 SQL。

## 12. 测试与验收

### 12.1 自动化测试

- 日/月完整周期计算和日期边界；
- 环比 20% 与同比 30% 阈值边界；
- 去年同期缺失、基线为零和无样本；
- 两个实例竞争同一计划租约；
- 定时运行键幂等和手动同周期多次重算；
- 本院生效口径、字段预检和 DBHub 只读执行；
- 波动预警自动触发诊断；
- 自动诊断失败不丢失预警；
- 运算失败预警和恢复任务；
- 计划 CRUD、启停、手动运行、结果和预警 API；
- Scheduler startup/shutdown 与系统自检；
- Trace 不包含 SQL 和患者明细。

### 12.2 真实环境验收

1. 为 `hospital_001/MQSI2025_005` 创建月度计划；
2. 手动生成一个上期基线结果；
3. 指定 `2026-07-01~2026-07-31` 重算，得到演示结果 66.67；
4. 验证环比超过配置阈值时生成预警；
5. 验证预警自动关联新的三层诊断报告；
6. 验证运行结果含本院口径版本 1、数据源、耗时和运行日志；
7. 重复调度扫描不产生重复定时运行；
8. 关闭并重启 FastAPI 后，启用计划仍被加载；
9. `/api/health/summary` 中调度器状态正常；
10. 全量自动化测试和 workflow manifest 校验通过。

## 13. 安全与审计约束

- 所有业务库查询继续经 DBHub，只允许单条 `SELECT`；
- 医院隔离键必须贯穿计划、结果、预警和诊断；
- 定时任务不调用 LLM 编写 SQL；
- 预警自动诊断不传输患者明细；
- 管理操作记录操作者和时间；
- 定时运行、手动运行、失败重试均保留独立审计信息；
- 关闭预警不会删除运行结果、诊断报告或历史告警记录。
