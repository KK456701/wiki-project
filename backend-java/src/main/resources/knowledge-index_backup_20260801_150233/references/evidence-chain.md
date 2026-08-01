# 证据链验证规程

> 核心原则：**数出有源，层层可追溯** — 任意指标值可沿证据链逐层回溯至业务系统源表。

## 6 层模型

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 6  数据验证层    ← 实时查询数据库，交叉验证指标值          │
├─────────────────────────────────────────────────────────────────┤
│  Layer 5  推导SQL层     ← 从目标表计算指标值（分子/分母/公式）    │
├─────────────────────────────────────────────────────────────────┤
│  Layer 4  规则定义层    ← 指标定义、计算公式、判定逻辑            │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3  目标表层      ← MRAS_BUSINESS_* 数据仓库表              │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2  抽取SQL层     ← ETL: 源表 → 目标表（字段映射+业务逻辑） │
├─────────────────────────────────────────────────────────────────┤
│  Layer 1  源表层        ← HIS/护士站/医生站业务系统原始表          │
└─────────────────────────────────────────────────────────────────┘
```

## 逐层验证项

### L1 源表层验证

```sql
-- 检查源表是否存在
SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES 
WHERE TABLE_SCHEMA = 'WINDBA_GN' 
  AND TABLE_NAME IN ('INPATIENT_ENCOUNTER','INPAT_TRANSFER','ORGANIZATION',
                      'INPATIENT_PARTICIPANT','EMPLOYEE_INFO');

-- 检查关键字段是否存在
SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'WINDBA_GN' AND TABLE_NAME = 'INPATIENT_ENCOUNTER'
  AND COLUMN_NAME IN ('ENCOUNTER_ID','FIRST_ADMITTED_TO_WARD_AT','IS_DEL','CURRENT_DEPT_ID');
```

**通过标准：** 所有源表和关键字段存在，数据类型兼容。

### L2 抽取SQL层验证

验证项：
1. **转科类型编码** — INPAT_TRANSFER_TYPE_CODE (399549991/399549990) 存在
2. **ICU排除编码** — 13 个 ORG_NO 排除项在 ORGANIZATION 表中存在
3. **时限判断** — DATEDIFF(HOUR, ...) < 48 逻辑正确
4. **去重逻辑** — ROW_NUMBER() PARTITION BY ENCOUNTER_ID 去重有效

```sql
-- 转科类型编码验证
SELECT DISTINCT INPAT_TRANSFER_TYPE_CODE FROM INPAT_TRANSFER 
WHERE INPAT_TRANSFER_TYPE_CODE IN ('399549991','399549990');

-- ICU排除编码验证
SELECT ORG_NO, ORG_NAME FROM ORGANIZATION 
WHERE ORG_NO IN ('12800000','42800000','42800200','31301','22800000',
                 '33802','34001','22800100','22800200','42800100',
                 '12800200','27401','12800100');
```

**通过标准：** 所有编码在数据库中存在且语义正确。

### L3 目标表层验证

```sql
-- 目标表结构
SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'WINDBA_GN' AND TABLE_NAME = 'MRAS_BUSINESS_FIRSTVISIT'
ORDER BY ORDINAL_POSITION;

-- 枚举值分布
SELECT TRANSFER_WITHIN_TWO_DAY, COUNT(*) AS cnt
FROM WINDBA_GN.MRAS_BUSINESS_FIRSTVISIT
WHERE IS_DEL = 0 AND EVENT_AT >= '{start}' AND EVENT_AT < DATEADD(DAY, 1, '{end}')
GROUP BY TRANSFER_WITHIN_TWO_DAY;
```

**通过标准：** 目标表存在，判定字段类型正确，枚举值仅含 98175/98176，无 NULL 异常。

### L4 规则定义层验证

逐字段比对规则文档中的分子/分母定义 vs 目标表判定字段：
- 分子条件 `↔` 判定字段 = 98175
- 分母条件 `↔` COUNT(*) WHERE IS_DEL=0
- 过滤条件 `↔` IS_DEL 字段语义

**通过标准：** 100% 字段覆盖率，无悬空指标。

### L5 推导SQL层验证

直接执行推导SQL，验证可执行性和结果合理性：

```sql
SELECT COUNT(*) AS den,
       SUM(CASE WHEN TRANSFER_WITHIN_TWO_DAY = 98175 THEN 1 ELSE 0 END) AS mol,
       CAST(SUM(CASE WHEN TRANSFER_WITHIN_TWO_DAY = 98175 THEN 1 ELSE 0 END) 
            * 100.0 / NULLIF(COUNT(*), 0) AS DECIMAL(10,2)) AS rate
FROM WINDBA_GN.MRAS_BUSINESS_FIRSTVISIT
WHERE IS_DEL = 0 AND EVENT_AT >= '{start}' AND EVENT_AT < DATEADD(DAY, 1, '{end}');
```

**通过标准：** SQL 可执行无报错，结果在合理范围 (0%~100%)。

### L5+ 应用层交叉验证

比对页面 CTE 逻辑 vs 直接聚合是否一致：

```sql
-- CTE方式
WITH DeptOrderStats AS (
    SELECT CURRENT_DEPT_ID, CURRENT_DEPT_NAME,
           COUNT(CASE WHEN TRANSFER_WITHIN_TWO_DAY = 98175 THEN 1 END) AS mol,
           COUNT(1) AS den
    FROM WINDBA_GN.MRAS_BUSINESS_FIRSTVISIT
    WHERE IS_DEL = 0 AND EVENT_AT >= '{start}' AND EVENT_AT < DATEADD(DAY, 1, '{end}')
    GROUP BY CURRENT_DEPT_ID, CURRENT_DEPT_NAME
)
SELECT SUM(mol) AS cte_mol, SUM(den) AS cte_den FROM DeptOrderStats;

-- 直接聚合
SELECT SUM(CASE WHEN TRANSFER_WITHIN_TWO_DAY = 98175 THEN 1 ELSE 0 END) AS direct_mol,
       COUNT(*) AS direct_den
FROM WINDBA_GN.MRAS_BUSINESS_FIRSTVISIT
WHERE IS_DEL = 0 AND EVENT_AT >= '{start}' AND EVENT_AT < DATEADD(DAY, 1, '{end}');
```

**通过标准：** CTE 聚合 = 直接聚合（误差 = 0）。

### L6 数据交叉验证

比对推导SQL执行结果 vs 报告中的数值：

```
推导SQL: 3 / 291 = 1.03%
报告值: 3 / 291 = 1.03%
误差: 0
```

**通过标准：** 分子/分母/比率三项完全一致（同库同表同条件下误差必须为 0）。

## 自动化验证流程

```
1. 查询 INFORMATION_SCHEMA → 验证 L1 源表 + L3 目标表结构
2. 执行枚举值分布查询 → 验证 L3 枚举值合规
3. 执行 L5 推导SQL → 获取分子/分母/比率
4. 执行 L5+ CTE vs 直接聚合 → 验证应用层一致性
5. 比对 L5 结果 vs 报告数据 → L6 交叉验证
6. 生成逐层 PASS/FAIL 状态表
```

## 验证结果模板

```
L1 源表        ✅ PASS  5张源表字段存在
  │
L2 抽取SQL     ✅ PASS  转科定义 + ICU排除(13编码) + 时限<48h + 去重
  │
L3 目标表      ✅ PASS  MRAS_BUSINESS_FIRSTVISIT · 枚举98175/98176 · 无NULL
  │
L4 规则定义    ✅ PASS  分子/分母 100%可映射
  │
L5 推导SQL     ✅ PASS  291 人次 · 3 人转科 · 1.03%
  │
L5+ 应用层     ✅ PASS  概览CTE = 直接聚合
  │
L6 数据验证    ✅ PASS  sqlcmd 直连交叉验证 3/291=1.03%
```

## 精简验证（非 CORE_FDR 事件）

对于源SQL未完成的事件，执行 L3 + L5 + L6 精简验证：
1. L3：查询目标表结构 + 枚举值分布
2. L5：执行推导SQL获取指标值
3. L6：记录查询时间戳作为交叉验证凭证
