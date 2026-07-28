--目标值的数据
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-013-001'
),
-- 按照科室来进行处理
DeptStats AS (
    SELECT
TEMP.UNIT_CODE AS '当前科室编码',
TEMP.UNIT_NAME AS '当前科室名称',
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
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子,
d.分母,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母 = 0 THEN '无数据'
WHEN d.监测情况 >= (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
  CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子 as "numerator",
d.分母 as "denominator"
    FROM
DeptStats d
WHERE 1 = 1
-- 达标
-- 未达标
)
-- 最终查询结果
SELECT * FROM TempResults ;
