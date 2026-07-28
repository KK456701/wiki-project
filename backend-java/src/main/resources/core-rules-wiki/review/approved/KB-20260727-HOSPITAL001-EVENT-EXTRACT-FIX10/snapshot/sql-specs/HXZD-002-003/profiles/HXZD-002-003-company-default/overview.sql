--查询出目标值，各个指标编码是固定的
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-002-003'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
  SELECT event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME
AS "科室名称",
COUNT(DISTINCT CASE
WHEN PATIENT_STATUS.STATUS = '98175' THEN event.ENCOUNTER_ID
ELSE NULL
END)
AS '分子行非计划手术的住院患者人次数',
COUNT(DISTINCT event.ENCOUNTER_ID) AS '分母同期入院患者总人次数',
CASE
WHEN COUNT(DISTINCT event.ENCOUNTER_ID) = 0 THEN 0
ELSE COUNT(DISTINCT CASE
WHEN PATIENT_STATUS.STATUS = '98175' THEN event.ENCOUNTER_ID
ELSE NULL
END) * 1.0 / COUNT(DISTINCT event.ENCOUNTER_ID)
END
AS '监测情况'
FROM MRAS_BUSINESS_WARDROUND event
JOIN (SELECT ENCOUNTER_ID,
CASE
WHEN SUM(CASE WHEN UNPLANNED_SURGERY = '98176' THEN 1 ELSE 0 END) > 0
THEN '98176'
ELSE '98175'
END AS STATUS
FROM MRAS_BUSINESS_WARDROUND
GROUP BY ENCOUNTER_ID) PATIENT_STATUS ON event.ENCOUNTER_ID = PATIENT_STATUS.ENCOUNTER_ID
  WHERE
   --布局组件设置提升效率
    event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
   GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子行非计划手术的住院患者人次数) AS "分子行非计划手术的住院患者人次数",
SUM(分母同期入院患者总人次数) AS "分母同期入院患者总人次数",
CASE
WHEN SUM(分母同期入院患者总人次数) = 0 THEN null
ELSE SUM(分子行非计划手术的住院患者人次数) * 1.0 / SUM(分母同期入院患者总人次数)
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
WHERE 分母同期入院患者总人次数 > 0
AND 监测情况 >= (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS "未达标科室列表"
FROM TotalStats t;
