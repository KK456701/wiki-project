WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL / 100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-014-001'
),
-- 所有记录的时间差计算
AllTimeDiffs AS (
    SELECT
DATEDIFF(minute, PUBLISH_AT, RECEIVE_TIME) AS diff_minutes,
CURRENT_DEPT_NAME AS 科室名称
    FROM MRAS_BUSINESS_CRITICAL_RPT WITH (NOLOCK)
    WHERE
--布局组件设置提升效率
 ENCOUNTER_TYPE_CODE = '145235'
AND ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt AND :marptEndAt
AND RECEIVE_TIME IS NOT NULL
AND PUBLISH_AT IS NOT NULL
),
-- 全院中位数计算（ROW_NUMBER 方法，兼容所有版本）
HospitalMedian AS (
SELECT
ROUND(AVG(CAST(diff_minutes AS FLOAT)), 2) AS median_minutes,
MAX(total_count) AS sample_count
    FROM (
SELECT
diff_minutes,
ROW_NUMBER() OVER (ORDER BY diff_minutes) AS rn,
COUNT(*) OVER () AS total_count
FROM AllTimeDiffs
    ) ranked
    WHERE rn IN ((total_count + 1) / 2, (total_count + 2) / 2)
),
-- 各科室中位数计算
DeptMedians AS (
    SELECT
科室名称,
ROUND(AVG(CAST(diff_minutes AS FLOAT)), 2) AS median_minutes
    FROM (
SELECT
科室名称,
diff_minutes,
ROW_NUMBER() OVER (PARTITION BY 科室名称 ORDER BY diff_minutes) AS rn,
COUNT(*) OVER (PARTITION BY 科室名称) AS total_count
FROM AllTimeDiffs
    ) ranked
    WHERE rn IN ((total_count + 1) / 2, (total_count + 2) / 2)
    GROUP BY 科室名称
)
-- 最终概览结果
SELECT
    hm.median_minutes AS 监测情况,
    hm.sample_count AS sample_count,
    tv.target_value AS 目标值,
    CASE WHEN hm.median_minutes <= tv.target_value THEN '达标' ELSE '未达标' END AS 达标情况
FROM HospitalMedian hm
CROSS JOIN TargetValue tv
