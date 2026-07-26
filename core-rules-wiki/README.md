# 医院核心制度指标版本化知识库

## 定位

本目录同时保存原始资料、人读 Wiki 和 Java Runtime 消费的机器契约。知识维护与
在线问答彻底分离：

```text
Excel / Markdown
→ 确定性提取
→ 大模型规范化为 KnowledgeDraftV2
→ 人工审核
→ SQL 分层门禁
→ 不可变 release
→ 原子切换 current 指针
→ Java 热加载
```

本地 Qwen 4B/8B 只用于在线问答和允许列表消歧，禁止参与知识发版。大模型也只能
生成待审核结构化候选，不能改写 SQL、直接发布或修改当前版本。

## 目录

```text
core-rules-wiki/
├── raw/
│   ├── company/                         公司原始资料
│   └── hospitals/{hospital_id}/         医院原始差异资料
├── review/
│   ├── pending/                         本地待审核候选（不提交）
│   ├── approved/                        已审核候选记录
│   └── rejected/                        被拒绝候选记录
├── releases/
│   ├── company/{release_id}/            公司不可变发布快照
│   └── hospitals/{hospital_id}/{release_id}/
├── pointers/
│   ├── company-current.json             公司当前版本指针
│   └── hospitals/{hospital_id}-current.json
├── prompts/
│   └── knowledge-release-normalizer.md  大模型知识规范化提示词
├── schema/                              发版和回收 JSON Schema
├── wiki/                                迁移期兼容的人读页面
├── sql-specs/                           迁移期兼容的 SQL 机器契约
└── indexes/                             迁移期兼容的检索索引
```

发布版本命名为 `KB-日期时间-内容哈希`。发布目录禁止原地修改；更新和回滚只通过
指针完成。医院 release 必须记录依赖的公司基础版本，公司升级不会静默覆盖医院口径。

## 一键发版

### 1. 生成待审核候选

标准 Markdown 会走确定性适配器；Excel 或非标准 Markdown 才调用配置中允许的
知识维护大模型。

```powershell
.\scripts\knowledge-release.ps1 `
  -Action Prepare `
  -Input ".\新指标资料.xlsx" `
  -Scope company `
  -ModelId deepseek-v4-flash
```

支持的模型密钥只从环境变量读取：

- DeepSeek：`DEEPSEEK_API_KEY`
- 阿里云百炼：`DASHSCOPE_API_KEY`

### 2. 校验

```powershell
.\scripts\knowledge-release.ps1 `
  -Action Validate `
  -Candidate "KB-20260726123000-xxxxxxxxxxxx"
```

校验失败不会改变当前知识库。当前门禁包括来源追踪、指标/Profile/SQL 引用一致性、
无效别名、Excel 残留、模板残留、多语句、写 SQL、临时表、动态 SQL、参数、
SQL Server 函数允许列表、结果契约和文件 SHA-256。

医院候选完成目标库 DBHub 验证后，可以附加不含患者数据的验证摘要：

```powershell
.\scripts\knowledge-release.ps1 `
  -Action Validate `
  -Candidate "<医院候选编号>" `
  -Verification ".\hospital_001-sql-verification.json"
```

验证摘要必须符合 `hospital-sql-verification-v1.schema.json`。医院、基础版本和
`normalized_sha256` 任一不一致都会拒绝；只有元数据、编译、小范围试运行和人工确认
按顺序全部完成的单项 SQL 能力才能提升为 `executable`。

### 3. 人工确认并发布

```powershell
.\scripts\knowledge-release.ps1 `
  -Action Publish `
  -Candidate "KB-20260726123000-xxxxxxxxxxxx" `
  -Confirmed
```

没有 `-Confirmed` 时命令必定拒绝。发布过程先复制并复验完整快照，再原子替换
`current` 指针。

### 4. 回滚

```powershell
.\scripts\knowledge-release.ps1 `
  -Action Rollback `
  -Scope company `
  -ReleaseId "KB-20260726-7e71f92e431a"
```

回滚只切换指针，不删除新旧版本。

## 医院差异回收

医院导出包只包含 Profile 覆盖、字段/参数映射、验证摘要、对象哈希和人工确认记录，
不包含患者明细、数据库凭据、Trace、SQL 查询结果或模型密钥。

```powershell
.\scripts\knowledge-release.ps1 `
  -Action ExportHospital `
  -HospitalId hospital_001 `
  -Input ".\hospital_001-knowledge.zip"

.\scripts\knowledge-release.ps1 `
  -Action Reclaim `
  -Input ".\hospital_001-knowledge.zip"
```

导入时校验清单与 SHA-256，并对“医院依赖的公司版本 / 当前公司版本 / 医院差异”
生成三方 Diff。回收包永远只进入 `review/pending`，不得直接修改公司版本，也不得
把一个医院的物理字段映射自动复用给其他医院。

## 检索契约

每次发布都生成紧凑 `retrieval_cards.json`，包括编号、正式名称、有效别名、制度、
定义、公式、分子、分母、时间维度、默认 Profile 和执行状态；还会生成名称、
别名、关键词、制度和字符 n-gram 索引。

运行时顺序固定为：

1. 指标编号或名称精确匹配；
2. 已审核别名匹配；
3. 关键词和本地相似度；
4. 仅在候选仍冲突时，让 LLM 在最多 3 个紧凑候选中消歧。

精确匹配不调用 LLM，小模型也不会收到整份 Markdown 或 169 段 SQL。

## SQL 能力门禁

四类 SQL 分别维护状态：

- `source_extract`
- `overview`
- `department_detail`
- `patient_detail`

状态逐级推进：

```text
raw
→ normalized
→ static_validated
→ metadata_validated
→ compile_validated
→ trial_validated
→ executable
```

未知函数、Oracle 方言函数、未知参数、缺表缺字段或结果列无法映射时只能保留为
`verification_required` / `documentation_only`。概览 SQL 通过不代表患者明细也能
执行；前端只展示实际达到 `executable` 的能力。

当前初始版本包含 35 项指标、45 个 Profile 和 169 个 SQL 块。所有指标都可检索和
解释；未完成目标医院 DBHub 验证的 Profile 不开放试运行，模型不得临时生成 SQL 绕过。

## 校验命令

```powershell
node .\scripts\build-wiki-from-markdown.mjs `
  --input ".\core-rules-wiki\raw\company\35项核心制度指标完整提取.md" `
  --check

node .\scripts\validate-wiki-contract.mjs `
  --expected-indicators 35 `
  --expected-profiles 45 `
  --expected-sql 169

node --test .\scripts\tests\knowledge-release.test.mjs
```

Java 启动及请求期间会验证指针目标和 release 中每个文件的哈希。新版本不完整或
哈希不一致时，`AtomicReference` 保留上一份有效快照，不会读到半发布状态。
