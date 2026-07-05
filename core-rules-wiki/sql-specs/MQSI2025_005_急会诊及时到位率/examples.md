# 急会诊及时到位率 SQL 示例

## MySQL 示例

```sql
-- 默认参数：30分钟内到达
SELECT CASE WHEN COUNT(*)=0 THEN 0 ELSE ROUND(SUM(CASE WHEN TIMESTAMPDIFF(MINUTE,request_time,arrive_time)<=30 THEN 1 ELSE 0 END)/COUNT(*)*100,2) END AS index_value FROM consult_record WHERE hospital_id='hospital_002' AND consult_type='急会诊' AND request_time>='2026-07-01' AND request_time<'2026-08-01';
```

## 预期结果

hospital_002 有 2 条急会诊，15分钟到达 1 条（50.00%）。
