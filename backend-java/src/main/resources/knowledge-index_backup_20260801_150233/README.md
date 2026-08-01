# knowledge-index-v60 — README

> **医疗质量安全核心制度落实情况监测指标知识库**
>
> 基于 LLM Wiki v3 方法论 + knowledgeskill6 v6.0.0 构建
> 数据源：AI指标顾问【AI Metrics Advisor】7.xls（2025年版 医疗质量安全核心制度）

---

## 一、这是什么？

一个按 **LLM Wiki 方法论**构建的结构化医疗指标知识库，将原始 Excel 指标定义文件消化为 **90+ 个相互交叉引用的 Markdown 页面**。

覆盖国家卫健委《医疗质量安全核心制度要点》中 **16 项核心制度**下的 **35 项监测指标**（含 45 个衍生实体实现方案）。

使用 [[wikilink]] 实现页面间双向链接，可在 Obsidian 中可视化浏览知识图谱。

### 🆕 v6.0.0 新特性

| 特性 | 说明 |
|------|------|
| **方案对比分析** | `comparisons/` 目录，7 维度 × 10 对变体自动差异分析 |
| **查询结果归档** | `queries/` 目录，SQL 查询结果 + 完整说明文档存档 |
| **人类可读概览** | 本 README.md，带快速导航 |
| **Wikilink 自动修复** | Lint 阶段自动检测并修复断裂 wikilink |
| **10 个新标签** | 标签库扩展：指标对比、变体方案、完整文档等 |

---

## 二、目录结构总览

```
knowledge-index-v60/
│
├── README.md                     ← 你正在看的文件 🆕
├── index.md                      ← 知识库导航首页（入口）
├── log.md                        ← 操作日志（只追加，不可删改）
├── .wiki-schema.md               ← Schema 元信息
│
├── raw/                          # Layer 1：不可变源材料
│   └── excel/                    #   Excel 指标定义源文件
│
├── concepts/                     # Layer 2：概念页（43个）
│   ├── 35 个指标概念页           #   按指标名称命名，如「患者入院48小时内转科的比例.md」
│   └── 8 个索引/技术页           #   四维分类索引、证据链验证模型、ETL抽取逻辑说明 等
│
├── entities/                     # Layer 2：衍生指标实体页（90个）
│   ├── 45 个衍生实体页           #   35个主方案(_001) + 10个变体(_002)
│   └── 45 个血缘解析页           #   _血缘说明.md，S0→S3完整数据链路
│
├── comparisons/                  # 🆕 Layer 3：方案对比分析
│   └── 35指标45衍生指标-主方案与变体差异分析.md
│
├── queries/                      # 🆕 Layer 3：查询结果存档
│   └── 20260728170600_说明文档.md
│
├── report/                       # 报告输出（时间戳命名）
├── command/                      # 工具脚本
├── references/                   # 参考文件
│   ├── indicator-map.md          #   16制度→35指标→45衍生→15表 完整映射
│   └── evidence-chain.md         #   6层证据链验证规程
│
└── templates/                    # 页面模板（5个）
    ├── concept-template.md
    ├── entity-template.md
    ├── entitymetricline-template.md # 🆕 血缘解析页模板
    ├── report-template.md
    └── queries-template.md
```

---

## 三、页面类型说明

| type | 用途 | 目录 | 数量 | 命名规则 |
|------|------|------|------|---------|
| `concept` | 指标概念页（按指标名称分类） | `concepts/` | 35 | `{指标名称}.md` |
| `concept` | 索引/技术页 | `concepts/` | 8 | `{索引名}.md` |
| `entity` | 衍生指标实体（主方案） | `entities/` | 35 | `{扩展编码}_001_{名称}.md` |
| `entity` | 衍生指标实体（变体） | `entities/` | 10 | `{扩展编码}_002_{名称}_可选方案.md` |
| `lineage` | 血缘解析页 🆕 | `entities/` | 45 | `{扩展编码}_{名称}_血缘说明.md` |
| `comparison` | 方案对比/版本 diff 🆕 | `comparisons/` | ≥1 | 描述性文件名 |
| `query` | 查询结果/说明文档 🆕 | `queries/` | ≥1 | 描述性文件名 |
| `report` | 时间戳指标报告 | `report/` | 按需 | `yyyymmddhhmmss.md` |

---

## 四、页面间关系图谱（v6 增强版）

```
                    ┌──────────────────────────────────┐
                    │          README.md (人类可读概览)  │ 🆕
                    └──────────────┬───────────────────┘
                                   │
                    ┌──────────────┴───────────────────┐
                    │          index.md (导航入口)       │
                    └──────┬────────────┬──────────────┘
                           │            │
           ┌───────────────┤            ├────────────────┐
           ▼               ▼            ▼                ▼
    ┌────────────┐  ┌───────────┐  ┌───────────┐  ┌────────────┐
    │ concepts/  │  │ entities/ │  │comparisons│  │references/ │
    │ 35个指标   │  │ 45衍生+   │  │ 方案对比   │  │ 映射表+    │
    │ 概念页     │◄─┤ 45血缘    │  │ 变体分析   │  │ 证据链规程 │
    └─────┬──────┘  └─────┬─────┘  └─────┬─────┘  └────────────┘
          │               │              │
          │    ┌──────────┘              │
          ▼    ▼                         ▼
    ┌─────────────────────────────────────────┐
    │  双向 wikilink 交叉引用                  │
    │  entity → concept (指标概念)             │
    │  entity → references (证据链)            │
    │  concept → entity (衍生实体列表)         │
    │  concept → concept (四维分类等)          │
    │  comparison → entity (逐对对比链接) 🆕   │
    │  query → concept/entity (说明文档链接)🆕  │
    └─────────────────────────────────────────┘
```

---

## 五、核心概念

### 5.1 指标 vs 衍生实体

| 概念 | 说明 | 数量 | 示例 |
|------|------|------|------|
| **指标** | 抽象的监测指标定义（概念层面的"测什么"） | 35 | "普通会诊及时完成率" |
| **衍生实体** | 指标的具体实现方案（工程层面的"怎么测"） | 45 | "普通会诊及时完成率_按申请科室统计"（主方案）<br>"普通会诊及时完成率_按会诊受邀科室数统计"（变体） |

一个指标可以有 1~2 个衍生实体。10 个指标存在主方案 `_001` 和变体 `_002` 两种实现。

### 5.2 v5/v6 核心设计：按指标名称分类

```
v3（旧）: concepts/首诊负责制度.md  → 用户要先知道制度名，再找指标
v5/v6（新）: concepts/患者入院48小时内转科的比例.md → 直接按指标名定位
```

### 5.3 四维分类

| 维度 | 核心方法 | 指标数 |
|------|---------|--------|
| **时限类** | `DATEDIFF` 时间窗口比较 | 14 |
| **逻辑判定类** | `CASE WHEN` 多字段组合判定 | 21 |
| **内容完整性** | 记录存在性 + 必填字段检查 | 8 |
| **AI模型调优** | NLP/LLM 语义理解 | 2 |

### 5.4 六层证据链

```
L6 数据验证层  ← 实时查询数据库，交叉验证指标值
L5 推导SQL层   ← 从目标表计算指标值（分子/分母/公式）
L4 规则定义层  ← 指标定义、计算公式、判定逻辑
L3 目标表层    ← MRAS_BUSINESS_* 数据仓库表
L2 抽取SQL层   ← ETL: 源表 → 目标表（字段映射+业务逻辑）
L1 源表层      ← HIS/护士站/医生站业务系统原始表
```

详见 [[references/evidence-chain]]。

### 5.5 🆕 方案对比分析（v6 新增）

`comparisons/` 目录按 **7 个差异维度** 对 10 对 `_001`/`_002` 变体进行逐对对比：
数据来源系统、时间维度、四维分类、患者范围、统计口径、实现状态、判定方式。

详见 [[comparisons/35指标45衍生指标-主方案与变体差异分析]]。

---

## 六、编码体系

### 指标编码：`HXZD-XXX-XXX`

```
HXZD  -  核心制度（HeXin ZhiDu）
XXX   -  制度序号（001~016 对应 16 项制度）
XXX   -  制度下指标序号
```

例：`HXZD-001-001` = 首诊负责制度（001）下第 1 个指标

### 扩展衍生编码：`HXZD-XXX-XXX_NNN`

```
HXZD-012-001_001  =  手术分级管理制度（012）· 并发症发生率比（001）· 主方案（_001）
HXZD-012-001_002  =  手术分级管理制度（012）· 并发症发生率比（001）· 变体方案（_002）
```

---

## 七、页面规范

所有页面必须包含 YAML frontmatter：

```yaml
---
title: "页面标题"
created: YYYY-MM-DD
updated: YYYY-MM-DD
type: entity           # entity | concept | comparison | query
tags: [标签1, 标签2]   # 必须来自 SCHEMA 标签库
sources: [raw/excel/源文件]
confidence: high       # high | medium | low
---
```

**约束规则：**
- 每个页面至少 2 个出站 `[[wikilink]]`（不能是孤岛）
- 标签必须来自 `.wiki-schema.md` / `SCHEMA.md` 的标签库
- 超过 200 行的页面建议拆分（entity 页因含 SQL 可放宽至 400 行）
- 仅在 2+ 个独立来源中出现的关键概念才建独立页面

---

## 八、快速导航

| 你想做什么？ | 去这里 |
|-------------|--------|
| 查看所有指标列表 | [[index]] → "35 项指标概念页" |
| 查看某指标的定义和公式 | `concepts/{指标名称}.md` |
| 查看某指标的 SQL 实现 | `entities/HXZD-XXX-XXX_XXX_{名称}.md` |
| 了解主方案和变体的区别 | [[comparisons/35指标45衍生指标-主方案与变体差异分析]] 🆕 |
| 查看完整指标说明文档 | [[queries/20260728170600_说明文档]] 🆕 |
| 了解指标和表之间的映射关系 | [[references/indicator-map]] |
| 了解证据链验证流程 | [[references/evidence-chain]] |
| 了解 ETL 数据抽取逻辑 | [[concepts/ETL抽取逻辑说明]] |
| 了解四维分类规则 | [[concepts/四维分类索引]] |
| 查看数据库中有哪些源表 | [[concepts/源表索引]] |
| 查看 XXJOB 调度作业 | [[concepts/XXJOB作业索引]] |
| 生成一份指标报告 | 参考 `templates/report-template.md` |
| 新增一个指标 | 参考 `templates/entity-template.md` + `templates/concept-template.md` |
| 新增血缘解析 | 参考 `templates/entitymetricline-template.md` 🆕 |
| 归档查询结果 | 参考 `templates/queries-template.md` 🆕 |

---

## 九、数据管线状态

| 状态 | 目标表数 | 记录总数 | 说明 |
|------|---------|---------|------|
| ✅ 有数据 | 11 张 | ~16,000+ | 可正常查询和生成报告 |
| ❌ 空表 | 4 张 | 0 | 数据管线已建立但暂无数据（DIFFI_EMR, DIFFI_EMR_SECOND, OP_DISC, SURGERY） |
| ❌ 无表 | 2 个指标 | — | HXZD-010-001（查对制度）、HXZD-013-001（新技术新项目） |

---

## 十、相关资源

| 资源 | 路径 |
|------|------|
| LLM Wiki 方法论 | `../readllmwiki.md` |
| knowledgeskill6 技能定义 | `../skills/knowledgeskill6/SKILL.md` |
| SCHEMA 结构规范 | `../skills/knowledgeskill6/SCHEMA.md` |
| Excel 源数据读取脚本 | `../skills/knowledgeskill6/scripts/read_excel.js` |
| 数据库查询脚本 | `command/query_indicators.js` |

---

## 十一、版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v60 | 2026-07-29 | 基于 knowledgeskill6 v6.0.0 + AI指标顾问7.xls，新增45个血缘解析页、entitymetricline-template模板、8个_002可选方案实体 |
| v60 init | 2026-07-28 | 初始创建，新增 comparisons/、queries/、README.md、Wikilink自动修复 |

---

<!-- readme: knowledge-index-v60 | updated: 2026-07-29 | pages: 135+ | concepts: 35 | entities: 45 | lineage: 45 | variant-pairs: 10 | features: lineage+template+wikilink-fix -->
