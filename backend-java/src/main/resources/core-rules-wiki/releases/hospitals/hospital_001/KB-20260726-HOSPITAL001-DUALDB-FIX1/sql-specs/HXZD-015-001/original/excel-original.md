---
page_type: sql_original
rule_id: HXZD-015-001
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-26
---

# 原始 SQL 存档：特殊使用级抗菌药物使用会诊率

> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。

## 源表 / 事件抽取 SQL

```sql
SELECT
    t1.ENCOUNTER_ID AS encounterId,
    '1' AS mrasTargetDefinitionId,
    '1' AS mrasBusinessIndexId,
    'V2.0' as version,
    t1.PERSON_ID AS personId,
    t1.FULL_NAME AS personName,
    t1.IMRN AS imrn,
    c.PRESCRIBED_AT AS eventAt,  --医嘱开立时间
    a.CLI_ORDER_ITEM_ID AS bizId,  --医嘱项标识
    GETDATE() AS extractAt,
    t3.EMPLOYEE_ID AS employeeId,
    t4.EMPLOYEE_NAME AS employeeName,
    ie.CURRENT_DEPT_ID AS deptId,
    o1.ORG_NAME AS deptName,
    ie.CURRENT_WARD_ID AS wardId,
    o2.ORG_NAME AS wardName,
    ie.ADMITTED_TO_WARD_AT AS admittedToWardAt,
    ie.DISCHARGED_FROM_WARD_AT AS dischargedFromWardAt,
    b.MEDICINE_ID AS antiMedicineId,
    m.COMMODITY_NAME_CHINESE AS antiMedicineName,
    c.PRESCRIBED_AT AS prescribedAt,
    wi.FINISH_AT AS registerFinishAt,
    CASE WHEN r.MRAS_WF_APPREGISTER_ID IS NOT NULL
THEN m.COMMODITY_NAME_CHINESE
ELSE TO_NCHAR('')
-- 转换为 NVARCHAR2 的空字符串
    END AS registerAntiMedicineName,
    CASE WHEN r.MRAS_WF_APPREGISTER_ID IS NOT NULL THEN 98175 ELSE 98176 END AS approvalAnti,
    a.CLI_ORDER_ITEM_ID AS orderItemId,
    a.CLI_ORDER_ID AS orderId,
    c.HOSPITAL_SOID AS hospitalSoid,
    c.HOSPITAL_AREA_ID AS hospitalAreaId,
    CASE WHEN (a.IS_DEL = '1' or b.IS_DEL = '1' OR c.IS_DEL = '1')  THEN 1 ELSE 0 END AS isDel
    FROM INP_CLI_ORDER_ITEM a
    inner join INPATIENT_ENCOUNTER ie on a.ENCOUNTER_ID = ie.ENCOUNTER_ID and ie.IS_DEL = 0
    inner join INP_CLI_ORDER c on a.CLI_ORDER_ID = c.CLI_ORDER_ID
    -- 特殊级抗菌药物
    inner join MEDICINE_DETAIL b on a.GOODS_ID = b.MEDICINE_ID and b.ANTIBACTRL_LEVEL_CODE = 138401
    LEFT JOIN MEDICINE m on b.MEDICINE_ID = m.MEDICINE_ID
    LEFT JOIN MRAS_WF_REGISTER r ON c.CLI_ORDER_ID = r.ORDERID AND r.IS_DEL = 0
    LEFT JOIN WORKFLOW_INSTANCE wi ON r.WF_INSTANCE_ID = wi.WF_INSTANCE_ID and wi.IS_DEL = 0
    LEFT JOIN INPATIENT_ENCOUNTER t1 ON  a.ENCOUNTER_ID =t1.ENCOUNTER_ID
    LEFT JOIN ORGANIZATION o1 ON ie.CURRENT_DEPT_ID = o1.ORG_ID
    LEFT JOIN ORGANIZATION o2 ON ie.CURRENT_WARD_ID = o2.ORG_ID
-- 责任医生
    LEFT JOIN INPATIENT_PARTICIPANT t3 ON ie.ENCOUNTER_ID = t3.ENCOUNTER_ID AND  t3.IS_DEL = 0 AND  t3.INPAT_PARTICIPANT_TYPE_CODE = 1000098
    LEFT JOIN EMPLOYEE_INFO t4 ON t3.EMPLOYEE_ID = t4.EMPLOYEE_ID
    WHERE 1 = 1
    -- 排除已失效，已作废医嘱
    AND c.CLI_ORDER_STATUS NOT IN ('98203','98203')
 AND a.ENCOUNTER_ID in (select ENCOUNTER_ID from INPATIENT_ENCOUNTER where DISCHARGED_FROM_WARD_AT BETWEEN :startTime and :endTime)
```

## 目标表－概览 SQL

```sql
--查询出目标值，各个指标编码是固定的
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION #{NOLOCK}
    WHERE
TARGET_NO = 'HXZD-015-001'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
SELECT
event.DEPT_ID,
event.DEPT_NAME AS "科室名称",
COUNT(CASE WHEN APPROVAL_ANTI = '98175' THEN 1 ELSE NULL END) AS '分子特殊使用级抗菌药物使用医嘱与会诊记录相对应的医嘱数量',
COUNT(1) AS '分母同期特殊使用级抗菌药物使用医嘱总数量',
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN APPROVAL_ANTI = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS '监测情况'
FROM
MRAS_BUSINESS_ANTI event #{NOLOCK}
WHERE
--布局组件设置提升效率
AND event.PRESCRIBED_AT BETWEEN :marptBeginAt and :marptEndAt
GROUP BY
event.DEPT_ID, event.DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子特殊使用级抗菌药物使用医嘱与会诊记录相对应的医嘱数量) AS "分子特殊使用级抗菌药物使用医嘱与会诊记录相对应的医嘱数量",
SUM(分母同期特殊使用级抗菌药物使用医嘱总数量) AS "分母同期特殊使用级抗菌药物使用医嘱总数量",
CASE
WHEN SUM(分母同期特殊使用级抗菌药物使用医嘱总数量) = 0 THEN null
ELSE SUM(分子特殊使用级抗菌药物使用医嘱与会诊记录相对应的医嘱数量) * 1.0 / SUM(分母同期特殊使用级抗菌药物使用医嘱总数量)
END AS "监测情况",
(SELECT target_value FROM TargetValue) AS "目标值"
    FROM DeptOrderStats
)
-- 进行数据输出
SELECT
    t.*,
CASE WHEN t.监测情况 < t.目标值 THEN '否' ELSE '是' END AS "是否达标",
    STUFF((
SELECT ', ' + 科室名称
FROM DeptOrderStats
WHERE 分母同期特殊使用级抗菌药物使用医嘱总数量 > 0
AND 监测情况 < (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS "未达标科室列表"
FROM TotalStats t;
```

## 目标表－科室统计 SQL

```sql
--目标值的数据
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION #{NOLOCK}
    WHERE
TARGET_NO = 'HXZD-015-001'
),
-- 按照科室来进行处理
DeptStats AS (
SELECT
event.DEPT_ID AS "当前科室编码",
event.DEPT_NAME AS "当前科室名称",
COUNT(CASE WHEN APPROVAL_ANTI = '98175' THEN 1 ELSE NULL END) AS "分子特殊使用级抗菌药物使用医嘱与会诊记录相对应的医嘱数量",
COUNT(1) AS "分母同期特殊使用级抗菌药物使用医嘱总数量",
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN APPROVAL_ANTI = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS "监测情况" ,
max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
FROM
MRAS_BUSINESS_ANTI event  #{NOLOCK}
WHERE
--布局组件设置提升效率
AND event.PRESCRIBED_AT BETWEEN :marptBeginAt and :marptEndAt
GROUP BY  event.DEPT_ID,event.DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子特殊使用级抗菌药物使用医嘱与会诊记录相对应的医嘱数量,
d.分母同期特殊使用级抗菌药物使用医嘱总数量,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期特殊使用级抗菌药物使用医嘱总数量 = 0 THEN '无数据'
WHEN d.监测情况 >= (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期特殊使用级抗菌药物使用医嘱总数量 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子特殊使用级抗菌药物使用医嘱与会诊记录相对应的医嘱数量 as "numerator",
d.分母同期特殊使用级抗菌药物使用医嘱总数量 as "denominator",
d.HOSPITAL_AREA_ID as "hospitalAreaId"
    FROM
DeptStats d
WHERE 1 = 1
-- 达标
-- 未达标
)
-- 最终查询结果
SELECT * FROM TempResults
```

## 目标表－患者明细 SQL

```sql
SELECT
event.ENCOUNTER_ID ,
event.DEPT_ID AS "当前科室编码",
event.DEPT_NAME AS "当前科室",
event.IMRN AS "住院号",
event.PERSON_NAME AS "患者姓名",
event.EMPLOYEE_NAME AS "责任医师",
team.ORG_NAME as "TEAM_NAME",
team.ORG_ID as "TEAM_ID",
team.ORG_NO as "TEAM_NO",
team.ORG_NAME as "当前医疗组",
event.ADMITTED_TO_WARD_AT AS "入区时间",
event.DISCHARGED_FROM_WARD_AT AS "出区时间",
event.ANTI_MEDICINE_NAME AS "特殊级抗菌药名称",
event.PRESCRIBED_AT AS "医嘱开立时间",
event.REGISTER_FINISH_AT AS "会诊记录完成时间",
event.REGISTER_ANTI_MEDICINE_NAME AS "会诊记录抗菌药名称",
CASE WHEN APPROVAL_ANTI = '98175' THEN '是' ELSE '否' END AS "医嘱和会诊记录药品是否一致",
CASE WHEN APPROVAL_ANTI = '98175' THEN 98175 ELSE 98176 END AS "standFlag"
FROM
MRAS_BUSINESS_ANTI event  #{NOLOCK}
LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
--布局组件设置提升效率
  AND event.PRESCRIBED_AT BETWEEN :marptBeginAt and :marptEndAt
AND APPROVAL_ANTI = '98175'
```
