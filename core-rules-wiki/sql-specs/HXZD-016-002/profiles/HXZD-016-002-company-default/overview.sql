 WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL / 100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION
    WHERE
TARGET_NO = 'HXZD-016-002'
),
DeptOrderStats AS (
    SELECT
event.MRAS_DIM_DEPT_ID,
event.MRAS_DIM_DEPT_NAME AS 科室名称,
COUNT(CASE WHEN AUTOLOGOUS_BLOOD_TRANS_FLAG = 98175 THEN 1 ELSE NULL END) AS 分子术中使用自体血回输总例数,
COUNT(1) AS 分母同期术中输血总例数,
CASE
WHEN COUNT(*) = 0 THEN 0
ELSE COUNT(CASE WHEN AUTOLOGOUS_BLOOD_TRANS_FLAG = 98175 THEN 1 END) / COUNT(*)
END AS 监测情况
    FROM
MRAS_INDEX_SURGREC event #{NOLOCK}
inner join MRAS_PATIENT_EVENT b #{NOLOCK} on event.MRAS_INDEX_ENCOUNTER_ID = b.ENCOUNTER_ID and b.EVENT_NO = 'PatientRecord' and b.IS_DEL=0
WHERE
--布局组件设置提升效率
AND event.SURG_END BETWEEN :marptBeginAt and :marptEndAt
AND event.IS_DEL = 0
AND event.INTRA_BLOOD_FLAG = 98175
GROUP BY
event.MRAS_DIM_DEPT_ID, event.MRAS_DIM_DEPT_NAME
),
TotalStats AS (
    SELECT
SUM(d.分子术中使用自体血回输总例数) AS 分子术中使用自体血回输总例数,
SUM(d.分母同期术中输血总例数) AS 分母同期术中输血总例数,
CASE
WHEN SUM(d.分母同期术中输血总例数) = 0 THEN 0
ELSE SUM(d.分子术中使用自体血回输总例数) / SUM(d.分母同期术中输血总例数)
END AS 监测情况
    FROM DeptOrderStats d
)
SELECT
    t.*,
    tv.target_value AS 目标值,
    CASE WHEN t.监测情况 < tv.target_value THEN '否' ELSE '是' END AS 是否达标,
STUFF((
SELECT ', ' + 科室名称
FROM DeptOrderStats
WHERE 分母同期术中输血总例数 > 0
AND 监测情况 < (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS "未达标科室列表"
FROM TotalStats t
CROSS JOIN TargetValue tv
