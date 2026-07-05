# 患者入院48小时内转科比例 SQL 示例

## MySQL 示例

```sql
SELECT CASE WHEN COUNT(DISTINCT admission_id)=0 THEN 0 ELSE ROUND(COUNT(DISTINCT CASE WHEN transfer_time IS NOT NULL AND TIMESTAMPDIFF(HOUR,admit_time,transfer_time)<=48 THEN admission_id END)/COUNT(DISTINCT admission_id)*100,2) END AS index_value FROM inpatient_transfer_record WHERE hospital_id='hospital_002' AND admit_time>='2026-07-01' AND admit_time<'2026-08-01';
```

## 预期结果

hospital_002 有 3 条入院记录，48小时内转科 1 条（33.33%）。
