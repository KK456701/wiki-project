# 医院核心制度指标 Wiki 知识库

## 用途

本知识库是医院核心制度指标智能体的结构化知识来源，支持：

- 35 项核心制度指标的精确检索
- 45 个口径方案（Profile）的独立管理
- 多医院独立口径继承与覆盖
- SQL 规格与运行模板分离管理
- 确定性索引检索（不依赖向量/RAG）

## 编码体系

采用 HXZD 编码：

```
HXZD-XXX       → 制度（如 HXZD-001 = 首诊负责制度）
HXZD-XXX-XXX   → 指标（如 HXZD-001-001 = 患者入院48小时内转科的比例）
```

每个指标拥有唯一 `rule_id`，名称、别名、口径、SQL 可变，但编码不变。

## 目录结构

```
core-rules-wiki/
├── README.md              # 本文件
├── AGENTS.md              # 小模型维护契约
├── schema.md              # 页面类型和 front matter 规范
├── index.md               # 总索引页（脚本生成）
├── log.md                 # 变更日志
├── raw/                   # 原始导入资料
│   └── company/           # 公司级原始文件
├── wiki/
│   ├── systems/           # 制度页面（脚本生成）
│   └── indicators/        # 指标目录（脚本生成）
│       └── HXZD-XXX-XXX/
│           ├── index.md   # 指标主页面
│           └── profiles/  # 口径方案页面
├── sql-specs/             # SQL 规格（脚本生成）
│   └── HXZD-XXX-XXX/
│       ├── sql-spec.md
│       └── original/
│           └── excel-original.md
├── indexes/               # 精确索引（脚本自动生成）
│   ├── indicator_index.json
│   ├── alias_index.json
│   ├── keyword_index.json
│   └── system_index.json
└── review/                # 审核流程
    ├── pending/
    ├── approved/
    └── rejected/
```

## 导入脚本

从原始 Markdown 文件一键生成全部知识页面和索引：

```bash
node scripts/build-wiki-from-markdown.mjs --input "core-rules-wiki/raw/company/35项核心制度指标完整提取.md"
```

脚本会生成：
- 35 个指标主页面
- 45 个口径 Profile 页面
- 35 组 SQL 规格
- 16 个制度页面
- 4 个精确索引 JSON
- 总索引页

## 检索优先级

```
1. 指标编码精确匹配（HXZD-XXX-XXX）
2. 正式名称精确匹配
3. 已审核别名精确匹配
4. 关键词倒排匹配
5. 候选冲突时调用小模型消歧
```

不使用向量语义检索。

## 设计原则

- 一个指标只有一个稳定身份（rule_id）
- 公版和医院口径使用继承覆盖
- 业务口径和技术映射分开
- Markdown 负责知识，SQL 模板负责执行
- 精确检索优先，模型消歧兜底
- 小模型可以维护草稿，不能自主发布
