---
page_type: sql_original
rule_id: HXZD-012-003
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-27
---

# 原始 SQL 存档：四级手术术前多学科讨论完成率

> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。

## 源表 / 事件抽取 SQL

```sql
SELECT a.*,
t6.INP_EMR_SET_CREATED_AT AS preopMdtCompletedAt,
CASE WHEN t6.INP_EMR_SET_ID IS NOT NULL THEN 98175 ELSE 98176 END AS preopMdtCompleted,
145235 AS encounterTypeCode,
0 AS mrasBusinessIndexId,
'V2.0' AS version
FROM (
SELECT
t1.CLI_ORDER_ITEM_ID AS bizId,
t1.SURGERY_END_AT AS eventAt,
GETDATE() AS extractAt,
'1' AS mrasTargetDefinitionId,
t1.ENCOUNTER_ID AS encounterId,
t2.PERSON_NAME AS personName,
t2.BIZ_ATTR_ONE_NO AS imrn,
t2.DEPT_ID AS currentDeptId,
t2.DEPT_NAME AS currentDeptName,
t2.WARD_ID AS currentWardId,
t2.WARD_NAME AS currentWardName,
t2.EVENT_AT AS admittedToWardAt,
t2.EVENT_END_AT AS dischargedFromWardAt,
t2.EMPLOYEE_NAME AS currentAdmitterName,
t11.SURGERY_ID AS surgeryId,
t11.SURGERY_NAME AS surgName,
t11.SURGERY_GRADE_CODE AS surgLevelCode,
s4.VALUE_DESC AS surgLevelName,
CASE WHEN t1.SURGERY_START_AT IS NULL THEN t1.PLANNED_SURGERY_DATE ELSE t1.SURGERY_START_AT END AS surgeryStartAt,
t1.SURGERY_END_AT AS surgeryEndAt,
case when b.DISCHARGE_TYPE_CODE = 136924 then 98175 else 98176 end as deathFlag,
case when b.DISCHARGE_TYPE_CODE = 136924 then b.DISCHARGED_AT else null end as deathAt,
t2.HOSPITAL_SOID AS hospitalSOID,
t1.IS_DEL AS isDel,
CASE WHEN t11.SURGERY_GRADE_CODE = 136619 THEN
(SELECT TOP 1 a1.INP_EMR_SET_ID FROM INPATIENT_EMR_SET a1
    WHERE a1.ENCOUNTER_ID = t1.ENCOUNTER_ID AND DATEDIFF(HOUR, a1.CREATED_AT, ISNULL(t1.SURGERY_START_AT, t1.PLANNED_SURGERY_DATE)) <= 48
    AND a1.INP_MRT_MONITOR_ID=125044678493294640 AND a1.INP_EMR_SET_TITLE LIKE '%多学科%' AND a1.IS_DEL = 0 AND a1.INP_EMR_STATUS NOT IN (960074,390030405)
    ORDER BY a1.INP_EMR_SET_CREATED_AT DESC)
ELSE NULL END AS INP_EMR_SET_ID
FROM INP_SURGICAL_PLAN t1
INNER JOIN MRAS_PATIENT_EVENT t2 ON t1.ENCOUNTER_ID = t2.ENCOUNTER_ID AND t2.EVENT_NO = 'PatientRecord' AND t2.IS_DEL = 0
INNER JOIN (
    SELECT t.CLI_ORDER_ITEM_ID,
MAX(t.SURGERY_ID) AS SURGERY_ID,
MAX(t.SURGERY_GRADE_CODE) AS SURGERY_GRADE_CODE,
LEFT(STUFF((
SELECT ',' + t2.SURGERY_NAME
FROM (
SELECT DISTINCT isa.CLI_ORDER_ITEM_ID, isa.SURGERY_ID, cs.SURGERY_GRADE_CODE, isa.SURGERY_NAME, isa.SURGICAL_ANESTHESIA_CODE
FROM INP_SURGICAL_ANESTHESIA_PLAN isa
INNER JOIN CLIBASIC_SURGERY cs ON isa.CS_ID = cs.CLIBASIC_SURGERY_ID AND cs.SURGERY_DIR_NO IN ('957348', '957347')
WHERE isa.IS_DEL = 0 AND isa.SURGERY_NAME IS NOT NULL
) t2
WHERE t2.CLI_ORDER_ITEM_ID = t.CLI_ORDER_ITEM_ID
ORDER BY t2.SURGERY_NAME
FOR XML PATH('')
), 1, 1, ''), 1999) AS SURGERY_NAME,
MAX(t.SURGICAL_ANESTHESIA_CODE) AS ANES_TYPE_ID
    FROM (
SELECT DISTINCT isa.CLI_ORDER_ITEM_ID, isa.SURGERY_ID, cs.SURGERY_GRADE_CODE, isa.SURGERY_NAME AS SURGERY_NAME, isa.SURGICAL_ANESTHESIA_CODE
FROM INP_SURGICAL_ANESTHESIA_PLAN isa
INNER JOIN CLIBASIC_SURGERY cs ON isa.CS_ID = cs.CLIBASIC_SURGERY_ID AND cs.SURGERY_DIR_NO IN ('957348', '957347')
WHERE isa.IS_DEL = 0 AND isa.SURGERY_NAME IS NOT NULL
    ) t
    GROUP BY t.CLI_ORDER_ITEM_ID
) t11 ON t1.CLI_ORDER_ITEM_ID = t11.CLI_ORDER_ITEM_ID
LEFT join MAHP_DMTS_MAIN b on t1.ENCOUNTER_ID = b.ENCOUNTER_ID and b.IS_DEL = 0
LEFT JOIN VALUE_SET s4 ON t11.SURGERY_GRADE_CODE = s4.VALUE_ID AND s4.CODE_SYSTEM_ID = 64655
WHERE 1 = 1 AND t11.SURGERY_GRADE_CODE IN (136618, 136619)
AND t2.EVENT_END_AT BETWEEN :startTime and :endTime
) a
LEFT JOIN INPATIENT_EMR_SET t6 on a.INP_EMR_SET_ID = t6.INP_EMR_SET_ID
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
TARGET_NO = 'HXZD-012-003'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
SELECT
event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME AS 科室名称,
COUNT(CASE WHEN PREOP_MDT_COMPLETED = 98175 THEN 1 ELSE NULL END) AS 分子术前完成多学科讨论的四级手术例数,
COUNT(1) AS 分母同期四级手术总例数,
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN PREOP_MDT_COMPLETED = 98175 THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS 监测情况
FROM
MRAS_BUSINESS_SUR_GRADE event #{NOLOCK}
WHERE
--布局组件设置提升效率
AND event.IS_DEL = 0
  AND event.VERSION = 'V2.0'
AND event.EVENT_AT BETWEEN :marptBeginAt and :marptEndAt
and event.SURG_LEVEL_CODE = 136619
GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子术前完成多学科讨论的四级手术例数) AS 分子术前完成多学科讨论的四级手术例数,
SUM(分母同期四级手术总例数) AS 分母同期四级手术总例数,
CASE
WHEN SUM(分母同期四级手术总例数) = 0 THEN 0
ELSE SUM(分子术前完成多学科讨论的四级手术例数) * 1.0 / SUM(分母同期四级手术总例数)
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
WHERE 分母同期四级手术总例数 > 0
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
TARGET_NO = 'HXZD-012-003'
),
-- 按照科室来进行处理
DeptStats AS (
SELECT
event.CURRENT_DEPT_ID AS 当前科室编码,
event.CURRENT_DEPT_NAME AS 当前科室名称,
COUNT(CASE WHEN PREOP_MDT_COMPLETED = 98175 THEN 1 ELSE NULL END) AS 分子术前完成多学科讨论的四级手术例数,
COUNT(1) AS 分母同期四级手术总例数,
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN PREOP_MDT_COMPLETED = 98175 THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS 监测情况,
  max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
FROM
MRAS_BUSINESS_SUR_GRADE event  (NOLOCK)
WHERE
--布局组件设置提升效率
AND event.IS_DEL = 0
  AND event.VERSION = 'V2.0'
AND  event.EVENT_AT BETWEEN :marptBeginAt and :marptEndAt
and event.SURG_LEVEL_CODE = 136619
GROUP BY  event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子术前完成多学科讨论的四级手术例数,
d.分母同期四级手术总例数,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期四级手术总例数 = 0 THEN '无数据'
WHEN d.监测情况 >= (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
  CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期四级手术总例数 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子术前完成多学科讨论的四级手术例数 as "numerator",
d.分母同期四级手术总例数 as "denominator",
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
event.PERSON_NAME AS 患者姓名,
event.IMRN AS 住院号,
event.CURRENT_ADMITTER_NAME AS 责任医师,
team.ORG_NAME as "TEAM_NAME",
team.ORG_ID as "TEAM_ID",
team.ORG_NO as "TEAM_NO",
team.ORG_NAME as "当前医疗组",
event.ADMITTED_TO_WARD_AT AS 入区时间,
event.DISCHARGED_FROM_WARD_AT AS 出区时间,
event.SURG_NAME AS 手术名称,
event.SURG_LEVEL_NAME AS 手术等级,
event.SURGERY_START_AT AS 手术日期,
event.PREOP_MDT_COMPLETED_AT AS 术前多学科会诊记录完成时间,
CASE WHEN event.PREOP_MDT_COMPLETED = 98175 THEN '是' ELSE '否' END AS 是否达标,
CASE WHEN event.PREOP_MDT_COMPLETED = 98175 THEN 98175 ELSE 98176 END AS "standFlag"
FROM
MRAS_BUSINESS_SUR_GRADE event #{NOLOCK}
LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
--布局组件设置提升效率
AND event.IS_DEL = 0
AND event.VERSION = 'V2.0'
    AND event.EVENT_AT BETWEEN :marptBeginAt and :marptEndAt
AND event.SURG_LEVEL_CODE = 136619
```
