WITH SUR_GRADE_INFO AS (
select t.* from (
select event.*,
ROW_NUMBER() OVER (PARTITION BY event.ENCOUNTER_ID ORDER BY event.SURG_LEVEL_CODE, event.SURGERY_START_AT desc) AS rn
from MRAS_BUSINESS_SUR_GRADE event
where
--布局组件设置提升效率
AND event.IS_DEL = 0
AND event.VERSION = 'V2.0'
AND event.SURG_LEVEL_CODE IN (136619, 136618)
AND event.EVENT_AT BETWEEN :marptBeginAt and :marptEndAt
) t WHERE t.rn = 1
)
SELECT
tt.CURRENT_DEPT_ID AS 当前科室编码,
tt.CURRENT_DEPT_NAME AS 当前科室名称,
tt.四级手术并发症患者数 as 四级手术并发症人数,
tt.四级手术患者数 as 四级手术人数,
tt.分子四级手术并发症发生率 AS 分子四级手术并发症发生率,
tt.三级手术并发症患者数 as 三级手术并发症人数,
tt.三级手术患者数 as 三级手术人数,
tt.分母三级手术并发症发生率 as 分母三级手术并发症发生率,
CONCAT(
tt.分子四级手术并发症发生率,
' : ',
tt.分母三级手术并发症发生率
) AS 监测情况,
'PERCENT_PAIR' AS "结果类型",
'分子四级手术并发症发生率' AS 分子,
'分母三级手术并发症发生率' AS 分母,
98175 AS "standFlag",
CONCAT(
tt.分子四级手术并发症发生率,
' : ',
tt.分母三级手术并发症发生率
) AS "resultVal",
tt.CURRENT_DEPT_ID as "deptId",
tt.CURRENT_DEPT_NAME as "deptName",
tt.分子四级手术并发症发生率 as "numerator",
tt.分母三级手术并发症发生率 as "denominator",
tt.HOSPITAL_AREA_ID as "hospitalAreaId"
from (
select
a.CURRENT_DEPT_ID, a.CURRENT_DEPT_NAME,
a.四级手术并发症患者数,
a.四级手术患者数,
CASE WHEN a.四级手术患者数 = 0 then '无数据' ELSE CONCAT(CONVERT( numeric ( 19, 2 ), 1.0 * a.四级手术并发症患者数 / a.四级手术患者数 * 100), '%' ) END as 分子四级手术并发症发生率,
b.三级手术并发症患者数,
b.三级手术患者数,
CASE WHEN b.三级手术患者数 = 0 then '无数据' ELSE CONCAT(CONVERT( numeric ( 19, 2 ), 1.0 * b.三级手术并发症患者数 / b.三级手术患者数 * 100), '%' ) END as 分母三级手术并发症发生率,
'-' AS '目标值',
'-' AS '对比结果',
  a.HOSPITAL_AREA_ID
 from (
SELECT event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME,
COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136619 and comp.MRAS_PATIENT_EVENT_ID IS NOT NULL THEN event.ENCOUNTER_ID ELSE NULL END) as 四级手术并发症患者数,
  COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136619 THEN event.ENCOUNTER_ID ELSE NULL END) AS 四级手术患者数,
   max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
FROM
SUR_GRADE_INFO event
LEFT JOIN MRAS_PATIENT_EVENT comp on event.ENCOUNTER_ID = comp.ENCOUNTER_ID and comp.EVENT_NO = 'MahpComplication' and comp.IS_DEL = 0
GROUP BY  event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME
) a
left join (
  SELECT event.CURRENT_DEPT_ID,
  COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136618 and comp.MRAS_PATIENT_EVENT_ID IS NOT NULL THEN event.ENCOUNTER_ID ELSE NULL END) as 三级手术并发症患者数,
  COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136618 THEN event.ENCOUNTER_ID ELSE NULL END) AS 三级手术患者数
FROM
SUR_GRADE_INFO event
LEFT JOIN MRAS_PATIENT_EVENT comp on event.ENCOUNTER_ID = comp.ENCOUNTER_ID and comp.EVENT_NO = 'MahpComplication' and comp.IS_DEL = 0
  WHERE
  not EXISTS(
select 1 from MRAS_BUSINESS_SUR_GRADE e
LEFT JOIN MRAS_PATIENT_EVENT f on e.ENCOUNTER_ID = f.ENCOUNTER_ID and f.EVENT_NO = 'MahpComplication' and f.IS_DEL = 0
where event.ENCOUNTER_ID = e.ENCOUNTER_ID and e.SURG_LEVEL_CODE = 136619 and f.MRAS_PATIENT_EVENT_ID IS NOT NULL
  )
  GROUP BY event.CURRENT_DEPT_ID
) b on a.CURRENT_DEPT_ID = b.CURRENT_DEPT_ID
) tt
