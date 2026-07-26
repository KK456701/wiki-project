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
