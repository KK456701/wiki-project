SELECT DISTINCT
b.ENCOUNTER_ID AS bizId,  --用来更新数据
'CORE_CV_RPT' as eventNo,
'危急值报告' as eventName,
inp.ADMITTED_TO_WARD_AT AS eventAt, -- 预留
GETDATE() AS extractAt,
'1' AS mrasTargetDefinitionId,
'V2.0' as version,
b.ENCOUNTER_ID as encounterId,--就诊标识
inp.FULL_NAME as personName, -- 患者姓名
b.ENCOUNTER_TYPE_CODE as encounterTypeCode,--患者类型
inp.CURRENT_DEPT_ID as currentDeptId,--科室
org1.ORG_NAME as currentDeptName,--科室名称
inp.CURRENT_WARD_ID as currentWardId,--病区
org2.ORG_NAME as currentWardName,--病区名称
inp.IMRN as imrn, --住院号
emp.EMPLOYEE_NAME as currentAdmitterName,--责任医师
inp.FIRST_ADMITTED_TO_WARD_AT as admittedToWardAt,--入区时间
inp.DISCHARGED_FROM_WARD_AT as wardDischargedAt,--出区时间
convert(varchar,b.CV_CONTENT) as criticalValDtl,--危急值详情
a.ACTION_AT as publishAt,--发布时间
a.ACTION_BY as reportBy,
hd.ACTION_BY as processBy,--处理医师
hd.ACTION_AT as processTime,--处理时间
case when a.ACTION_AT > hd.ACTION_AT then 0 else datediff(second,a.ACTION_AT,hd.ACTION_AT) end as processDuration, --医生处理时长
hd.CV_REPLY_CONTENT as processIssue,--处理描述
acc.ACTION_BY as receiveBy,--接收护士
acc.ACTION_AT as receiveTime,--接收时间
CASE
WHEN hd.ACTION_AT IS NOT NULL AND a.ACTION_AT IS NOT NULL
AND DATEDIFF(HOUR, a.ACTION_AT, hd.ACTION_AT) <= 24
THEN 98175  -- 24小时内完成
WHEN hd.ACTION_AT IS NOT NULL AND a.ACTION_AT IS NOT NULL
AND DATEDIFF(HOUR, a.ACTION_AT, hd.ACTION_AT) > 24
THEN 98176  -- 超过24小时完成
ELSE 98176   -- 未处理或时间信息不全
END as cvCompleted, --是否当日完成
a.HOSPITAL_SOID AS hospitalSoid,
  inp.SOURCE_HOSPITAL_AREA_ID AS hospitalAreaId,
  CASE WHEN a.IS_DEL = '1'  THEN 1 ELSE 0 END AS isDel
FROM
CV_REPORT_ACTION_LOG a
LEFT JOIN CRITICAL_VALUE_REPORT b ON a.CV_REPORT_ID = b.CV_REPORT_ID
LEFT JOIN CV_REPORT_ACTION_LOG acc ON b.CV_REPORT_ID = acc.CV_REPORT_ID AND acc.CV_ACTIVITY_CODE = 399282032 AND acc.OPERATOR_ROLE_CODE = 399282083 --护士
LEFT JOIN CV_REPORT_ACTION_LOG hd ON b.CV_REPORT_ID = hd.CV_REPORT_ID AND hd.CV_ACTIVITY_CODE = 399282032 AND hd.OPERATOR_ROLE_CODE IN ( 399282060, 399282082 ) --医嘱开立医师 ，医师
LEFT JOIN INPATIENT_ENCOUNTER inp on b.ENCOUNTER_ID = inp.ENCOUNTER_ID
LEFT JOIN INPATIENT_PARTICIPANT t3 ON t3.ENCOUNTER_ID = b.ENCOUNTER_ID AND t3.IS_DEL=0 AND t3.INPAT_PARTICIPANT_TYPE_CODE = 1000098
left join EMPLOYEE_INFO emp on t3.EMPLOYEE_ID = emp.EMPLOYEE_ID and emp.IS_DEL = 0
left join ORGANIZATION org1 on inp.CURRENT_DEPT_ID = org1.ORG_ID and org1.IS_DEL = 0
left join ORGANIZATION org2 on inp.CURRENT_WARD_ID = org2.ORG_ID and org2.IS_DEL = 0
WHERE
a.CV_ACTIVITY_CODE = 399282029
  and not exists(select 1 from CV_REPORT_ACTION_LOG t where t.CV_REPORT_ID=a.CV_REPORT_ID and t.CV_ACTIVITY_CODE=399282030 and t.IS_DEL=0)
AND b.ENCOUNTER_ID IS NOT NULL
AND a.IS_DEL = 0
AND b.ENCOUNTER_ID in (select ENCOUNTER_ID from INPATIENT_ENCOUNTER where DISCHARGED_FROM_WARD_AT BETWEEN :startTime and :endTime)
