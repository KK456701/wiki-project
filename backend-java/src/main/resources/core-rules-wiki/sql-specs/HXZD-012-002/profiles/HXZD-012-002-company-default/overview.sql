 WITH SUR_GRADE_INFO AS (
select * from MRAS_BUSINESS_SUR_GRADE
where
--布局组件设置提升效率
AND IS_DEL = 0
AND VERSION = 'V2.0'
AND SURG_LEVEL_CODE IN (136619, 136618)
AND EVENT_AT BETWEEN :start_time and :end_time
)
select
CASE WHEN tt.四级手术患者死亡率 IS NULL THEN '无数据'
     ELSE CONCAT(CONVERT(numeric(19,2), tt.四级手术患者死亡率), '%') END
     as "分子-四级手术患者死亡率",
CASE WHEN tt.三级手术患者死亡率 IS NULL THEN '无数据'
     ELSE CONCAT(CONVERT(numeric(19,2), tt.三级手术患者死亡率), '%') END
     as "分母-三级手术患者死亡率",
CASE WHEN tt.四级手术患者死亡率 IS NULL
          OR tt.三级手术患者死亡率 IS NULL
          OR tt.三级手术患者死亡率 = 0
     THEN NULL
     ELSE tt.四级手术患者死亡率 / tt.三级手术患者死亡率
END as index_value,
CONCAT(
    CASE WHEN tt.四级手术患者死亡率 IS NULL THEN '无数据'
         ELSE CONCAT(CONVERT(numeric(19,2), tt.四级手术患者死亡率), '%') END,
    ' : ',
    CASE WHEN tt.三级手术患者死亡率 IS NULL THEN '无数据'
         ELSE CONCAT(CONVERT(numeric(19,2), tt.三级手术患者死亡率), '%') END
) as 监测情况
from (
select
CASE WHEN a.四级手术例数 = 0 THEN NULL
     ELSE 1.0 * a.四级手术且死亡患者人数 / a.四级手术例数 * 100 END
     as 四级手术患者死亡率,
CASE WHEN b.三级手术例数 = 0 THEN NULL
     ELSE 1.0 * b.三级手术且死亡患者人数 / b.三级手术例数 * 100 END
     as 三级手术患者死亡率
 from (
SELECT
COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136619 and event.DEATH_FLAG = 98175 THEN event.ENCOUNTER_ID ELSE NULL END) as 四级手术且死亡患者人数,
  COUNT(CASE WHEN event.SURG_LEVEL_CODE = 136619 THEN event.MRAS_BUSINESS_SUR_GRADE_ID ELSE NULL END) AS 四级手术例数
FROM
SUR_GRADE_INFO event
) a
left join (
  SELECT
  COUNT(DISTINCT CASE WHEN event.SURG_LEVEL_CODE = 136618 and event.DEATH_FLAG = 98175 THEN event.ENCOUNTER_ID ELSE NULL END) as 三级手术且死亡患者人数,
  COUNT(CASE WHEN event.SURG_LEVEL_CODE = 136618 THEN event.MRAS_BUSINESS_SUR_GRADE_ID ELSE NULL END) AS 三级手术例数
FROM
SUR_GRADE_INFO event
  WHERE
  not EXISTS(
  select 1 from MRAS_BUSINESS_SUR_GRADE e where event.ENCOUNTER_ID = e.ENCOUNTER_ID and e.SURG_LEVEL_CODE = 136619 and e.DEATH_FLAG = 98175
  )
) b on 1=1
) tt
