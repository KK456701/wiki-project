---
page_type: sql_original
rule_id: HXZD-013-001
source_status: raw_imported
executable: false
contains_unresolved_tokens: true
updated_at: 2026-07-27
---

# 原始 SQL 存档：新技术新项目留存转化率

> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。

## 目标表－概览 SQL

```sql
--查询出目标值，各个指标编码是固定的
WITH TargetValue AS (
    SELECT
TARGET_COMP_VAL/100.0 AS target_value
    FROM
MRAS_TARGET_DEFINITION #{NOLOCK}
    WHERE
TARGET_NO = 'HXZD-013-001'
),
-- 按照科室来进行分组查询，用来查询哪个科室不达标
DeptOrderStats AS (
    SELECT
TEMP.UNIT_CODE AS '科室编码',
TEMP.UNIT_NAME AS '科室名称',
COUNT(CASE WHEN PROC_DEL = '1' THEN 1 ELSE NULL END) AS '分子',
COUNT(1) AS '分母',
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN PROC_DEL = '1' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS '监测情况'
FROM
(SELECT
pro.*,
CASE
WHEN EXISTS (
SELECT 1
FROM MRAS_MEDTECH_PROC p2
WHERE p2.MRAS_MEDTECH_PRO_ID = pro.MRAS_MEDTECH_PRO_ID
AND p2.IS_DEL = 0
AND p2.CREATED_AT BETWEEN :marptBeginAt AND :marptEndAt
) THEN '1'
ELSE '0'
END AS PROC_DEL
FROM
MRAS_MEDTECH_PRO pro
WHERE
--布局组件设置提升效率
AND pro.IS_DEL = 0
-- 关键修改：将主表时间+2年与开始参数比较，+1年与结束参数比较
AND DATEADD(YEAR, 2, pro.CREATED_AT) >= :marptBeginAt
AND DATEADD(YEAR, 1, pro.CREATED_AT) <= :marptEndAt) TEMP
WHERE 1 = 1
GROUP BY  TEMP.UNIT_CODE,TEMP.UNIT_NAME
),
-- 用来处理总数
TotalStats AS (
    SELECT
SUM(分子) AS "分子",
SUM(分母) AS "分母",
CASE
WHEN SUM(分母) = 0 THEN null
ELSE SUM(分子) * 1.0 / SUM(分母)
END AS "监测情况",
(SELECT target_value FROM TargetValue) AS "目标值"
    FROM DeptOrderStats
)
-- 进行数据输出
SELECT
    t.*,
CASE WHEN t.监测情况 < t.目标值 THEN '否' ELSE '是' END AS "是否达标",
    STUFF((
SELECT ', ' + 科室名称
FROM DeptOrderStats
WHERE 分母 > 0
AND 监测情况 < (SELECT target_value FROM TargetValue)
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
TARGET_NO = 'HXZD-013-001'
),
-- 按照科室来进行处理
DeptStats AS (
    SELECT
TEMP.UNIT_CODE AS '当前科室编码',
TEMP.UNIT_NAME AS '当前科室名称',
COUNT(CASE WHEN PROC_DEL = '1' THEN 1 ELSE NULL END) AS '分子',
COUNT(1) AS '分母',
CASE WHEN COUNT(1) = 0 THEN 0  ELSE COUNT(CASE WHEN PROC_DEL = '1' THEN 1 ELSE NULL END) * 1.0 / COUNT(1) * 1.0 END AS '监测情况'
FROM
(SELECT
pro.*,
CASE
WHEN EXISTS (
SELECT 1
FROM MRAS_MEDTECH_PROC p2
WHERE p2.MRAS_MEDTECH_PRO_ID = pro.MRAS_MEDTECH_PRO_ID
AND p2.IS_DEL = 0
AND p2.CREATED_AT BETWEEN :marptBeginAt AND :marptEndAt
) THEN '1'
ELSE '0'
END AS PROC_DEL
FROM
MRAS_MEDTECH_PRO pro
WHERE
--布局组件设置提升效率
AND pro.IS_DEL = 0
-- 关键修改：将主表时间+2年与开始参数比较，+1年与结束参数比较
AND DATEADD(YEAR, 2, pro.CREATED_AT) >= :marptBeginAt
AND DATEADD(YEAR, 1, pro.CREATED_AT) <= :marptEndAt) TEMP
WHERE 1 = 1
GROUP BY  TEMP.UNIT_CODE,TEMP.UNIT_NAME
),
-- 条件进一步筛选
TempResults AS (
    SELECT
d.当前科室编码,
d.当前科室名称,
d.分子,
d.分母,
d.监测情况,
(SELECT target_value FROM TargetValue) AS "目标值",
CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
WHEN d.分母 = 0 THEN '无数据'
WHEN d.监测情况 >= (SELECT target_value FROM TargetValue) THEN '达标'
ELSE '未达标'
END AS "对比结果",
  CASE
WHEN (SELECT target_value FROM TargetValue) IS NULL THEN 98176
WHEN d.分母 = 0 THEN 98176
WHEN d.监测情况 < (SELECT target_value FROM TargetValue) THEN 98175
ELSE 98176
END AS "standFlag",
  d.监测情况 as "resultVal",
d.当前科室编码 as "deptId",
d.当前科室名称 as "deptName",
d.分子 as "numerator",
d.分母 as "denominator"
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
e2.ENCOUNTER_ID,
pro.MEDTECH_PRO_NO AS "新技术项目编码",
pro.MEDTECH_PRO_NAME AS "新技术项目名称",
pro.CREATED_AT AS "新技术项目新增时间",
'是' AS "第四年是否开展",
p2.CURRENT_DEPT_NAME AS "当前科室",
p2.PERSON_NAME AS "患者姓名",
e2.IMRN AS "住院号",
t4.EMPLOYEE_NAME AS "责任医师",
team.ORG_NAME as "TEAM_NAME",
team.ORG_ID as "TEAM_ID",
team.ORG_NO as "TEAM_NO",
team.ORG_NAME as "当前医疗组",
e2.ADMITTED_TO_WARD_AT AS "入区时间",
e2.DISCHARGED_FROM_WARD_AT AS "出区时间",
98175 AS "standFlag"
FROM
    MRAS_MEDTECH_PRO pro
LEFT JOIN MRAS_MEDTECH_PROC p2 ON p2.MRAS_MEDTECH_PRO_ID = pro.MRAS_MEDTECH_PRO_ID  AND p2.IS_DEL = 0
LEFT JOIN INPATIENT_ENCOUNTER e2 ON p2.ENCOUNTER_ID = e2.ENCOUNTER_ID
-- 责任医生
LEFT JOIN INPATIENT_PARTICIPANT t3 ON e2.ENCOUNTER_ID = t3.ENCOUNTER_ID AND  t3.IS_DEL = 0 AND  t3.INPAT_PARTICIPANT_TYPE_CODE = 1000098
LEFT JOIN EMPLOYEE_INFO t4 ON t3.EMPLOYEE_ID = t4.EMPLOYEE_ID
LEFT JOIN MRAS_ORGANIZATION team #{NOLOCK}  ON team.ORG_ID = e2.CURRENT_MEDICAL_GROUP_ID
WHERE
--布局组件设置提升效率
    AND pro.IS_DEL = 0
    AND DATEADD(YEAR, 2, pro.CREATED_AT) >= :marptBeginAt
AND DATEADD(YEAR, 1, pro.CREATED_AT) <= :marptEndAt
AND p2.CREATED_AT BETWEEN :marptBeginAt AND :marptEndAt
```
