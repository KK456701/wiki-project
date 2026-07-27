---
page_type: sql_original
rule_id: HXZD-006-002
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-27
---

# 原始 SQL 存档：非计划再次住院/手术患者疑难病例讨论记录完整率

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
t5.PLANNED_SURGERY_AT
AS unPlannedSurgeryAt,--非计划手术时间
t5.SURGERY_LEVEL_CODE
AS surgeryLevelCode,--手术级别
t6.CREATED_AT
AS emrCreatedAt,--疑难病历讨论时间
t6.INP_EMR_SET_CREATED_AT
AS emrSetSubmitAt,--疑难病历讨论提交时间
case when t2.ENCOUNTER_ID is not null and C.READM_PLAN_WITHIN_MONTH_NO=1 then 98175 else 98176 end AS unPlanedAdmitted,--非计划再次入院
CASE WHEN t1.IS_DEL = '1' THEN 1 ELSE 0 END
AS isDel,
CASE
WHEN t6.INP_EMR_SET_ID IS NOT NULL THEN 98175
ELSE 98176 END
AS unplannedDifficultDiscussion,--非计划手术患者疑难病历讨论
''
AS memo
FROM INPATIENT_ENCOUNTER t1
left join INPATIENT_ENCOUNTER t2
on t1.PERSON_ID = t2.PERSON_ID and datediff(day, t1.ADMITTED_TO_WARD_AT, t2.ADMITTED_TO_WARD_AT) < 31
and t1.ENCOUNTER_ID <> t2.ENCOUNTER_ID
left join (SELECT A.*,
B.PLANNED_SURGERY_AT,
B.SURGERY_LEVEL_CODE,
C.CS_NAME,
ROW_NUMBER() OVER (PARTITION BY A.ENCOUNTER_ID ORDER BY A.PRESCRIBED_AT DESC ) AS rn
FROM INP_CLI_ORDER A
INNER JOIN INP_SURGICAL_PLAN B ON
B.CLI_ORDER_ID = A.CLI_ORDER_ID
inner join INP_CLI_ORDER_ITEM C on C.CLI_ORDER_ID = A.CLI_ORDER_ID
left join CLINICAL_SERVICE_SURGERY D on C.CS_ID = D.CS_ID
AND B.UNPLANNED_REOPERATION_FLAG = 98175
AND B.PLANNED_SURGERY_DATE IS NOT NULL
WHERE A.IS_DEL = 0
and B.IS_DEL = 0
and D.SURGERY_TYPE_CODE in (957347, 957348)
AND A.CLI_ORDER_TYPE_CODE = 256200) t5 ON t1.ENCOUNTER_ID = t5.ENCOUNTER_ID and t5.rn = 1
left join (select A.*, ROW_NUMBER() OVER (PARTITION BY A.ENCOUNTER_ID ORDER BY A.CREATED_AT DESC ) AS rn
from INPATIENT_EMR_SET A
where A.IS_DEL = 0
and A.INP_MRT_MONITOR_ID = 125044678493294634) t6
on t1.ENCOUNTER_ID = t6.ENCOUNTER_ID and t6.rn = 1
left JOIN MAHP_MAIN B ON t1.ENCOUNTER_ID = B.ENCOUNTER_ID AND B.MAHP_STATUS = 958566 AND B.IS_DEL = 0
INNER JOIN MAHP_QC C ON C.MAHP_ID = B.MAHP_ID AND C.IS_DEL = 0
LEFT JOIN ORGANIZATION o1 ON t1.CURRENT_DEPT_ID = o1.ORG_ID
LEFT JOIN ORGANIZATION o2 ON t1.CURRENT_WARD_ID = o2.ORG_ID
LEFT JOIN INPATIENT_PARTICIPANT t3
ON t1.ENCOUNTER_ID = t3.ENCOUNTER_ID AND t3.IS_DEL = 0 AND t3.INPAT_PARTICIPANT_TYPE_CODE = 1000098
LEFT JOIN EMPLOYEE_INFO t4 ON t3.EMPLOYEE_ID = t4.EMPLOYEE_ID
left join VALUE_SET v on t5.SURGERY_LEVEL_CODE = v.VALUE_ID
WHERE t1.IS_DEL = 0
 and ((t2.ENCOUNTER_ID is not null and C.READM_PLAN_WITHIN_MONTH_NO=1) or t5.ENCOUNTER_ID is not null)
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
TARGET_NO = 'HXZD-006-002'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
  SELECT
   event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME AS "科室名称",
   COUNT(CASE WHEN event.EMR_SET_SUBMIT_AT is not null THEN 1 ELSE NULL END) AS '分子对非计划再次住院手术患者进行疑难病例讨论并将讨论结论记入病历的数量',
   COUNT(1) AS '分母同期对非计划再次住院手术患者进行疑难病例讨论的数量',
   CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN event.EMR_SET_SUBMIT_AT is not null THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS '监测情况'
  FROM
   MRAS_BUSINESS_DIFFI_EMR event #{NOLOCK}
  WHERE
   --布局组件设置提升效率
AND event.UNPLANNED_DIFFICULT_DISCUSSION=98175
   AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
   GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子对非计划再次住院手术患者进行疑难病例讨论并将讨论结论记入病历的数量) AS "分子对非计划再次住院手术患者进行疑难病例讨论并将讨论结论记入病历的数量",
SUM(分母同期对非计划再次住院手术患者进行疑难病例讨论的数量) AS "分母同期对非计划再次住院手术患者进行疑难病例讨论的数量",
CASE
WHEN SUM(分母同期对非计划再次住院手术患者进行疑难病例讨论的数量) = 0 THEN null
ELSE SUM(分子对非计划再次住院手术患者进行疑难病例讨论并将讨论结论记入病历的数量) * 1.0 / SUM(分母同期对非计划再次住院手术患者进行疑难病例讨论的数量)
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
WHERE 分母同期对非计划再次住院手术患者进行疑难病例讨论的数量 > 0
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
MRAS_TARGET_DEFINITION (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-006-002'
),
-- 按照科室来进行处理
DeptStats AS (
  SELECT
   event.CURRENT_DEPT_ID AS "当前科室编码",
   event.CURRENT_DEPT_NAME AS "当前科室名称",
   COUNT(CASE WHEN event.EMR_SET_SUBMIT_AT is not null THEN 1 ELSE NULL END) AS "分子对非计划再次住院手术患者进行疑难病例讨论并将讨论结论记入病历的数量",
   COUNT(1) AS "分母同期对非计划再次住院手术患者进行疑难病例讨论的数量",
   CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN event.EMR_SET_SUBMIT_AT is not null THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS "监测情况" ,
   max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
  FROM
   MRAS_BUSINESS_DIFFI_EMR event  (NOLOCK)
  WHERE
   --布局组件设置提升效率
AND event.UNPLANNED_DIFFICULT_DISCUSSION=98175
   AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
  GROUP BY  event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子对非计划再次住院手术患者进行疑难病例讨论并将讨论结论记入病历的数量,
d.分母同期对非计划再次住院手术患者进行疑难病例讨论的数量,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
   WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期对非计划再次住院手术患者进行疑难病例讨论的数量 = 0 THEN '无数据'
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
  CASE
   WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期对非计划再次住院手术患者进行疑难病例讨论的数量 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子对非计划再次住院手术患者进行疑难病例讨论并将讨论结论记入病历的数量 as "numerator",
d.分母同期对非计划再次住院手术患者进行疑难病例讨论的数量 as "denominator",
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
 event.IMRN AS '住院号',
   event.PERSON_NAME AS '患者姓名',
 event.CURRENT_ADMITTER_NAME AS '责任医师',
 team.ORG_NAME as "TEAM_NAME",
 team.ORG_ID as "TEAM_ID",
 team.ORG_NO as "TEAM_NO",
 team.ORG_NAME as "当前医疗组",
 event.ADMITTED_TO_WARD_AT AS '入区时间',
 event.WARD_DISCHARGED_AT AS '出区时间',
 event.UN_PLANED_ADMITTED as '是否非计划再入院',
 event.UN_PLANNED_SURGERY_AT as '非计划再次手术时间',
 event.EMR_CREATED_AT as '疑难病历讨论时间',
 event.EMR_SET_SUBMIT_AT as '病历提交时间',
 CASE WHEN UNPLANNED_DIFFICULT_DISCUSSION = '98175' THEN '是' ELSE '否' END AS '是否达标',
 CASE WHEN UNPLANNED_DIFFICULT_DISCUSSION = '98175' THEN 98175 ELSE 98176 END AS "standFlag"
FROM
 MRAS_BUSINESS_DIFFI_EMR event  #{NOLOCK}
   LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
 LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
 --布局组件设置提升效率
 AND event.UNPLANNED_DIFFICULT_DISCUSSION=98175
    AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
```
