# 患者入院 48 小时内转科的比例_本院口径

## 本院指标定义

入院后 0 至 48 小时内发生转科，且转入、转出科室均非 ICU 的入院人次数，占同期入院患者总人次数的比例。

## 本院计算公式

患者入院 48 小时内转科的比例 = 入院后 0 至 48 小时内转科且转入、转出科室均非 ICU 的入院人次数 ÷ 同期入院患者总人次数（按入院流水号去重） × 100%

## 本院标准 SQL

SQL 模板与字段映射分别以 `sql-specs/MQSI2025_001_患者入院48小时内转科比例` 和 `hospital-mappings/hospital_001/MQSI2025_001.yaml` 为准。

## 迁移信息

- source: med_index_hospital_custom
- hospital_version: 4
- migrated_at: 2026-07-22
- status: approved
