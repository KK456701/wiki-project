select a.*,
t6.INP_EMR_SET_CREATED_AT as preopMdtCompletedAt,
case when t6.INP_EMR_SET_ID is not null then 98175 else 98176 end as preopMdtCompleted,
145235 as encounterTypeCode,
0 as mrasBusinessIndexId,
'V2.0' as version
from (
select
t1.MAHP_DMTS_ICD_OPERATION_ID AS bizId,
t2.EVENT_END_AT AS eventAt,
GETDATE() AS extractAt,
'1' AS mrasTargetDefinitionId,
b.ENCOUNTER_ID AS encounterId,
t2.PERSON_NAME AS personName,
t2.BIZ_ATTR_ONE_NO AS imrn,
t2.DEPT_ID AS currentDeptId,
t2.DEPT_NAME AS currentDeptName,
t2.WARD_ID AS currentWardId,
t2.WARD_NAME AS currentWardName,
t2.EVENT_AT AS admittedToWardAt,
t2.EVENT_END_AT AS dischargedFromWardAt,
t2.EMPLOYEE_NAME AS currentAdmitterName,
cs.CLIBASIC_SURGERY_ID as surgeryId,
t1.OPERATION_NAME as surgName,
t1.OPERATION_GRADE_CODE as surgLevelCode,
s4.VALUE_DESC as surgLevelName,
t1.OPERATION_PERFORMED_AT AS surgeryStartAt,
t1.OPERATION_PERFORMED_AT AS surgeryEndAt,
case when b.DISCHARGE_TYPE_CODE = 136924 then 98175 else 98176 end as deathFlag,
case when b.DISCHARGE_TYPE_CODE = 136924 then b.DISCHARGED_AT else null end as deathAt,
t2.HOSPITAL_SOID as hospitalSOID,
t1.IS_DEL as isDel,
case when t1.OPERATION_GRADE_CODE = 136619 then
(SELECT TOP 1 a1.INP_EMR_SET_ID FROM INPATIENT_EMR_SET a1
    where a1.ENCOUNTER_ID = b.ENCOUNTER_ID and DATEDIFF(HOUR, a1.CREATED_AT, t1.OPERATION_PERFORMED_AT) <= 48
    and a1.INP_MRT_MONITOR_ID=125044678493294640 and a1.INP_EMR_SET_TITLE like '%多学科%' and a1.IS_DEL = 0 and a1.INP_EMR_STATUS not in (960074,390030405)
    order by a1.INP_EMR_SET_CREATED_AT desc) else null end as INP_EMR_SET_ID
from MAHP_DMTS_ICD_OPERATION t1
LEFT join MAHP_DMTS_MAIN b on t1.MAHP_DMTS_ID = b.MAHP_DMTS_ID and b.IS_DEL = 0
inner JOIN CLIBASIC_SURGERY cs on t1.OPERATION_CODE = cs.SURGERY_CODE and cs.SURGERY_DIR_NO in ('957348', '957347')
INNER JOIN MRAS_PATIENT_EVENT t2 ON b.ENCOUNTER_ID = t2.ENCOUNTER_ID AND t2.EVENT_NO = 'PatientRecord' AND t2.IS_DEL = 0
LEFT JOIN VALUE_SET s4 on t1.OPERATION_GRADE_CODE = s4.VALUE_ID and s4.CODE_SYSTEM_ID = 64655
WHERE 1 = 1 and t1.OPERATION_GRADE_CODE in (136618, 136619)
AND t2.EVENT_END_AT BETWEEN :startTime and :endTime
) a
LEFT JOIN INPATIENT_EMR_SET t6 on a.INP_EMR_SET_ID = t6.INP_EMR_SET_ID
