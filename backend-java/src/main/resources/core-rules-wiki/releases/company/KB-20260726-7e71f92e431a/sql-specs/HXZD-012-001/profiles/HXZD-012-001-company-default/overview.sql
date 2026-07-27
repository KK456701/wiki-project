WITH SUR_GRADE_INFO AS (
select * from MRAS_BUSINESS_SUR_GRADE
where
--布局组件设置提升效率
AND IS_DEL = 0
AND VERSION = 'V2.0'
AND SURG_LEVEL_CODE IN (136619, 136618)
AND SURGERY_END_AT BETWEEN :start_time and :end_time
)
select tt."分子-四级手术并发症发生率", tt."分母-三级手术并发症发生率", CONCAT(tt."分子-四级手术并发症发生率", ' : ', tt."分母-三级手术并发症发生率") as 监测情况 from (
select
CONCAT(CONVERT(numeric(19,2), CASE WHEN a.四级手术患者数 = 0 then 0 ELSE 1.0 * a.四级手术并发症患者数 / a.四级手术患者数 * 100 END), '%') as "分子-四级手术并发症发生率",
CONCAT(CONVERT(numeric(19,2), CASE WHEN b.三级手术患者数 = 0 then 0 ELSE 1.0 * b.三级手术并发症患者数 / b.三级手术患者数 * 100 END), '%') as "分母-三级手术并发症发生率"
 from (
SELECT
COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136619 and comp.MRAS_PATIENT_EVENT_ID IS NOT NULL THEN event.ENCOUNTER_ID ELSE NULL END) as 四级手术并发症患者数,
  COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136619 THEN event.ENCOUNTER_ID ELSE NULL END) AS 四级手术患者数
FROM
SUR_GRADE_INFO event
  LEFT JOIN MRAS_PATIENT_EVENT comp on event.ENCOUNTER_ID = comp.ENCOUNTER_ID and comp.EVENT_NO = 'MahpComplication' and comp.IS_DEL = 0
) a
left join (
  SELECT
  COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136618 and  comp.MRAS_PATIENT_EVENT_ID IS NOT NULL THEN event.ENCOUNTER_ID ELSE NULL END) as 三级手术并发症患者数,
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
) b on 1=1
) tt
