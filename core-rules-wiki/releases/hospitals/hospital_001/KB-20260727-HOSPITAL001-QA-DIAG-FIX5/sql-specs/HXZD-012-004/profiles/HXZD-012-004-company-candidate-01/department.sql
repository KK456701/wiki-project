--目标值的数据
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-012-004'
),
-- 按照科室来进行处理
DeptStats AS (
SELECT
a.当前科室编码,
a.当前科室名称,
a.开展三四级手术术种 as 分子实际开展的三四级手术术种数,
b.备案三四级手术术种 as 分母同期备案的三四级手术术种数,
CASE WHEN b.备案三四级手术术种 = 0 THEN 0 ELSE a.开展三四级手术术种 * 1.0 / b.备案三四级手术术种 * 1.0 END AS 监测情况,
a.HOSPITAL_AREA_ID as HOSPITAL_AREA_ID
from (
SELECT
event.CURRENT_DEPT_ID AS 当前科室编码,
event.CURRENT_DEPT_NAME AS 当前科室名称,
COUNT(DISTINCT event.SURGERY_ID) as 开展三四级手术术种,
max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
FROM
MRAS_BUSINESS_SUR_GRADE event
  where
  --布局组件设置提升效率
 event.IS_DEL = 0
  AND event.VERSION = 'V2.0'
  AND event.EVENT_AT BETWEEN :marptBeginAt and :marptEndAt
  and event.SURG_LEVEL_CODE in (136618,136619)
GROUP BY event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME
) a
left join (
  SELECT
  COUNT(DISTINCT a1.CLIBASIC_SURGERY_ID) as 备案三四级手术术种
FROM
CLIBASIC_SURGERY a1
  WHERE a1.IS_DEL = 0
  and a1.SURGERY_GRADE_CODE in (136618, 136619) and a1.SURGERY_DIR_NO in (957347, 957348)
  and a1.ENABLED_FLAG=1
) b on 1=1
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子实际开展的三四级手术术种数,
d.分母同期备案的三四级手术术种数,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期备案的三四级手术术种数 = 0 THEN '无数据'
WHEN d.监测情况 >= (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期备案的三四级手术术种数 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子实际开展的三四级手术术种数 as "numerator",
d.分母同期备案的三四级手术术种数 as "denominator",
d.HOSPITAL_AREA_ID as "hospitalAreaId"
    FROM
DeptStats d
WHERE 1 = 1
-- 达标
-- 未达标
)
-- 最终查询结果
SELECT * FROM TempResults ;
