---
page_type: sql_original
rule_id: HXZD-016-002
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-27
---

# 原始 SQL 存档：术中自体血回输率

> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。

## 目标表－概览 SQL

```sql
 WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL / 100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION
    WHERE
TARGET_NO = 'HXZD-016-002'
),
DeptOrderStats AS (
    SELECT
event.MRAS_DIM_DEPT_ID,
event.MRAS_DIM_DEPT_NAME AS 科室名称,
COUNT(CASE WHEN AUTOLOGOUS_BLOOD_TRANS_FLAG = 98175 THEN 1 ELSE NULL END) AS 分子术中使用自体血回输总例数,
COUNT(1) AS 分母同期术中输血总例数,
CASE
WHEN COUNT(*) = 0 THEN 0
ELSE COUNT(CASE WHEN AUTOLOGOUS_BLOOD_TRANS_FLAG = 98175 THEN 1 END) / COUNT(*)
END AS 监测情况
    FROM
MRAS_INDEX_SURGREC event #{NOLOCK}
inner join MRAS_PATIENT_EVENT b #{NOLOCK} on event.MRAS_INDEX_ENCOUNTER_ID = b.ENCOUNTER_ID and b.EVENT_NO = 'PatientRecord' and b.IS_DEL=0
WHERE
--布局组件设置提升效率
AND event.SURG_END BETWEEN :marptBeginAt and :marptEndAt
AND event.IS_DEL = 0
AND event.INTRA_BLOOD_FLAG = 98175
GROUP BY
event.MRAS_DIM_DEPT_ID, event.MRAS_DIM_DEPT_NAME
),
TotalStats AS (
    SELECT
SUM(d.分子术中使用自体血回输总例数) AS 分子术中使用自体血回输总例数,
SUM(d.分母同期术中输血总例数) AS 分母同期术中输血总例数,
CASE
WHEN SUM(d.分母同期术中输血总例数) = 0 THEN 0
ELSE SUM(d.分子术中使用自体血回输总例数) / SUM(d.分母同期术中输血总例数)
END AS 监测情况
    FROM DeptOrderStats d
)
SELECT
    t.*,
    tv.target_value AS 目标值,
    CASE WHEN t.监测情况 < tv.target_value THEN '否' ELSE '是' END AS 是否达标,
STUFF((
SELECT ', ' + 科室名称
FROM DeptOrderStats
WHERE 分母同期术中输血总例数 > 0
AND 监测情况 < (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS "未达标科室列表"
FROM TotalStats t
CROSS JOIN TargetValue tv
```

## 目标表－科室统计 SQL

```sql
-- 目标值的数据
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION
    WHERE
TARGET_NO = 'HXZD-016-002'
),
-- 按照科室来进行处理
DeptStats AS (
    SELECT
event.MRAS_DIM_DEPT_ID AS 当前科室编码,
event.MRAS_DIM_DEPT_NAME AS 当前科室名称,
COUNT(CASE WHEN AUTOLOGOUS_BLOOD_TRANS_FLAG = 98175 THEN 1 END) AS 分子术中使用自体血回输总例数,
COUNT(*) AS 分母同期术中输血总例数,
CASE
WHEN COUNT(*) = 0 THEN 0
ELSE COUNT(CASE WHEN AUTOLOGOUS_BLOOD_TRANS_FLAG = 98175 THEN 1 END) / COUNT(*)
END AS 监测情况,
max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
    FROM
MRAS_INDEX_SURGREC event
inner join MRAS_PATIENT_EVENT b #{NOLOCK} on event.MRAS_INDEX_ENCOUNTER_ID = b.ENCOUNTER_ID and b.EVENT_NO = 'PatientRecord' and b.IS_DEL=0
WHERE
--布局组件设置提升效率
AND event.SURG_END BETWEEN :marptBeginAt and :marptEndAt
AND event.IS_DEL = 0
AND event.INTRA_BLOOD_FLAG = 98175
GROUP BY  event.MRAS_DIM_DEPT_ID,event.MRAS_DIM_DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子术中使用自体血回输总例数,
d.分母同期术中输血总例数,
d.监测情况,
tv.target_value AS 目标值,
CASE
WHEN tv.target_value IS NULL THEN NULL
WHEN d.分母同期术中输血总例数 = 0 THEN '无数据'
WHEN d.监测情况 >= tv.target_value THEN '达标'
ELSE '未达标'
END AS 对比结果,
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期术中输血总例数 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子术中使用自体血回输总例数 as "numerator",
d.分母同期术中输血总例数 as "denominator",
d.HOSPITAL_AREA_ID as "hospitalAreaId"
    FROM
DeptStats d
CROSS JOIN TargetValue tv
    WHERE 1 = 1
-- 达标条件
-- 未达标条件
)
-- 最终查询结果
SELECT * FROM TempResults
```

## 目标表－患者明细 SQL

```sql
SELECT
event.MRAS_INDEX_ENCOUNTER_ID as ENCOUNTER_ID,
event.MRAS_DIM_DEPT_NAME AS 当前科室,
b.PERSON_NAME AS 患者姓名,
b.BIZ_ATTR_ONE_NO AS 住院号,
b.EMPLOYEE_NAME AS 责任医师,
team.ORG_NAME as "TEAM_NAME",
team.ORG_ID as "TEAM_ID",
team.ORG_NO as "TEAM_NO",
team.ORG_NAME as "当前医疗组",
b.EVENT_AT AS 入区时间,
b.EVENT_END_AT AS 出区时间,
event.SURG_NAME AS 手术名称,
event.SURG_LEVEL AS 手术等级,
event.SURG_START as 手术开始时间,
event.SURG_END as 手术结束时间,
CASE WHEN INTRA_BLOOD_FLAG = 98175 THEN '是' ELSE '否' END AS 是否进行术中输血,
CASE WHEN AUTOLOGOUS_BLOOD_TRANS_FLAG = 98175 THEN '是' ELSE '否' END AS 是否自体血回输,
CASE WHEN AUTOLOGOUS_BLOOD_TRANS_FLAG = 98175 THEN 98175 ELSE 98176 END AS "standFlag"
FROM
MRAS_INDEX_SURGREC event #{NOLOCK}
inner join MRAS_PATIENT_EVENT b #{NOLOCK} on event.MRAS_INDEX_ENCOUNTER_ID = b.ENCOUNTER_ID and b.EVENT_NO = 'PatientRecord' and b.IS_DEL=0
LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.MRAS_INDEX_ENCOUNTER_ID = inp.ENCOUNTER_ID
LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
--布局组件设置提升效率
    AND event.SURG_END BETWEEN :marptBeginAt and :marptEndAt
AND event.IS_DEL = 0
AND event.INTRA_BLOOD_FLAG = 98175
```
