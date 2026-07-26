 --查询出目标值，各个指标编码是固定的
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-012-004'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
select
a.CURRENT_DEPT_ID,
a.科室名称,
a.开展三四级手术术种 as 分子实际开展的三四级手术术种数,
b.备案三四级手术术种 as 分母同期备案的三四级手术术种数,
CASE WHEN b.备案三四级手术术种 = 0 THEN 0 ELSE a.开展三四级手术术种 * 1.0 / b.备案三四级手术术种 * 1.0 END AS 监测情况
from (
SELECT
event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME AS 科室名称,
COUNT(DISTINCT event.SURGERY_ID) as 开展三四级手术术种
FROM
MRAS_BUSINESS_SUR_GRADE event
  where
--布局组件设置提升效率
AND event.IS_DEL = 0
   AND event.VERSION = 'V2.0'
  AND event.EVENT_AT BETWEEN :start_time and :end_time
  and event.SURG_LEVEL_CODE in (136618,136619)
  GROUP BY event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
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
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子实际开展的三四级手术术种数) AS 分子实际开展的三四级手术术种数,
SUM(分母同期备案的三四级手术术种数) AS 分母同期备案的三四级手术术种数,
CASE
WHEN SUM(分母同期备案的三四级手术术种数) = 0 THEN 0
ELSE SUM(分子实际开展的三四级手术术种数) * 1.0 / SUM(分母同期备案的三四级手术术种数)
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
WHERE 分母同期备案的三四级手术术种数 > 0
AND 监测情况 < (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS 未达标科室列表
FROM TotalStats t;
