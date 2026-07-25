# 页面类型与 Front Matter 规范

本文件定义 Wiki 知识库中各页面类型的 YAML front matter 必填/选填字段。

---

## 1. 制度页面（system）

路径：`wiki/systems/HXZD-XXX-制度名.md`

```yaml
---
page_type: system          # 必填，固定值
system_id: HXZD-001        # 必填，制度编码
title: 首诊负责制度         # 必填，制度名称
indicator_count: 3         # 必填，下属指标数量
updated_at: 2026-07-25     # 必填，最后更新日期
---
```

---

## 2. 指标主页面（indicator）

路径：`wiki/indicators/HXZD-XXX-XXX/index.md`

```yaml
---
page_type: indicator                    # 必填，固定值
rule_id: HXZD-001-001                   # 必填，指标唯一编码
title: 患者入院48小时内转科的比例        # 必填，正式名称
status: published                       # 必填，published | draft
system_id: HXZD-001                     # 必填，所属制度编码
system_name: 首诊负责制度               # 必填，所属制度名称
aliases:                                # 选填，别名列表
  - 入院48小时转科比例
  - 48小时内转科率
keywords:                               # 选填，检索关键词
  - 转科
  - 48小时
direction: lower_is_better              # 必填，lower_is_better | higher_is_better
unit: percentage                        # 必填，percentage | count | days
default_profile: HXZD-001-001-company-default  # 必填，默认口径 profile_id
updated_at: 2026-07-25                  # 必填，最后更新日期
---
```

---

## 3. 口径方案页面（caliber_profile）

路径：`wiki/indicators/HXZD-XXX-XXX/profiles/company-default.md`

```yaml
---
page_type: caliber_profile              # 必填，固定值
profile_id: HXZD-001-001-company-default  # 必填，口径唯一 ID
rule_id: HXZD-001-001                   # 必填，所属指标编码
profile_name: 公版推荐方案              # 必填，方案名称
owner_scope: company                    # 必填，company | hospital
status: published                       # 必填，published | draft
effective_from: 2025-01-01              # 选填，生效起始日期
effective_to:                           # 选填，生效结束日期（空=长期）
time_dimension: admitted_to_ward_at     # 必填，时间维度字段
patient_scope:                          # 必填，患者范围
  - inpatient_current
  - inpatient_discharged
dedup_key: encounter_id                 # 必填，去重键
direction: lower_is_better              # 必填，指标导向
sql_spec: ../../../sql-specs/HXZD-001-001/sql-spec.md  # 必填，SQL 规格引用
updated_at: 2026-07-25                  # 必填，最后更新日期
---
```

---

## 4. SQL 规格页面（sql_spec）

路径：`sql-specs/HXZD-XXX-XXX/sql-spec.md`

```yaml
---
page_type: sql_spec                     # 必填，固定值
rule_id: HXZD-001-001                   # 必填，所属指标编码
database_type: sqlserver                # 必填，数据库类型
status: published                       # 必填，published | draft
updated_at: 2026-07-25                  # 必填，最后更新日期
---
```

---

## 5. 原始 SQL 存档页面

路径：`sql-specs/HXZD-XXX-XXX/original/excel-original.md`

```yaml
---
page_type: sql_original                 # 必填，固定值
rule_id: HXZD-001-001                   # 必填，所属指标编码
source_status: raw_imported             # 必填，固定值
executable: false                       # 必填，固定值 false
contains_unresolved_tokens: true        # 必填，是否含未解析标记
updated_at: 2026-07-25                  # 必填，导入日期
---
```

---

## 6. 医院差异覆盖页面（hospital_override）

路径：`wiki/hospitals/HOSPITAL_X/overrides/HXZD-XXX-XXX.md`（后续阶段）

```yaml
---
page_type: hospital_override            # 必填，固定值
hospital_id: HOSPITAL_A                 # 必填，医院标识
rule_id: HXZD-001-001                   # 必填，指标编码
profile_id: HOSPITAL_A-HXZD-001-001-v1  # 必填，覆盖 Profile ID
extends: HXZD-001-001-company-default   # 必填，继承的公版 Profile
status: published                       # 必填
effective_from: 2026-01-01              # 必填
effective_to:                           # 选填
override_fields:                        # 必填，覆盖字段列表
  - time_dimension
  - target_policy
updated_at: 2026-07-25                  # 必填
---
```

---

## 7. 医院字段映射页面（hospital_mapping）

路径：`hospital-mappings/HOSPITAL_X/indicators/HXZD-XXX-XXX.md`（后续阶段）

```yaml
---
page_type: hospital_mapping             # 必填，固定值
hospital_id: HOSPITAL_A                 # 必填
rule_id: HXZD-001-001                   # 必填
database_type: sqlserver                # 必填
status: verified                        # 必填，verified | pending
verified_at: 2026-07-25                 # 选填
updated_at: 2026-07-25                  # 必填
---
```

---

## 通用规则

1. 所有页面必须包含 `page_type` 字段。
2. 所有页面必须包含 `updated_at` 字段（ISO 日期格式 YYYY-MM-DD）。
3. `rule_id` 格式：`HXZD-XXX-XXX`（三位数字-三位数字）。
4. `system_id` 格式：`HXZD-XXX`（三位数字）。
5. `profile_id` 格式：`{rule_id}-{scope}-{name}`。
6. YAML front matter 以 `---` 开头和结尾。
7. 页面正文使用标准 Markdown 格式。
