/*
 * HXZD-001-001 患者入院48小时内转科的比例
 * 手工验证区间：2025-01-01 00:00:00（含）至 2025-04-01 00:00:00（不含）
 *
 * 使用说明：
 * 1. SQL 1 在业务源库执行，只预览待抽取数据，不会写入真实库。
 * 2. SQL 2～4 在试运行真实库 winex_aima 执行。
 * 3. 四段 SQL 应分别执行；每段都单独声明了时间变量。
 */

/* ========================================================================== */
/* SQL 1：源表 / 事件抽取 SQL（在业务源库执行）                               */
/* ========================================================================== */

DECLARE @ExtractBeginAt datetime2(0) = '2025-01-01T00:00:00';
DECLARE @ExtractEndAt   datetime2(0) = '2025-04-01T00:00:00';

SELECT DISTINCT
    t1.ENCOUNTER_ID AS bizId,
    t1.FIRST_ADMITTED_TO_WARD_AT AS eventAt,
    GETDATE() AS extractAt,
    '1' AS mrasTargetDefinitionId,
    'V2.0' AS version,
    t1.ENCOUNTER_ID AS encounterId,
    t1.FULL_NAME AS personName,
    t1.IMRN AS imrn,
    COALESCE(t2.ORIGIN_DEPT_ID, t1.CURRENT_DEPT_ID) AS currentDeptId,
    o1.ORG_NAME AS currentDeptName,
    COALESCE(t2.ORIGIN_WARD_ID, t1.CURRENT_WARD_ID) AS currentWardId,
    o2.ORG_NAME AS currentWardName,
    t1.FIRST_ADMITTED_TO_WARD_AT AS admittedToWardAt,
    t1.DISCHARGED_FROM_WARD_AT AS wardDischargedAt,
    t3.EMPLOYEE_ID AS currentAdmitterId,
    t4.EMPLOYEE_NAME AS currentAdmitterName,
    t1.HOSPITAL_SOID AS hospitalSoid,
    t1.SOURCE_HOSPITAL_AREA_ID AS hospitalAreaId,
    CASE WHEN t1.IS_DEL = '1' THEN 1 ELSE 0 END AS isDel,
    CASE
        WHEN DATEDIFF(HOUR, t1.FIRST_ADMITTED_TO_WARD_AT, t2.INPAT_TRANSFER_AT) < 48
            THEN 98175
        ELSE 98176
    END AS transferWithinTwoDay,
    '' AS memo
FROM INPATIENT_ENCOUNTER t1
LEFT JOIN (
    SELECT
        t.*,
        ROW_NUMBER() OVER (
            PARTITION BY t.ENCOUNTER_ID
            ORDER BY t.CREATED_AT ASC
        ) AS rn
    FROM INPAT_TRANSFER t
    WHERE t.IS_DEL = '0'
      AND (
          t.INPAT_TRANSFER_TYPE_CODE = '399549991'
          OR (
              t.INPAT_TRANSFER_TYPE_CODE = '399549990'
              AND t.ORIGIN_DEPT_ID <> t.DESTINATION_DEPT_ID
          )
      )
      AND t.ORIGIN_DEPT_ID NOT IN (
          SELECT ORG_ID
          FROM ORGANIZATION
          WHERE ORG_NO IN (
              '12800000', '42800000', '42800200', '31301', '22800000',
              '33802', '34001', '22800100', '22800200', '42800100',
              '12800200', '27401', '12800100'
          )
      )
      AND t.ORIGIN_WARD_ID NOT IN (
          SELECT ORG_ID
          FROM ORGANIZATION
          WHERE ORG_NO IN (
              '12800000', '42800000', '42800200', '31301', '22800000',
              '33802', '34001', '22800100', '22800200', '42800100',
              '12800200', '27401', '12800100'
          )
      )
      AND t.DESTINATION_DEPT_ID NOT IN (
          SELECT ORG_ID
          FROM ORGANIZATION
          WHERE ORG_NO IN (
              '12800000', '42800000', '42800200', '31301', '22800000',
              '33802', '34001', '22800100', '22800200', '42800100',
              '12800200', '27401', '12800100'
          )
      )
      AND t.DESTINATION_WARD_ID NOT IN (
          SELECT ORG_ID
          FROM ORGANIZATION
          WHERE ORG_NO IN (
              '12800000', '42800000', '42800200', '31301', '22800000',
              '33802', '34001', '22800100', '22800200', '42800100',
              '12800200', '27401', '12800100'
          )
      )
) t2
    ON t1.ENCOUNTER_ID = t2.ENCOUNTER_ID
   AND t2.rn = 1
LEFT JOIN ORGANIZATION o1
    ON COALESCE(t2.ORIGIN_DEPT_ID, t1.CURRENT_DEPT_ID) = o1.ORG_ID
LEFT JOIN ORGANIZATION o2
    ON COALESCE(t2.ORIGIN_WARD_ID, t1.CURRENT_WARD_ID) = o2.ORG_ID
LEFT JOIN INPATIENT_PARTICIPANT t3
    ON t1.ENCOUNTER_ID = t3.ENCOUNTER_ID
   AND t3.IS_DEL = 0
   AND t3.INPAT_PARTICIPANT_TYPE_CODE = 1000098
LEFT JOIN EMPLOYEE_INFO t4
    ON t3.EMPLOYEE_ID = t4.EMPLOYEE_ID
WHERE t1.INPAT_ENC_BIZ_TYPE_CODE <> 399552157
  AND t1.ENCOUNTER_ID IN (
      SELECT encounter_scope.ENCOUNTER_ID
      FROM INPATIENT_ENCOUNTER encounter_scope
      WHERE encounter_scope.DISCHARGED_FROM_WARD_AT >= @ExtractBeginAt
        AND encounter_scope.DISCHARGED_FROM_WARD_AT < @ExtractEndAt
  );


/* ========================================================================== */
/* SQL 2：全院概览 SQL（在 winex_aima 执行）                                  */
/* ========================================================================== */

DECLARE @OverviewBeginAt datetime2(0) = '2025-01-01T00:00:00';
DECLARE @OverviewEndAt   datetime2(0) = '2025-04-01T00:00:00';

WITH TargetValue AS (
    SELECT TARGET_COMP_VAL / 100.0 AS target_value
    FROM MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE TARGET_NO = 'HXZD-001-001'
),
DeptOrderStats AS (
    SELECT
        event.CURRENT_DEPT_ID,
        event.CURRENT_DEPT_NAME AS [科室名称],
        COUNT(CASE
            WHEN event.TRANSFER_WITHIN_TWO_DAY = '98175' THEN 1
        END) AS [分子入院48小时内转科患者人次数],
        COUNT(1) AS [分母同期入院患者总人次数],
        CASE
            WHEN COUNT(1) = 0 THEN 0
            ELSE COUNT(CASE
                WHEN event.TRANSFER_WITHIN_TWO_DAY = '98175' THEN 1
            END) * 1.0 / COUNT(1)
        END AS [监测情况]
    FROM MRAS_BUSINESS_FIRSTVISIT event WITH (NOLOCK)
    WHERE event.ADMITTED_TO_WARD_AT >= @OverviewBeginAt
      AND event.ADMITTED_TO_WARD_AT < @OverviewEndAt
    GROUP BY
        event.CURRENT_DEPT_ID,
        event.CURRENT_DEPT_NAME
),
TotalStats AS (
    SELECT
        SUM([分子入院48小时内转科患者人次数])
            AS [分子入院48小时内转科患者人次数],
        SUM([分母同期入院患者总人次数])
            AS [分母同期入院患者总人次数],
        CASE
            WHEN SUM([分母同期入院患者总人次数]) = 0 THEN NULL
            ELSE SUM([分子入院48小时内转科患者人次数]) * 1.0
                 / SUM([分母同期入院患者总人次数])
        END AS [监测情况],
        (SELECT target_value FROM TargetValue) AS [目标值]
    FROM DeptOrderStats
)
SELECT
    t.*,
    CASE
        WHEN t.[监测情况] >= t.[目标值] THEN '否'
        ELSE '是'
    END AS [是否达标],
    STUFF((
        SELECT ', ' + d.[科室名称]
        FROM DeptOrderStats d
        WHERE d.[分母同期入院患者总人次数] > 0
          AND d.[监测情况] >= (SELECT target_value FROM TargetValue)
        FOR XML PATH('')
    ), 1, 2, '') AS [未达标科室列表]
FROM TotalStats t;


/* ========================================================================== */
/* SQL 3：科室统计 SQL（在 winex_aima 执行）                                  */
/* ========================================================================== */

DECLARE @DeptBeginAt datetime2(0) = '2025-01-01T00:00:00';
DECLARE @DeptEndAt   datetime2(0) = '2025-04-01T00:00:00';

WITH TargetValue AS (
    SELECT TARGET_COMP_VAL / 100.0 AS target_value
    FROM MRAS_TARGET_DEFINITION WITH (NOLOCK)
    WHERE TARGET_NO = 'HXZD-001-001'
),
DeptStats AS (
    SELECT
        event.CURRENT_DEPT_ID AS [当前科室编码],
        event.CURRENT_DEPT_NAME AS [当前科室名称],
        COUNT(CASE
            WHEN event.TRANSFER_WITHIN_TWO_DAY = '98175' THEN 1
        END) AS [分子入院48小时内转科患者人次数],
        COUNT(1) AS [分母同期入院患者总人次数],
        CASE
            WHEN COUNT(1) = 0 THEN 0
            ELSE COUNT(CASE
                WHEN event.TRANSFER_WITHIN_TWO_DAY = '98175' THEN 1
            END) * 1.0 / COUNT(1)
        END AS [监测情况],
        MAX(event.HOSPITAL_AREA_ID) AS [HOSPITAL_AREA_ID]
    FROM MRAS_BUSINESS_FIRSTVISIT event WITH (NOLOCK)
    WHERE event.ADMITTED_TO_WARD_AT >= @DeptBeginAt
      AND event.ADMITTED_TO_WARD_AT < @DeptEndAt
    GROUP BY
        event.CURRENT_DEPT_ID,
        event.CURRENT_DEPT_NAME
),
TempResults AS (
    SELECT
        d.[当前科室编码],
        d.[当前科室名称],
        d.[分子入院48小时内转科患者人次数],
        d.[分母同期入院患者总人次数],
        d.[监测情况],
        (SELECT target_value FROM TargetValue) AS [目标值],
        CASE
            WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
            WHEN d.[分母同期入院患者总人次数] = 0 THEN '无数据'
            WHEN d.[监测情况] < (SELECT target_value FROM TargetValue) THEN '达标'
            ELSE '未达标'
        END AS [对比结果],
        CASE
            WHEN (SELECT target_value FROM TargetValue) IS NULL THEN NULL
            WHEN d.[分母同期入院患者总人次数] = 0 THEN 98176
            WHEN d.[监测情况] < (SELECT target_value FROM TargetValue) THEN 98175
            ELSE 98176
        END AS [standFlag],
        d.[监测情况] AS [resultVal],
        d.[当前科室编码] AS [deptId],
        d.[当前科室名称] AS [deptName],
        d.[分子入院48小时内转科患者人次数] AS [numerator],
        d.[分母同期入院患者总人次数] AS [denominator],
        d.[HOSPITAL_AREA_ID] AS [hospitalAreaId]
    FROM DeptStats d
)
SELECT *
FROM TempResults
ORDER BY [当前科室名称];


/* ========================================================================== */
/* SQL 4：患者明细 SQL（在 winex_aima 执行）                                  */
/* ========================================================================== */

DECLARE @PatientBeginAt datetime2(0) = '2025-01-01T00:00:00';
DECLARE @PatientEndAt   datetime2(0) = '2025-04-01T00:00:00';

SELECT
    event.ENCOUNTER_ID,
    event.ENCOUNTER_ID AS [ROWNUM],
    event.CURRENT_DEPT_ID AS [当前科室编码],
    event.CURRENT_DEPT_NAME AS [当前科室],
    event.IMRN AS [住院号],
    event.PERSON_NAME AS [患者姓名],
    event.CURRENT_ADMITTER_NAME AS [责任医师],
    team.ORG_NAME AS [TEAM_NAME],
    team.ORG_ID AS [TEAM_ID],
    team.ORG_NO AS [TEAM_NO],
    team.ORG_NAME AS [当前医疗组],
    event.ADMITTED_TO_WARD_AT AS [入区时间],
    event.WARD_DISCHARGED_AT AS [出区时间],
    t1.INPAT_TRANSFER_AT AS [转科时间],
    DATEDIFF(
        HOUR,
        event.ADMITTED_TO_WARD_AT,
        t1.INPAT_TRANSFER_AT
    ) AS [转科时间-入院时间],
    o1.ORG_NAME AS [转出科室],
    o2.ORG_NAME AS [转入科室],
    CASE
        WHEN event.TRANSFER_WITHIN_TWO_DAY = 98175
         AND DATEDIFF(
             HOUR,
             event.ADMITTED_TO_WARD_AT,
             t1.INPAT_TRANSFER_AT
         ) < 48
            THEN '是'
        ELSE '否'
    END AS [是否48小时内转科],
    CASE
        WHEN event.TRANSFER_WITHIN_TWO_DAY = 98175
         AND DATEDIFF(
             HOUR,
             event.ADMITTED_TO_WARD_AT,
             t1.INPAT_TRANSFER_AT
         ) < 48
            THEN 98175
        ELSE 98175
    END AS [standFlag]
FROM MRAS_BUSINESS_FIRSTVISIT event WITH (NOLOCK)
LEFT JOIN INPAT_TRANSFER t1 WITH (NOLOCK)
    ON event.ENCOUNTER_ID = t1.ENCOUNTER_ID
LEFT JOIN ORGANIZATION o1 WITH (NOLOCK)
    ON t1.ORIGIN_DEPT_ID = o1.ORG_ID
LEFT JOIN ORGANIZATION o2 WITH (NOLOCK)
    ON t1.DESTINATION_DEPT_ID = o2.ORG_ID
LEFT JOIN INPATIENT_ENCOUNTER inp WITH (NOLOCK)
    ON event.ENCOUNTER_ID = inp.ENCOUNTER_ID
LEFT JOIN MRAS_ORGANIZATION team WITH (NOLOCK)
    ON team.ORG_ID = inp.CURRENT_MEDICAL_GROUP_ID
WHERE event.ADMITTED_TO_WARD_AT >= @PatientBeginAt
  AND event.ADMITTED_TO_WARD_AT < @PatientEndAt
  AND t1.INPAT_TRANSFER_ID IS NOT NULL
  AND (
      t1.INPAT_TRANSFER_TYPE_CODE = '399549991'
      OR (
          t1.INPAT_TRANSFER_TYPE_CODE = '399549990'
          AND t1.ORIGIN_DEPT_ID <> t1.DESTINATION_DEPT_ID
      )
  )
  AND t1.IS_DEL = 0
  AND event.VERSION = 'V2.0'
  AND event.IS_DEL = 0
ORDER BY
    event.CURRENT_DEPT_NAME,
    event.ENCOUNTER_ID,
    t1.INPAT_TRANSFER_AT;
