---
page_type: sql_original
rule_id: HXZD-003-003
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-27
---

# 原始 SQL 存档：普通会诊及时完成率

> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。

## 源表 / 事件抽取 SQL

```sql
SELECT t1.ENCOUNTER_ID
AS bizId,
--用来更新数据
t1.FIRST_ADMITTED_TO_WARD_AT
AS eventAt,
-- 预留
'1' AS mrasTargetDefinitionId,
'V2.0' as version,
GETDATE()
AS extractAt,
t1.ENCOUNTER_ID
AS encounterId,
t1.ENCOUNTER_TYPE_CODE   as encounterTypeCode,
t1.FULL_NAME
AS personName,
t1.IMRN
as imrn,
t1.CURRENT_DEPT_ID
AS currentDeptId,
o1.ORG_NAME
AS currentDeptName,
t1.CURRENT_WARD_ID
AS currentWardId,
o2.ORG_NAME
AS currentWardName,
t1.FIRST_ADMITTED_TO_WARD_AT
AS admittedToWardAt,
t1.DISCHARGED_FROM_WARD_AT
AS wardDischargedAt,
t3.EMPLOYEE_ID
AS currentAdmitterId,
t4.EMPLOYEE_NAME
AS currentAdmitterName,
t1.HOSPITAL_SOID
AS hospitalSoid,
t1.SOURCE_HOSPITAL_AREA_ID
AS hospitalAreaId,
t2.PRESCRIBED_AT
AS firstOrderAt,--会诊后医嘱开立时间
A.APPLY_CONSULT_EMPLOYEE_NAME
as applyConsultEmployeeName,--发起医师
A.APPLY_CONSULT_AT
AS applyConsultAt,--会诊发起时间
C.SIGNED_AT
AS signedAt,--会诊签到时间
reply_time.CONSULT_REPLAY_AT
AS finishAt,--会诊完成时间
reply_time.CONSULT_REPLAY_AT
AS firstEmrSetSubmitAt,--会诊病历完成时间
A.CONSULT_LEVEL_CODE
AS courseLevelCode,--会诊等级
t2.PRESCRIBING_DOCTOR_NAME
AS prescribingDoctorName,--医嘱开立医生
t2.CS_NAME
AS orderName,--医嘱名称
A.CONSULT_STATUS_CODE
AS courseStatus,--会诊状态
CASE WHEN t1.IS_DEL = '1' THEN 1 ELSE 0 END AS isDel,
CASE
WHEN DATEDIFF(minute, A.APPLY_CONSULT_AT, C.SIGNED_AT) < 10 and A.CONSULT_LEVEL_CODE = 977578 THEN 98175
ELSE 98176 END
AS emergencyConsultTimely,--急会诊及时到位率
CASE
WHEN DATEDIFF(minute, D.CONSULT_COMPLETED_AT, t2.PRESCRIBED_AT) < 40 and A.CONSULT_LEVEL_CODE = 977578 THEN 98175
ELSE 98176 END
AS emergencyConsultEffective,--急会诊有效率
CASE
WHEN DATEDIFF(hour, A.APPLY_CONSULT_AT, t5.INP_EMR_SET_CREATED_AT) < 24 and A.CONSULT_LEVEL_CODE = 977577 THEN 98175
ELSE 98176 END
AS regularConsultTimely,--普通会诊及时完成率
CASE
WHEN DATEDIFF(hour, D.CONSULT_COMPLETED_AT, t2.PRESCRIBED_AT) < 24 and A.CONSULT_LEVEL_CODE = 977577 THEN 98175
ELSE 98176 END
AS regularConsultEffective,--普通会诊及时完成率
''
AS memo
FROM INPATIENT_ENCOUNTER t1
inner join INPATIENT_CONSULT_APPLY A on t1.ENCOUNTER_ID = A.ENCOUNTER_ID AND A.IS_DEL = 0
left JOIN (select A.INP_CONSULT_APPLY_ID,B.PRESCRIBED_AT,B.PRESCRIBING_DOCTOR_NAME,I.*,
ROW_NUMBER() OVER (PARTITION BY A.ENCOUNTER_ID ORDER BY B.PRESCRIBING_DOCTOR_NAME ASC) AS rn
from INPATIENT_CONSULT_APPLY A
left join INP_CLI_ORDER B on A.ENCOUNTER_ID = B.ENCOUNTER_ID
inner join INP_CLI_ORDER_ITEM I on I.CLI_ORDER_ID = B.CLI_ORDER_ID
where B.PRESCRIBED_AT > A.CONSULT_COMPLETED_AT) t2
on A.INP_CONSULT_APPLY_ID = t2.INP_CONSULT_APPLY_ID and t2.rn = 1
left join (select INP_CONSULT_APPLY_ID, min(SIGNED_AT) as SIGNED_AT
from INP_CONSULT_INVITATION
where IS_DEL = 0
group by INP_CONSULT_APPLY_ID) C on A.INP_CONSULT_APPLY_ID = C.INP_CONSULT_APPLY_ID
left join (select INP_CONSULT_APPLY_ID, max(CREATED_AT) as CONSULT_COMPLETED_AT
from INPATIENT_CONSULT_REPLY
where IS_DEL = 0
group by INP_CONSULT_APPLY_ID) D on A.INP_CONSULT_APPLY_ID = D.INP_CONSULT_APPLY_ID
left join INPATIENT_EMR_SET t5 on A.INP_EMR_SET_ID=t5.INP_EMR_SET_ID and t5.IS_DEL=0
LEFT JOIN ORGANIZATION o1 ON t1.CURRENT_DEPT_ID = o1.ORG_ID
LEFT JOIN ORGANIZATION o2 ON t1.CURRENT_WARD_ID = o2.ORG_ID
LEFT JOIN INPATIENT_PARTICIPANT t3
ON t1.ENCOUNTER_ID = t3.ENCOUNTER_ID AND t3.IS_DEL = 0 AND t3.INPAT_PARTICIPANT_TYPE_CODE = 1000098
LEFT JOIN EMPLOYEE_INFO t4 ON t3.EMPLOYEE_ID = t4.EMPLOYEE_ID
LEFT JOIN (
   SELECT r.INP_CONSULT_APPLY_ID,
   MAX ( r.CONSULT_REPLAY_AT ) AS CONSULT_REPLAY_AT
   FROM INPATIENT_CONSULT_REPLY r WHERE r.IS_DEL = 0 GROUP BY r.INP_CONSULT_APPLY_ID
) reply_time ON A.INP_CONSULT_APPLY_ID = reply_time.INP_CONSULT_APPLY_ID
WHERE 1 = 1
 AND t1.ENCOUNTER_ID in (select ENCOUNTER_ID from INPATIENT_ENCOUNTER where DISCHARGED_FROM_WARD_AT BETWEEN :startTime and :endTime)
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
TARGET_NO = 'HXZD-003-003'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
  SELECT
   event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME AS "科室名称",
   COUNT(CASE WHEN REGULAR_CONSULT_TIMELY = '98175' THEN 1 ELSE NULL END) AS '分子普通会诊24小时内完成次数',
   COUNT(1) AS '分母同期普通会诊总次数',
   CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN REGULAR_CONSULT_TIMELY = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS '监测情况'
  FROM
   MRAS_BUSINESS_CONSULTATION event #{NOLOCK}
  WHERE
   --布局组件设置提升效率
AND event.COURSE_LEVEL_CODE=977577
   AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
   GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子普通会诊24小时内完成次数) AS "分子普通会诊24小时内完成次数",
SUM(分母同期普通会诊总次数) AS "分母同期普通会诊总次数",
CASE
WHEN SUM(分母同期普通会诊总次数) = 0 THEN null
ELSE SUM(分子普通会诊24小时内完成次数) * 1.0 / SUM(分母同期普通会诊总次数)
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
WHERE 分母同期普通会诊总次数 > 0
AND 监测情况 < (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS "未达标科室列表"
FROM TotalStats t;
```

## 目标表－科室统计 SQL

```sql
--查询出目标值，各个指标编码是固定的
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION #{NOLOCK}
    WHERE
TARGET_NO = 'HXZD-003-001'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptStats AS (
  SELECT
   event.CURRENT_DEPT_ID AS "当前科室编码",
event.CURRENT_DEPT_NAME AS "当前科室名称",
   COUNT(CASE WHEN REGULAR_CONSULT_TIMELY = '98175' THEN 1 ELSE NULL END) AS "分子普通会诊24小时内完成次数",
   COUNT(1) AS "分母同期普通会诊总次数",
   CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN REGULAR_CONSULT_TIMELY = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS "监测情况",
   max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
  FROM
   MRAS_BUSINESS_CONSULTATION event #{NOLOCK}
  WHERE
   --布局组件设置提升效率
AND event.COURSE_LEVEL_CODE=977577
   AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
   GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子普通会诊24小时内完成次数,
d.分母同期普通会诊总次数,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
   WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期普通会诊总次数 = 0 THEN '无数据'
WHEN d.监测情况 >= (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
  CASE
   WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期普通会诊总次数 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子普通会诊24小时内完成次数 as "numerator",
d.分母同期普通会诊总次数 as "denominator",
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
  v.VALUE_DESC AS '患者类型',
 event.CURRENT_DEPT_ID AS '当前科室编码',
 event.CURRENT_DEPT_NAME AS '当前科室',
 event.IMRN AS '住院号',
   event.PERSON_NAME AS '患者姓名',
 event.CURRENT_ADMITTER_NAME AS '责任医师',
 team.ORG_NAME as "TEAM_NAME",
 team.ORG_ID as "TEAM_ID",
 team.ORG_NO as "TEAM_NO",
 team.ORG_NAME as "当前医疗组",
 event.ADMITTED_TO_WARD_AT AS '入区时间',
 event.WARD_DISCHARGED_AT AS '出区时间',
 event.APPLY_CONSULT_EMPLOYEE_NAME as '会诊发起医师',
  event.APPLY_CONSULT_AT as '会诊发起时间',
    event.FIRST_EMR_SET_SUBMIT_AT as '会诊病历完成时间',
 cast(DATEDIFF(minute,event.APPLY_CONSULT_AT,event.FIRST_EMR_SET_SUBMIT_AT)* 1.0 / 60 as decimal(19, 2))  AS '会诊病历完成时间-会诊发起时间(小时)',
 CASE WHEN REGULAR_CONSULT_TIMELY = '98175' THEN '是' ELSE '否' END AS '是否达标',
 CASE WHEN REGULAR_CONSULT_TIMELY = '98175' THEN 98175 ELSE 98176 END AS "standFlag"
FROM
 MRAS_BUSINESS_CONSULTATION event  #{NOLOCK}
 left join VALUE_SET v on event.ENCOUNTER_TYPE_CODE=VALUE_ID
   LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
 LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
 --布局组件设置提升效率
 AND event.COURSE_LEVEL_CODE=977577
    AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
```
