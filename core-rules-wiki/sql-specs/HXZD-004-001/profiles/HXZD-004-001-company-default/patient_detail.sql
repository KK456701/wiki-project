SELECT
 event.ENCOUNTER_ID ,
 event.CURRENT_DEPT_ID AS '当前科室编码',
 event.CURRENT_DEPT_NAME AS '当前科室',
 event.IMRN AS '住院号',
 event.PERSON_NAME AS "患者姓名",
 event.CURRENT_ADMITTER_NAME AS '责任医师',
 team.ORG_NAME as "TEAM_NAME",
 team.ORG_ID as "TEAM_ID",
 team.ORG_NO as "TEAM_NO",
 team.ORG_NAME as "当前医疗组",
 event.ADMITTED_TO_WARD_AT AS '入区时间',
 event.WARD_DISCHARGED_AT AS '出区时间',
 event.SURGERY_NAME AS '手术名称',
 event.SURGERY_AT AS '手术完成时间',
  event.CARE_LEVEL as '出区时护理级别'
FROM
 MRAS_BUSINESS_GRADED_CARE event  #{NOLOCK}
   LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
 LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
 --布局组件设置提升效率
    AND event.WARD_DISCHARGED_AT BETWEEN :marptBeginAt and :marptEndAt
