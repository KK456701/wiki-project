--目标值的数据
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-002-003'
),
-- 按照科室来进行处理
DeptStats AS (
  SELECT event.CURRENT_DEPT_ID AS '当前科室编码',
event.CURRENT_DEPT_NAME
AS "当前科室名称",
COUNT(DISTINCT CASE
WHEN PATIENT_STATUS.STATUS = '98175' THEN event.ENCOUNTER_ID
ELSE NULL
END)
AS '分子行非计划手术的住院患者人次数',
COUNT(DISTINCT event.ENCOUNTER_ID) AS '分母同期入院患者总人次数',
CASE
WHEN COUNT(DISTINCT event.ENCOUNTER_ID) = 0 THEN 0
ELSE COUNT(DISTINCT CASE
WHEN PATIENT_STATUS.STATUS = '98175' THEN event.ENCOUNTER_ID
ELSE NULL
END) * 1.0 / COUNT(DISTINCT event.ENCOUNTER_ID)
END
AS '监测情况',
  max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
FROM MRAS_BUSINESS_WARDROUND event
JOIN (SELECT ENCOUNTER_ID,
CASE
WHEN SUM(CASE WHEN UNPLANNED_SURGERY = '98176' THEN 1 ELSE 0 END) > 0
THEN '98176'
ELSE '98175'
END AS STATUS
FROM MRAS_BUSINESS_WARDROUND
GROUP BY ENCOUNTER_ID) PATIENT_STATUS ON event.ENCOUNTER_ID = PATIENT_STATUS.ENCOUNTER_ID
WHERE
   --布局组件设置提升效率
    event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
  GROUP BY  event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子行非计划手术的住院患者人次数,
d.分母同期入院患者总人次数,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
   WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期入院患者总人次数 = 0 THEN '无数据'
WHEN d.监测情况 >= (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
  CASE
   WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期入院患者总人次数 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子行非计划手术的住院患者人次数 as "numerator",
d.分母同期入院患者总人次数 as "denominator",
d.HOSPITAL_AREA_ID as "hospitalAreaId"
    FROM
DeptStats d
  WHERE 1 = 1
  -- 达标
  -- 未达标
)
-- 最终查询结果
SELECT * FROM TempResults ;
