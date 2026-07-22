# MySQL 到 Wiki + SQLite 迁移记录

> 状态：已完成，更新日期 2026-07-22。本文只记录迁移结果，不再提供 Python 迁移程序。

## 当前结构

```text
Java 17 + Spring Boot
├─ core-rules-wiki/                  规则、医院口径、字段映射和 SQL 规格
├─ runtime/wiki_agent_runtime.db     会话、认证、审批、Trace、Evidence 和对象引用
└─ DBHub sidecar                     只读访问医院 SQL Server
```

患者级业务数据没有迁移到 Wiki 或 SQLite，也不会进入 LLM、Trace 或 Evidence。

## 已完成事项

- 旧 MySQL 中的可变运行数据已复制到 `runtime/wiki_agent_runtime.db`。
- 规则类数据已结构化写入 `core-rules-wiki/`，Java 不再读取 MySQL 规则表。
- 迁移时逐表核对行数并执行 `PRAGMA integrity_check`。
- 当前部署、构建和启动均不需要 MySQL 或 Python。
- Java 生产规则读取固定委托 `WikiRuleKnowledgeSource`。

迁移工具及 Python 双栈实现已经从当前源码树移除；如需审计迁移算法或恢复历史工具，应从 2026-07-22 之前的 Git 历史读取，而不是在生产目录保留一套不可维护的旧实现。

## 当前验收口径

- Wiki 模糊检索能定位核心指标并返回本院覆盖版本。
- 同一医院账号可以从 SQLite 登录，运行数据按医院隔离。
- Qwen 与 DeepSeek 使用同一套 Java 时间解析和确定性 SQL 链路。
- DBHub 试运行继续访问原医院 SQL Server，只返回受控聚合结果。
- Java 测试和生产启动均不依赖运行中的 MySQL。
