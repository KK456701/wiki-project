---
page_type: sql_original
rule_id: HXZD-002-001
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-26
---

# 原始 SQL 存档：患者入院8小时内查房率

> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。

## 源表 / 事件抽取 SQL

```sql
SELECT t1.ENCOUNTER_ID
AS bizId,
--用来更新数据
t1.FIRST_ADMITTED_TO_WARD_AT
AS eventAt,
-- 预留
'1' AS mrasTargetDefinitionId,
'V2.0' as version,
GETDATE()
AS extractAt,
t1.ENCOUNTER_ID
AS encounterId,
t1.FULL_NAME
AS personName,
t1.IMRN
as imrn,
t1.CURRENT_DEPT_ID
AS currentDeptId,
o1.ORG_NAME
AS currentDeptName,
t1.CURRENT_WARD_ID
AS currentWardId,
o2.ORG_NAME
AS currentWardName,
t1.FIRST_ADMITTED_TO_WARD_AT
AS admittedToWardAt,
t1.DISCHARGED_FROM_WARD_AT
AS wardDischargedAt,
t3.EMPLOYEE_ID
AS currentAdmitterId,
t4.EMPLOYEE_NAME
AS currentAdmitterName,
t1.HOSPITAL_SOID
AS hospitalSoid,
t1.SOURCE_HOSPITAL_AREA_ID
AS hospitalAreaId,
t2.CREATED_AT
AS firstOrderAt,--首次治疗医嘱时间
t5.CREATED_AT
AS unplanOrderAt,--非计划手术医嘱时间
t5.SURGERY_LEVEL_CODE
AS surgeryLevelCode,--手术级别
v.VALUE_DESC
AS surgeryLevelName,--手术级别名称
t5.CS_NAME
AS surgeryName,--手术名称
t6.shouldNum
AS shouldNum,--应完成查房记录数
t6.normalNum
AS normalNum,--按时完成查房记录数
t6.unfinishNum
AS unfinishNum,--未按时完成查房记录数
CASE WHEN t1.IS_DEL = '1' THEN 1 ELSE 0 END AS isDel,
CASE
WHEN DATEDIFF(minute, t1.FIRST_ADMITTED_TO_WARD_AT, t2.PRESCRIBED_AT) < 480 THEN 98175
ELSE 98176 END
AS roundWithinEightHour,--患者入院8小时内查房率
CASE
WHEN t6.shouldNum = t6.normalNum THEN 98175
ELSE 98176 END
AS superiorRoundStandard,
CASE
WHEN t5.CLI_ORDER_ID is not null THEN 98175
ELSE 98176 END
AS unplannedSurgery,
''
AS memo
FROM INPATIENT_ENCOUNTER t1
LEFT JOIN (SELECT t.*,
ROW_NUMBER() OVER (PARTITION BY ENCOUNTER_ID ORDER BY PRESCRIBED_AT ASC) AS rn
FROM INP_CLI_ORDER t
WHERE t.IS_DEL = '0'
AND t.CLI_ORDER_TYPE_CODE IN (256194, 256196, 256197, 256198, 256199, 256200)
AND t.CLI_ORDER_STATUS IN (98435, 98436, 98437, 98438, 959856, 399014726, 399303726, 98442)) t2
ON t1.ENCOUNTER_ID = t2.ENCOUNTER_ID AND t2.rn = 1
left join (SELECT A.*,
B.SURGERY_LEVEL_CODE,
C.CS_NAME,
ROW_NUMBER() OVER (PARTITION BY A.ENCOUNTER_ID ORDER BY A.PRESCRIBED_AT ASC) AS rn
FROM INP_CLI_ORDER A
INNER JOIN INP_SURGICAL_PLAN B ON
B.CLI_ORDER_ID = A.CLI_ORDER_ID
inner join INP_CLI_ORDER_ITEM C on C.CLI_ORDER_ID = A.CLI_ORDER_ID
AND B.UNPLANNED_REOPERATION_FLAG = 98175
AND B.PLANNED_SURGERY_DATE IS NOT NULL
WHERE A.IS_DEL = 0
and B.IS_DEL = 0
AND A.CLI_ORDER_TYPE_CODE = 256200) t5 ON t1.ENCOUNTER_ID = t5.ENCOUNTER_ID and t5.rn=1
left join (select a.ENCOUNTER_ID,
count(1)
as shouldNum,
sum(case
when a.INP_EMR_COMPLETED_STATUS = 399579288 and
a.REMIND_AT >= a.INP_EMR_SET_CREATED_AT then 1
else 0 end)
as normalNum,
sum(case when a.INP_EMR_COMPLETED_STATUS = 399579287 then 1 else 0 end) as unfinishNum,max(a.MODIFIED_AT) as MODIFIED_AT
from INPATIENT_EMR_INCOMPLETE a
where a.IS_DEL = 0
and a.EMR_MRT_MONITOR_ID in (125044676345810978,
125044676345810980,
125044676345810982,
125044676345810984,
125044676345810986,
125044676345810988,
125044676345810990,
125044676345810994
)
group by a.ENCOUNTER_ID) t6 on t1.ENCOUNTER_ID = t6.ENCOUNTER_ID
LEFT JOIN ORGANIZATION o1 ON t1.CURRENT_DEPT_ID = o1.ORG_ID
LEFT JOIN ORGANIZATION o2 ON t1.CURRENT_WARD_ID = o2.ORG_ID
LEFT JOIN INPATIENT_PARTICIPANT t3
ON t1.ENCOUNTER_ID = t3.ENCOUNTER_ID AND t3.IS_DEL = 0 AND t3.INPAT_PARTICIPANT_TYPE_CODE = 1000098
LEFT JOIN EMPLOYEE_INFO t4 ON t3.EMPLOYEE_ID = t4.EMPLOYEE_ID
left join VALUE_SET v on t5.SURGERY_LEVEL_CODE=v.VALUE_ID
WHERE 1 = 1
 AND t1.INPAT_ENC_BIZ_TYPE_CODE != 399552157
 AND t1.ENCOUNTER_ID in (select ENCOUNTER_ID from INPATIENT_ENCOUNTER where DISCHARGED_FROM_WARD_AT BETWEEN :startTime and :endTime)
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
TARGET_NO = 'HXZD-002-001'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
  SELECT
   event.CURRENT_DEPT_ID,
event.CURRENT_DEPT_NAME AS "科室名称",
   COUNT(CASE WHEN ROUND_WITHIN_EIGHT_HOUR = '98175' THEN 1 ELSE NULL END) AS '分子入院8小时内开具检查或治疗医嘱的患者人次数',
   COUNT(1) AS '分母同期入院患者总人次数',
   CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN ROUND_WITHIN_EIGHT_HOUR = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS '监测情况'
  FROM
   MRAS_BUSINESS_WARDROUND event #{NOLOCK}
  WHERE
   --布局组件设置提升效率
   AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
   GROUP BY
event.CURRENT_DEPT_ID, event.CURRENT_DEPT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子入院8小时内开具检查或治疗医嘱的患者人次数) AS "分子入院8小时内开具检查或治疗医嘱的患者人次数",
SUM(分母同期入院患者总人次数) AS "分母同期入院患者总人次数",
CASE
WHEN SUM(分母同期入院患者总人次数) = 0 THEN null
ELSE SUM(分子入院8小时内开具检查或治疗医嘱的患者人次数) * 1.0 / SUM(分母同期入院患者总人次数)
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
WHERE 分母同期入院患者总人次数 > 0
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
TARGET_NO = 'HXZD-002-001'
),
-- 按照科室来进行处理
DeptStats AS (
  SELECT
   event.CURRENT_DEPT_ID AS "当前科室编码",
   event.CURRENT_DEPT_NAME AS "当前科室名称",
   COUNT(CASE WHEN ROUND_WITHIN_EIGHT_HOUR = '98175' THEN 1 ELSE NULL END) AS "分子入院8小时内开具检查或治疗医嘱的患者人次数",
   COUNT(1) AS "分母同期入院患者总人次数",
   CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN ROUND_WITHIN_EIGHT_HOUR = '98175' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS "监测情况" ,
max(event.HOSPITAL_AREA_ID) as HOSPITAL_AREA_ID
  FROM
   MRAS_BUSINESS_WARDROUND event  (NOLOCK)
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
d.分子入院8小时内开具检查或治疗医嘱的患者人次数,
d.分母同期入院患者总人次数,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
   WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母同期入院患者总人次数 = 0 THEN '无数据'
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
  CASE
   WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母同期入院患者总人次数 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子入院8小时内开具检查或治疗医嘱的患者人次数 as "numerator",
d.分母同期入院患者总人次数 as "denominator",
d.HOSPITAL_AREA_ID as "hospitalAreaId"
    FROM
DeptStats d
  WHERE 1 = 1
  -- 达标
  -- 未达标
)
-- 最终查询结果
SELECT * FROM TempResults ;
```

## 目标表－患者明细 SQL

```sql
SELECT
 event.ENCOUNTER_ID ,
 event.CURRENT_DEPT_ID AS '当前科室编码',
 event.CURRENT_DEPT_NAME AS '当前科室',
    event.PERSON_NAME AS '患者姓名',
 event.IMRN AS '住院号',
 event.CURRENT_ADMITTER_NAME AS '责任医师',
 team.ORG_NAME as "TEAM_NAME",
 team.ORG_ID as "TEAM_ID",
  team.ORG_NO as "TEAM_NO",
 team.ORG_NAME as "当前医疗组",
 event.ADMITTED_TO_WARD_AT AS '入区时间',
 event.WARD_DISCHARGED_AT AS '出区时间',
 event.FIRST_ORDER_AT as '首次医嘱开立时间',
 DATEDIFF(HOUR,event.ADMITTED_TO_WARD_AT,event.FIRST_ORDER_AT)  AS '首次医嘱开立时间-入院时间',
 CASE WHEN ROUND_WITHIN_EIGHT_HOUR = '98175' THEN '是' ELSE '否' END AS '是否入院8小时内开具检查或治疗医嘱',
 CASE WHEN ROUND_WITHIN_EIGHT_HOUR = '98175' THEN 98175 ELSE 98176 END AS "standFlag"
FROM
 MRAS_BUSINESS_WARDROUND event  #{NOLOCK}
  LEFT JOIN INPATIENT_ENCOUNTER inp #{NOLOCK}  ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
 LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE
 --布局组件设置提升效率
    AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
```
