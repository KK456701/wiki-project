# Oracle 知识库 SQL 兼容性实测报告

## 结论

当前运行知识库：

`backend-java/src/main/resources/knowledge-index_backup_20260801_150233`

已使用医院 Oracle 主库完成连接与只读试跑。Oracle 连接正常，但当前 43 个口径的 172 段 SQL **没有任何一段可以原样直接在 Oracle 执行**。

这不是数据库连接问题，而是当前知识库 SQL 的正式方言是 SQL Server：抽取、概览、科室统计和患者明细均使用了 SQL Server 函数、提示、字符串聚合或日期写法。若医院正式数据库为 Oracle，必须建立对应医院的 Oracle 口径版本，不能把当前公版 SQL 原样迁过去。

## 实测范围与方法

| 项目 | 结果 |
|---|---:|
| Oracle 连接测试 | 通过，`select 1 from dual` 返回 1 |
| 解析口径数 | 43 |
| 每口径检查段数 | 4：源表抽取、概览、科室统计、患者明细 |
| SQL 总数 | 172 |
| 执行方式 | 只读；渲染默认统计窗口 `2025-01-01` 至 `2026-01-01`；最多读取 1 行；单段最多 5 秒 |
| 写入、清表、抽取同步 | 均未执行 |

## 汇总结果

| 结果 | 数量 | 含义 |
|---|---:|---|
| 可原样直接执行 | 0 | 无 |
| 需要 Oracle 方言修改 | 161 | 已发送到 Oracle，返回 SQL Server 方言或函数不兼容错误 |
| 未登记 SQL | 7 | 当前知识库该口径未提供这段 SQL |
| 不是单条只读查询 | 4 | 检查器没有执行，需先拆成一条查询或明确该段用途 |

### Oracle 实际错误分布

| Oracle 返回 | 数量 | 常见原因 |
|---|---:|---|
| `ORA-00905` 缺失关键字 | 91 | SQL Server 写法、`WITH (NOLOCK)`、`FOR XML`、`STUFF` 等不适用于 Oracle |
| `ORA-00923` 未找到 FROM | 36 | SQL Server 风格的别名、字符串聚合或查询结构不能被 Oracle 解析 |
| `ORA-00904 DATEDIFF` | 11 | Oracle 没有 `DATEDIFF`，应改为日期差、`NUMTODSINTERVAL` 或相应 Oracle 时间表达式 |
| `ORA-00904 GETDATE` | 6 | Oracle 应使用 `SYSDATE` 或 `SYSTIMESTAMP` |
| `ORA-00936` 缺失表达式 | 7 | SQL Server 特有语法或模板渲染后的表达式不兼容 |
| `ORA-00909` 参数个数无效 | 6 | 同名函数在 Oracle 的参数规则不同 |
| `ORA-00907` 缺失右括号 | 2 | SQL Server 方言结构不兼容 |
| `ORA-01861` 日期格式不匹配 | 2 | 日期字符串需要使用 `TO_DATE`/`TO_TIMESTAMP` 并指定格式 |

## 哪些 SQL 可以直接用

没有。43 个口径、4 类 SQL 中，Oracle 均没有返回成功。

需要注意：这不代表“指标逻辑都错了”。它只证明**当前 SQL 文本是 SQL Server 版，不能原样执行在 Oracle**。指标定义、分子分母、去重和业务规则仍可作为 Oracle 版本改写时的依据。

## 需要先处理的知识库空配置

| 口径 | 未登记 SQL |
|---|---|
| `HXZD-009-002_002` 可选方案 | 源表抽取、概览、科室统计、患者明细均未登记 |
| `HXZD-010-001` 长期医嘱当日终止率 | 源表抽取 SQL 未登记（该口径当前按真实库已有表直接统计） |
| `HXZD-013-001` 新技术新项目留存转化率 | 源表抽取 SQL 未登记（该口径当前按真实库已有表直接统计） |
| `HXZD-016-002` 术中自体血回输率 | 源表抽取 SQL 未登记（该口径当前按真实库已有表直接统计） |

`HXZD-009-002_002` 是“可选方案未实现”，应继续保持不可执行，不能为了 Oracle 测试补造 SQL。

## 需要先拆分的 4 段 SQL

以下 SQL 渲染后不是单条 `SELECT` 或 `WITH … SELECT`，本次没有执行。先明确它们是统计 SQL、脚本片段还是多语句后，再做 Oracle 改写。

| 口径 | SQL 段 |
|---|---|
| `HXZD-002-002` 上级医师查房记录规范率 | 科室统计 SQL |
| `HXZD-002-003` 住院患者非计划手术率 | 概览统计 SQL、科室统计 SQL |
| `HXZD-006-002` 非计划再次住院 | 科室统计 SQL |

## Oracle 改写规则

按以下顺序建立医院 Oracle 草稿口径，不能做全局字符串替换后直接上线：

1. 先核对 Oracle 实际表、字段、医院 Schema 和字段类型；当前 SQL Server 表名不应假定在 Oracle 中同名存在。
2. 改写源表抽取 SQL，并先用影子表验证输出字段、记录数、去重键和案例记录。
3. 使用同一影子数据改写概览 SQL，重聚合分子、分母和结果值。
4. 再改写科室统计与患者明细 SQL，确保它们与概览的分子、分母对账一致。
5. 影子试跑通过后，只保存为该医院草稿；不要替换当前 SQL Server 公版口径。

常见对应关系：

| SQL Server 写法 | Oracle 改写方向 |
|---|---|
| `GETDATE()` | `SYSDATE` 或 `SYSTIMESTAMP` |
| `DATEDIFF(HOUR, a, b)` | 基于日期差换算小时，或 `NUMTODSINTERVAL(b-a, 'DAY')` 的等价写法 |
| `WITH (NOLOCK)` | 删除；Oracle 的一致性读模型不同 |
| `TOP (n)` | `FETCH FIRST n ROWS ONLY` 或 `ROWNUM` |
| `STUFF(... FOR XML PATH(''))` | `LISTAGG`（需明确溢出与排序规则） |
| 方括号标识符 `[TABLE]` | Oracle 未加引号的大写标识符，或按实际大小写使用双引号 |
| SQL Server 日期隐式转换 | `TO_DATE`/`TO_TIMESTAMP` 并明确格式 |

## 对当前系统的建议

- 当前正式计算继续使用 SQL Server 真实库，不应切换到 Oracle。
- Oracle 仅作为医院业务主库或迁移目标时，创建 `hospitals/{hospitalId}/drafts/...` 的 Oracle 草稿 SQL；草稿不参与正式计算。
- 后续“医院口径发布”应在 manifest 中增加 `databaseDialect: ORACLE`，并要求四段 SQL 都完成 Oracle 影子试跑和分子分母对账。
- 本次试跑接口已加入运行控制台，后续可在 Oracle 连接配置正确后重复执行同一检查。
