---
description: 项目规则总入口（Roo 自动加载缓存）。本文件仅作指针，不含任何规则条文；唯一真相来源为 docs/agent-rules/ 与仓库根 AGENTS.md。AI 编写任何代码前应读取 AGENTS.md 第 4 节分派表并据此加载 docs/agent-rules/ 下的对应子规则
alwaysApply: true
enabled: true
---

# 项目规则总入口（Roo 指针版）

> ⚠️ **本文件（`.roo/rules/` 下）仅作为指针，不内联任何规则条文，也不重复维护规则文件清单或红线摘要。**
> 规则的**唯一真相来源**是 `docs/agent-rules/*.md`，完整索引与分派表见仓库根 `AGENTS.md` 第 4 节，最高频红线摘要见 `AGENTS.md` 第 5 节。
> 若本文件与 `docs/agent-rules/` 不一致，**以 `docs/agent-rules/` 为准**。

## 如何使用本指针

1. 读 `AGENTS.md` 第 4 节「规则分派表」——那里是**唯一**的规则文件清单与触发条件，新增/改名规则只改那一处。
2. 读 `AGENTS.md` 第 5 节「核心约定入口」——最高频 3 条硬性红线（路径别名 / 网络请求 / 查故障）的速记入口。
3. 按分派表到 `docs/agent-rules/` 读取对应文件（常驻 A 段 always 加载，按需 B 段命中触发条件时套用）。
4. **不要等用户提醒**：每次任务应主动据此分派表套用对应规则。
