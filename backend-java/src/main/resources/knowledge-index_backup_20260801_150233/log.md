# knowledge-index-v60 更新日志

## v60 — 初始创建 (2026-07-28)

### 基于 skills/knowledgeskill6 v6.0.0 创建

**设计理念：** concepts 按**指标名称分类**（如 `患者入院48小时内转科的比例.md`），不再按制度分类。

**v6.0.0 新增能力 (vs v50):**
- 🆕 comparisons/ — 7维度 × 10对变体差异分析
- 🆕 queries/ — 35指标×45衍生指标完整说明文档
- 🆕 README.md — 人类可读概览 + 快速导航
- 🆕 Wikilink 自动修复
- 🆕 标签库扩展（10 个新标签）

**新建页面：**

**concepts/ — 35个指标概念页（按指标名称分类）：**
- 三、四级手术实际开展率.md
- 上级医师查房记录规范率.md
- 临床用血后评估记录率.md
- 住院患者危急值当日及时处置率.md
- 住院患者非计划手术率.md
- 医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值.md
- 危急值报告时间.md
- 四级手术与三级手术并发症发生率比.md
- 四级手术与三级手术患者死亡率比.md
- 四级手术患者手术当日床旁交接班占比.md
- 四级手术术前多学科讨论完成率.md
- 实际手术术者与计划手术术者一致率.md
- 急会诊及时到位率.md
- 急会诊有效率.md
- 急危重症患者抢救成功率.md
- 患者入院48小时内转科的比例.md
- 患者入院8小时内查房率.md
- 手术医师手术时间重合率.md
- 手术患者特级护理一级护理出院率.md
- 新技术新项目留存转化率.md
- 普通会诊及时完成率.md
- 普通会诊有效率.md
- 术中自体血回输率.md
- 术前讨论完成率.md
- 术前讨论计划手术一致率.md
- 术者参加术前讨论率.md
- 死亡患者病案上传率.md
- 死亡病例讨论5日完成率.md
- 特殊使用级抗菌药物使用会诊率.md
- 科主任主持死亡病例讨论率.md
- 长期医嘱当日终止率.md
- 非计划再次住院手术患者疑难病例讨论完成率.md
- 非计划再次住院手术患者疑难病例讨论记录完整率.md
- 高额异常费用患者进行疑难病例讨论的占比.md
- 麻醉医师手术时间重合率.md

**concepts/ — 8个索引/技术页：**
- 四维分类索引.md
- 证据链验证模型.md
- ETL抽取逻辑说明.md
- 目标表-概览索引.md
- 目标表-科室统计索引.md
- 目标表-患者明细索引.md
- 源表索引.md
- XXJOB作业索引.md

**entities/ — 45个衍生指标实体页：**
- entities/HXZD-001-001_患者入院48小时内转科的比例.md
- entities/HXZD-002-001_患者入院8小时内查房率.md
- entities/HXZD-002-002_上级医师查房记录规范率.md
- entities/HXZD-002-003_住院患者非计划手术率.md
- entities/HXZD-003-001_急会诊及时到位率.md
- entities/HXZD-003-002_急会诊有效率.md
- entities/HXZD-003-003_001_普通会诊及时完成率.md
- entities/HXZD-003-003_002_普通会诊及时完成率_按会诊受邀科室数统计.md [变体]
- entities/HXZD-003-004_普通会诊有效率.md
- entities/HXZD-004-001_手术患者特级护理一级护理出院率.md
- entities/HXZD-005-001_四级手术患者手术当日床旁交接班占比.md
- entities/HXZD-006-001_非计划再次住院手术患者疑难病例讨论完成率.md
- entities/HXZD-006-002_非计划再次住院手术患者疑难病例讨论记录完整率.md
- entities/HXZD-006-003_001_高额异常费用患者进行疑难病例讨论的占比.md
- entities/HXZD-006-003_002_高额异常费用患者进行疑难病例讨论的占比_入区时间.md [变体]
- entities/HXZD-007-001_急危重症患者抢救成功率.md
- entities/HXZD-008-001_术前讨论完成率.md
- entities/HXZD-008-002_001_术者参加术前讨论率.md
- entities/HXZD-008-002_002_术者参加术前讨论率_出区时间.md [变体]
- entities/HXZD-008-003_术前讨论计划手术一致率.md
- entities/HXZD-008-004_实际手术术者与计划手术术者一致率.md
- entities/HXZD-009-001_死亡病例讨论5日完成率.md
- entities/HXZD-009-002_001_医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值.md
- entities/HXZD-009-002_002_医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值.md [变体]
- entities/HXZD-009-003_001_科主任主持死亡病例讨论率.md
- entities/HXZD-009-003_002_科主任主持死亡病例讨论率.md [变体]
- entities/HXZD-009-004_死亡患者病案上传率.md
- entities/HXZD-010-001_长期医嘱当日终止率.md
- entities/HXZD-011-001_手术医师手术时间重合率.md
- entities/HXZD-011-002_麻醉医师手术时间重合率.md
- entities/HXZD-012-001_001_四级手术与三级手术并发症发生率比.md
- entities/HXZD-012-001_002_四级手术与三级手术并发症发生率比.md [变体]
- entities/HXZD-012-002_001_四级手术与三级手术患者死亡率比.md
- entities/HXZD-012-002_002_四级手术与三级手术患者死亡率比.md [变体]
- entities/HXZD-012-003_001_四级手术术前多学科讨论完成率.md
- entities/HXZD-012-003_002_四级手术术前多学科讨论完成率.md [变体]
- entities/HXZD-012-004_001_三、四级手术实际开展率.md
- entities/HXZD-012-004_002_三、四级手术实际开展率_出区时间.md [变体]
- entities/HXZD-013-001_新技术新项目留存转化率.md
- entities/HXZD-014-001_危急值报告时间.md
- entities/HXZD-014-002_住院患者危急值当日及时处置率.md
- entities/HXZD-015-001_001_特殊使用级抗菌药物使用会诊率.md
- entities/HXZD-015-001_002_特殊使用级抗菌药物使用会诊率_会诊管理.md [变体]
- entities/HXZD-016-001_临床用血后评估记录率.md
- entities/HXZD-016-002_术中自体血回输率.md

**comparisons/ — 方案对比分析：**
- 35指标45衍生指标-主方案与变体差异分析.md

**queries/ — 查询结果存档：**
- 20260728170600_说明文档.md (35指标×45衍生指标完整说明文档)

**更新文件：**
- .wiki-schema.md — v60 元信息
- index.md — 含 35 指标概念页 + 45 实体页 wikilink
- log.md — 本摄入记录
- README.md — 人类可读概览 + 快速导航
- tutorials/ — 教程文件 (预留)

**指标覆盖：** 35项指标 × 45个衍生指标（10个变体可选方案）
**concepts 分类：** 按指标名称分类（v5/v6 核心特性）
**新增特性：** comparisons/ + queries/ + README.md + Wikilink 自动修复

---

## v61 — Agent 体系定义 (2026-07-29)

### 新增文件

- `AGENTS.md` — 全部智能体定义（3内置 + 3自定义）：IngestAgent、QueryAgent、LintAgent、IndicatorUnderstandingAgent、ComparisonAgent、EvidenceChainAgent
- `references/IndicatorUnderstandingAgent-技术方案.md` — IndicatorUnderstandingAgent 详细技术方案（IR设计、模块架构、六步流水线、实现路线图）

### 设计要点

- **IndicatorUnderstandingAgent**：指标编译引擎，输入自然语言/Excel/JSON → 输出 concept 页 + entity 页 + 索引更新
- **ComparisonAgent**：方案对比分析，自动识别 _001 vs _002 的 7 维差异
- **EvidenceChainAgent**：六层证据链验证，连接数据库逐层 PASS/FAIL 检查
- 所有 Agent 遵循全局规则：读写隔离、日志强制、标签合规、Wikilink 闭合、模板遵循、编码唯一

---

## v62 — knowledgeskill7 Skill 技能包创建 (2026-07-29)

### 新增 Skill 仓库

创建 `skills/knowledgeskill7/` 完整技能包（24 个文件）：

**核心文件（4个）：**
- `SKILL.md` — Skill 入口 + 5种工作流定义 + 6 Agent架构
- `SCHEMA.md` — 结构规范 + 标签库（继承v6 + 新增15个v7标签）
- `AGENTS.md` — 6 Agent完整定义（3内置+3自定义）
- `README.md` — Skill 级人类可读概览

**Agent 独立定义（7个）：**
- `agents/openai.yaml` — Skill 级 agent 注册配置
- `agents/ingest-agent.md` — 资料摄入编译
- `agents/query-agent.md` — 知识库问答检索
- `agents/lint-agent.md` — 健康巡检（12项检查 + 7项自动修复）
- `agents/indicator-understanding-agent.md` — 指标理解生成引擎 🆕
- `agents/comparison-agent.md` — 方案对比分析 🆕
- `agents/evidence-chain-agent.md` — 证据链验证 🆕

**模板（5个）：**
- `templates/entity-template.md` — 衍生实体页模板（继承v6）
- `templates/concept-template.md` — 概念页模板（继承v6）
- `templates/report-template.md` — 报告模板（继承v6）
- `templates/queries-template.md` — 查询文档模板（继承v6）
- `templates/ir-schema.json` — Indicator IR JSON Schema v1.0 🆕

**参考文件（3个）：**
- `references/indicator-map.md` — 指标映射表（继承v6）
- `references/evidence-chain.md` — 证据链规程（继承v6）
- `references/agent-workflows.md` — 5种工作流详细定义 🆕

**脚本（3个）：**
- `scripts/agent_router.js` — Agent 路由调度器 🆕
- `scripts/query_indicators.js` — 数据库查询（继承v6）
- `scripts/read_excel.js` — Excel解析（继承v6）

**模板文件（2个）：**
- `comparisons/variant-comparison-template.md` — 变体对比模板
- `queries/query-doc-template.md` — 查询文档模板

### 设计要点

- **6 Agent 协作网络**：从单体 Skill 升级为多Agent架构，每个Agent独立定义、独立触发
- **5 种工作流**：全量摄入(A) / 增量更新(B) / 报告生成(C) / 健康巡检(D) / 问答检索(E)
- **Indicator IR 协议**：统一 JSON Schema，Agent 间标准化通信
- **IR v1.0**：header + indicator + formula + scope + derivedEntities + evidenceChain + pages
- **v7 新增标签**：agent-generated / nl-generated / sql-generated / L1~L6-PASS 等15个
- **向后兼容**：完全兼容 knowledge-index-v60 目录结构、模板和编码体系
