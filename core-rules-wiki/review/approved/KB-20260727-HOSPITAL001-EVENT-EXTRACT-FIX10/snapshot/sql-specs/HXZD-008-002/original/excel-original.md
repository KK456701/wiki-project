---
page_type: sql_original
rule_id: HXZD-008-002
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-27
---

# 原始 SQL 存档：术者参加术前讨论率

> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。

## 源表 / 事件抽取 SQL

```sql
select a.*,
case when b.BIZ_ID is not null then 98175 else 98176 end as preopDiscComplete,
b.EVENT_AT eventAt,
b.EVENT_AT as preOpDiscAt,
c.EVENT_AT as operationAt,
case when b.BIZ_ID is not null and (
select count(*) from MRAS_PATIENT_EVENT t11 where t11.ENCOUNTER_ID = a.encounterId and t11.EVENT_NO = 'EmrOperationRecord' and a.bizId = t11.BIZ_ATTR_TRI_ID and t11.BIZ_ATTR_TWO_ID is not null and b.BIZ_ATTR_TWO_NAME is not null and t11.IS_DEL = 0 and CHARINDEX(CONVERT(VARCHAR, t11.BIZ_ATTR_TWO_ID) + '#', b.BIZ_ATTR_TWO_NAME) > 0
) > 0 then 98175 else 98176 end as preopDiscAttended,
CASE WHEN b.BIZ_ID IS NOT NULL AND (
SELECT COUNT(*) FROM MRAS_PATIENT_EVENT t12 WHERE t12.ENCOUNTER_ID = a.encounterId AND t12.EVENT_NO = 'EmrOperationRecord' AND a.bizId = t12.BIZ_ATTR_TRI_ID AND t12.BIZ_ATTR_ONE_NAME IS NOT NULL AND b.BIZ_ATTR_ONE_NAME IS NOT NULL AND CHARINDEX(t12.BIZ_ATTR_ONE_NAME + ',' , b.BIZ_ATTR_ONE_NAME + ',') > 0 AND t12.IS_DEL = 0
) > 0 THEN 98175 ELSE 98176 END AS preopPlanConsistent,
case when a.planSurgerySurgeonId is not null and (
select count(*) from MRAS_PATIENT_EVENT t13 where t13.ENCOUNTER_ID = a.encounterId and t13.EVENT_NO = 'EmrOperationRecord' and a.bizId = t13.BIZ_ATTR_TRI_ID and t13.IS_DEL = 0 and a.planSurgerySurgeonId = t13.BIZ_ATTR_TWO_ID) > 0 then 98175 else 98176 end as surgeonConsistent,
REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(b.BIZ_ATTR_ONE_NAME, '0', ''), '1', ''), '2', ''), '3', ''), '4', ''),
    '5', ''), '6', ''), '7', ''), '8', ''), '9', ''), '#', '') as preOpName,
c.BIZ_ATTR_ONE_NAME AS emrOpName,
d.EMPLOYEE_NAME as surgerySurgeon,
'V2.0' as version
 from (
select
t1.BIZ_ID AS bizId,
GETDATE() AS extractAt,
'1' AS mrasTargetDefinitionId,
t1.ENCOUNTER_ID AS encounterId,
t2.PERSON_NAME AS personName,
t2.BIZ_ATTR_ONE_NO as imrn,
t2.DEPT_ID AS currentDeptId,
t2.DEPT_NAME AS currentDeptName,
t2.WARD_ID AS currentWardId,
t2.WARD_NAME AS currentWardName,
t2.EVENT_AT AS admittedToWardAt,
t2.EVENT_END_AT AS dischargedFromWardAt,
t2.EMPLOYEE_NAME AS currentAdmitterName,
t1.BIZ_ATTR_ONE_NAME as opName,
t1.BIZ_ATTR_TWO_NO as surgeryClassCode,
t4.VALUE_DESC as surgeryLevelName,
t1.EVENT_END_AT AS surgeryEndAt,
t1.IS_DEL as isDel,
t1.HOSPITAL_SOID as hospitalSoid,
t1.BIZ_ATTR_ONE_ID as planSurgerySurgeonId,
t3.EMPLOYEE_NAME as planSurgerySurgeon,
(SELECT a1.BIZ_ID FROM MRAS_PATIENT_EVENT a1
    where a1.ENCOUNTER_ID = t1.ENCOUNTER_ID and DATEDIFF(HOUR, t1.EVENT_AT, a1.EVENT_AT) <= 48
    and a1.EVENT_NO = 'PreoperativeDisc' and a1.IS_DEL = 0
    order by a1.EVENT_AT desc
    OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY) as PRE_BIZ_ID,
(SELECT a2.BIZ_ID FROM MRAS_PATIENT_EVENT a2
    where a2.ENCOUNTER_ID = t1.ENCOUNTER_ID and t1.BIZ_ID = a2.BIZ_ATTR_TRI_ID
    and a2.EVENT_NO = 'EmrOperationRecord' and a2.IS_DEL = 0
    order by a2.EVENT_AT desc
    OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY) as EMR_BIZ_ID
from MRAS_PATIENT_EVENT t1
inner join MRAS_PATIENT_EVENT t2 on t1.ENCOUNTER_ID = t2.ENCOUNTER_ID and t2.EVENT_NO = 'PatientRecord' and t2.IS_DEL = 0
left join EMPLOYEE_INFO t3 on t1.BIZ_ATTR_ONE_ID  = t3.EMPLOYEE_ID
LEFT JOIN VALUE_SET t4 on t1.BIZ_ATTR_TWO_NO = t4.VALUE_ID and t4.CODE_SYSTEM_ID = 64655
where t1.EVENT_NO = 'OperationRecord' and t1.IS_DEL = 0 and t1.BIZ_ATTR_ONE_COST = 98175
AND t2.EVENT_END_AT BETWEEN :startTime and :endTime
) a
left join MRAS_PATIENT_EVENT b on a.encounterId = b.ENCOUNTER_ID and b.EVENT_NO = 'PreoperativeDisc' and a.PRE_BIZ_ID = b.BIZ_ID and b.IS_DEL = 0
left join MRAS_PATIENT_EVENT c on a.encounterId = c.ENCOUNTER_ID and c.EVENT_NO = 'EmrOperationRecord' and a.EMR_BIZ_ID = c.BIZ_ID and c.IS_DEL = 0
left join EMPLOYEE_INFO d on c.BIZ_ATTR_TWO_ID  = d.EMPLOYEE_ID
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
TARGET_NO = 'HXZD-008-002'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
SELECT
event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME AS 科室名称,
COUNT(CASE WHEN PREOP_DISC_ATTENDED = 98175 THEN 1 ELSE NULL END) AS 分子术者参加术前讨论的手术例数,
COUNT(1) AS 分母同期进行术前讨论手术总例数,
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN PREOP_DISC_ATTENDED = 98175 THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS 监测情况
FROM
MRAS_BUSINESS_OP_DISC event #{NOLOCK}
WHERE
--布局组件设置提升效率
    AND event.VERSION = 'V2.0'
  AND event.EVENT_AT BETWEEN :marptBeginAt and :marptEndAt
  AND event.PREOP_DISC_COMPLETE = 98175
GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子术者参加术前讨论的手术例数) AS 分子术者参加术前讨论的手术例数,
SUM(分母同期进行术前讨论手术总例数) AS 分母同期进行术前讨论手术总例数,
CASE
WHEN SUM(分母同期进行术前讨论手术总例数) = 0 THEN 0
ELSE SUM(分子术者参加术前讨论的手术例数) * 1.0 / SUM(分母同期进行术前讨论手术总例数)
END AS 监测情况,
(SELECT target_value FROM TargetValue) AS 目标值
    FROM DeptOrderStats
)
-- 进行数据输出
SELECT
    t.*,
CASE WHEN t.监测情况 < t.目标值 THEN '否' ELSE '是' END AS 是否达标,
    STUFF((
SELECT ', ' + 科室名称
FROM DeptOrderStats
WHERE 分母同期进行术前讨论手术总例数 > 0
AND 监测情况 < (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS 未达标科室列表
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
TARGET_NO = 'HXZD-008-002'
),
-- 按照科室来进行处理
DeptStats AS (
SELECT
event.CURRENT_DEPT_ID AS 当前科室编码,
event.CURRENT_DEPT_NAME AS 当前科室名称,
COUNT(CASE WHEN PREOP_DISC_ATTENDED = 98175 THEN 1 ELSE NULL END) AS 分子术者参加术前讨论的手术例数,
COUNT(1) AS 分母同期进行术前讨论手术总例数,
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN PREOP_DISC_ATTENDED = 98175 THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS 监测情况 ,
  max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
FROM
MRAS_BUSINESS_OP_DISC event  (NOLOCK)
WHERE
--布局组件设置提升效率
  AND event.VERSION = 'V2.0'
AND  event.EVENT_AT BETWEEN :marptBeginAt and :marptEndAt
AND event.PREOP_DISC_COMPLETE = 98175
GROUP BY  event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子术者参加术前讨论的手术例数,
d.分母同期进行术前讨论手术总例数,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期进行术前讨论手术总例数 = 0 THEN '无数据'
WHEN d.监测情况 >= (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
  CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期进行术前讨论手术总例数 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子术者参加术前讨论的手术例数 as "numerator",
d.分母同期进行术前讨论手术总例数 as "denominator",
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
event.CURRENT_DEPT_ID AS '当前科室编码',
event.CURRENT_DEPT_NAME AS '当前科室',
event.PERSON_NAME AS '患者姓名',
event.IMRN AS '住院号',
event.CURRENT_ADMITTER_NAME AS '责任医师',
team.ORG_NAME as "TEAM_NAME",
team.ORG_ID as "TEAM_ID",
team.ORG_NO as "TEAM_NO",
team.ORG_NAME as "当前医疗组",
event.ADMITTED_TO_WARD_AT AS '入区时间',
event.DISCHARGED_FROM_WARD_AT AS '出区时间',
event.PRE_OP_NAME AS '手术名称',
event.PRE_OP_DISC_AT as '术前讨论记录完成时间',
event.SURGERY_END_AT as '手术记录完成时间',
CASE WHEN PREOP_DISC_ATTENDED = 98175 THEN '是' ELSE '否' END AS '主刀医师是否参与参与术前讨论',
CASE WHEN PREOP_DISC_ATTENDED = 98175 THEN '是' ELSE '否' END AS '是否达标',
CASE WHEN PREOP_DISC_ATTENDED = 98175 THEN 98175 ELSE 98176 END AS "standFlag"
FROM
MRAS_BUSINESS_OP_DISC event  #{NOLOCK}
LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
--布局组件设置提升效率
AND event.VERSION = 'V2.0'
    AND event.EVENT_AT BETWEEN :marptBeginAt and :marptEndAt
```
