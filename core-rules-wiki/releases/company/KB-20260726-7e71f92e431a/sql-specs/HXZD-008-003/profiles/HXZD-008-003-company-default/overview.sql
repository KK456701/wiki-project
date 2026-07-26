 --查询出目标值，各个指标编码是固定的
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-008-003'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
SELECT
event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME AS 科室名称,
COUNT(CASE WHEN PREOP_PLAN_CONSISTENT = 98175 THEN 1 ELSE NULL END) AS 分子实际开展手术与术前讨论计划手术一致的手术例数,
COUNT(1) AS 分母同期手术总例数,
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN PREOP_PLAN_CONSISTENT = 98175 THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS 监测情况
FROM
MRAS_BUSINESS_OP_DISC event WITH (NOLOCK)
WHERE
--布局组件设置提升效率
  AND event.VERSION = 'V2.0'
AND event.SURGERY_END_AT BETWEEN :start_time and :end_time
GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子实际开展手术与术前讨论计划手术一致的手术例数) AS 分子实际开展手术与术前讨论计划手术一致的手术例数,
SUM(分母同期手术总例数) AS 分母同期手术总例数,
CASE
WHEN SUM(分母同期手术总例数) = 0 THEN 0
ELSE SUM(分子实际开展手术与术前讨论计划手术一致的手术例数) * 1.0 / SUM(分母同期手术总例数)
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
WHERE 分母同期手术总例数 > 0
AND 监测情况 < (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS 未达标科室列表
FROM TotalStats t;
