--目标值的数据
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-010-001'
),
-- 按照科室来进行处理
DeptStats AS (
    SELECT
e.CURRENT_DEPT_ID AS "当前科室编码",
o1.ORG_NAME AS "当前科室名称",
COUNT(CASE WHEN DATEDIFF(HOUR, t1.START_AT, t1.TERMINATED_AT) < 24 THEN 1 ELSE NULL END) AS "分子开具长期医嘱后当日终止执行的医嘱数量",
COUNT(1) AS "分母同期开具长期医嘱总数量",
CASE WHEN COUNT(1) = 0 THEN 0
ELSE COUNT(CASE WHEN DATEDIFF(HOUR, t1.START_AT, t1.TERMINATED_AT) < 24 THEN 1 ELSE NULL END) * 1.0 / COUNT(1)
END AS "监测情况",
max(e.SOURCE_HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
    FROM
INP_CLI_ORDER t1 WITH (NOLOCK)
LEFT JOIN INPATIENT_ENCOUNTER e WITH (NOLOCK) ON t1.ENCOUNTER_ID = e.ENCOUNTER_ID
LEFT JOIN ORGANIZATION o1 WITH (NOLOCK) ON e.CURRENT_DEPT_ID = o1.ORG_ID
    WHERE
--布局组件设置提升效率
AND t1.START_AT BETWEEN :start_time AND :end_time
AND t1.IS_DEL = '0'
AND e.INPAT_ENC_BIZ_TYPE_CODE <> 399552157
-- 长期医嘱
AND t1.ORDER_PERIOD_CODE = '138128'
-- 排除已作废，已失效
AND t1.CLI_ORDER_STATUS NOT IN ('98440','98441')
  --去除24小时出入院
AND (e.DISCHARGED_FROM_WARD_AT IS NULL OR DATEDIFF(HOUR, e.FIRST_ADMITTED_TO_WARD_AT, e.DISCHARGED_FROM_WARD_AT) > 24)
    GROUP BY
e.CURRENT_DEPT_ID, o1.ORG_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子开具长期医嘱后当日终止执行的医嘱数量,
d.分母同期开具长期医嘱总数量,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期开具长期医嘱总数量 = 0 THEN '无数据'
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期开具长期医嘱总数量 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子开具长期医嘱后当日终止执行的医嘱数量 as "numerator",
d.分母同期开具长期医嘱总数量 as "denominator",
d.HOSPITAL_AREA_ID as "hospitalAreaId"
    FROM
DeptStats d
WHERE 1 = 1
-- 达标
-- 未达标
)
-- 最终查询结果
SELECT * FROM TempResults
