--查询出目标值，各个指标编码是固定的
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-007-001'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
  SELECT
   event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME AS "科室名称",
   SUM(EMERGENCY_COUNT_SUCCESS) AS '分子急危重症患者抢救成功的例次数',
   SUM(EMERGENCY_COUNT) AS '分母同期急危重症患者抢救的总例次数',
   CASE WHEN COUNT(1) = 0 THEN 0  ELSE  SUM(EMERGENCY_COUNT_SUCCESS)* 1.0 / COUNT(1) * 1.0 END AS '监测情况'
  FROM
   MRAS_BUSINESS_PATRESCUE event WITH (NOLOCK)
  WHERE
    --布局组件设置提升效率
   AND event.ADMITTED_TO_WARD_AT BETWEEN :start_time and :end_time
   GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子急危重症患者抢救成功的例次数) AS "分子急危重症患者抢救成功的例次数",
SUM(分母同期急危重症患者抢救的总例次数) AS "分母同期急危重症患者抢救的总例次数",
CASE
WHEN SUM(分母同期急危重症患者抢救的总例次数) = 0 THEN 0
ELSE SUM(分子急危重症患者抢救成功的例次数) * 1.0 / SUM(分母同期急危重症患者抢救的总例次数)
END AS "监测情况",
(SELECT target_value FROM TargetValue) AS "目标值"
    FROM DeptOrderStats
)
-- 进行数据输出
SELECT
    t.*,
  CASE WHEN t.监测情况 >= t.目标值 THEN '是' ELSE '否' END AS "是否达标",
    STUFF((
SELECT ', ' + 科室名称
FROM DeptOrderStats
WHERE 分母同期急危重症患者抢救的总例次数 > 0
AND 监测情况 < (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS "未达标科室列表"
FROM TotalStats t;
