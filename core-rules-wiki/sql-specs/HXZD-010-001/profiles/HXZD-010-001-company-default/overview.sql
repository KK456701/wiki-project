--查询出目标值，各个指标编码是固定的
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION #{NOLOCK}
    WHERE
TARGET_NO = 'HXZD-010-001'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
    SELECT
o1.ORG_ID,
o1.ORG_NAME AS "科室名称",
COUNT(CASE WHEN DATEDIFF(HOUR, t1.START_AT, t1.TERMINATED_AT) < 24 THEN 1 ELSE NULL END) AS "当日终止数量",
COUNT(1) AS "长期医嘱总数",
CASE
WHEN COUNT(1) = 0 THEN 0
ELSE COUNT(CASE WHEN DATEDIFF(HOUR, t1.START_AT, t1.TERMINATED_AT) < 24 THEN 1 ELSE NULL END) * 1.0 / COUNT(1)
END AS "监测情况"
    FROM
INP_CLI_ORDER t1  #{NOLOCK}
LEFT JOIN INPATIENT_ENCOUNTER e  #{NOLOCK} ON t1.ENCOUNTER_ID = e.ENCOUNTER_ID
LEFT JOIN ORGANIZATION o1  #{NOLOCK} ON e.CURRENT_DEPT_ID = o1.ORG_ID
    WHERE
--布局组件设置提升效率
AND t1.START_AT BETWEEN :marptBeginAt and :marptEndAt
    AND t1.IS_DEL = '0'
   AND e.INPAT_ENC_BIZ_TYPE_CODE <> 399552157
-- 长期医嘱
    AND t1.ORDER_PERIOD_CODE = '138128'
-- 排除已作废状态
    AND t1.CLI_ORDER_STATUS NOT IN ('98440','98441')
  --去除24小时出入院
AND (e.DISCHARGED_FROM_WARD_AT IS NULL OR DATEDIFF(HOUR, e.FIRST_ADMITTED_TO_WARD_AT, e.DISCHARGED_FROM_WARD_AT) > 24)
    GROUP BY
o1.ORG_ID, o1.ORG_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(当日终止数量) AS "分子开具长期医嘱后当日终止执行的医嘱数量",
SUM(长期医嘱总数) AS "分母同期开具长期医嘱总数量",
CASE
WHEN SUM(长期医嘱总数) = 0 THEN null
ELSE SUM(当日终止数量) * 1.0 / SUM(长期医嘱总数)
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
WHERE 长期医嘱总数 > 0
AND 监测情况 >= (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS "未达标科室列表"
FROM TotalStats t;
