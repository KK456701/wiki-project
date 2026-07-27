---
page_type: sql_original
rule_id: HXZD-009-003
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-27
---

# 原始 SQL 存档：科主任主持死亡病例讨论率

> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。

## 源表 / 事件抽取 SQL

```sql
-- 死亡病例（死亡记录）
WITH DeathCases AS (
    SELECT
a.ENCOUNTER_ID,
a.HOSPITAL_SOID,
t1.DISCHARGED_FROM_WARD_AT AS DEATH_DATE
    FROM MAHP_DMTS_MAIN a
    INNER JOIN INPATIENT_ENCOUNTER t1 ON a.ENCOUNTER_ID = t1.ENCOUNTER_ID
    WHERE a.DISCHARGE_TYPE_CODE = 136924
AND a.IS_DEL = 0
),
-- 生成 1-30 的数字序列（SQL Server 使用递归 CTE）
DateSequence AS (
    SELECT 1 AS day_offset
    UNION ALL
    SELECT day_offset + 1
    FROM DateSequence
    WHERE day_offset < 30
),
WorkdayCalc AS (
    SELECT
dc.ENCOUNTER_ID,
dc.DEATH_DATE,
dc.HOSPITAL_SOID,
-- 计算第5个工作日
(
SELECT future_date
FROM (
SELECT
DATEADD(DAY, ds.day_offset, dc.DEATH_DATE) AS future_date,
ds.day_offset,
ROW_NUMBER() OVER (ORDER BY ds.day_offset) AS workday_rank
FROM DateSequence ds
WHERE
-- 排除周末（SQL Server 中周日=1,周六=7，取决于 @@DATEFIRST，此处假定默认）
DATEPART(WEEKDAY, DATEADD(DAY, ds.day_offset, dc.DEATH_DATE)) NOT IN (1, 7)
-- 排除节假日
AND NOT EXISTS (
SELECT 1
FROM HOLIDAY h
WHERE h.IS_DEL = 0
AND h.HOSPITAL_SOID = dc.HOSPITAL_SOID
AND CAST(DATEADD(DAY, ds.day_offset, dc.DEATH_DATE) AS DATE)
BETWEEN CAST(h.START_AT AS DATE) AND CAST(h.END_AT AS DATE)
)
) workdays
WHERE workdays.workday_rank = 5
) AS FIFTH_WORKDAY
    FROM DeathCases dc
)
SELECT
    t1.ENCOUNTER_ID AS bizId,  -- 用来更新数据
    'CORE_DEATH' AS eventNo,
    '死亡病例' AS eventName,
    t1.ADMITTED_TO_WARD_AT AS eventAt, -- 预留
    GETDATE() AS extractAt,  -- SQL Server 用 SYSDATETIME() 或 GETDATE()
    '1' AS mrasBusinessIndexId,
    '1' AS mrasTargetDefinitionId,
    'V2.0' AS version,
    t1.ENCOUNTER_ID AS encounterId,
    t1.FULL_NAME AS personName,
    t1.IMRN AS imrn,
    emp.EMPLOYEE_NAME AS currentAdmitterName,
    t1.CURRENT_DEPT_ID AS currentDeptId,
    o1.ORG_NAME AS currentDeptName,
    t1.CURRENT_WARD_ID AS currentWardId,
    o2.ORG_NAME AS currentWardName,
    t1.ADMITTED_TO_WARD_AT AS admittedToWardAt,
    t1.DISCHARGED_FROM_WARD_AT AS wardDischargedAt,
    t1.DISCHARGED_FROM_WARD_AT AS deathAt,
    t2.INP_EMR_SET_CREATED_AT AS FINISH_AT,
    t3.hostEmployeeId AS hostEmployeeId,
    -- 死亡讨论是否5日内完成
    CASE
WHEN t2.INP_EMR_SET_CREATED_AT IS NOT NULL
AND t2.INP_EMR_SET_CREATED_AT <= wc.FIFTH_WORKDAY
THEN 98175
ELSE 98176
    END AS deathDiscussedInDays,
    -- 是否由医务部门组织
    CASE WHEN EXISTS (
SELECT 1
FROM ORGANIZATION_X_EMPLOYEE A
INNER JOIN ORGANIZATION B ON A.ORG_ID = B.ORG_ID AND B.IS_DEL = 0
WHERE A.IS_DEL = 0
AND A.ORGANIZATION_X_EMPLOYEE_TYPE = 399566319
AND (B.ORG_NAME = '医务部' OR B.ORG_NAME = '医务科')
AND A.EMPLOYEE_ID = t3.hostEmployeeId
    ) THEN 98175 ELSE 98176 END AS medicalDeptOrganized,
    -- 是否发生医疗纠纷
    CASE WHEN EXISTS (
SELECT 1
FROM MRAS_MANAGE_COMPLAIN mc
WHERE mc.ENCOUNTER_NO = t1.IMRN
AND mc.IS_DEL = 0
    ) THEN 98175 ELSE 98176 END AS medicalDisputeCase,
    -- 是否由科主任主持
    CASE WHEN t3.hostEmployeeId IS NOT NULL THEN 98175 ELSE 98176 END AS chiefPhysicianChaired,
    NULL AS requiredUploadRecord,
    NULL AS shouldUploadRecord,
    a.HOSPITAL_SOID AS hospitalSoid,
    t1.SOURCE_HOSPITAL_AREA_ID AS hospitalAreaId,
    CASE WHEN a.IS_DEL = 1 THEN 1 ELSE 0 END AS isDel
FROM MAHP_DMTS_MAIN a
LEFT JOIN INPATIENT_ENCOUNTER t1 ON a.ENCOUNTER_ID = t1.ENCOUNTER_ID
LEFT JOIN ORGANIZATION o1 ON t1.CURRENT_DEPT_ID = o1.ORG_ID
LEFT JOIN ORGANIZATION o2 ON t1.CURRENT_WARD_ID = o2.ORG_ID
LEFT JOIN INPATIENT_PARTICIPANT pr ON pr.ENCOUNTER_ID = a.ENCOUNTER_ID
    AND pr.IS_DEL = 0
    AND pr.INPAT_PARTICIPANT_TYPE_CODE = 1000098
LEFT JOIN EMPLOYEE_INFO emp ON pr.EMPLOYEE_ID = emp.EMPLOYEE_ID
    AND emp.IS_DEL = 0
LEFT JOIN (
    SELECT
D.ENCOUNTER_ID,
D.INP_EMR_SET_CREATED_AT
    FROM INP_EMR_SECTION_DATA_ELEMENT A
    LEFT JOIN INPATIENT_EMR_SECTION B ON A.INP_EMR_SECTION_ID = B.INP_EMR_SECTION_ID
AND B.IS_DEL = 0
    LEFT JOIN INPATIENT_EMR_CONTENT C ON C.INP_EMR_CONTENT_ID = B.INP_EMR_CONTENT_ID
AND C.IS_DEL = 0
    LEFT JOIN INPATIENT_EMR_SET D ON D.INP_EMR_RECORD_ID = C.INP_EMR_RECORD_ID
AND D.IS_DEL = 0
    WHERE A.IS_DEL = 0
AND D.INP_EMR_SET_TITLE = '死亡病例讨论记录'
AND A.INP_EMR_DATA_ELEMENT_WIN_ID = '399301329' -- 讨论日期
) t2 ON t2.ENCOUNTER_ID = t1.ENCOUNTER_ID
LEFT JOIN (
    SELECT
D.ENCOUNTER_ID,
CASE
WHEN A.INP_EMR_DATA_ELEMENT_VALUE IS NOT NULL
THEN A.CREATED_BY
ELSE NULL
END AS hostEmployeeId
    FROM INP_EMR_SECTION_DATA_ELEMENT A
    LEFT JOIN INPATIENT_EMR_SECTION B ON A.INP_EMR_SECTION_ID = B.INP_EMR_SECTION_ID
    LEFT JOIN INPATIENT_EMR_CONTENT C ON C.INP_EMR_CONTENT_ID = B.INP_EMR_CONTENT_ID
    LEFT JOIN INPATIENT_EMR_SET D ON D.INP_EMR_RECORD_ID = C.INP_EMR_RECORD_ID
    WHERE D.INP_EMR_SET_TITLE = '死亡病例讨论记录'
AND A.INP_EMR_DATA_ELEMENT_WIN_ID = '399336516' -- 病例讨论主持人姓名
) t3 ON t3.ENCOUNTER_ID = t1.ENCOUNTER_ID
LEFT JOIN WorkdayCalc wc ON a.ENCOUNTER_ID = wc.ENCOUNTER_ID
WHERE a.DISCHARGE_TYPE_CODE = 136924
    AND a.IS_DEL = 0
-- 后续的 ETC 占位符请根据实际需要取消注释并替换为有效的条件
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
TARGET_NO = 'HXZD-009-003'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
SELECT
event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME AS "科室名称",
COUNT(CASE WHEN CHIEF_PHYSICIAN_CHAIRED = '98175' THEN 1 ELSE NULL END) AS "分子死亡病例讨论由科主任主持的病例数量",
COUNT(1) AS "分母同期死亡病例总数量",
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN CHIEF_PHYSICIAN_CHAIRED = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS "监测情况"
FROM
MRAS_BUSINESS_DEATH event #{NOLOCK}
WHERE
--布局组件设置提升效率
AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子死亡病例讨论由科主任主持的病例数量) AS "分子死亡病例讨论由科主任主持的病例数量",
SUM(分母同期死亡病例总数量) AS "分母同期死亡病例总数量",
CASE
WHEN SUM(分母同期死亡病例总数量) = 0 THEN 0
ELSE SUM(分子死亡病例讨论由科主任主持的病例数量) * 1.0 / SUM(分母同期死亡病例总数量)
END AS "监测情况",
(SELECT target_value FROM TargetValue) AS "目标值"
    FROM DeptOrderStats
)
-- 进行数据输出
SELECT
    t.*,
CASE WHEN t.监测情况 >= t.目标值 THEN '否' ELSE '是' END AS "是否达标",
    STUFF((
SELECT ', ' + 科室名称
FROM DeptOrderStats
WHERE 分母同期死亡病例总数量 > 0
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
TARGET_NO = 'HXZD-009-003'
),
-- 按照科室来进行处理
DeptStats AS (
SELECT
event.CURRENT_DEPT_ID AS "当前科室编码",
event.CURRENT_DEPT_NAME AS "当前科室名称",
COUNT(CASE WHEN CHIEF_PHYSICIAN_CHAIRED = '98175' THEN 1 ELSE NULL END) AS "分子死亡病例讨论由科主任主持的病例数量",
COUNT(1) AS "分母同期死亡病例总数量",
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN CHIEF_PHYSICIAN_CHAIRED = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS "监测情况" ,
max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
FROM
MRAS_BUSINESS_DEATH event  (NOLOCK)
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
d.分子死亡病例讨论由科主任主持的病例数量,
d.分母同期死亡病例总数量,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期死亡病例总数量 = 0 THEN '无数据'
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期死亡病例总数量 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子死亡病例讨论由科主任主持的病例数量 as "numerator",
d.分母同期死亡病例总数量 as "denominator",
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
event.WARD_DISCHARGED_AT AS 出区时间,
event.FINISH_AT as 死亡病例讨论记录完成时间,
emp1.EMPLOYEE_NAME as 死亡病例讨论主持人,
CASE WHEN CHIEF_PHYSICIAN_CHAIRED = '98175' THEN '是' ELSE '否' END AS 是否科主任主持,
CASE WHEN CHIEF_PHYSICIAN_CHAIRED = '98175' THEN 98175 ELSE 98176 END AS "standFlag"
FROM
MRAS_BUSINESS_DEATH event  #{NOLOCK}
LEFT JOIN EMPLOYEE_INFO emp1  #{NOLOCK}  ON emp1.EMPLOYEE_ID = event.HOST_EMPLOYEE_ID
LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
--布局组件设置提升效率
and event.IS_DEL = 0
    AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
```
