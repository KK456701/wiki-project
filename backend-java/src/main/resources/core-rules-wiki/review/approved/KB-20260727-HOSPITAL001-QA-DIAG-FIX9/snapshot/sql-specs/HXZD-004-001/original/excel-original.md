---
page_type: sql_original
rule_id: HXZD-004-001
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-27
---

# 原始 SQL 存档：手术患者特级护理/一级护理出院率

> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。

## 目标表－概览 SQL

```sql
--查询出目标值，各个指标编码是固定的
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION #{NOLOCK}
    WHERE
TARGET_NO = 'HXZD040101'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
 SELECT
  event.CURRENT_DEPT_ID,
  event.CURRENT_DEPT_NAME AS "科室名称",
  COUNT(CASE WHEN CARE_LEVEL IN('Ⅰ级护理','特级护理') THEN 1 ELSE NULL END) AS '分子手术患者出院时为特级护理一级护理级别的患者数量',
  COUNT(1) AS '分母同期手术患者总数量',
  CASE WHEN COUNT(1) = 0 THEN NULL WHEN COUNT(1) != 0 AND COUNT(CASE WHEN CARE_LEVEL IN('Ⅰ级护理','特级护理') THEN 1 ELSE NULL END)=0 THEN 0  ELSE COUNT(CASE WHEN CARE_LEVEL IN('Ⅰ级护理','特级护理') THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS '监测情况'
 FROM (
  SELECT
   DISTINCT CURRENT_DEPT_ID,CURRENT_DEPT_NAME,ENCOUNTER_ID,CARE_LEVEL
  FROM MRAS_BUSINESS_GRADED_CARE event #{NOLOCK}
  WHERE
   --布局组件设置提升效率
   AND event.WARD_DISCHARGED_AT BETWEEN :marptBeginAt and :marptEndAt
 ) event
 GROUP BY event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子手术患者出院时为特级护理一级护理级别的患者数量) AS "分子手术患者出院时为特级护理一级护理级别的患者数量",
SUM(分母同期手术患者总数量) AS "分母同期手术患者总数量",
CASE
WHEN SUM(分母同期手术患者总数量) = 0 THEN 0
ELSE SUM(分子手术患者出院时为特级护理一级护理级别的患者数量) * 1.0 / SUM(分母同期手术患者总数量)
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
WHERE 分母同期手术患者总数量 > 0
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
TARGET_NO = 'HXZD-004-001'
),
-- 按照科室来进行处理
DeptStats AS (
  SELECT
   event.CURRENT_DEPT_ID AS "当前科室编码",
   event.CURRENT_DEPT_NAME AS "当前科室名称",
   COUNT(CASE WHEN CARE_LEVEL IN('Ⅰ级护理','特级护理') THEN 1 ELSE NULL END) AS "分子手术患者出院时为特级护理一级护理级别的患者数量",
   COUNT(1) AS "分母同期手术患者总数量",
   CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN CARE_LEVEL IN('Ⅰ级护理','特级护理') THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS "监测情况" ,
   max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
  FROM
   MRAS_BUSINESS_GRADED_CARE event  (NOLOCK)
  WHERE
   --布局组件设置提升效率
   AND event.WARD_DISCHARGED_AT BETWEEN :marptBeginAt and :marptEndAt
  GROUP BY  event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子手术患者出院时为特级护理一级护理级别的患者数量,
d.分母同期手术患者总数量,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
   WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期手术患者总数量 = 0 THEN '无数据'
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
  CASE
   WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期手术患者总数量 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子手术患者出院时为特级护理一级护理级别的患者数量 as "numerator",
d.分母同期手术患者总数量 as "denominator",
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
 event.PERSON_NAME AS "患者姓名",
 event.CURRENT_ADMITTER_NAME AS '责任医师',
 team.ORG_NAME as "TEAM_NAME",
 team.ORG_ID as "TEAM_ID",
 team.ORG_NO as "TEAM_NO",
 team.ORG_NAME as "当前医疗组",
 event.ADMITTED_TO_WARD_AT AS '入区时间',
 event.WARD_DISCHARGED_AT AS '出区时间',
 event.SURGERY_NAME AS '手术名称',
 event.SURGERY_AT AS '手术完成时间',
  event.CARE_LEVEL as '出区时护理级别'
FROM
 MRAS_BUSINESS_GRADED_CARE event  #{NOLOCK}
   LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
 LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
 --布局组件设置提升效率
    AND event.WARD_DISCHARGED_AT BETWEEN :marptBeginAt and :marptEndAt
```
