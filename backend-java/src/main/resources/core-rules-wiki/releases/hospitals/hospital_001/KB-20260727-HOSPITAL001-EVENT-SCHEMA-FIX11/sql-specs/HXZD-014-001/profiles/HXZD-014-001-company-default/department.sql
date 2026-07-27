WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL / 100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION
    WHERE
TARGET_NO = 'HXZD-014-001'
),
-- 所有记录的时间差计算（整数分钟）
AllTimeDiffs AS (
    SELECT
DATEDIFF(minute, PUBLISH_AT, RECEIVE_TIME) AS diff_minutes,
CURRENT_DEPT_NAME AS 科室名称,
CURRENT_DEPT_ID AS 当前科室编码,
HOSPITAL_AREA_ID AS HOSPITAL_AREA_ID
    FROM MRAS_BUSINESS_CRITICAL_RPT WITH (NOLOCK)
    WHERE
  --布局组件设置提升效率
 ENCOUNTER_TYPE_CODE = '145235'
AND ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt AND :marptEndAt
AND RECEIVE_TIME IS NOT NULL
AND PUBLISH_AT IS NOT NULL
),
-- 各科室中位数计算（ROW_NUMBER 方法，兼容所有版本）
DeptMedians AS (
    SELECT
科室名称,
HOSPITAL_AREA_ID,
当前科室编码,
ROUND(AVG(CAST(diff_minutes AS FLOAT)), 2) AS 中位数,
(SELECT target_value FROM TargetValue) AS 目标值
    FROM (
SELECT
科室名称,
HOSPITAL_AREA_ID,
当前科室编码,
diff_minutes,
ROW_NUMBER() OVER (PARTITION BY 科室名称 ORDER BY diff_minutes) AS rn,
COUNT(*) OVER (PARTITION BY 科室名称) AS total_count
FROM AllTimeDiffs
    ) ranked
    WHERE rn IN ((total_count + 1) / 2, (total_count + 2) / 2)
    GROUP BY 科室名称, HOSPITAL_AREA_ID, 当前科室编码
)
-- 最终科室维度报表
SELECT
    科室名称 AS 当前科室名称,
    中位数,
    目标值,
    CASE
WHEN 中位数 <= 目标值 THEN '达标'
ELSE '未达标'
    END AS 对比结果,
    CASE
WHEN 中位数 <= 目标值 THEN 98175
ELSE 98176
    END AS "standFlag",
    中位数 AS "resultVal",
    当前科室编码 AS "deptId",
    科室名称 AS "deptName",
    HOSPITAL_AREA_ID AS "hospitalAreaId"
FROM DeptMedians
ORDER BY 对比结果 DESC, 中位数 DESC
