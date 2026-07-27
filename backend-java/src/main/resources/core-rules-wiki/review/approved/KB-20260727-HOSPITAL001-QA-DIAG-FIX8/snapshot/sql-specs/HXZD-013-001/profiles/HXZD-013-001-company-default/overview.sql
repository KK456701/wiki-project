--查询出目标值，各个指标编码是固定的
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-013-001'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
    SELECT
TEMP.UNIT_CODE AS '科室编码',
TEMP.UNIT_NAME AS '科室名称',
COUNT(CASE WHEN PROC_DEL = '1' THEN 1 ELSE NULL END) AS '分子',
COUNT(1) AS '分母',
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN PROC_DEL = '1' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS '监测情况'
FROM
(SELECT
pro.*,
CASE
WHEN EXISTS (
SELECT 1
FROM MRAS_MEDTECH_PROC p2
WHERE p2.MRAS_MEDTECH_PRO_ID = pro.MRAS_MEDTECH_PRO_ID
AND p2.IS_DEL = 0
AND p2.CREATED_AT BETWEEN :marptBeginAt AND :marptEndAt
) THEN '1'
ELSE '0'
END AS PROC_DEL
FROM
MRAS_MEDTECH_PRO pro
WHERE
--布局组件设置提升效率
 pro.IS_DEL = 0
-- 关键修改：将主表时间+2年与开始参数比较，+1年与结束参数比较
AND DATEADD(YEAR, 2, pro.CREATED_AT) >= :marptBeginAt
AND DATEADD(YEAR, 1, pro.CREATED_AT) <= :marptEndAt) TEMP
WHERE 1 = 1
GROUP BY  TEMP.UNIT_CODE,TEMP.UNIT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子) AS "分子",
SUM(分母) AS "分母",
CASE
WHEN SUM(分母) = 0 THEN null
ELSE SUM(分子) * 1.0 / SUM(分母)
END AS "监测情况",
(SELECT target_value FROM TargetValue) AS "目标值"
    FROM DeptOrderStats
)
-- 进行数据输出
SELECT
    t.*,
CASE WHEN t.监测情况 < t.目标值 THEN '否' ELSE '是' END AS "是否达标",
    STUFF((
SELECT ', ' + 科室名称
FROM DeptOrderStats
WHERE 分母 > 0
AND 监测情况 < (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS "未达标科室列表"
FROM TotalStats t;
