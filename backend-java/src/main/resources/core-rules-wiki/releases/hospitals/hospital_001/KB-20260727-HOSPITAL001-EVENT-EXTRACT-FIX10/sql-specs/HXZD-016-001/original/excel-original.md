---
page_type: sql_original
rule_id: HXZD-016-001
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-27
---

# 原始 SQL 存档：临床用血后评估记录率

> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。

## 源表 / 事件抽取 SQL

```sql
SELECT
t1.ENCOUNTER_ID AS bizId,  --用来更新数据
'CORE_BLOOD_RECORD' as eventNo,
'临床用血后评估记录' as eventName,
t1.ADMITTED_TO_WARD_AT AS eventAt, -- 预留
GETDATE() AS extractAt,
item.CLI_ORDER_ITEM_ID AS mrasTargetDefinitionId,
'V2.0' as version,
t1.ENCOUNTER_ID as encounterId,--就诊标识
t1.FULL_NAME AS personName,--姓名
t1.IMRN as imrn,--住院号
emp.EMPLOYEE_NAME as currentAdmitterName,--责任医师
t1.CURRENT_DEPT_ID AS currentDeptId,--科室
o1.ORG_NAME AS currentDeptName, --科室名称
t1.CURRENT_WARD_ID AS currentWardId,--病区
o2.ORG_NAME AS currentWardName,--病区名称
t1.FIRST_ADMITTED_TO_WARD_AT AS admittedToWardAt, --入区时间
t1.DISCHARGED_FROM_WARD_AT AS wardDischargedAt, --出区时间
a.PRESCRIBED_AT as prescribedAt, -- 开立日期时间
emr.max_submitted_at as recordEndAt, -- 输血记录完成时间
CASE
WHEN emr.ENCOUNTER_ID IS NOT NULL THEN 98175  -- 有输血记录
ELSE 98176  -- 无输血记录
END as transfusionRecordStandard, -- 符合输血治疗后规范书写评估输血记录
null as actualMajorSurgery,-- 符合手术记录
null as autoBloodTran, -- 符合使用自体血回输
item.ORDER_ITEM_CONTENT as bizAttrOneName, -- 医嘱内容
a.HOSPITAL_SOID AS hospitalSoid,
t1.SOURCE_HOSPITAL_AREA_ID AS hospitalAreaId,
0 AS isDel
from INP_CLI_ORDER_ITEM item
LEFT JOIN INP_CLI_ORDER a on a.CLI_ORDER_ID = item.CLI_ORDER_ID
left join INPATIENT_ENCOUNTER t1 on a.ENCOUNTER_ID = t1.ENCOUNTER_ID
LEFT JOIN INPATIENT_PARTICIPANT t3 ON t3.ENCOUNTER_ID = t1.ENCOUNTER_ID AND t3.IS_DEL=0 AND t3.INPAT_PARTICIPANT_TYPE_CODE = 1000098
left join EMPLOYEE_INFO emp on t3.EMPLOYEE_ID = emp.EMPLOYEE_ID and emp.IS_DEL = 0
LEFT JOIN ORGANIZATION o1 ON t1.CURRENT_DEPT_ID = o1.ORG_ID
LEFT JOIN ORGANIZATION o2 ON t1.CURRENT_WARD_ID = o2.ORG_ID
LEFT JOIN (
    SELECT ENCOUNTER_ID, MAX(EMR_SET_SUBMIT_AT) as max_submitted_at
    FROM INPATIENT_EMR_SET
    WHERE IS_DEL = 0
    GROUP BY ENCOUNTER_ID
) emr ON t1.ENCOUNTER_ID = emr.ENCOUNTER_ID
WHERE a.IS_DEL = 0
and a.CLI_ORDER_TYPE_CODE = '384031'
AND a.CLI_ORDER_STATUS in ('98435','399014726','399303726')
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
TARGET_NO = 'HXZD-016-001'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
SELECT
event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME AS "科室名称",
COUNT(CASE WHEN TRANSFUSION_RECORD_STANDARD = '98175' THEN 1 ELSE NULL END) AS '分子输血治疗后规范书写评估输血记录例次数',
COUNT(1) AS '分母同期临床输血治疗病例总例次数',
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN TRANSFUSION_RECORD_STANDARD = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS '监测情况'
FROM
MRAS_BUSINESS_BLOOD_AUDIT event #{NOLOCK}
WHERE
--布局组件设置提升效率
AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子输血治疗后规范书写评估输血记录例次数) AS "分子输血治疗后规范书写评估输血记录例次数",
SUM(分母同期临床输血治疗病例总例次数) AS "分母同期临床输血治疗病例总例次数",
CASE
WHEN SUM(分母同期临床输血治疗病例总例次数) = 0 THEN 0
ELSE SUM(分子输血治疗后规范书写评估输血记录例次数) * 1.0 / SUM(分母同期临床输血治疗病例总例次数)
END AS "监测情况",
(SELECT target_value FROM TargetValue) AS "目标值"
    FROM DeptOrderStats
)
-- 进行数据输出
SELECT
    t.*,
CASE WHEN t.监测情况 >= t.目标值 THEN '是' ELSE '否' END AS "是否达标",
    STUFF((
SELECT ', ' + 科室名称
FROM DeptOrderStats
WHERE 分母同期临床输血治疗病例总例次数 > 0
AND 监测情况 >= (SELECT target_value FROM TargetValue)
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
MRAS_TARGET_DEFINITION (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-016-001'
),
-- 按照科室来进行处理
DeptStats AS (
SELECT
event.CURRENT_DEPT_ID AS "当前科室编码",
event.CURRENT_DEPT_NAME AS "当前科室名称",
COUNT(CASE WHEN TRANSFUSION_RECORD_STANDARD = '98175' THEN 1 ELSE NULL END) AS "分子输血治疗后规范书写评估输血记录例次数",
COUNT(1) AS "分母同期临床输血治疗病例总例次数",
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN TRANSFUSION_RECORD_STANDARD = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS "监测情况" ,
max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
FROM
MRAS_BUSINESS_BLOOD_AUDIT event  (NOLOCK)
WHERE
--布局组件设置提升效率
AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
GROUP BY  event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子输血治疗后规范书写评估输血记录例次数,
d.分母同期临床输血治疗病例总例次数,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期临床输血治疗病例总例次数 = 0 THEN '无数据'
WHEN d.监测情况 >= (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期临床输血治疗病例总例次数 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子输血治疗后规范书写评估输血记录例次数 as "numerator",
d.分母同期临床输血治疗病例总例次数 as "denominator",
d.HOSPITAL_AREA_ID as "hospitalAreaId"
    FROM
DeptStats d
WHERE 1 = 1
-- 达标
-- 未达标
)
-- 最终查询结果
SELECT * FROM TempResults ;
```

## 目标表－患者明细 SQL

```sql
SELECT
event.ENCOUNTER_ID ,
event.CURRENT_DEPT_ID AS 当前科室编码,
event.CURRENT_DEPT_NAME AS 当前科室,
event.IMRN AS 住院号,
event.PERSON_NAME AS 患者姓名,
event.CURRENT_ADMITTER_NAME AS 责任医师,
team.ORG_NAME as "TEAM_NAME",
team.ORG_ID as "TEAM_ID",
team.ORG_NO as "TEAM_NO",
team.ORG_NAME as "当前医疗组",
event.ADMITTED_TO_WARD_AT AS 入区时间,
event.WARD_DISCHARGED_AT AS 出区时间,
event.PRESCRIBED_AT as 输血医嘱时间,
event.RECORD_END_AT as 输血记录完成时间,
event.TRANSFUSION_RECORD_STANDARD as "standFlag"
FROM
MRAS_BUSINESS_BLOOD_AUDIT event  #{NOLOCK}
LEFT JOIN INPAT_TRANSFER t1  #{NOLOCK}  ON event.ENCOUNTER_ID = t1.ENCOUNTER_ID
  LEFT JOIN ORGANIZATION o1  #{NOLOCK}   ON t1.ORIGIN_DEPT_ID = o1.ORG_ID
  LEFT JOIN ORGANIZATION o2  #{NOLOCK}  ON t1.DESTINATION_DEPT_ID = o2.ORG_ID
LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
--布局组件设置提升效率
    AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
```
