# 指标抽取后双库计算与自动差异诊断

## 目标

在不增加数据库、中间件或任务队列的前提下，为现有 Compiled Plan Agent 增加一条
确定性的双库执行链。Java 和 DBHub 始终只读；写真实库只允许由后续接入的外部抽取
接口完成。

## 固定执行顺序

```text
校验左闭右开区间且不超过一个自然月
→ 校验 Wiki Profile 的 ETL、概览和双库同构契约
→ 调用 SourceExtractionGateway 一次
→ 业务库 winex_all_dev 执行 overview
→ 真实库 winex_aima 执行同一 overview 和参数
→ 比较 numerator_count、denominator_count
   ├─ 相同：结束，不查询明细
   └─ 不同：两库分别执行 department_detail、patient_detail
             → 按已验证比较键统计集合与字段差异
             → 保存安全诊断报告引用，授权用户可导出逐条差异 Excel
```

指标率由 Java 使用分子、分母统一复算。两侧指标率相同但分子或分母不同，仍判定为
不一致。

## 配置

```yaml
wiki:
  agent:
    extraction:
      mode: disabled # disabled | required
  dbhub:
    sources:
      business:
        source-id: winex_all_dev
        execute-tool: execute_sql_winex_all_dev
        database-name: WiNEX_All_DEV
        schema-name: dbo
      real:
        source-id: winex_aima
        execute-tool: execute_sql_winex_aima
        database-name: winex_aima
        schema-name: dbo
```

`disabled` 保持原单库行为。`required` 不提供模拟成功：网关缺失、抽取失败、双库契约
未验证或任一数据库执行失败都会终止本轮。

普通单库试运行固定访问 `business`。旧顶层 `wiki.dbhub.source-id/execute-tool/
database-name/schema-name` 已删除；启动时发现旧配置会直接失败。元数据接口只接受
`winex_all_dev` 或 `winex_aima`，历史退役数据源只允许查看已有 Trace，不能重新执行
SQL 或刷新患者明细。数字 `991827` 只作为 `hospital_scope_value` 绑定到 SQL 模板的
`:hospital_soid`，不表示数据库。

## 抽取接口边界

`SourceExtractionGateway` 接收发布版本、规则、Profile、统计周期、Wiki ETL SQL、
参数、SQL 哈希、两个逻辑数据源和本轮幂等键。返回抽取 ID、写入计数、拒绝计数、
完成时间、错误码和可选快照 ID。每个指标子任务在相同周期内只调用一次，候选口径
与明细诊断复用成功回执。

## 安全和审计

- SQL 只来自当前已发布、已验证的 Wiki Profile。
- 双库契约必须分别记录业务库、真实库的元数据和编译验证状态，以及概览结果列映射、
  分子判定字段和允许比较字段；任一项未验证都不会进入抽取。
- 浏览器、用户和模型不能提交抽取 SQL、物理字段或参数覆盖。
- 两库执行严格串行，同一医院的抽取与查询由进程内信号量串行化。
- SQLite 只保存复合运行 ID、两侧运行 ID、抽取 ID、数量和比较状态。
- Evidence 分别记录抽取完成、业务库结果、真实库结果和双库比较。
- Trace 只记录 SQL 哈希、来源、耗时和差异数量，不记录 SQL 正文或患者原始行。
- 明细契约缺失时保留概览不一致结论，并返回
  `DETAIL_COMPARISON_CONTRACT_MISSING`，不猜测原因。
- 双库不一致时报告只保存两个运行 ID 和安全计数。用户确认导出后，服务端才按两个
  运行 ID 分别生成短期明细快照，并输出“双方都有、仅业务库有、仅真实库有、字段与
  分子判定不同”的 Excel；患者行不写入报告、Evidence 或 Trace。
