SELECT
t1.ENCOUNTER_ID AS bizId,  --用来更新数据
'CORE_BLOOD_RECORD' as eventNo,
'临床用血后评估记录' as eventName,
t1.ADMITTED_TO_WARD_AT AS eventAt, -- 预留
GETDATE() AS extractAt,
item.CLI_ORDER_ITEM_ID AS mrasTargetDefinitionId,
'V2.0' as version,
t1.ENCOUNTER_ID as encounterId,--就诊标识
t1.FULL_NAME AS personName,--姓名
t1.IMRN as imrn,--住院号
emp.EMPLOYEE_NAME as currentAdmitterName,--责任医师
t1.CURRENT_DEPT_ID AS currentDeptId,--科室
o1.ORG_NAME AS currentDeptName, --科室名称
t1.CURRENT_WARD_ID AS currentWardId,--病区
o2.ORG_NAME AS currentWardName,--病区名称
t1.FIRST_ADMITTED_TO_WARD_AT AS admittedToWardAt, --入区时间
t1.DISCHARGED_FROM_WARD_AT AS wardDischargedAt, --出区时间
a.PRESCRIBED_AT as prescribedAt, -- 开立日期时间
emr.max_submitted_at as recordEndAt, -- 输血记录完成时间
CASE
WHEN emr.ENCOUNTER_ID IS NOT NULL THEN 98175  -- 有输血记录
ELSE 98176  -- 无输血记录
END as transfusionRecordStandard, -- 符合输血治疗后规范书写评估输血记录
null as actualMajorSurgery,-- 符合手术记录
null as autoBloodTran, -- 符合使用自体血回输
item.ORDER_ITEM_CONTENT as bizAttrOneName, -- 医嘱内容
a.HOSPITAL_SOID AS hospitalSoid,
t1.SOURCE_HOSPITAL_AREA_ID AS hospitalAreaId,
0 AS isDel
from INP_CLI_ORDER_ITEM item
LEFT JOIN INP_CLI_ORDER a on a.CLI_ORDER_ID = item.CLI_ORDER_ID
left join INPATIENT_ENCOUNTER t1 on a.ENCOUNTER_ID = t1.ENCOUNTER_ID
LEFT JOIN INPATIENT_PARTICIPANT t3 ON t3.ENCOUNTER_ID = t1.ENCOUNTER_ID AND t3.IS_DEL=0 AND t3.INPAT_PARTICIPANT_TYPE_CODE = 1000098
left join EMPLOYEE_INFO emp on t3.EMPLOYEE_ID = emp.EMPLOYEE_ID and emp.IS_DEL = 0
LEFT JOIN ORGANIZATION o1 ON t1.CURRENT_DEPT_ID = o1.ORG_ID
LEFT JOIN ORGANIZATION o2 ON t1.CURRENT_WARD_ID = o2.ORG_ID
LEFT JOIN (
    SELECT ENCOUNTER_ID, MAX(EMR_SET_SUBMIT_AT) as max_submitted_at
    FROM INPATIENT_EMR_SET
    WHERE IS_DEL = 0
    GROUP BY ENCOUNTER_ID
) emr ON t1.ENCOUNTER_ID = emr.ENCOUNTER_ID
WHERE a.IS_DEL = 0
and a.CLI_ORDER_TYPE_CODE = '384031'
AND a.CLI_ORDER_STATUS in ('98435','399014726','399303726')
AND a.ENCOUNTER_ID in (select ENCOUNTER_ID from INPATIENT_ENCOUNTER where DISCHARGED_FROM_WARD_AT BETWEEN :startTime and :endTime)
