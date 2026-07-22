# MySQL 到 Wiki + SQLite 轻量化迁移

> 更新日期：2026-07-22。Java 权威运行时和 Python 回退运行时已采用本方案。

## 目标架构

```text
Java Spring Boot / Python FastAPI
├─ core-rules-wiki/                 规则唯一事实源
│  ├─ 国家与公司标准
│  ├─ 医院 override 与不可变版本
│  ├─ 医院字段映射
│  ├─ SQL 规格与模板
│  └─ 术语和检索索引
├─ runtime/wiki_agent_runtime.db    内嵌 SQLite 运行状态
│  ├─ 用户、权限和会话
│  ├─ 审批与审计
│  ├─ Trace 与 Evidence
│  ├─ SQL/导出对象引用
│  └─ 监控、实施和恢复状态
└─ DBHub sidecar                    只读访问医院 SQL Server
```

患者行级业务数据不会迁移到 Wiki 或 SQLite，也不会进入 LLM、Trace 或 Evidence。

## 迁移命令

在仍能访问旧 MySQL 的机器上执行一次：

```powershell
python scripts\migrate_mysql_to_sqlite.py --switch-config
```

脚本会：

1. 读取旧库全部表、列和索引。
2. 创建 `runtime/wiki_agent_runtime.db`。
3. 复制可变运行数据，并逐表核对行数。
4. 执行 `PRAGMA integrity_check`。
5. 输出不含密码和患者数据的迁移清单。
6. 将本地 `config.yaml` 切换为 SQLite。

当前项目实际迁移结果为 42 张表、78,350 行，完整性检查为 `ok`。规则类数据已经结构化写入 `core-rules-wiki/`，应用不再读取旧 MySQL 规则表。

## 双栈访问约定

- Python 的 `RuleRepository` 工厂始终返回 `WikiRuleRepository`。
- Java 的 `RuleReadRepository` 生产构造器始终委托 `WikiRuleKnowledgeSource`。
- 两套运行时使用相同 Wiki 根目录和 SQLite 文件。
- `rule_source` 统一为 `wiki`；关键 Wiki 文件缺失时失败关闭。
- Java 保留的 JDBC 构造器仅用于旧 H2 契约测试和迁移审计，不是生产分支。

## 启动与回退

首次启动会自动创建 SQLite schema。Java 部署只需要 Java 17、JAR、Wiki 目录、SQLite 文件、DBHub 和模型服务，不需要数据库服务。

旧 MySQL 可在稳定观察期作为离线备份保留。应用回退是 Java 与 FastAPI 之间切换；两者都继续使用 Wiki + SQLite，不再把 MySQL 恢复为事实源。

## 验收

- Wiki 模糊检索能定位 `MQSI2025_001` 和 `MQSI2025_005`。
- 本院生效规则返回医院版本、字段映射和 SQL 规格。
- 同一医院账号可通过 SQLite 登录。
- Qwen 8B 与 DeepSeek 对“从一月份到现在”使用同一个服务端时间边界。
- DBHub 试运行结果与迁移前相同。
- Java/Python 测试均不依赖运行中的 MySQL。
