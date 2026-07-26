--目标值的数据
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION
    WHERE
TARGET_NO = 'HXZD-008-003'
),
-- 按照科室来进行处理
DeptStats AS (
    SELECT
event.CURRENT_DEPT_ID AS "当前科室编码",
event.CURRENT_DEPT_NAME AS "当前科室名称",
COUNT(CASE WHEN PREOP_PLAN_CONSISTENT = 98175 THEN 1 END) AS "分子实际开展手术与术前讨论计划手术一致的手术例数",
COUNT(*) AS "分母同期手术总例数",
CASE
WHEN COUNT(*) = 0 THEN 0
ELSE COUNT(CASE WHEN PREOP_PLAN_CONSISTENT = 98175 THEN 1 END) / COUNT(*)
END AS "监测情况",
  max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
    FROM
MRAS_BUSINESS_OP_DISC event
    WHERE
--布局组件设置提升效率
AND event.VERSION = 'V2.0'
AND event.SURGERY_END_AT BETWEEN :marptBeginAt AND :marptEndAt
    GROUP BY event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d."当前科室编码",
d."当前科室名称",
d."分子实际开展手术与术前讨论计划手术一致的手术例数",
d."分母同期手术总例数",
d."监测情况",
tv.target_value AS "目标值",
CASE
WHEN tv.target_value IS NULL THEN NULL
WHEN d."分母同期手术总例数" = 0 THEN '无数据'
WHEN d."监测情况" >= tv.target_value THEN '达标'
ELSE '未达标'
END AS "对比结果",
  CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d."分母同期手术总例数" = 0 THEN 98176
WHEN d."监测情况" < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d."监测情况" as "resultVal",
d."当前科室编码" as "deptId",
d."当前科室名称" as "deptName",
d."分子实际开展手术与术前讨论计划手术一致的手术例数" as "numerator",
d."分母同期手术总例数" as "denominator",
d.HOSPITAL_AREA_ID as "hospitalAreaId"
    FROM
DeptStats d
CROSS JOIN TargetValue tv
    WHERE 1 = 1
-- 达标条件
-- 未达标条件
)
-- 最终查询结果
SELECT * FROM TempResults;
