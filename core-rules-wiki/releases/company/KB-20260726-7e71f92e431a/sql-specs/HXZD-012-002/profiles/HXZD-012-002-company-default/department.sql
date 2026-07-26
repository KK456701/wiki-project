WITH SUR_GRADE_INFO AS (
select t.* from (
select *,
ROW_NUMBER() OVER (PARTITION BY ENCOUNTER_ID ORDER BY SURG_LEVEL_CODE, SURGERY_START_AT desc) AS rn
from MRAS_BUSINESS_SUR_GRADE
where
--布局组件设置提升效率
AND IS_DEL = 0
AND VERSION = 'V2.0'
AND SURG_LEVEL_CODE IN (136619, 136618)
AND EVENT_AT BETWEEN :start_time and :end_time
) t WHERE t.rn = 1
)
SELECT
  tt.CURRENT_DEPT_ID AS 当前科室编码,
  tt.CURRENT_DEPT_NAME AS 当前科室名称,
  tt.分子四级手术患者死亡率 as 分子四级手术患者死亡率,
  tt.分母三级手术患者死亡率 as 分母三级手术患者死亡率,
  CONCAT(
    tt.分子四级手术患者死亡率,
    ' : ',
    tt.分母三级手术患者死亡率
  ) AS 监测情况,
  'PERCENT_PAIR' AS "结果类型",
  '分子四级手术患者死亡率' AS 分子,
  '分母三级手术患者死亡率' AS 分母,
  CONCAT(
    tt.分子四级手术患者死亡率,
    ' : ',
    tt.分母三级手术患者死亡率
  ) AS "resultVal",
tt.CURRENT_DEPT_ID as "deptId",
tt.CURRENT_DEPT_NAME as "deptName",
tt.分子四级手术患者死亡率 as "numerator",
tt.分母三级手术患者死亡率 as "denominator",
tt.HOSPITAL_AREA_ID as "hospitalAreaId"
from (
select
a.CURRENT_DEPT_ID, a.CURRENT_DEPT_NAME,
CASE WHEN a.四级手术例数 = 0 then '无数据' ELSE CONCAT( CONVERT( numeric ( 19, 2 ), 1.0 * a.四级手术且死亡患者人数 / a.四级手术例数 * 100.0), '%' ) END as 分子四级手术患者死亡率,
CASE WHEN b.三级手术例数 = 0 then '无数据' ELSE CONCAT( CONVERT( numeric ( 19, 2 ), 1.0 * b.三级手术且死亡患者人数 / b.三级手术例数 * 100.0), '%' ) END as 分母三级手术患者死亡率,
'-' AS '目标值',
'-' AS '对比结果',
  a.HOSPITAL_AREA_ID
 from (
SELECT event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME,
COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136619 and event.DEATH_FLAG = 98175 THEN event.ENCOUNTER_ID ELSE NULL END) as 四级手术且死亡患者人数,
  COUNT(CASE WHEN event.SURG_LEVEL_CODE = 136619 THEN event.MRAS_BUSINESS_SUR_GRADE_ID ELSE NULL END) AS 四级手术例数,
   max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
FROM
SUR_GRADE_INFO event
  GROUP BY  event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME
) a
left join (
  SELECT event.CURRENT_DEPT_ID,
  COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136618 and event.DEATH_FLAG = 98175 THEN event.ENCOUNTER_ID ELSE NULL END) as 三级手术且死亡患者人数,
  COUNT(CASE WHEN event.SURG_LEVEL_CODE = 136618 THEN event.MRAS_BUSINESS_SUR_GRADE_ID ELSE NULL END) AS 三级手术例数
FROM
SUR_GRADE_INFO event
  WHERE
  not EXISTS(
  select 1 from MRAS_BUSINESS_SUR_GRADE e where event.ENCOUNTER_ID = e.ENCOUNTER_ID and e.SURG_LEVEL_CODE = 136619 and e.DEATH_FLAG = 98175
  )
  GROUP BY event.CURRENT_DEPT_ID
) b on a.CURRENT_DEPT_ID = b.CURRENT_DEPT_ID
) tt
