---
page_type: sql_original
rule_id: HXZD-012-002
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-26
---

# 原始 SQL 存档：四级手术与三级手术患者死亡率比

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
 WITH SUR_GRADE_INFO AS (
select * from MRAS_BUSINESS_SUR_GRADE
where
--布局组件设置提升效率
AND IS_DEL = 0
AND VERSION = 'V2.0'
AND SURG_LEVEL_CODE IN (136619, 136618)
AND EVENT_AT BETWEEN :marptBeginAt and :marptEndAt
)
select tt."分子-四级手术患者死亡率", tt."分母-三级手术患者死亡率", CONCAT(tt."分子-四级手术患者死亡率", ' : ', tt."分母-三级手术患者死亡率") as 监测情况 from (
select
CASE WHEN a.四级手术例数 = 0 then '无数据' ELSE CONCAT(CONVERT(numeric(19,2), 1.0 * a.四级手术且死亡患者人数 / a.四级手术例数 * 100), '%') END as "分子-四级手术患者死亡率",
CASE WHEN b.三级手术例数 = 0 then '无数据' ELSE CONCAT(CONVERT(numeric(19,2), 1.0 * b.三级手术且死亡患者人数 / b.三级手术例数 * 100), '%') END as "分母-三级手术患者死亡率"
 from (
SELECT
COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136619 and event.DEATH_FLAG = 98175 THEN event.ENCOUNTER_ID ELSE NULL END) as 四级手术且死亡患者人数,
  COUNT(CASE WHEN event.SURG_LEVEL_CODE = 136619 THEN event.MRAS_BUSINESS_SUR_GRADE_ID ELSE NULL END) AS 四级手术例数
FROM
SUR_GRADE_INFO event
) a
left join (
  SELECT
  COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136618 and event.DEATH_FLAG = 98175 THEN event.ENCOUNTER_ID ELSE NULL END) as 三级手术且死亡患者人数,
  COUNT(CASE WHEN event.SURG_LEVEL_CODE = 136618 THEN event.MRAS_BUSINESS_SUR_GRADE_ID ELSE NULL END) AS 三级手术例数
FROM
SUR_GRADE_INFO event
  WHERE
  not EXISTS(
  select 1 from MRAS_BUSINESS_SUR_GRADE e where event.ENCOUNTER_ID = e.ENCOUNTER_ID and e.SURG_LEVEL_CODE = 136619 and e.DEATH_FLAG = 98175
  )
) b on 1=1
) tt
```

## 目标表－科室统计 SQL

```sql
WITH SUR_GRADE_INFO AS (
select t.* from (
select *,
ROW_NUMBER() OVER (PARTITION BY ENCOUNTER_ID ORDER BY SURG_LEVEL_CODE, SURGERY_START_AT desc) AS rn
from MRAS_BUSINESS_SUR_GRADE
where
--布局组件设置提升效率
AND IS_DEL = 0
AND VERSION = 'V2.0'
AND SURG_LEVEL_CODE IN (136619, 136618)
AND EVENT_AT BETWEEN :marptBeginAt and :marptEndAt
) t WHERE t.rn = 1
)
SELECT
  tt.CURRENT_DEPT_ID AS 当前科室编码,
  tt.CURRENT_DEPT_NAME AS 当前科室名称,
  tt.分子四级手术患者死亡率 as 分子四级手术患者死亡率,
  tt.分母三级手术患者死亡率 as 分母三级手术患者死亡率,
  CONCAT(
    tt.分子四级手术患者死亡率,
    ' : ',
    tt.分母三级手术患者死亡率
  ) AS 监测情况,
  'PERCENT_PAIR' AS "结果类型",
  '分子四级手术患者死亡率' AS 分子,
  '分母三级手术患者死亡率' AS 分母,
  CONCAT(
    tt.分子四级手术患者死亡率,
    ' : ',
    tt.分母三级手术患者死亡率
  ) AS "resultVal",
tt.CURRENT_DEPT_ID as "deptId",
tt.CURRENT_DEPT_NAME as "deptName",
tt.分子四级手术患者死亡率 as "numerator",
tt.分母三级手术患者死亡率 as "denominator",
tt.HOSPITAL_AREA_ID as "hospitalAreaId"
from (
select
a.CURRENT_DEPT_ID, a.CURRENT_DEPT_NAME,
CASE WHEN a.四级手术例数 = 0 then '无数据' ELSE CONCAT( CONVERT( numeric ( 19, 2 ), 1.0 * a.四级手术且死亡患者人数 / a.四级手术例数 * 100.0), '%' ) END as 分子四级手术患者死亡率,
CASE WHEN b.三级手术例数 = 0 then '无数据' ELSE CONCAT( CONVERT( numeric ( 19, 2 ), 1.0 * b.三级手术且死亡患者人数 / b.三级手术例数 * 100.0), '%' ) END as 分母三级手术患者死亡率,
'-' AS '目标值',
'-' AS '对比结果',
  a.HOSPITAL_AREA_ID
 from (
SELECT event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME,
COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136619 and event.DEATH_FLAG = 98175 THEN event.ENCOUNTER_ID ELSE NULL END) as 四级手术且死亡患者人数,
  COUNT(CASE WHEN event.SURG_LEVEL_CODE = 136619 THEN event.MRAS_BUSINESS_SUR_GRADE_ID ELSE NULL END) AS 四级手术例数,
   max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
FROM
SUR_GRADE_INFO event
  GROUP BY  event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME
) a
left join (
  SELECT event.CURRENT_DEPT_ID,
  COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136618 and event.DEATH_FLAG = 98175 THEN event.ENCOUNTER_ID ELSE NULL END) as 三级手术且死亡患者人数,
  COUNT(CASE WHEN event.SURG_LEVEL_CODE = 136618 THEN event.MRAS_BUSINESS_SUR_GRADE_ID ELSE NULL END) AS 三级手术例数
FROM
SUR_GRADE_INFO event
  WHERE
  not EXISTS(
  select 1 from MRAS_BUSINESS_SUR_GRADE e where event.ENCOUNTER_ID = e.ENCOUNTER_ID and e.SURG_LEVEL_CODE = 136619 and e.DEATH_FLAG = 98175
  )
  GROUP BY event.CURRENT_DEPT_ID
) b on a.CURRENT_DEPT_ID = b.CURRENT_DEPT_ID
) tt
```

## 目标表－患者明细 SQL

```sql
select t.* from (
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
CASE WHEN event.DEATH_FLAG = 98175 THEN '是' ELSE '否' END AS 患者是否死亡,
  CASE WHEN event.DEATH_FLAG = 98175 THEN 98175 ELSE 98176 END AS "standFlag",
event.DEATH_AT AS 患者死亡时间,
ROW_NUMBER() OVER (PARTITION BY event.ENCOUNTER_ID ORDER BY event.SURG_LEVEL_CODE, event.SURGERY_START_AT desc) AS rn
FROM
MRAS_BUSINESS_SUR_GRADE event #{NOLOCK}
LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
--布局组件设置提升效率
AND event.VERSION = 'V2.0'
AND event.IS_DEL = 0
    AND event.EVENT_AT BETWEEN :marptBeginAt and :marptEndAt
AND event.SURG_LEVEL_CODE in (136619, 136618)
) t WHERE
t.rn = 1
```
