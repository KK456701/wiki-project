---
title: "ETL抽取逻辑说明 — 源表建表 → 事件更新 → 目标表 → 口径推理链路"
created: 2026-07-28
updated: 2026-07-29
type: concept
tags: ["ETL", "索引", "数据血缘", "推理链路"]
sources: [raw/excel/AI指标顾问【AI Metrics Advisor】7.xls]
confidence: high
---

# ETL抽取逻辑说明

> 完整推理链路：**S0 建表** → **S1 抽取** → **S2 事件更新** → **S3 口径计算** → **S4 LLM 血缘解析**

---

## 推理链路全景

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        S0: 在 newdata 数据库中建表                            │
│                                                                             │
│  从 Excel「源表」列提取所有源表名，去重后在 newdata 库中创建对应的表结构         │
│  源表清单（15 张核心业务表 + 若干辅助表）：                                    │
│  INPATIENT_ENCOUNTER, INPAT_TRANSFER, INP_CLI_ORDER, INP_CLI_ORDER_ITEM,     │
│  INP_SURGICAL_PLAN, INP_SURGICAL_ANESTHESIA_PLAN, INPATIENT_EMR_SET,        │
│  INPATIENT_CONSULT_APPLY, INPATIENT_PARTICIPANT, EMPLOYEE_INFO,              │
│  ORGANIZATION, CLIBASIC_SURGERY, MAHP_DMTS_MAIN, CRITICAL_VALUE_REPORT,      │
│  MRAS_PATIENT_EVENT, VALUE_SET ...                                          │
└───────────────────────┬─────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      S1: 从业务系统抽取源表数据到 newdata                       │
│                                                                             │
│  HIS/EMR/护士站/手麻/会诊/病案 ... 等业务系统的原始数据                         │
│         ↓ 全量/增量同步                                                      │
│  newdata 库中的源表（结构与业务系统一致，数据定期同步）                           │
│                                                                             │
│  ★ 这一步是数据底座：把分散在各业务系统的表汇集到一个库中，                       │
│    后续所有的事件转换和指标计算都基于 newdata 库完成                             │
└───────────────────────┬─────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                  S2: 事件引擎 → 更新目标中间表 (XXJOB T+1)                      │
│                                                                             │
│  每个指标对应一个「事件编码」(event)，事件引擎读取源表，                          │
│  按事件规则将源表行转换为统一的事件格式，写入 MRAS_BUSINESS_* 目标表：            │
│                                                                             │
│  事件编码              →  目标表                      指标数                   │
│  ──────────────────────────────────────────────────────────────────────      │
│  CORE_FDR              →  MRAS_BUSINESS_FIRSTVISIT      1 (首诊负责)         │
│  CORE_WARDROUND        →  MRAS_BUSINESS_WARDROUND       3 (三级查房)         │
│  CORE_CONSUL(_OUT)     →  MRAS_BUSINESS_CONSULTATION    4 (会诊制度)         │
│  GRADE_CARE            →  MRAS_BUSINESS_GRADED_CARE     1 (分级护理)         │
│  CORE_SHIFTHANDOVER    →  MRAS_BUSINESS_SHIFTHANDOVER   1 (值班交接班)       │
│  CORE_DIFFI_EMR        →  MRAS_BUSINESS_DIFFI_EMR       2 (疑难病例讨论)     │
│  CORE_DIFFI_EMR_SECOND →  MRAS_BUSINESS_DIFFI_EMR_SECOND 1 (疑难病例2)       │
│  CORE_RESCUE           →  MRAS_BUSINESS_PATRESCUE       1 (急危重抢救)       │
│  CORE_OP_DISC(_V2)     →  MRAS_BUSINESS_OP_DISC         4 (术前讨论)         │
│  CORE_DEATH(_EXT)      →  MRAS_BUSINESS_DEATH           4 (死亡病例讨论)     │
│  —                     →  —                             1 (查对制度)         │
│  CORE_SURGERY          →  MRAS_BUSINESS_SURGERY         2 (手术安全核查)     │
│  CORE_SUR_GRADE(_V2)   →  MRAS_BUSINESS_SUR_GRADE       4 (手术分级管理)     │
│  —                     →  —                             1 (新技术新项目)      │
│  CORE_CV_RPT           →  MRAS_BUSINESS_CRITICAL_RPT    2 (危急值报告)       │
│  CORE_SPECIAL_ANTI     →  MRAS_BUSINESS_ANTI            1 (抗菌药物分级)     │
│  (_EXT)                →  MRAS_BUSINESS_ANTI            1 (抗菌药物-可选)    │
│  CORE_BLOOD_RECORD     →  MRAS_BUSINESS_BLOOD_AUDIT     1 (临床用血-评估)    │
│  CORE_BLOOD_SURG       →  —                             1 (临床用血-回输)    │
│                                                                             │
│  ★ 事件引擎的核心 SQL 写在各 entity 页的「## 源表」代码块中                     │
│  ★ XXJOB 每日 T+1 执行：读取昨日增量数据 → 事件转换 → UPSERT 目标表             │
│  ★ 一个目标表可能被多个指标共享（如 MRAS_BUSINESS_DEATH 承载 4 个死亡病例指标）   │
└───────────────────────┬─────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     S3: 口径计算 → 三层查询输出指标结果                         │
│                                                                             │
│  从目标表 MRAS_BUSINESS_* 出发，按三层粒度计算指标：                            │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────┐           │
│  │ 目标表-概览  │ 全院级别  │ COUNT + CASE WHEN 聚合            │           │
│  │             │           │ 输出：分母/分子/监测情况/是否达标     │           │
│  │             │           │ + 未达标科室列表                    │           │
│  ├──────────────────────────────────────────────────────────────┤           │
│  │ 目标表-科室统计│ 科室级别  │ GROUP BY CURRENT_DEPT_ID          │           │
│  │             │           │ 输出：各科室 分母/分子/达标判定       │           │
│  ├──────────────────────────────────────────────────────────────┤           │
│  │ 目标表-患者明细│ 患者级别  │ 逐行明细                          │           │
│  │             │           │ 输出：患者姓名/住院号/是否命中/明细字段│           │
│  └──────────────────────────────────────────────────────────────┘           │
│                                                                             │
│  计算公式（以 HXZD-001-001 为例）：                                            │
│  分母 = COUNT(1)                          — 同期入院患者总人次                │
│  分子 = COUNT(CASE WHEN judgeField='98175' THEN 1 END)  — 命中条件的记录     │
│  监测情况 = 分子 / 分母 × 100%                                                │
│  是否达标 = 监测情况 >= 目标值 ? '是' : '否'                                   │
│                                                                             │
│  ★ judgeField（判定字段）是核心：值为 '98175' 时计入分子，'98176' 不计入         │
│  ★ 98175/98176 是系统的标准「是/否」编码体系                                   │
│  ★ 三层 SQL 写在各 entity 页的「## 目标表-概览」「## 目标表-科室统计」            │
│    「## 目标表-患者明细」代码块中                                              │
└───────────────────────┬─────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                   S4: LLM 血缘解析 → 结构化 JSON 输出                          │
│                                                                             │
│  每个 entity 页 Markdown 包含 4 个 SQL 块，LLM 逐文件解析：                     │
│                                                                             │
│  entity 页 Markdown                                                         │
│  ├── ## 源表                 → LLM 解析 FROM/JOIN/WHERE → upstream 表字段     │
│  ├── ## 目标表-概览           → LLM 解析 聚合/判定逻辑 → formula + targetValue  │
│  ├── ## 目标表-科室统计       → LLM 解析 GROUP BY → dimFields + standFlag     │
│  └── ## 目标表-患者明细       → LLM 解析 SELECT 列表 → detailFields + 动态参数  │
│                                                                             │
│         ↓ 45 个 entity × 4 个 SQL 块 = 180 次独立 LLM 调用（可完全并行）        │
│         ↓ 每个 entity 输出一份标准化 JSON                                     │
│         ↓ 汇总到 lineage-index.json，形成完整的指标血缘图谱                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 输出结构化 JSON

LLM 解析每个 entity 页后输出如下结构：

```jsonc
{
  // ==================== S0-S1: 源表信息 ====================
  "indicator": "HXZD-001-001",
  // 基础指标编码（35 个 concept 各一个）

  "entityCode": "HXZD-001-001",
  // 衍生实体编码（45 个 entity 各一个，_001 推荐方案 / _002 可选方案）

  "name": "患者入院48小时内转科的比例",
  "schemeType": "推荐方案",
  // 方案类型：推荐方案 | 可选方案 | 可选方案（未实现）

  // ==================== S2: 事件引擎 ====================
  "event": {
    "code": "CORE_FDR",
    // 事件编码，对应 XXJOB 中的事件类型
    // 35 个指标共使用 18 个事件编码（含 V2/EXT 变体）

    "name": "首诊负责",
    // 事件中文名

    "targetTable": "MRAS_BUSINESS_FIRSTVISIT",
    // 事件写入的目标中间表，命名规则 MRAS_BUSINESS_{模块大写}
    // 概览/科室统计/患者明细三层查询都基于此表

    "upstreamTables": [
      // 事件引擎从 newdata 库中读取的源表（从 ## 源表 SQL 解析）
      { "table": "INPATIENT_ENCOUNTER",   "alias": "t1",  "role": "primary",
        // role: primary(主事实表) | lookup(维度关联) | filter(筛选辅助)
        "fields": ["ENCOUNTER_ID", "FIRST_ADMITTED_TO_WARD_AT", "CURRENT_DEPT_ID", "IS_DEL"],
        "joinOn": null },

      { "table": "INPAT_TRANSFER",        "alias": "t2",  "role": "lookup",
        "fields": ["ENCOUNTER_ID", "INPAT_TRANSFER_AT", "ORIGIN_DEPT_ID"],
        "joinOn": "t1.ENCOUNTER_ID = t2.ENCOUNTER_ID AND t2.IS_DEL = 0" },

      { "table": "ORGANIZATION",          "alias": "o1",  "role": "lookup",
        "fields": ["ORG_ID", "ORG_NAME"],
        "joinOn": "t2.ORIGIN_DEPT_ID = o1.ORG_ID" }
    ]
  },

  // ==================== S3: 口径计算 ====================
  "calculation": {
    // 从 ## 目标表-概览 SQL 解析的口径

    "judgeField": "TRANSFER_WITHIN_TWO_DAY",
    // 判定字段（在 ## 源表 SQL 中 AS 别名定义，写入目标表）
    // '98175' → 命中分子，'98176' → 未命中

    "formula": {
      "numerator":   "COUNT(CASE WHEN TRANSFER_WITHIN_TWO_DAY='98175' THEN 1 END)",
      "denominator": "COUNT(1)",
      "expression":  "分子 / 分母 × 100%",
      "unit":        "%",
      "targetValue": 0.05
      // 目标值来源：MRAS_TARGET_DEFINITION 表的 TARGET_COMP_VAL 字段
      // Excel 存百分数（5），计算时 / 100
    },

    "dimensions": {
      // 从 ## 目标表-科室统计 SQL 解析的 GROUP BY 维度
      "dimFields": ["CURRENT_DEPT_ID", "HOSPITAL_AREA_ID"],
      "standLogic": "监测情况 >= 目标值 → '达标'，否则 '未达标'"
      // 达标/未达标判定（standFlag: 98175=达标, 98176=未达标）
    },

    "timeWindow": {
      "field": "FIRST_ADMITTED_TO_WARD_AT",
      // 时间过滤字段（WHERE ... BETWEEN :startTime AND :endTime）
      "type": "admittedToWard"
      // 时间类型：admittedToWard | discharged | surgeryEnd | death | orderTime | consultFinish
    },

    "dynamicParams": [
      // 从 SQL 中解析的占位符参数
      { "type": "ETC",    "param": "hospitalAreaList", "fragment": "AND HOSPITAL_AREA_ID IN (:hospitalAreaList)" },
      { "type": "EQUALS", "param": "syncType",
        "branches": [
          { "value": "increment", "meaning": "增量同步，WHERE modified_at BETWEEN" },
          { "value": "single",    "meaning": "在院患者，WHERE discharged_at IS NULL" },
          { "value": "outHosp",   "meaning": "出院患者，WHERE discharged_at BETWEEN" }
        ]
      }
    ],

    "patientScope": "出院患者",
    // 患者范围：在院患者 | 出院患者 | 在院+出院患者
    // 影响 syncType 分支的选择

    "direction": "逐步降低",
    // 指标导向：逐步提高 | 逐步降低 | 监测 | --
    // 决定达标判定方向（>= 目标还是 <= 目标）
  },

  // ==================== S4: 表级汇总 ====================
  "tableSummary": {
    "upstreamTables": [
      "INPATIENT_ENCOUNTER",
      "INPAT_TRANSFER",
      "ORGANIZATION",
      "EMPLOYEE_INFO",
      "INPATIENT_PARTICIPANT",
      "BUSINESS_UNIT_X_BU_TYPE"
    ],
    "targetTable": "MRAS_BUSINESS_FIRSTVISIT",
    "affectsData": true
    // "是" → 业务表变更会触发指标重算，"否" → 仅依赖源表数据
  }
}
```

---

## 事件-目标表-指标 全量映射

| 目标表 | 事件编码 | 制度 | 指标数 |
|--------|---------|------|--------|
| `MRAS_BUSINESS_FIRSTVISIT` | CORE_FDR | 首诊负责制度 | 1 |
| `MRAS_BUSINESS_WARDROUND` | CORE_WARDROUND | 三级查房制度 | 3 |
| `MRAS_BUSINESS_CONSULTATION` | CORE_CONSUL / CORE_CONSUL_OUT | 会诊制度 | 4 |
| `MRAS_BUSINESS_GRADED_CARE` | GRADE_CARE | 分级护理制度 | 1 |
| `MRAS_BUSINESS_SHIFTHANDOVER` | CORE_SHIFTHANDOVER | 值班和交接班制度 | 1 |
| `MRAS_BUSINESS_DIFFI_EMR` | CORE_DIFFI_EMR | 疑难病例讨论制度 | 2 |
| `MRAS_BUSINESS_DIFFI_EMR_SECOND` | CORE_DIFFI_EMR_SECOND | 疑难病例讨论制度(高额费用) | 1 |
| `MRAS_BUSINESS_PATRESCUE` | CORE_RESCUE | 急危重患者抢救制度 | 1 |
| `MRAS_BUSINESS_OP_DISC` | CORE_OP_DISC / CORE_OP_DISC_V2 | 术前讨论制度 | 4 |
| `MRAS_BUSINESS_DEATH` | CORE_DEATH / CORE_DEATH_EXT | 死亡病例讨论制度 | 4 |
| `MRAS_BUSINESS_SURGERY` | CORE_SURGERY | 手术安全核查制度 | 2 |
| `MRAS_BUSINESS_SUR_GRADE` | CORE_SUR_GRADE / CORE_SUR_GRADE_V2 | 手术分级管理制度 | 4 |
| `MRAS_BUSINESS_CRITICAL_RPT` | CORE_CV_RPT | 危急值报告制度 | 2 |
| `MRAS_BUSINESS_ANTI` | CORE_SPECIAL_ANTI / CORE_SPECIAL_ANTI_EXT | 抗菌药物分级管理制度 | 1 |
| `MRAS_BUSINESS_BLOOD_AUDIT` | CORE_BLOOD_RECORD | 临床用血审核制度 | 1 |
| `MRAS_MEDTECH_PRO/PROC` | — | 新技术和新项目准入制度 | 1 |
| — | — | 查对制度 | 1 |
| `MRAS_INDEX_SURGREC` | CORE_BLOOD_SURG | 临床用血审核制度(术中回输) | 1 |

> 共 **15 个目标表**、**18 个事件编码**、**35 个指标概念**、**45 个衍生实体**

---

## 源表汇总

### 核心业务表（在 newdata 库中建表）

| 源表 | 来源系统 | 说明 |
|------|---------|------|
| `INPATIENT_ENCOUNTER` | 住院医生站 | 住院患者主表，所有住院指标的核心主表 |
| `INPAT_TRANSFER` | 护士站 | 转科记录表 |
| `INP_CLI_ORDER` | 住院医生站 | 医嘱主表 |
| `INP_CLI_ORDER_ITEM` | 住院医生站 | 医嘱明细表 |
| `INP_SURGICAL_PLAN` | 住院医生站 | 手术计划表 |
| `INP_SURGICAL_ANESTHESIA_PLAN` | 手麻管理系统 | 手术麻醉计划表 |
| `INPATIENT_EMR_SET` | 电子病历系统 | 病历文书表（术前讨论/死亡讨论等） |
| `INPATIENT_CONSULT_APPLY` | 会诊管理系统 | 会诊申请表 |
| `INPATIENT_CONSULT_REPLY` | 会诊管理系统 | 会诊答复表 |
| `CRITICAL_VALUE_REPORT` | 医技系统 | 危急值报告表 |
| `MAHP_DMTS_MAIN` | 病案管理系统 | 病案首页主表 |
| `MAHP_DMTS_ICD_OPERATION` | 病案管理系统 | 病案首页手术记录 |
| `CLIBASIC_SURGERY` | 手术临床服务 | 手术目录/术种基础数据 |
| `MRAS_PATIENT_EVENT` | 事件引擎 | 患者事件统一模型（中间层） |
| `MRAS_WF_REGISTER` | 医务系统 | 工作流/审批记录（如抗菌药物报批） |

### 维度辅助表

| 维度表 | 说明 |
|--------|------|
| `EMPLOYEE_INFO` | 员工信息表（医师/护士/科主任） |
| `ORGANIZATION` | 组织架构表（科室/病区/院区） |
| `MRAS_ORGANIZATION` | 组织架构快照表（含医疗组） |
| `VALUE_SET` | 术语值集表（编码→中文映射） |
| `MEDICINE` / `MEDICINE_DETAIL` | 药品目录表（抗菌药物分级） |
| `HOLIDAY` | 节假日表（死亡病例讨论时限计算） |
| `INPATIENT_PARTICIPANT` | 住院参与者表（关联责任医师） |
| `CLINICAL_SERVICE_SURGERY` | 手术临床服务配置表 |

---

## 动态 SQL 占位符

SQL 中的两种占位符语法：

### `#ETC{SQL片段}` — 条件占位符
入参存在时注入 SQL 片段，入参为空时整行移除。用于可选过滤条件。

### `#EQUALS{:paramName; value; SQL片段}` — 等值分支占位符
入参值匹配时注入对应分支。用于同步模式切换（增量/在院/出院）。

详见各 entity 页「sql自定义参数」章节。
