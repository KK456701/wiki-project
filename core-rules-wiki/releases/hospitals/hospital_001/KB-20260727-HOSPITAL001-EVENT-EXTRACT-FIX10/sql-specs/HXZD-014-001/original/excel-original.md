---
page_type: sql_original
rule_id: HXZD-014-001
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-27
---

# 原始 SQL 存档：危急值报告时间

> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。

## 源表 / 事件抽取 SQL

```sql
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
```

## 目标表－概览 SQL

```sql
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL / 100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION #{NOLOCK}
    WHERE
TARGET_NO = 'HXZD-014-001'
),
-- 所有记录的时间差计算
AllTimeDiffs AS (
    SELECT
DATEDIFF(minute, PUBLISH_AT, RECEIVE_TIME) AS diff_minutes,
CURRENT_DEPT_NAME AS 科室名称
    FROM MRAS_BUSINESS_CRITICAL_RPT #{NOLOCK}
    WHERE
--布局组件设置提升效率
and ENCOUNTER_TYPE_CODE = '145235'
AND ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt AND :marptEndAt
AND RECEIVE_TIME IS NOT NULL
AND PUBLISH_AT IS NOT NULL
),
-- 全院中位数计算（ROW_NUMBER 方法，兼容所有版本）
HospitalMedian AS (
SELECT
ROUND(AVG(CAST(diff_minutes AS FLOAT)), 2) AS median_minutes,
MAX(total_count) AS sample_count
    FROM (
SELECT
diff_minutes,
ROW_NUMBER() OVER (ORDER BY diff_minutes) AS rn,
COUNT(*) OVER () AS total_count
FROM AllTimeDiffs
    ) ranked
    WHERE rn IN ((total_count + 1) / 2, (total_count + 2) / 2)
),
-- 各科室中位数计算
DeptMedians AS (
    SELECT
科室名称,
ROUND(AVG(CAST(diff_minutes AS FLOAT)), 2) AS median_minutes
    FROM (
SELECT
科室名称,
diff_minutes,
ROW_NUMBER() OVER (PARTITION BY 科室名称 ORDER BY diff_minutes) AS rn,
COUNT(*) OVER (PARTITION BY 科室名称) AS total_count
FROM AllTimeDiffs
    ) ranked
    WHERE rn IN ((total_count + 1) / 2, (total_count + 2) / 2)
    GROUP BY 科室名称
)
-- 最终概览结果
SELECT
    hm.median_minutes AS 监测情况,
    hm.sample_count AS sample_count,
    tv.target_value AS 目标值,
    CASE WHEN hm.median_minutes <= tv.target_value THEN '达标' ELSE '未达标' END AS 达标情况
FROM HospitalMedian hm
CROSS JOIN TargetValue tv
```

## 目标表－科室统计 SQL

```sql
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL / 100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION
    WHERE
TARGET_NO = 'HXZD-014-001'
),
-- 所有记录的时间差计算（整数分钟）
AllTimeDiffs AS (
    SELECT
DATEDIFF(minute, PUBLISH_AT, RECEIVE_TIME) AS diff_minutes,
CURRENT_DEPT_NAME AS 科室名称,
CURRENT_DEPT_ID AS 当前科室编码,
HOSPITAL_AREA_ID AS HOSPITAL_AREA_ID
    FROM MRAS_BUSINESS_CRITICAL_RPT #{NOLOCK}
    WHERE
  --布局组件设置提升效率
and ENCOUNTER_TYPE_CODE = '145235'
AND ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt AND :marptEndAt
AND RECEIVE_TIME IS NOT NULL
AND PUBLISH_AT IS NOT NULL
),
-- 各科室中位数计算（ROW_NUMBER 方法，兼容所有版本）
DeptMedians AS (
    SELECT
科室名称,
HOSPITAL_AREA_ID,
当前科室编码,
ROUND(AVG(CAST(diff_minutes AS FLOAT)), 2) AS 中位数,
(SELECT target_value FROM TargetValue) AS 目标值
    FROM (
SELECT
科室名称,
HOSPITAL_AREA_ID,
当前科室编码,
diff_minutes,
ROW_NUMBER() OVER (PARTITION BY 科室名称 ORDER BY diff_minutes) AS rn,
COUNT(*) OVER (PARTITION BY 科室名称) AS total_count
FROM AllTimeDiffs
    ) ranked
    WHERE rn IN ((total_count + 1) / 2, (total_count + 2) / 2)
    GROUP BY 科室名称, HOSPITAL_AREA_ID, 当前科室编码
)
-- 最终科室维度报表
SELECT
    科室名称 AS 当前科室名称,
    中位数,
    目标值,
    CASE
WHEN 中位数 <= 目标值 THEN '达标'
ELSE '未达标'
    END AS 对比结果,
    CASE
WHEN 中位数 <= 目标值 THEN 98175
ELSE 98176
    END AS "standFlag",
    中位数 AS "resultVal",
    当前科室编码 AS "deptId",
    科室名称 AS "deptName",
    HOSPITAL_AREA_ID AS "hospitalAreaId"
FROM DeptMedians
ORDER BY 对比结果 DESC, 中位数 DESC
```

## 目标表－患者明细 SQL

```sql
SELECT
event.ENCOUNTER_ID ,
event.CURRENT_DEPT_ID AS 当前科室编码,
event.CURRENT_DEPT_NAME AS 当前科室名称,
event.IMRN AS 住院号,
event.PERSON_NAME AS 患者姓名,
event.CURRENT_ADMITTER_NAME AS 责任医师,
event.ADMITTED_TO_WARD_AT AS 入区时间,
event.WARD_DISCHARGED_AT AS 出区时间,
event.CRITICAL_VAL_DTL as 危急值详情,
event.PUBLISH_AT as 危急值报告时间,
event.RECEIVE_TIME as 危急值接收时间,
emp1.EMPLOYEE_NAME as 危急值接收人,
  DATEDIFF(MINUTE,PUBLISH_AT,RECEIVE_TIME) AS "危急值接收时间-报告时间(分钟)"
FROM
MRAS_BUSINESS_CRITICAL_RPT event  #{NOLOCK}
LEFT JOIN EMPLOYEE_INFO emp1 #{NOLOCK}  ON event.RECEIVE_BY = emp1.EMPLOYEE_ID
WHERE
--布局组件设置提升效率
and ENCOUNTER_TYPE_CODE = '145235'
AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
```
