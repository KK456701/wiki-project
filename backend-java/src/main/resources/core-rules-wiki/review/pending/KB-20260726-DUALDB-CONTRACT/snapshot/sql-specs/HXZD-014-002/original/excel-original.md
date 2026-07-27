---
page_type: sql_original
rule_id: HXZD-014-002
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-26
---

# 原始 SQL 存档：住院患者危急值当日及时处置率

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
--查询出目标值，各个指标编码是固定的
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION #{NOLOCK}
    WHERE
TARGET_NO = 'HXZD-014-002'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
SELECT
event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME AS "科室名称",
COUNT(CASE WHEN CV_COMPLETED = '98175' THEN 1 ELSE NULL END) AS '分子当日处置的住院患者危急值项目数',
COUNT(1) AS '分母同期临床科室接获住院患者危急值项目数',
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN CV_COMPLETED = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS '监测情况'
FROM
MRAS_BUSINESS_CRITICAL_RPT event #{NOLOCK}
WHERE
--布局组件设置提升效率
AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子当日处置的住院患者危急值项目数) AS "分子当日处置的住院患者危急值项目数",
SUM(分母同期临床科室接获住院患者危急值项目数) AS "分母同期临床科室接获住院患者危急值项目数",
CASE
WHEN SUM(分母同期临床科室接获住院患者危急值项目数) = 0 THEN 0
ELSE SUM(分子当日处置的住院患者危急值项目数) * 1.0 / SUM(分母同期临床科室接获住院患者危急值项目数)
END AS "监测情况",
(SELECT target_value FROM TargetValue) AS "目标值"
    FROM DeptOrderStats
)
-- 进行数据输出
SELECT
    t.*,
CASE WHEN t.监测情况 >= t.目标值 THEN '否' ELSE '是' END AS "是否达标",
    STUFF((
SELECT ', ' + 科室名称
FROM DeptOrderStats
WHERE 分母同期临床科室接获住院患者危急值项目数 > 0
AND 监测情况 >= (SELECT target_value FROM TargetValue)
FOR XML PATH('')
    ), 1, 2, '') AS "未达标科室列表"
FROM TotalStats t;
```

## 目标表－科室统计 SQL

```sql
--目标值的数据
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION (NOLOCK)
    WHERE
TARGET_NO = 'HXZD-014-002'
),
-- 按照科室来进行处理
DeptStats AS (
SELECT
event.CURRENT_DEPT_ID AS "当前科室编码",
event.CURRENT_DEPT_NAME AS "当前科室名称",
COUNT(CASE WHEN CV_COMPLETED = '98175' THEN 1 ELSE NULL END) AS "分子当日处置的住院患者危急值项目数",
COUNT(1) AS "分母同期临床科室接获住院患者危急值项目数",
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN CV_COMPLETED = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS "监测情况" ,
max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
FROM
MRAS_BUSINESS_CRITICAL_RPT event  (NOLOCK)
WHERE
--布局组件设置提升效率
AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
GROUP BY  event.CURRENT_DEPT_ID,event.CURRENT_DEPT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子当日处置的住院患者危急值项目数,
d.分母同期临床科室接获住院患者危急值项目数,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期临床科室接获住院患者危急值项目数 = 0 THEN '无数据'
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期临床科室接获住院患者危急值项目数 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子当日处置的住院患者危急值项目数 as "numerator",
d.分母同期临床科室接获住院患者危急值项目数 as "denominator",
d.HOSPITAL_AREA_ID as "hospitalAreaId"
    FROM
DeptStats d
WHERE 1 = 1
-- 达标
-- 未达标
)
-- 最终查询结果
SELECT * FROM TempResults
```

## 目标表－患者明细 SQL

```sql
SELECT
event.ENCOUNTER_ID ,
event.CURRENT_DEPT_ID AS 当前科室编码,
event.CURRENT_DEPT_NAME AS 当前科室,
event.IMRN AS 住院号,
event.PERSON_NAME AS 患者姓名,
event.CURRENT_ADMITTER_NAME AS 责任医师,
team.ORG_NAME as "TEAM_NAME",
team.ORG_ID as "TEAM_ID",
team.ORG_NO as "TEAM_NO",
team.ORG_NAME as "当前医疗组",
event.ADMITTED_TO_WARD_AT AS 入区时间,
event.WARD_DISCHARGED_AT AS 出区时间,
event.CRITICAL_VAL_DTL as 危急值详情,
emp3.EMPLOYEE_NAME as 危急值发布人,
event.RECEIVE_TIME as 危急值接收时间,
emp1.EMPLOYEE_NAME as 危急值接收人,
event.PROCESS_TIME as 危急值处置时间,
emp2.EMPLOYEE_NAME as 处置人,
CASE
WHEN event.PROCESS_TIME IS NULL THEN NULL  -- 如果未处置，返回NULL
ELSE DATEDIFF(HOUR, event.RECEIVE_TIME, event.PROCESS_TIME)  -- 计算小时差
END AS "危急值处置时间-危急值接收时间（小时）",
CASE WHEN CV_COMPLETED = '98175' THEN '是' ELSE '否' END AS 是否当日处置,
CASE WHEN CV_COMPLETED = '98175' THEN '是' ELSE '否' END AS 是否达标,
CASE WHEN CV_COMPLETED = '98175' THEN 98175 ELSE 98176 END AS "standFlag"
FROM
MRAS_BUSINESS_CRITICAL_RPT event  #{NOLOCK}
LEFT JOIN INPAT_TRANSFER t1  #{NOLOCK}  ON event.ENCOUNTER_ID = t1.ENCOUNTER_ID
LEFT JOIN ORGANIZATION o1  #{NOLOCK}   ON t1.ORIGIN_DEPT_ID = o1.ORG_ID
LEFT JOIN ORGANIZATION o2  #{NOLOCK}  ON t1.DESTINATION_DEPT_ID = o2.ORG_ID
LEFT JOIN EMPLOYEE_INFO emp1   #{NOLOCK}  ON event.RECEIVE_BY = emp1.EMPLOYEE_ID
LEFT JOIN EMPLOYEE_INFO emp2   #{NOLOCK}  ON event.PROCESS_BY = emp2.EMPLOYEE_ID
LEFT JOIN EMPLOYEE_INFO emp3 #{NOLOCK}  ON event.REPORT_BY = emp3.EMPLOYEE_ID
LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
--布局组件设置提升效率
AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
```
