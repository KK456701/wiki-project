---
title: "ETL抽取逻辑说明"
created: 2026-07-28
updated: 2026-07-28
type: concept
tags: ["ETL", "索引"]
sources: [raw/excel/AI指标顾问【AI Metrics Advisor】 v6.xls]
confidence: high
---

# ETL抽取逻辑说明

> 数据管线：源表 → XXJOB(T+1) → 中间表(MRAS_BUSINESS_*) → 目标表(概览/科室统计/患者明细)

## 数据流

```
HIS/护士站/医生站  →  XXJOB T+1每日抽取  →  MRAS_BUSINESS_*  →  应用层查询
```

## 目标表汇总

| 目标表(中间表) | 说明 |
|--------|------|
| `MRAS_BUSINESS_ANTI` | 对应业务指标 |
| `MRAS_BUSINESS_BLOOD_AUDIT` | 对应业务指标 |
| `MRAS_BUSINESS_CONSULTATION` | 对应业务指标 |
| `MRAS_BUSINESS_CRITICAL_RPT` | 对应业务指标 |
| `MRAS_BUSINESS_DEATH` | 对应业务指标 |
| `MRAS_BUSINESS_DIFFI_EMR` | 对应业务指标 |
| `MRAS_BUSINESS_DIFFI_EMR_SECOND` | 对应业务指标 |
| `MRAS_BUSINESS_FIRSTVISIT` | 对应业务指标 |
| `MRAS_BUSINESS_GRADED_CARE` | 对应业务指标 |
| `MRAS_BUSINESS_OP_DISC` | 对应业务指标 |
| `MRAS_BUSINESS_PATRESCUE` | 对应业务指标 |
| `MRAS_BUSINESS_SHIFTHANDOVER` | 对应业务指标 |
| `MRAS_BUSINESS_SURGERY` | 对应业务指标 |
| `MRAS_BUSINESS_SUR_GRADE` | 对应业务指标 |
| `MRAS_BUSINESS_WARDROUND` | 对应业务指标 |

## 业务表（影响数据）

| 业务表 | 说明 |
|--------|------|
| `BUSINESS_UNIT_X_BU_TYPE` | |
| `CLIBASIC_SURGERY` | |
| `INPATIENT_ENCOUNTER` | |
| `INPAT_TRANSFER` | |
| `INP_CLI_ORDER` | |
| `INP_SURGICAL_ANESTHESIA_PLAN` | |
| `MRAS_INDEX_SURGREC` | |
| `MRAS_MEDTECH_PRO` | |
| `MRAS_MEDTECH_PROC` | |
| `MRAS_PATIENT_EVENT` | |

## 业务表（不影响数据）

| 业务表 | 说明 |
|--------|------|
| `EMPLOYEE_INFO` | |
| `INPATIENT_ENCOUNTER` | |
| `INPATIENT_PARTICIPANT` | |
| `INPAT_TRANSFER` | |
| `INP_SURGICAL_PLAN` | |
| `MRAS_ORGANIZATION` | |
| `MRAS_PATIENT_EVENT` | |
| `ORGANIZATION` | |
| `VALUE_SET` | |
