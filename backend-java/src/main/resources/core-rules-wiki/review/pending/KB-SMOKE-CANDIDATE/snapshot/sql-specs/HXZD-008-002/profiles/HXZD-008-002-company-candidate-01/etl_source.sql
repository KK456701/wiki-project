select a.*,
case when b.BIZ_ID is not null then 98175 else 98176 end as preopDiscComplete,
b.EVENT_AT as preOpDiscAt,
c.EVENT_AT as operationAt,
case when b.BIZ_ID is not null and (
select count(*) from MRAS_PATIENT_EVENT t11 where t11.ENCOUNTER_ID = a.encounterId and t11.EVENT_NO = 'EmrOperationRecord' and a.bizId = t11.BIZ_ATTR_TRI_ID and t11.BIZ_ATTR_TWO_ID is not null and b.BIZ_ATTR_TWO_NAME is not null and t11.IS_DEL = 0 and CHARINDEX(CONVERT(VARCHAR, t11.BIZ_ATTR_TWO_ID) + '#', b.BIZ_ATTR_TWO_NAME) > 0
) > 0 then 98175 else 98176 end as preopDiscAttended,
CASE WHEN b.BIZ_ID IS NOT NULL AND (
SELECT COUNT(*) FROM MRAS_PATIENT_EVENT t12 WHERE t12.ENCOUNTER_ID = a.encounterId AND t12.EVENT_NO = 'EmrOperationRecord' AND a.bizId = t12.BIZ_ATTR_TRI_ID AND t12.BIZ_ATTR_ONE_NAME IS NOT NULL AND b.BIZ_ATTR_ONE_NAME IS NOT NULL AND CHARINDEX(t12.BIZ_ATTR_ONE_NAME + ',' , b.BIZ_ATTR_ONE_NAME + ',') > 0 AND t12.IS_DEL = 0
) > 0 THEN 98175 ELSE 98176 END AS preopPlanConsistent,
case when a.planSurgerySurgeonId is not null and (
select count(*) from MRAS_PATIENT_EVENT t13 where t13.ENCOUNTER_ID = a.encounterId and t13.EVENT_NO = 'EmrOperationRecord' and a.bizId = t13.BIZ_ATTR_TRI_ID and t13.IS_DEL = 0 and a.planSurgerySurgeonId = t13.BIZ_ATTR_TWO_ID) > 0 then 98175 else 98176 end as surgeonConsistent,
REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(b.BIZ_ATTR_ONE_NAME, '0', ''), '1', ''), '2', ''), '3', ''), '4', ''),
    '5', ''), '6', ''), '7', ''), '8', ''), '9', ''), '#', '') as preOpName,
c.BIZ_ATTR_ONE_NAME AS emrOpName,
d.EMPLOYEE_NAME as surgerySurgeon,
'V2.0' as version
 from (
select
t1.BIZ_ID AS bizId,
t1.EVENT_AT eventAt,
GETDATE() AS extractAt,
'1' AS mrasTargetDefinitionId,
t1.ENCOUNTER_ID AS encounterId,
t2.PERSON_NAME AS personName,
t2.BIZ_ATTR_ONE_NO as imrn,
t2.DEPT_ID AS currentDeptId,
t2.DEPT_NAME AS currentDeptName,
t2.WARD_ID AS currentWardId,
t2.WARD_NAME AS currentWardName,
t2.EVENT_AT AS admittedToWardAt,
t2.EVENT_END_AT AS dischargedFromWardAt,
t2.EMPLOYEE_NAME AS currentAdmitterName,
t1.BIZ_ATTR_ONE_NAME as opName,
t1.BIZ_ATTR_TWO_NO as surgeryClassCode,
t4.VALUE_DESC as surgeryLevelName,
t1.EVENT_END_AT AS surgeryEndAt,
t1.IS_DEL as isDel,
t1.HOSPITAL_SOID as hospitalSoid,
t1.BIZ_ATTR_ONE_ID as planSurgerySurgeonId,
t3.EMPLOYEE_NAME as planSurgerySurgeon,
(SELECT a1.BIZ_ID FROM MRAS_PATIENT_EVENT a1
    where a1.ENCOUNTER_ID = t1.ENCOUNTER_ID and DATEDIFF(HOUR, t1.EVENT_AT, a1.EVENT_AT) <= 48
    and a1.EVENT_NO = 'PreoperativeDisc' and a1.IS_DEL = 0
    order by a1.EVENT_AT desc
    OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY) as PRE_BIZ_ID,
(SELECT a2.BIZ_ID FROM MRAS_PATIENT_EVENT a2
    where a2.ENCOUNTER_ID = t1.ENCOUNTER_ID and t1.BIZ_ID = a2.BIZ_ATTR_TRI_ID
    and a2.EVENT_NO = 'EmrOperationRecord' and a2.IS_DEL = 0
    order by a2.EVENT_AT desc
    OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY) as EMR_BIZ_ID
from MRAS_PATIENT_EVENT t1
inner join MRAS_PATIENT_EVENT t2 on t1.ENCOUNTER_ID = t2.ENCOUNTER_ID and t2.EVENT_NO = 'PatientRecord' and t2.IS_DEL = 0
left join EMPLOYEE_INFO t3 on t1.BIZ_ATTR_ONE_ID  = t3.EMPLOYEE_ID
LEFT JOIN VALUE_SET t4 on t1.BIZ_ATTR_TWO_NO = t4.VALUE_ID and t4.CODE_SYSTEM_ID = 64655
where t1.EVENT_NO = 'OperationRecord' and t1.IS_DEL = 0 and t1.BIZ_ATTR_ONE_COST = 98175
AND t2.EVENT_END_AT BETWEEN :start_time and :end_time
) a
left join MRAS_PATIENT_EVENT b on a.encounterId = b.ENCOUNTER_ID and b.EVENT_NO = 'PreoperativeDisc' and a.PRE_BIZ_ID = b.BIZ_ID and b.IS_DEL = 0
left join MRAS_PATIENT_EVENT c on a.encounterId = c.ENCOUNTER_ID and c.EVENT_NO = 'EmrOperationRecord' and a.EMR_BIZ_ID = c.BIZ_ID and c.IS_DEL = 0
left join EMPLOYEE_INFO d on c.BIZ_ATTR_TWO_ID  = d.EMPLOYEE_ID
