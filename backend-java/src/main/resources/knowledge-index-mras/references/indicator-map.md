# 指标映射表

> 16项核心制度 → 45个衍生指标（35个指标）→ 15张目标表 → 判定字段 完整映射

## 制度-事件-指标 总表

| 序号 | 扩展编码 | 指标名称 | 制度 | 事件编码 | 目标表 | 判定字段 | 导向 | 数据状态 |
|------|---------|---------|------|---------|--------|---------|------|---------|
| Ind01 | HXZD-001-001 | 患者入院48小时内转科的比例_入区时间 | 首诊负责制度 | CORE_FDR | MRAS_BUSINESS_FIRSTVISIT | TRANSFER_WITHIN_TWO_DAY | 逐步降低 | ✅ 有数据 |
| Ind01 | HXZD-001-002 | 患者入院48小时内转科的比例_入院时间 | 首诊负责制度 | CORE_FDR | MRAS_BUSINESS_FIRSTVISIT | TRANSFER_WITHIN_TWO_DAY_ADMIT | 逐步降低 | ✅ 有数据 |
| Ind02 | HXZD-002-001 | 患者入院8小时内查房率 | 三级查房制度 | CORE_WARDROUND | MRAS_BUSINESS_WARDROUND | ROUND_WITHIN_EIGHT_HOUR | 逐步提高 | ⚠️ 部分 |
| Ind03 | HXZD-002-002 | 医嘱开具时间符合率 | 三级查房制度 | CORE_WARDROUND | MRAS_BUSINESS_WARDROUND | ORDER_TIME_COMPLIANCE | 逐步提高 | ⚠️ 部分 |
| Ind04 | HXZD-002-003 | 非计划重返率 | 三级查房制度 | CORE_WARDROUND | MRAS_BUSINESS_WARDROUND | UNPLANNED_RETURN | 逐步降低 | ⚠️ 部分 |
| Ind05 | HXZD-003-001 | 急会诊及时到位率 | 会诊制度 | CORE_CONSUL | MRAS_BUSINESS_CONSULTATION | CONSULTATION_TIMELY | 逐步提高 | ⚠️ 部分 |
| Ind06 | HXZD-003-002 | 会诊意见执行率 | 会诊制度 | CORE_CONSUL | MRAS_BUSINESS_CONSULTATION | CONSULTATION_EXECUTED | 逐步提高 | ⚠️ 部分 |
| Ind07 | HXZD-003-003 | 多学科会诊率 | 会诊制度 | CORE_CONSUL | MRAS_BUSINESS_CONSULTATION | MULTI_DEPT_CONSULTATION | 逐步提高 | ⚠️ 部分 |
| Ind08 | HXZD-003-004 | 会诊记录完整率 | 会诊制度 | CORE_CONSUL | MRAS_BUSINESS_CONSULTATION | CONSULTATION_RECORD_COMPLETE | 逐步提高 | ⚠️ 部分 |
| Ind09 | HXZD-004-001 | 护理级别与病情相符率 | 分级护理制度 | CORE_GRADED | MRAS_BUSINESS_GRADED_CARE | NURSING_GRADE_MATCH | 逐步降低 | ⚠️ 待验证 |
| Ind10 | HXZD-005-001 | 四级手术床旁交接率 | 值班和交接班制度 | CORE_SHIFTHANDOVER | MRAS_BUSINESS_SHIFTHANDOVER | BEDSIDE_HANDOVER | 逐步提高 | ⚠️ 待验证 |
| Ind14 | HXZD-007-001 | 急危重症患者抢救成功率 | 急危重患者抢救制度 | CORE_RESCUE | MRAS_BUSINESS_PATRESCUE | RESCUE_RESULTS | 逐步提高 | ⚠️ 部分 |
| Ind33 | HXZD-015-001 | 特殊使用级抗菌药物使用会诊率 | 抗菌药物分级管理制度 | CORE_SPECIAL_ANTI | MRAS_BUSINESS_ANTI | APPROVAL_ANTI | 逐步提高 | ✅ 有数据 |
| Ind34 | HXZD-016-001 | 临床用血后评估记录率 | 临床用血审核制度 | CORE_BLOOD_RECORD | MRAS_BUSINESS_BLOOD_AUDIT | TRANSFUSION_RECORD_STANDARD | 逐步提高 | ✅ 完整 |
| Ind35 | HXZD-016-002 | 术中自体血回输率 | 临床用血审核制度 | CORE_BLOOD_SURG | MRAS_BUSINESS_BLOOD_AUDIT | AUTOLOGOUS_TRANSFUSION | 逐步提高 | ⚠️ 待查 |

> 注：以上为有数据的主要指标。完整 45 个衍生指标 + 35 个指标见 Excel 源文件。

## 16 项制度分组

```
首诊负责制度          → CORE_FDR              → FIRSTVISIT       → HXZD-001-001, HXZD-001-002
三级查房制度          → CORE_WARDROUND        → WARDROUND        → HXZD-002-001, HXZD-002-002, HXZD-002-003
会诊制度              → CORE_CONSUL           → CONSULTATION     → HXZD-003-001~004
分级护理制度          → CORE_GRADED           → GRADED_CARE      → HXZD-004-001
值班和交接班制度      → CORE_SHIFTHANDOVER     → SHIFTHANDOVER    → HXZD-005-001
疑难病例讨论制度      → CORE_DIFFI_EMR         → DIFFI_EMR        → HXZD-006-001~002
                      → CORE_DIFFI_EMR_SECOND  → DIFFI_EMR_SECOND → HXZD-006-003
急危重患者抢救制度    → CORE_RESCUE           → PATRESCUE        → HXZD-007-001
术前讨论制度          → CORE_OP_DISC          → OP_DISC          → HXZD-008-001~004
死亡病例讨论制度      → CORE_DEATH            → DEATH            → HXZD-009-001~004
查对制度              → 无                    → —                → HXZD-010-001
手术安全核查制度      → CORE_SURGERY          → OPSAFE           → HXZD-011-001~002
手术分级管理制度      → CORE_SUR_GRADE        → SUR_GRADE        → HXZD-012-001~004
新技术和新项目准入制度 → 无                    → —                → HXZD-013-001
危急值报告制度        → CORE_CV_RPT           → CRITICAL_RPT     → HXZD-014-001~002
抗菌药物分级管理制度  → CORE_SPECIAL_ANTI     → ANTI             → HXZD-015-001
临床用血审核制度      → CORE_BLOOD_RECORD     → BLOOD_AUDIT      → HXZD-016-001
                      → CORE_BLOOD_SURG       → BLOOD_AUDIT      → HXZD-016-002
```

## SQL 查询速查

### 按事件查询（以 CORE_FDR 为例）

```sql
-- 概览 (HXZD-001-001)
SELECT COUNT(*) AS den, 
       SUM(CASE WHEN TRANSFER_WITHIN_TWO_DAY = 98175 THEN 1 ELSE 0 END) AS mol,
       CAST(SUM(CASE WHEN TRANSFER_WITHIN_TWO_DAY = 98175 THEN 1 ELSE 0 END) * 100.0 
            / NULLIF(COUNT(*), 0) AS DECIMAL(10,2)) AS rate
FROM WINDBA_GN.MRAS_BUSINESS_FIRSTVISIT
WHERE IS_DEL = 0 AND EVENT_AT >= '{start}' AND EVENT_AT < DATEADD(DAY, 1, '{end}');
```

## 枚举值标准

所有判定字段使用统一编码：
- `98175` = 是 / 达标 / 通过
- `98176` = 否 / 未达标 / 未通过
- `NULL` = 数据缺失（异常）

## 四维分类

35项指标按判定逻辑类型分为4个维度：

| 维度 | 核心方法 | 指标 |
|------|---------|------|
| **时限类规则** | DATEDIFF 时间窗口比较 | Ind01, Ind02, Ind05, Ind06, Ind14, Ind19, Ind20, Ind31 |
| **逻辑判定类规则** | CASE WHEN 多字段组合判定 | Ind03, Ind04, Ind07, Ind08, Ind09, Ind14, Ind26, Ind27, Ind28, Ind29, Ind33 |
| **内容完整性规则** | 记录存在性 + 必填字段检查 | Ind10, Ind11, Ind12, Ind13, Ind15, Ind16, Ind17, Ind18, Ind21, Ind22, Ind24, Ind25, Ind32, Ind34 |
| **AI模型调优规则** | NLP/LLM 语义理解 | Ind11, Ind12, Ind13, Ind30, Ind34, Ind35 |

> 注：部分指标跨多个维度（如 Ind14 同时涉及时限和逻辑判定），以上为主分类。
