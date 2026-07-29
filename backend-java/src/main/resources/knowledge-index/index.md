# knowledge-index-v60

> 医疗质量安全核心制度落实情况监测指标知识库
>
> 基于 `skills/knowledgeskill6` v6.0.0 | 2026-07-29 | 数据源：AI指标顾问【AI Metrics Advisor】7.xls
>
> **编码体系：** HXZD-XXX-XXX · **概念分类：** 按指标名称分类 · 35项指标 · 45个衍生实体 · 45个血缘解析

---

## 35 项指标概念页（按指标名称分类）

- [[concepts/HXZD-001-001患者入院48小时内转科的比例]]
- [[concepts/HXZD-002-001if object_id('tempdb..#TEMP_DIAG') is not null
DROP TABLE #TEMP_DIAG

-- 创建临时表
CREATE TABLE #TEMP_DIAG (
    DIAG_NO VARCHAR(64)
)

-- 插入数据
INSERT INTO #TEMP_DIAG VALUES ('I26.9'),('I80.2'),('I82.8'),('A40.0'),('A40.1'),('A40.2'),('A40.3'),('A40.4'),('A40.5'),('A40.6'),('A40.7'),('A40.8'),('A40.9'),('A41.0'),('A41.9'),('T81.411'),('T81.0'),('T81.1'),('T81.2'),('T81.3'),('T81.4'),('T81.5'),('T81.6'),('T81.7'),('T81.8'),('T81.9'),('R96.0'),('R96.1'),('I46.1'),('J96.0'),('J96.1'),('J96.9'),('E89.9'),('T88.5'),('J95.1'),('J95.9'),('J98.4'),('N17.0'),('N17.1'),('N17.2'),('N17.3'),('N17.4'),('N17.5'),('N17.6'),('N17.7'),('N17.8'),('N17.9'),('N99.0'),('K91.0'),('K91.1'),('K91.2'),('K91.3'),('K91.4'),('K91.5'),('K91.6'),('K91.7'),('K91.8'),('K91.9'),('I97.0'),('I97.1'),('I97.8'),('I97.9'),('G97.0'),('G97.1'),('G97.2'),('G97.8'),('G97.9'),('I6.0'),('I6.1'),('I6.2'),('I6.3'),('I6.4'),('H59.0'),('H59.8'),('H59.9'),('H95.0'),('H95.1'),('H95.8'),('H95.9'),('M96.0'),('M96.1'),('M96.2'),('M96.3'),('M96.4'),('M96.5'),('M96.6'),('M96.7'),('M96.8'),('M96.9'),('N98.0'),('N98.2'),('N98.1'),('N98.3'),('N98.8'),('N98.9'),('N99.0'),('N99.1'),('N99.2'),('N99.3'),('N99.4'),('N99.5'),('N99.6'),('N99.7'),('N99.8'),('N99.9'),('K11.4'),('S04.3'),('S04.5'),('T82.0'),('T82.1'),('T82.2'),('T82.3'),('T82.4'),('T82.5'),('T82.6'),('T82.7'),('T82.8'),('T82.9'),('T83.0'),('T83.1'),('T83.2'),('T83.3'),('T83.4'),('T83.5'),('T83.6'),('T83.7'),('T83.8'),('T83.9'),('T84.0'),('T84.1'),('T84.2'),('T84.3'),('T84.4'),('T84.5'),('T84.6'),('T84.7'),('T84.8'),('T84.9'),('T85.0'),('T85.1'),('T85.2'),('T85.3'),('T85.4'),('T85.5'),('T85.6'),('T85.7'),('T85.8'),('T85.9'),('T86.0'),('T86.1'),('T86.2'),('T86.3'),('T86.4'),('T86.5'),('T86.6'),('T86.7'),('T86.8'),('T86.9'),('T87.0'),('T87.1'),('T87.2'),('T87.3'),('T87.4'),('T87.5'),('T87.6'),('T87.7'),('T87.8'),('T87.9'),('J93.8'),('J93.9'),('J95.8'),('T81.218');


with TEMP_COMPLICATION AS (
SELECT 
t3.ENCOUNTER_ID,t1.DIAGNOSIS_NAME,t1.DIAGNOSED_AT,t1.HOSPITAL_SOID,t1.IS_DEL
FROM MAHP_DMTS_ICD_DIAGNOSIS t1, MAHP_DMTS_MAIN t2, INPATIENT_ENCOUNTER t3, #TEMP_DIAG t4 
where 1=1
and t1.MAHP_DMTS_ID = t2.MAHP_DMTS_ID 
and t2.ENCOUNTER_ID = t3.ENCOUNTER_ID
AND t3.INPAT_ENC_BIZ_TYPE_CODE != 399552157
#ETC{AND t3.CURRENT_DEPT_ID NOT IN (:exDeptSet)}
#ETC{AND t3.FULL_NAME NOT LIKE :exPatientSet}
#EQUALS{:syncType; increment; AND (t1.MODIFIED_AT BETWEEN :startTime AND :endTime or t2.MODIFIED_AT BETWEEN :startTime AND :endTime or t3.MODIFIED_AT BETWEEN :startTime AND :endTime)}
#EQUALS{:syncType; single; AND (t3.DISCHARGED_FROM_WARD_AT is null or t3.DISCHARGED_FROM_WARD_AT BETWEEN :startTime and :endTime)}
#EQUALS{:syncType; outHosp; AND t3.DISCHARGED_FROM_WARD_AT BETWEEN :startTime and :endTime}
and t1.ADMISSION_CONDITION_NO = '4' 
and t1.DIAGNOSIS_TYPE_CODE in (957005, 957006)
and t1.DIAGNOSIS_NO like t4.DIAG_NO + '%'
),
TEMP_COMPLICATION_GROUP AS (
SELECT 
ENCOUNTER_ID AS bizId,
ENCOUNTER_ID AS encounterId,
min(DIAGNOSED_AT) AS eventAt,
min(DIAGNOSED_AT) AS eventEndAt,
GETDATE() AS extractAt,
min(HOSPITAL_SOID) as hospitalSoid,
IS_DEL AS isDel,
STUFF((
   SELECT ',' + DIAGNOSIS_NAME
   FROM TEMP_COMPLICATION AS h2
   WHERE h2.ENCOUNTER_ID = h1.ENCOUNTER_ID
   ORDER BY DIAGNOSIS_NAME
   FOR XML PATH(''), TYPE).value('.', 'NVARCHAR(MAX)'), 1, 1, '') AS bizAttrOneName
FROM TEMP_COMPLICATION AS h1
GROUP BY ENCOUNTER_ID, IS_DEL
)

select * from (
    select * from TEMP_COMPLICATION_GROUP
    where isDel = 0
    union all
    select t1.* from TEMP_COMPLICATION_GROUP t1
    where t1.isDel = 1 
        and not exists (
        select 1 from TEMP_COMPLICATION_GROUP t2 where t2.isDel = 0 and t1.encounterId = t2.encounterId
        )
) t]]
- [[concepts/HXZD-002-002上级医师查房记录规范率]]
- [[concepts/HXZD-002-003住院患者非计划手术率]]
- [[concepts/HXZD-003-001急会诊及时到位率]]
- [[concepts/HXZD-003-002急会诊有效率]]
- [[concepts/HXZD-003-003普通会诊及时完成率]]
- [[concepts/HXZD-003-004普通会诊有效率]]
- [[concepts/HXZD-004-001手术患者特级护理_一级护理出院率]]
- [[concepts/HXZD-005-001四级手术患者手术当日床旁交接班占比]]
- [[concepts/HXZD-006-001非计划再次住院_手术患者疑难病例讨论完成率]]
- [[concepts/HXZD-006-002非计划再次住院_手术患者疑难病例讨论记录完整率]]
- [[concepts/HXZD-006-003高额异常费用患者进行疑难病例讨论的占比]]
- [[concepts/HXZD-007-001急危重症患者抢救成功率]]
- [[concepts/HXZD-008-001术前讨论完成率]]
- [[concepts/HXZD-008-002术者参加术前讨论率]]
- [[concepts/HXZD-008-003术前讨论计划手术一致率]]
- [[concepts/HXZD-008-004实际手术术者与计划手术术者一致率]]
- [[concepts/HXZD-009-001死亡病例讨论5日完成率]]
- [[concepts/HXZD-009-002医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值]]
- [[concepts/HXZD-009-003科主任主持死亡病例讨论率]]
- [[concepts/HXZD-009-004死亡患者病案上传率]]
- [[concepts/HXZD-010-001长期医嘱当日终止率]]
- [[concepts/HXZD-011-001手术医师手术时间重合率]]
- [[concepts/HXZD-011-002麻醉医师手术时间重合率]]
- [[concepts/HXZD-012-001四级手术与三级手术并发症发生率比]]
- [[concepts/HXZD-012-002四级手术与三级手术患者死亡率比]]
- [[concepts/HXZD-012-003四级手术术前多学科讨论完成率]]
- [[concepts/HXZD-012-004三、四级手术实际开展率]]
- [[concepts/HXZD-013-001新技术新项目留存转化率]]
- [[concepts/HXZD-014-001危急值报告时间]]
- [[concepts/HXZD-014-002住院患者危急值当日及时处置率]]
- [[concepts/HXZD-015-001特殊使用级抗菌药物使用会诊率]]
- [[concepts/HXZD-016-001临床用血后评估记录率]]
- [[concepts/HXZD-016-002术中自体血回输率]]

---

## 45 个衍生指标实体页

| [[entities/HXZD-001-001_患者入院48小时内转科的比例_入区时间.md|HXZD-001-001]] | 患者入院48小时内转科的比例 | [[concepts/HXZD-001-001患者入院48小时内转科的比例]] |
| [[entities/HXZD-002-001_患者入院8小时内查房率.md|HXZD-002-001]] | if object_id('tempdb..#TEMP_DIAG') is not null
DROP TABLE #TEMP_DIAG

-- 创建临时表
CREATE TABLE #TEMP_DIAG (
    DIAG_NO VARCHAR(64)
)

-- 插入数据
INSERT INTO #TEMP_DIAG VALUES ('I26.9'),('I80.2'),('I82.8'),('A40.0'),('A40.1'),('A40.2'),('A40.3'),('A40.4'),('A40.5'),('A40.6'),('A40.7'),('A40.8'),('A40.9'),('A41.0'),('A41.9'),('T81.411'),('T81.0'),('T81.1'),('T81.2'),('T81.3'),('T81.4'),('T81.5'),('T81.6'),('T81.7'),('T81.8'),('T81.9'),('R96.0'),('R96.1'),('I46.1'),('J96.0'),('J96.1'),('J96.9'),('E89.9'),('T88.5'),('J95.1'),('J95.9'),('J98.4'),('N17.0'),('N17.1'),('N17.2'),('N17.3'),('N17.4'),('N17.5'),('N17.6'),('N17.7'),('N17.8'),('N17.9'),('N99.0'),('K91.0'),('K91.1'),('K91.2'),('K91.3'),('K91.4'),('K91.5'),('K91.6'),('K91.7'),('K91.8'),('K91.9'),('I97.0'),('I97.1'),('I97.8'),('I97.9'),('G97.0'),('G97.1'),('G97.2'),('G97.8'),('G97.9'),('I6.0'),('I6.1'),('I6.2'),('I6.3'),('I6.4'),('H59.0'),('H59.8'),('H59.9'),('H95.0'),('H95.1'),('H95.8'),('H95.9'),('M96.0'),('M96.1'),('M96.2'),('M96.3'),('M96.4'),('M96.5'),('M96.6'),('M96.7'),('M96.8'),('M96.9'),('N98.0'),('N98.2'),('N98.1'),('N98.3'),('N98.8'),('N98.9'),('N99.0'),('N99.1'),('N99.2'),('N99.3'),('N99.4'),('N99.5'),('N99.6'),('N99.7'),('N99.8'),('N99.9'),('K11.4'),('S04.3'),('S04.5'),('T82.0'),('T82.1'),('T82.2'),('T82.3'),('T82.4'),('T82.5'),('T82.6'),('T82.7'),('T82.8'),('T82.9'),('T83.0'),('T83.1'),('T83.2'),('T83.3'),('T83.4'),('T83.5'),('T83.6'),('T83.7'),('T83.8'),('T83.9'),('T84.0'),('T84.1'),('T84.2'),('T84.3'),('T84.4'),('T84.5'),('T84.6'),('T84.7'),('T84.8'),('T84.9'),('T85.0'),('T85.1'),('T85.2'),('T85.3'),('T85.4'),('T85.5'),('T85.6'),('T85.7'),('T85.8'),('T85.9'),('T86.0'),('T86.1'),('T86.2'),('T86.3'),('T86.4'),('T86.5'),('T86.6'),('T86.7'),('T86.8'),('T86.9'),('T87.0'),('T87.1'),('T87.2'),('T87.3'),('T87.4'),('T87.5'),('T87.6'),('T87.7'),('T87.8'),('T87.9'),('J93.8'),('J93.9'),('J95.8'),('T81.218');


with TEMP_COMPLICATION AS (
SELECT 
t3.ENCOUNTER_ID,t1.DIAGNOSIS_NAME,t1.DIAGNOSED_AT,t1.HOSPITAL_SOID,t1.IS_DEL
FROM MAHP_DMTS_ICD_DIAGNOSIS t1, MAHP_DMTS_MAIN t2, INPATIENT_ENCOUNTER t3, #TEMP_DIAG t4 
where 1=1
and t1.MAHP_DMTS_ID = t2.MAHP_DMTS_ID 
and t2.ENCOUNTER_ID = t3.ENCOUNTER_ID
AND t3.INPAT_ENC_BIZ_TYPE_CODE != 399552157
#ETC{AND t3.CURRENT_DEPT_ID NOT IN (:exDeptSet)}
#ETC{AND t3.FULL_NAME NOT LIKE :exPatientSet}
#EQUALS{:syncType; increment; AND (t1.MODIFIED_AT BETWEEN :startTime AND :endTime or t2.MODIFIED_AT BETWEEN :startTime AND :endTime or t3.MODIFIED_AT BETWEEN :startTime AND :endTime)}
#EQUALS{:syncType; single; AND (t3.DISCHARGED_FROM_WARD_AT is null or t3.DISCHARGED_FROM_WARD_AT BETWEEN :startTime and :endTime)}
#EQUALS{:syncType; outHosp; AND t3.DISCHARGED_FROM_WARD_AT BETWEEN :startTime and :endTime}
and t1.ADMISSION_CONDITION_NO = '4' 
and t1.DIAGNOSIS_TYPE_CODE in (957005, 957006)
and t1.DIAGNOSIS_NO like t4.DIAG_NO + '%'
),
TEMP_COMPLICATION_GROUP AS (
SELECT 
ENCOUNTER_ID AS bizId,
ENCOUNTER_ID AS encounterId,
min(DIAGNOSED_AT) AS eventAt,
min(DIAGNOSED_AT) AS eventEndAt,
GETDATE() AS extractAt,
min(HOSPITAL_SOID) as hospitalSoid,
IS_DEL AS isDel,
STUFF((
   SELECT ',' + DIAGNOSIS_NAME
   FROM TEMP_COMPLICATION AS h2
   WHERE h2.ENCOUNTER_ID = h1.ENCOUNTER_ID
   ORDER BY DIAGNOSIS_NAME
   FOR XML PATH(''), TYPE).value('.', 'NVARCHAR(MAX)'), 1, 1, '') AS bizAttrOneName
FROM TEMP_COMPLICATION AS h1
GROUP BY ENCOUNTER_ID, IS_DEL
)

select * from (
    select * from TEMP_COMPLICATION_GROUP
    where isDel = 0
    union all
    select t1.* from TEMP_COMPLICATION_GROUP t1
    where t1.isDel = 1 
        and not exists (
        select 1 from TEMP_COMPLICATION_GROUP t2 where t2.isDel = 0 and t1.encounterId = t2.encounterId
        )
) t | [[concepts/HXZD-002-001if object_id('tempdb..#TEMP_DIAG') is not null
DROP TABLE #TEMP_DIAG

-- 创建临时表
CREATE TABLE #TEMP_DIAG (
    DIAG_NO VARCHAR(64)
)

-- 插入数据
INSERT INTO #TEMP_DIAG VALUES ('I26.9'),('I80.2'),('I82.8'),('A40.0'),('A40.1'),('A40.2'),('A40.3'),('A40.4'),('A40.5'),('A40.6'),('A40.7'),('A40.8'),('A40.9'),('A41.0'),('A41.9'),('T81.411'),('T81.0'),('T81.1'),('T81.2'),('T81.3'),('T81.4'),('T81.5'),('T81.6'),('T81.7'),('T81.8'),('T81.9'),('R96.0'),('R96.1'),('I46.1'),('J96.0'),('J96.1'),('J96.9'),('E89.9'),('T88.5'),('J95.1'),('J95.9'),('J98.4'),('N17.0'),('N17.1'),('N17.2'),('N17.3'),('N17.4'),('N17.5'),('N17.6'),('N17.7'),('N17.8'),('N17.9'),('N99.0'),('K91.0'),('K91.1'),('K91.2'),('K91.3'),('K91.4'),('K91.5'),('K91.6'),('K91.7'),('K91.8'),('K91.9'),('I97.0'),('I97.1'),('I97.8'),('I97.9'),('G97.0'),('G97.1'),('G97.2'),('G97.8'),('G97.9'),('I6.0'),('I6.1'),('I6.2'),('I6.3'),('I6.4'),('H59.0'),('H59.8'),('H59.9'),('H95.0'),('H95.1'),('H95.8'),('H95.9'),('M96.0'),('M96.1'),('M96.2'),('M96.3'),('M96.4'),('M96.5'),('M96.6'),('M96.7'),('M96.8'),('M96.9'),('N98.0'),('N98.2'),('N98.1'),('N98.3'),('N98.8'),('N98.9'),('N99.0'),('N99.1'),('N99.2'),('N99.3'),('N99.4'),('N99.5'),('N99.6'),('N99.7'),('N99.8'),('N99.9'),('K11.4'),('S04.3'),('S04.5'),('T82.0'),('T82.1'),('T82.2'),('T82.3'),('T82.4'),('T82.5'),('T82.6'),('T82.7'),('T82.8'),('T82.9'),('T83.0'),('T83.1'),('T83.2'),('T83.3'),('T83.4'),('T83.5'),('T83.6'),('T83.7'),('T83.8'),('T83.9'),('T84.0'),('T84.1'),('T84.2'),('T84.3'),('T84.4'),('T84.5'),('T84.6'),('T84.7'),('T84.8'),('T84.9'),('T85.0'),('T85.1'),('T85.2'),('T85.3'),('T85.4'),('T85.5'),('T85.6'),('T85.7'),('T85.8'),('T85.9'),('T86.0'),('T86.1'),('T86.2'),('T86.3'),('T86.4'),('T86.5'),('T86.6'),('T86.7'),('T86.8'),('T86.9'),('T87.0'),('T87.1'),('T87.2'),('T87.3'),('T87.4'),('T87.5'),('T87.6'),('T87.7'),('T87.8'),('T87.9'),('J93.8'),('J93.9'),('J95.8'),('T81.218');


with TEMP_COMPLICATION AS (
SELECT 
t3.ENCOUNTER_ID,t1.DIAGNOSIS_NAME,t1.DIAGNOSED_AT,t1.HOSPITAL_SOID,t1.IS_DEL
FROM MAHP_DMTS_ICD_DIAGNOSIS t1, MAHP_DMTS_MAIN t2, INPATIENT_ENCOUNTER t3, #TEMP_DIAG t4 
where 1=1
and t1.MAHP_DMTS_ID = t2.MAHP_DMTS_ID 
and t2.ENCOUNTER_ID = t3.ENCOUNTER_ID
AND t3.INPAT_ENC_BIZ_TYPE_CODE != 399552157
#ETC{AND t3.CURRENT_DEPT_ID NOT IN (:exDeptSet)}
#ETC{AND t3.FULL_NAME NOT LIKE :exPatientSet}
#EQUALS{:syncType; increment; AND (t1.MODIFIED_AT BETWEEN :startTime AND :endTime or t2.MODIFIED_AT BETWEEN :startTime AND :endTime or t3.MODIFIED_AT BETWEEN :startTime AND :endTime)}
#EQUALS{:syncType; single; AND (t3.DISCHARGED_FROM_WARD_AT is null or t3.DISCHARGED_FROM_WARD_AT BETWEEN :startTime and :endTime)}
#EQUALS{:syncType; outHosp; AND t3.DISCHARGED_FROM_WARD_AT BETWEEN :startTime and :endTime}
and t1.ADMISSION_CONDITION_NO = '4' 
and t1.DIAGNOSIS_TYPE_CODE in (957005, 957006)
and t1.DIAGNOSIS_NO like t4.DIAG_NO + '%'
),
TEMP_COMPLICATION_GROUP AS (
SELECT 
ENCOUNTER_ID AS bizId,
ENCOUNTER_ID AS encounterId,
min(DIAGNOSED_AT) AS eventAt,
min(DIAGNOSED_AT) AS eventEndAt,
GETDATE() AS extractAt,
min(HOSPITAL_SOID) as hospitalSoid,
IS_DEL AS isDel,
STUFF((
   SELECT ',' + DIAGNOSIS_NAME
   FROM TEMP_COMPLICATION AS h2
   WHERE h2.ENCOUNTER_ID = h1.ENCOUNTER_ID
   ORDER BY DIAGNOSIS_NAME
   FOR XML PATH(''), TYPE).value('.', 'NVARCHAR(MAX)'), 1, 1, '') AS bizAttrOneName
FROM TEMP_COMPLICATION AS h1
GROUP BY ENCOUNTER_ID, IS_DEL
)

select * from (
    select * from TEMP_COMPLICATION_GROUP
    where isDel = 0
    union all
    select t1.* from TEMP_COMPLICATION_GROUP t1
    where t1.isDel = 1 
        and not exists (
        select 1 from TEMP_COMPLICATION_GROUP t2 where t2.isDel = 0 and t1.encounterId = t2.encounterId
        )
) t]] |
| [[entities/HXZD-002-002_上级医师查房记录规范率.md|HXZD-002-002]] | 上级医师查房记录规范率 | [[concepts/HXZD-002-002上级医师查房记录规范率]] |
| [[entities/HXZD-002-003_住院患者非计划手术率.md|HXZD-002-003]] | 住院患者非计划手术率 | [[concepts/HXZD-002-003住院患者非计划手术率]] |
| [[entities/HXZD-003-001_急会诊及时到位率.md|HXZD-003-001]] | 急会诊及时到位率 | [[concepts/HXZD-003-001急会诊及时到位率]] |
| [[entities/HXZD-003-002_急会诊有效率.md|HXZD-003-002]] | 急会诊有效率 | [[concepts/HXZD-003-002急会诊有效率]] |
| [[entities/HXZD-003-003_001_普通会诊及时完成率.md|HXZD-003-003_001]] | 普通会诊及时完成率 | [[concepts/HXZD-003-003普通会诊及时完成率]] |
| [[entities/HXZD-003-003_002_普通会诊及时完成率.md|HXZD-003-003_002]] ⚠️ | 普通会诊及时完成率 | [[concepts/HXZD-003-003普通会诊及时完成率]] |
| [[entities/HXZD-003-004_普通会诊有效率.md|HXZD-003-004]] | 普通会诊有效率 | [[concepts/HXZD-003-004普通会诊有效率]] |
| [[entities/HXZD-004-001_手术患者特级护理_一级护理出院率.md|HXZD-004-001]] | 手术患者特级护理/一级护理出院率 | [[concepts/HXZD-004-001手术患者特级护理_一级护理出院率]] |
| [[entities/HXZD-005-001_四级手术患者手术当日床旁交接班占比.md|HXZD-005-001]] | 四级手术患者手术当日床旁交接班占比 | [[concepts/HXZD-005-001四级手术患者手术当日床旁交接班占比]] |
| [[entities/HXZD-006-001_非计划再次住院_手术患者疑难病例讨论完成率.md|HXZD-006-001]] | 非计划再次住院/手术患者疑难病例讨论完成率 | [[concepts/HXZD-006-001非计划再次住院_手术患者疑难病例讨论完成率]] |
| [[entities/HXZD-006-002_非计划再次住院_手术患者疑难病例讨论记录完整率.md|HXZD-006-002]] | 非计划再次住院/手术患者疑难病例讨论记录完整率 | [[concepts/HXZD-006-002非计划再次住院_手术患者疑难病例讨论记录完整率]] |
| [[entities/HXZD-006-003_001_高额异常费用患者进行疑难病例讨论的占比_出区时间.md|HXZD-006-003_001]] | 高额异常费用患者进行疑难病例讨论的占比 | [[concepts/HXZD-006-003高额异常费用患者进行疑难病例讨论的占比]] |
| [[entities/HXZD-006-003_002_高额异常费用患者进行疑难病例讨论的占比_入区时间.md|HXZD-006-003_002]] ⚠️ | 高额异常费用患者进行疑难病例讨论的占比 | [[concepts/HXZD-006-003高额异常费用患者进行疑难病例讨论的占比]] |
| [[entities/HXZD-007-001_急危重症患者抢救成功率.md|HXZD-007-001]] | 急危重症患者抢救成功率 | [[concepts/HXZD-007-001急危重症患者抢救成功率]] |
| [[entities/HXZD-008-001_术前讨论完成率.md|HXZD-008-001]] | 术前讨论完成率 | [[concepts/HXZD-008-001术前讨论完成率]] |
| [[entities/HXZD-008-002_001_术者参加术前讨论率_病历完成时间.md|HXZD-008-002_001]] | 术者参加术前讨论率 | [[concepts/HXZD-008-002术者参加术前讨论率]] |
| [[entities/HXZD-008-002_002_术者参加术前讨论率_可选方案.md|HXZD-008-002_002]] ⚠️ | 术者参加术前讨论率 | [[concepts/HXZD-008-002术者参加术前讨论率]] |
| [[entities/HXZD-008-003_术前讨论计划手术一致率.md|HXZD-008-003]] | 术前讨论计划手术一致率 | [[concepts/HXZD-008-003术前讨论计划手术一致率]] |
| [[entities/HXZD-008-004_实际手术术者与计划手术术者一致率.md|HXZD-008-004]] | 实际手术术者与计划手术术者一致率 | [[concepts/HXZD-008-004实际手术术者与计划手术术者一致率]] |
| [[entities/HXZD-009-001_死亡病例讨论5日完成率.md|HXZD-009-001]] | 死亡病例讨论5日完成率 | [[concepts/HXZD-009-001死亡病例讨论5日完成率]] |
| [[entities/HXZD-009-002_001_医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值_出院患者.md|HXZD-009-002_001]] | 医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值 | [[concepts/HXZD-009-002医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值]] |
| [[entities/HXZD-009-002_002_医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值_可选方案.md|HXZD-009-002_002]] ⚠️ | 医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值 | [[concepts/HXZD-009-002医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值]] |
| [[entities/HXZD-009-003_001_科主任主持死亡病例讨论率_死亡讨论记录.md|HXZD-009-003_001]] | 科主任主持死亡病例讨论率 | [[concepts/HXZD-009-003科主任主持死亡病例讨论率]] |
| [[entities/HXZD-009-003_002_科主任主持死亡病例讨论率_可选方案.md|HXZD-009-003_002]] ⚠️ | 科主任主持死亡病例讨论率 | [[concepts/HXZD-009-003科主任主持死亡病例讨论率]] |
| [[entities/HXZD-009-004_死亡患者病案上传率.md|HXZD-009-004]] | 死亡患者病案上传率 | [[concepts/HXZD-009-004死亡患者病案上传率]] |
| [[entities/HXZD-010-001_长期医嘱当日终止率.md|HXZD-010-001]] | 长期医嘱当日终止率 | [[concepts/HXZD-010-001长期医嘱当日终止率]] |
| [[entities/HXZD-011-001_手术医师手术时间重合率.md|HXZD-011-001]] | 手术医师手术时间重合率 | [[concepts/HXZD-011-001手术医师手术时间重合率]] |
| [[entities/HXZD-011-002_麻醉医师手术时间重合率.md|HXZD-011-002]] | 麻醉医师手术时间重合率 | [[concepts/HXZD-011-002麻醉医师手术时间重合率]] |
| [[entities/HXZD-012-001_001_四级手术与三级手术并发症发生率比.md|HXZD-012-001_001]] | 四级手术与三级手术并发症发生率比 | [[concepts/HXZD-012-001四级手术与三级手术并发症发生率比]] |
| [[entities/HXZD-012-001_002_四级手术与三级手术并发症发生率比_可选方案.md|HXZD-012-001_002]] ⚠️ | 四级手术与三级手术并发症发生率比 | [[concepts/HXZD-012-001四级手术与三级手术并发症发生率比]] |
| [[entities/HXZD-012-002_001_四级手术与三级手术患者死亡率比.md|HXZD-012-002_001]] | 四级手术与三级手术患者死亡率比 | [[concepts/HXZD-012-002四级手术与三级手术患者死亡率比]] |
| [[entities/HXZD-012-002_002_四级手术与三级手术患者死亡率比_可选方案.md|HXZD-012-002_002]] ⚠️ | 四级手术与三级手术患者死亡率比 | [[concepts/HXZD-012-002四级手术与三级手术患者死亡率比]] |
| [[entities/HXZD-012-003_001_四级手术术前多学科讨论完成率.md|HXZD-012-003_001]] | 四级手术术前多学科讨论完成率 | [[concepts/HXZD-012-003四级手术术前多学科讨论完成率]] |
| [[entities/HXZD-012-003_002_四级手术术前多学科讨论完成率_可选方案.md|HXZD-012-003_002]] ⚠️ | 四级手术术前多学科讨论完成率 | [[concepts/HXZD-012-003四级手术术前多学科讨论完成率]] |
| [[entities/HXZD-012-004_001_三、四级手术实际开展率_手术完成时间.md|HXZD-012-004_001]] | 三、四级手术实际开展率 | [[concepts/HXZD-012-004三、四级手术实际开展率]] |
| [[entities/HXZD-012-004_002_三、四级手术实际开展率_可选方案.md|HXZD-012-004_002]] ⚠️ | 三、四级手术实际开展率 | [[concepts/HXZD-012-004三、四级手术实际开展率]] |
| [[entities/HXZD-013-001_新技术新项目留存转化率.md|HXZD-013-001]] | 新技术新项目留存转化率 | [[concepts/HXZD-013-001新技术新项目留存转化率]] |
| [[entities/HXZD-014-001_危急值报告时间.md|HXZD-014-001]] | 危急值报告时间 | [[concepts/HXZD-014-001危急值报告时间]] |
| [[entities/HXZD-014-002_住院患者危急值当日及时处置率.md|HXZD-014-002]] | 住院患者危急值当日及时处置率 | [[concepts/HXZD-014-002住院患者危急值当日及时处置率]] |
| [[entities/HXZD-015-001_001_特殊使用级抗菌药物使用会诊率_医务报批.md|HXZD-015-001_001]] | 特殊使用级抗菌药物使用会诊率 | [[concepts/HXZD-015-001特殊使用级抗菌药物使用会诊率]] |
| [[entities/HXZD-015-001_002_特殊使用级抗菌药物使用会诊率_可选方案.md|HXZD-015-001_002]] ⚠️ | 特殊使用级抗菌药物使用会诊率 | [[concepts/HXZD-015-001特殊使用级抗菌药物使用会诊率]] |
| [[entities/HXZD-016-001_临床用血后评估记录率.md|HXZD-016-001]] | 临床用血后评估记录率 | [[concepts/HXZD-016-001临床用血后评估记录率]] |
| [[entities/HXZD-016-002_术中自体血回输率.md|HXZD-016-002]] | 术中自体血回输率 | [[concepts/HXZD-016-002术中自体血回输率]] |

---

## 45 个血缘解析页

> 每个衍生实体对应一个血缘解析页，追溯 S0 建表 → S3 口径计算的完整数据链路。

- [[entities/HXZD-001-001_患者入院48小时内转科的比例_血缘说明.md]]
- [[entities/HXZD-002-001_患者入院8小时内查房率_血缘说明.md]]
- [[entities/HXZD-002-002_上级医师查房记录规范率_血缘说明.md]]
- [[entities/HXZD-002-003_住院患者非计划手术率_血缘说明.md]]
- [[entities/HXZD-003-001_急会诊及时到位率_血缘说明.md]]
- [[entities/HXZD-003-002_急会诊有效率_血缘说明.md]]
- [[entities/HXZD-003-003_001_普通会诊及时完成率_血缘说明.md]]
- [[entities/HXZD-003-003_002_普通会诊及时完成率_血缘说明.md]]
- [[entities/HXZD-003-004_普通会诊有效率_血缘说明.md]]
- [[entities/HXZD-004-001_手术患者特级护理_一级护理出院率_血缘说明.md]]
- [[entities/HXZD-005-001_四级手术患者手术当日床旁交接班占比_血缘说明.md]]
- [[entities/HXZD-006-001_非计划再次住院_手术患者疑难病例讨论完成率_血缘说明.md]]
- [[entities/HXZD-006-002_非计划再次住院_手术患者疑难病例讨论记录完整率_血缘说明.md]]
- [[entities/HXZD-006-003_001_高额异常费用患者进行疑难病例讨论的占比_血缘说明.md]]
- [[entities/HXZD-006-003_002_高额异常费用患者进行疑难病例讨论的占比_血缘说明.md]]
- [[entities/HXZD-007-001_急危重症患者抢救成功率_血缘说明.md]]
- [[entities/HXZD-008-001_术前讨论完成率_血缘说明.md]]
- [[entities/HXZD-008-002_001_术者参加术前讨论率_血缘说明.md]]
- [[entities/HXZD-008-002_002_术者参加术前讨论率_血缘说明.md]]
- [[entities/HXZD-008-003_术前讨论计划手术一致率_血缘说明.md]]
- [[entities/HXZD-008-004_实际手术术者与计划手术术者一致率_血缘说明.md]]
- [[entities/HXZD-009-001_死亡病例讨论5日完成率_血缘说明.md]]
- [[entities/HXZD-009-002_001_医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值_血缘说明.md]]
- [[entities/HXZD-009-002_002_医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值_血缘说明.md]]
- [[entities/HXZD-009-003_001_科主任主持死亡病例讨论率_血缘说明.md]]
- [[entities/HXZD-009-003_002_科主任主持死亡病例讨论率_血缘说明.md]]
- [[entities/HXZD-009-004_死亡患者病案上传率_血缘说明.md]]
- [[entities/HXZD-010-001_长期医嘱当日终止率_血缘说明.md]]
- [[entities/HXZD-011-001_手术医师手术时间重合率_血缘说明.md]]
- [[entities/HXZD-011-002_麻醉医师手术时间重合率_血缘说明.md]]
- [[entities/HXZD-012-001_001_四级手术与三级手术并发症发生率比_血缘说明.md]]
- [[entities/HXZD-012-001_002_四级手术与三级手术并发症发生率比_血缘说明.md]]
- [[entities/HXZD-012-002_001_四级手术与三级手术患者死亡率比_血缘说明.md]]
- [[entities/HXZD-012-002_002_四级手术与三级手术患者死亡率比_血缘说明.md]]
- [[entities/HXZD-012-003_001_四级手术术前多学科讨论完成率_血缘说明.md]]
- [[entities/HXZD-012-003_002_四级手术术前多学科讨论完成率_血缘说明.md]]
- [[entities/HXZD-012-004_001_三、四级手术实际开展率_血缘说明.md]]
- [[entities/HXZD-012-004_002_三、四级手术实际开展率_血缘说明.md]]
- [[entities/HXZD-013-001_新技术新项目留存转化率_血缘说明.md]]
- [[entities/HXZD-014-001_危急值报告时间_血缘说明.md]]
- [[entities/HXZD-014-002_住院患者危急值当日及时处置率_血缘说明.md]]
- [[entities/HXZD-015-001_001_特殊使用级抗菌药物使用会诊率_血缘说明.md]]
- [[entities/HXZD-015-001_002_特殊使用级抗菌药物使用会诊率_血缘说明.md]]
- [[entities/HXZD-016-001_临床用血后评估记录率_血缘说明.md]]
- [[entities/HXZD-016-002_术中自体血回输率_血缘说明.md]]

---

## 快速入口

- 查四维分类：[[concepts/四维分类索引]]
- 查证据链：[[concepts/证据链验证模型]]
- 查ETL逻辑：[[concepts/ETL抽取逻辑说明]]
- 查方案对比：[[comparisons/35指标45衍生指标-主方案与变体差异分析]]
- 查完整文档：[[queries/20260728170600_说明文档]]
- 查指标映射表：[[references/indicator-map]]
- 查源表索引：[[concepts/源表索引]]
- 查目标表索引：[[concepts/目标表-概览索引]]
- 查血缘解析模板：[[templates/entitymetricline-template]]
- 人类可读概览：[[README|README]]