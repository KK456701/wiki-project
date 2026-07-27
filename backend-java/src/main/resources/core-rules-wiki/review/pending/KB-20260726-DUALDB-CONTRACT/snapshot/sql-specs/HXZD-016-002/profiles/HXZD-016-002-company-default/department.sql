-- 目标值的数据
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION
    WHERE
TARGET_NO = 'HXZD-008-001'
),
-- 按照科室来进行处理
DeptStats AS (
    SELECT
event.MRAS_DIM_DEPT_ID AS 当前科室编码,
event.MRAS_DIM_DEPT_NAME AS 当前科室名称,
COUNT(CASE WHEN AUTOLOGOUS_BLOOD_TRANS_FLAG = 98175 THEN 1 END) AS 分子术中使用自体血回输总例数,
COUNT(*) AS 分母同期术中输血总例数,
CASE
WHEN COUNT(*) = 0 THEN 0
ELSE COUNT(CASE WHEN AUTOLOGOUS_BLOOD_TRANS_FLAG = 98175 THEN 1 END) / COUNT(*)
END AS 监测情况,
max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
    FROM
MRAS_INDEX_SURGREC event
inner join MRAS_PATIENT_EVENT b WITH (NOLOCK) on event.MRAS_INDEX_ENCOUNTER_ID = b.ENCOUNTER_ID and b.EVENT_NO = 'PatientRecord' and b.IS_DEL=0
WHERE
--布局组件设置提升效率
 event.SURG_END BETWEEN :marptBeginAt and :marptEndAt
AND event.IS_DEL = 0
AND event.INTRA_BLOOD_FLAG = 98175
GROUP BY  event.MRAS_DIM_DEPT_ID,event.MRAS_DIM_DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子术中使用自体血回输总例数,
d.分母同期术中输血总例数,
d.监测情况,
tv.target_value AS 目标值,
CASE
WHEN tv.target_value IS NULL THEN NULL
WHEN d.分母同期术中输血总例数 = 0 THEN '无数据'
WHEN d.监测情况 >= tv.target_value THEN '达标'
ELSE '未达标'
END AS 对比结果,
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期术中输血总例数 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子术中使用自体血回输总例数 as "numerator",
d.分母同期术中输血总例数 as "denominator",
d.HOSPITAL_AREA_ID as "hospitalAreaId"
    FROM
DeptStats d
CROSS JOIN TargetValue tv
    WHERE 1 = 1
-- 达标条件
-- 未达标条件
)
-- 最终查询结果
SELECT * FROM TempResults
