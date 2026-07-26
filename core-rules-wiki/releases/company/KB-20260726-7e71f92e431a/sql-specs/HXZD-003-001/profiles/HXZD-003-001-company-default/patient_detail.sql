 SELECT
 event.ENCOUNTER_ID ,
 v.VALUE_DESC AS '患者类型',
 event.CURRENT_DEPT_ID AS '当前科室编码',
 event.CURRENT_DEPT_NAME AS '当前科室',
 event.IMRN AS '住院号/就诊号',
 event.PERSON_NAME AS '患者姓名',
 event.CURRENT_ADMITTER_NAME AS '责任医师/就诊医生',
 team.ORG_NAME as "TEAM_NAME",
 team.ORG_ID as "TEAM_ID",
 team.ORG_NO as "TEAM_NO",
 team.ORG_NAME as "当前医疗组",
 event.ADMITTED_TO_WARD_AT AS '入区时间/就诊时间',
 event.WARD_DISCHARGED_AT AS '出区时间',
 event.APPLY_CONSULT_EMPLOYEE_NAME as '会诊发起医师',
  event.APPLY_CONSULT_AT as '会诊发起时间',
    event.SIGNED_AT as '会诊到位时间',
event.FINISH_AT as '会诊完成时间',
 cast(DATEDIFF(second,event.APPLY_CONSULT_AT,event.SIGNED_AT)* 1.0 / 60 as decimal(19, 2))  AS '会诊到位时间-会诊发起时间(分钟)',
 CASE WHEN EMERGENCY_CONSULT_TIMELY = '98175' THEN '是' ELSE '否' END AS '是否达标',
 CASE WHEN EMERGENCY_CONSULT_TIMELY = '98175' THEN 98175 ELSE 98176 END AS "standFlag"
FROM
 MRAS_BUSINESS_CONSULTATION event  WITH (NOLOCK)
 left join VALUE_SET v on event.ENCOUNTER_TYPE_CODE=VALUE_ID
   LEFT JOIN INPATIENT_ENCOUNTER inp WITH (NOLOCK)  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
 LEFT JOIN MRAS_ORGANIZATION team WITH (NOLOCK)  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
 --布局组件设置提升效率
    AND event.ADMITTED_TO_WARD_AT BETWEEN :start_time and :end_time
   and event.COURSE_LEVEL_CODE=977578
