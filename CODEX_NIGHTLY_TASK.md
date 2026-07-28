# Codex 夜间执行任务书

> 生成时间：2026-07-24 | 项目：f:\A-wiki-project | 分支：main
> 远程仓库：https://github.com/KK456701/wiki-project.git
> 构建命令：`cd backend-java && mvn clean test -q` | 前端：`cd frontend-vue && npm run build`
> 提交规范：中文 commit message，每完成一个阶段 commit + push

---

## 第一部分：交接文档（Qoder 接手后做了什么）

### 1.1 背景

Codex 之前完成了基础架构搭建（Agent 运行时、知识库、批量执行、前端对话等），但存在以下问题：
- CI 全红（编译错误 + 测试失败）
- 抽取网关接入方式错误（旧 `BusinessMcpSourceExtractionGateway` 的 contract 验证逻辑与同事实际代码不匹配）
- `ExtractionRequest` 缺少关键字段导致编译失败
- 多处构造器签名不一致

### 1.2 已提交的修复（git log f975ea60 → 2e53cc07）

| 提交 | 内容 |
|------|------|
| `f975ea60` | **核心纠正**：新建 `McpSyncSourceExtractionGateway` 正确接入同事 biz-mcp 抽取代码（execCustomQuery），禁用旧 `BusinessMcpSourceExtractionGateway` |
| `7712f293` | 移除 SQL Server 写入禁止 sa 账号限制（开发环境需要） |
| `e11f60fd` | 拓宽时间正则，支持月份级别表达；修复"怎么算+时间"被误判为纯解释 |
| `0cbe1e8e` | 意图升级时带入原文时间表达；代理排除内网 172.16/172.17 |
| `92bb123c` | 启动脚本强制用 config.yaml 覆盖 DBHUB_BIZ_MCP_URL |
| `f7b6a02f` | batchInsert 先查目标表实际列名，过滤 MCP 返回多余列 |
| `43604de5` | 数据写入对齐 SyncDataService（Snowflake 主键 + resolveLocalColumns） |
| `2e53cc07` | SSE 超时 5→15 分钟；NOT NULL 无默认值列自动填充时间戳 |

### 1.3 未提交的本地修改（当前 working tree）

**新增文件（untracked）：**
- `backend-java/src/main/java/com/hospital/wikiagent/api/SyncDataController.java`
- `backend-java/src/main/java/com/hospital/wikiagent/dto/package-info.java`
- `backend-java/src/main/java/com/hospital/wikiagent/service/package-info.java`
- `backend-java/src/main/java/com/hospital/wikiagent/sqlserver/SqlServerJpaConfig.java`
- `backend-java/src/main/resources/knowledge-index-mras/`（领导知识库，37 个实体页 + concepts + templates）

**删除文件：**
- `backend-java/src/main/java/com/hospital/wikiagent/sqlserver/SqlServerWriteConfig.java`（被 SqlServerJpaConfig 替代）

**修改文件（85+ 个）：** 主要包括：
- `ExtractionRequest.java` — 新增 `hospitalSoid` 和 `extractionContract` 字段
- `SourceExtractionGateway.java` — 接口新增 `prepare()` 方法
- `DualDatabaseIndicatorExecutionWorkflow.java` — 构造器对齐新接口
- `McpSyncSourceExtractionGateway.java` — 正确实现同事的抽取逻辑
- `pom.xml` — 添加 hutool/lombok/jakarta.persistence-api 依赖
- 35 个 `runtime.json` — 补充 data_lineage 和 extraction_contract
- 所有测试文件 — 适配新构造器签名

### 1.4 纠正 Codex 的关键错误

| 问题 | Codex 原实现 | 纠正后 |
|------|-------------|--------|
| 抽取网关 | 使用 `BusinessMcpSourceExtractionGateway`（本地 DBHub 直连 + 严格 contract 验证） | 新建 `McpSyncSourceExtractionGateway`（走 biz-mcp-url 的 `execCustomQuery` 工具） |
| ExtractionRequest | 只有 15 个字段 | 补充 `hospitalSoid`（MCP 路由用）和 `extractionContract`（依赖表清单） |
| SourceExtractionGateway 接口 | 只有 `extract()` 和 `available()` | 新增 `prepare()` 方法（预检连接和表存在性） |
| 数据写入 | 直接 INSERT 所有 MCP 返回列 | 先 `resolveLocalColumns` 只插目标表存在的列 + Snowflake 主键 |
| SqlServerWriteConfig | 独立配置类 | 合并为 `SqlServerJpaConfig`（解决 bean 冲突） |

### 1.5 当前遗留问题（7 个测试失败）

**必须在第一步修复，否则 CI 仍然红：**

1. **BusinessMcpSourceExtractionGatewayTest（3 个失败）**
   - 原因：旧网关的 `ExtractionContract` 验证逻辑拒绝测试数据（`EXTRACTION_CONTRACT_INVALID`）
   - 修复方案：给这 3 个测试方法加 `@Disabled("旧网关已禁用，由 McpSyncSourceExtractionGateway 替代")`
   - 文件：`backend-java/src/test/java/com/hospital/wikiagent/agent/extraction/BusinessMcpSourceExtractionGatewayTest.java`

2. **WikiRuleKnowledgeSourceTest（3 个失败）**
   - 原因：测试期望 `hospital_001` 解析到 `execution_status: "executable"`，但指针解析可能未正确读取 release 包
   - 文件：`backend-java/src/test/java/com/hospital/wikiagent/rules/WikiRuleKnowledgeSourceTest.java`
   - 指针文件：`core-rules-wiki/pointers/hospitals/hospital_001-current.json` → 指向 `KB-20260727-HOSPITAL001-QA-DIAG-FIX9`
   - Release runtime.json 路径：`core-rules-wiki/releases/hospitals/hospital_001/KB-20260727-HOSPITAL001-QA-DIAG-FIX9/sql-specs/HXZD-001-001/runtime.json`（其中 `execution_status: "executable"`）
   - 修复方案：检查 `WikiRuleKnowledgeSource.effectiveRule()` 的指针解析逻辑，确保 hospital_001 能正确 fallback 到 release 包

3. **IndicatorSqlToolsTest（1 个失败）**
   - 原因：同上，HXZD-001-001 在公司级是 `documentation_only`，测试期望 executable
   - 文件：`backend-java/src/test/java/com/hospital/wikiagent/agent/sql/IndicatorSqlToolsTest.java`

**修复后验证：** `cd backend-java && mvn test` 全部通过 → commit + push

---

## 第二部分：接入领导知识库（不修改知识库文件）

> 知识库路径：`backend-java/src/main/resources/knowledge-index-mras/`
> 核心原则：**只读接入，不修改任何 knowledge-index-mras 下的文件**

### 2.1 知识库结构说明

```
knowledge-index-mras/
├── index.md                    # 总索引（35指标37实体）
├── entities/                   # 37个实体页（每个含4段SQL + 元数据）
│   ├── HXZD-001-001_患者入院48小时内转科的比例_入区时间.md
│   ├── HXZD-002-001_患者入院8小时内查房率.md
│   └── ... (共37个)
├── concepts/                   # 35个概念页（指标定义、计算公式、统计口径）
├── templates/                  # SQL模板片段
├── queries/                    # 查询说明文档
├── references/                 # 指标映射表
└── comparisons/                # 方案对比分析
```

**实体页 SQL 结构（以 HXZD-001-001 为例）：**
- `## 源表` — ETL 抽取 SQL（含 `#EQUALS{:syncType; outHosp; ...}`）
- `## 目标表-概览` — 概览统计 SQL（含 `#ETC{...}`、`#EQUALS{...}`、`""别名""`、`(NOLOCK)`）
- `## 目标表-科室统计` — 科室下钻 SQL
- `## 目标表-患者明细` — 患者级明细 SQL

**模板占位符语法：**
- `#ETC{SQL片段}` — 条件注入：有对应参数就保留，没有就整行删除
- `#EQUALS{:param; value; SQL片段}` — 分支匹配：参数值=value 时保留 SQL片段，否则删除
- `""别名""` — 双引号别名（SQL Server 方言，需转为 `"别名"`）
- `TABLE (NOLOCK)` — 缺少 WITH 关键字（需转为 `TABLE WITH (NOLOCK)`）
- 命名参数：`:marptBeginAt`、`:marptEndAt`、`:startTime`、`:endTime`、`:deptIdIn`、`:qualified`、`:status`、`:syncType`

### 2.2 第一步：模板 SQL 解析器

**新建文件：** `backend-java/src/main/java/com/hospital/wikiagent/agent/mras/MrasTemplateRenderer.java`

```java
package com.hospital.wikiagent.agent.mras;

/**
 * 解析领导知识库的 #ETC{} / #EQUALS{} 模板语法。
 * 注意：这与现有 SqlTemplateRenderer（Jinja风格 {{ }}）完全不同，是独立的解析器。
 */
public class MrasTemplateRenderer {
    /**
     * @param templateSql 原始模板 SQL（从实体页提取）
     * @param params      用户/系统提供的参数 Map
     * @return 渲染后的可执行 SQL
     */
    public String render(String templateSql, Map<String, Object> params);
}
```

**解析规则：**
1. `#ETC{ AND event.HOSPITAL_AREA_ID IN (:hospitalAreaList) }` → 如果 params 包含 `hospitalAreaList` 则保留 `AND event.HOSPITAL_AREA_ID IN (:hospitalAreaList)`，否则删除整行
2. `#EQUALS{:qualified; 98175; AND d.监测情况 < ...}` → 如果 params.get("qualified") == "98175" 则保留 `AND d.监测情况 < ...`，否则删除
3. 替换命名参数：`:marptBeginAt` → `'2025-01-01 00:00:00'`（加引号）
4. 后处理：`""别名""` → `"别名"`；`TABLE (NOLOCK)` → `TABLE WITH (NOLOCK)`

**测试文件：** `backend-java/src/test/java/com/hospital/wikiagent/agent/mras/MrasTemplateRendererTest.java`
- 测试 #ETC 有参数/无参数
- 测试 #EQUALS 匹配/不匹配
- 测试参数替换
- 测试双引号别名修复
- 测试 NOLOCK 修复
- 测试组合场景（从 HXZD-001-001 实体页取真实 SQL）

### 2.3 第二步：实体页 SQL 提取器

**新建文件：** `backend-java/src/main/java/com/hospital/wikiagent/agent/mras/EntityPageParser.java`

```java
package com.hospital.wikiagent.agent.mras;

/**
 * 从 knowledge-index-mras/entities/*.md 中解析结构化数据。
 * 启动时扫描 37 个实体页，缓存为 Map<String, EntityPageData>。
 */
@Component
public class EntityPageParser {
    // 按 ## 章节标题切割 Markdown
    // 提取 ```sql ... ``` 代码块
    // 提取元数据段落（指标定义、统计口径、监测参数、数据来源）
    public Map<String, EntityPageData> getAllEntities();
    public EntityPageData getEntity(String indicatorCode); // e.g. "HXZD-001-001"
}
```

**EntityPageData 结构：**
```java
public record EntityPageData(
    String code,              // HXZD-001-001
    String name,              // 患者入院48小时内转科的比例
    String definition,        // 指标定义文本
    String formula,           // 计算公式
    String caliber,           // 统计口径
    String dataSource,        // 数据来源
    String monitorParams,     // 监测参数
    String sourceTableSql,    // ## 源表 SQL
    String overviewSql,       // ## 目标表-概览 SQL
    String deptStatSql,       // ## 目标表-科室统计 SQL
    String patientDetailSql   // ## 目标表-患者明细 SQL
) {}
```

**解析逻辑：**
- 按 `## ` 前缀切割章节
- SQL 块用 ` ```sql ` 和 ` ``` ` 包围
- 元数据在章节开头的非 SQL 段落中
- 文件名格式：`HXZD-XXX-XXX_指标名称_后缀.md`，从文件名提取 code

**测试：** 验证 37 个实体页全部正确解析，每个至少有 overviewSql

### 2.4 第三步：参数映射层

**新建文件：** `backend-java/src/main/java/com/hospital/wikiagent/agent/mras/MrasParameterMapper.java`

| 用户/Agent 参数 | 映射到领导知识库参数 | 说明 |
|----------------|---------------------|------|
| 开始时间 | `:marptBeginAt` / `:startTime` | 两个都要填，格式 `yyyy-MM-dd HH:mm:ss` |
| 结束时间 | `:marptEndAt` / `:endTime` | 同上 |
| 科室过滤（可选） | `:deptIdIn` | 逗号分隔的科室 ID |
| 达标状态（可选） | `:qualified` / `:status` | 98175=达标, 98176=未达标 |
| 同步模式（ETL用） | `:syncType` | 默认 `outHosp` |

```java
@Component
public class MrasParameterMapper {
    /**
     * 将 Agent 解析出的时间范围 + 可选过滤条件 → 领导知识库参数 Map
     */
    public Map<String, Object> mapParameters(
        LocalDateTime start, LocalDateTime end,
        String deptFilter,    // nullable
        String qualifiedFilter // nullable
    );
}
```

### 2.5 第四步：接入现有 Agent 流程

**修改文件：** `IndicatorSqlTools.java` 或新建 `MrasSqlExecutionService.java`

接入点：
1. **概览查询**：当 `execution_status == "executable"` 时，优先从 `EntityPageParser` 取 overviewSql → `MrasTemplateRenderer.render()` → 通过 DBHub 执行
2. **新增意图**：
   - `INDICATOR_DEPT_DRILL` — 科室下钻（用 deptStatSql）
   - `INDICATOR_PATIENT_DETAIL` — 患者明细（用 patientDetailSql）
3. **解释上下文**：把 `definition`、`caliber`、`dataSource`、`monitorParams` 喂给 FinalAnswerComposer 做 LLM 解释

**关键约束：**
- 不修改 `knowledge-index-mras/` 下任何文件
- SQL 执行仍走现有 `IndicatorBusinessQueryClient`（DBHub MCP）
- 参数绑定仍走 `SqlParameterBinder`（防注入）

### 2.6 第五步：逐步替换 core-rules-wiki/sql-specs

- 领导知识库稳定后，`sql-specs/` 下的 `overview_sql_template` 可降级为备份
- 保留 `runtime.json` 作为程序索引（execution_status、result_mapping、declared_parameters）
- SQL 正文改从 `EntityPageParser` 读取
- 在 `runtime.json` 中新增字段 `"sql_source": "mras"` 标记已迁移的指标

### 2.7 诊断功能接入

接入领导知识库后，诊断（`IndicatorDiagnosisTools`）可以：
- 从实体页取 `源表 SQL` 做数据血缘追踪
- 从 `监测参数` 段落提取阈值配置
- 用 `目标表-患者明细` SQL 做异常患者定位
- 在 `IndicatorDifferenceDiagnosisWorkflow` 中用领导知识库的口径定义做差异归因

---

## 第三部分：UX 改进

### 3.1 意图选择改为可点击选项（不要用户打字）

**问题：** 当意图识别不清时，系统应该给出 35 个指标作为可选项，而不是让用户自己输入。

**修改文件：** `ClarificationPromptFactory.java`

**要求：**
- `indicator_selection` 类型：列出所有 35 个指标（code + name），前端渲染为可点击按钮
- `intent_selection` 类型：列出 5 种意图（查看定义/计算结果/生成SQL/排查异常/对比文件），可点击
- `time_range` 类型：改为下拉框（见 3.4）
- `caliber_selection` 类型：列出该指标的所有口径选项，可点击
- **所有澄清问题都必须是选择题，不允许让用户自由输入**

### 3.2 修复死循环问题

**问题：** 有些意图没经过模型就直接让用户选择，然后一直循环问。

**修改文件：** `AgentRunner.java`、`CompoundAgentRuntime.java`

**修复方案：**
1. 添加 `clarificationCount` 计数器到 `AgentRunState`
2. 同一会话中澄清次数 ≥ 3 时，强制使用默认值继续执行（不再问）
3. 默认值策略：
   - 时间未确定 → 默认"今年至今"
   - 意图未确定 → 默认"计算具体结果"
   - 指标未确定 → 不允许默认，必须选择（但只问一次，用列表展示）
4. 检测循环：如果连续 2 次生成相同类型的 clarification，第 2 次直接跳过用默认值
5. 前端：澄清选项点击后直接发送，不再弹出输入框

### 3.3 数据抽取去重

**问题：** 同一个数据、同一个范围、同一个口径，不需要重复抽取。

**修改文件：** `McpSyncSourceExtractionGateway.java`、`DualDatabaseIndicatorExecutionWorkflow.java`

**方案：**
1. 新建 `ExtractionCache`（内存 ConcurrentHashMap）
2. Key = `hospitalSoid + "|" + indicatorCode + "|" + startTime + "|" + endTime + "|" + caliberProfileId`
3. Value = 抽取完成时间戳 + 数据快照 ID
4. TTL = 30 分钟（与 SQL_TTL 一致）
5. 执行抽取前先查缓存，命中则跳过抽取直接计算

### 3.4 时间选择改为下拉框 + 限制一年

**修改文件：**
- `StatPeriodPolicy.java` — 将 1 个月限制改为 1 年
- `ClarificationPromptFactory.java` — 时间选项改为下拉框
- `TimeRangeResolver.java` — 增强模糊时间解析

**StatPeriodPolicy 修改：**
```java
// 原：LocalDateTime latestEnd = start.plusMonths(1);
// 改：LocalDateTime latestEnd = start.plusYears(1);
// 错误消息改为："单次指标查询最多允许一年..."
```

**时间下拉选项（ClarificationPromptFactory）：**
```
今年至今 | 最近一年 | 最近半年 | 最近3个月 | 最近1个月 | 本月 | 上月 | 自定义范围
```

**TimeRangeResolver 增强（必须全部支持）：**
- `2025.01.01-2025.06.30`（点分隔）
- `2025/1/1到2025/6/30`（斜杠分隔，无前导零）
- `2025年1月1号到6月30号`（中文"号"）
- `20250101-20250630`（纯数字）
- `2025,01,01`（逗号分隔）
- `去年`、`前年`、`去年3月到今年2月`
- `最近N天/周/月/年`
- 所有格式混合：`从25年5月开始到2025-06-01`

### 3.5 指标串行执行 + 逐个展示

**问题：** 指标只能串行跑（因为一个指标需要的患者数据另一个也可能要），算一个展示一个。

**修改文件：** `BatchIndicatorRuntime.java`、`PreparedIndicatorExecutor.java`

**方案：**
1. 保持串行执行（已有），但改为 **SSE 逐条推送**
2. 每完成一个指标，立即通过 SSE 推送该指标结果（不等全部完成）
3. 结果格式：
   ```
   ## HXZD-001-001 患者入院48小时内转科的比例
   - 分母（同期入院患者总人次数）：1234
   - 分子（入院48小时内转科患者人次数）：56
   - 指标值：4.54%
   - 分子明细：[查看](链接)
   - 分母明细：[查看](链接)
   ```

### 3.6 分子/分母明细表

**问题：** 需要一张表记录分子明细，一张记录分母明细，避免重算。

**方案：**
1. 在 `DualDatabaseIndicatorExecutionWorkflow` 执行概览 SQL 后，额外执行患者明细 SQL
2. 结果存入内存（或临时表）：
   - `numerator_details` — 分子患者明细（ENCOUNTER_ID, 患者ID, 科室, 入区时间, 转科时间...）
   - `denominator_details` — 分母患者明细（ENCOUNTER_ID, 患者ID, 科室, 入区时间...）
3. 前端展示为可展开的表格
4. 缓存策略：与抽取缓存同生命周期（30分钟）

---

## 第四部分：验收测试计划

> 共 22 个对话场景，每个约 10 轮交互。
> 测试环境：启动后端 `mvn spring-boot:run`，前端 `npm run dev`
> 验收标准：每轮交互的预期结果必须完全匹配

### 对话 1：单指标精确查询（HXZD-001-001）

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 帮我算一下患者入院48小时内转科的比例 | 识别 HXZD-001-001，询问时间范围（下拉框） |
| 2 | （点击"今年至今"） | 开始计算，返回分母/分子/指标值 |
| 3 | 分母明细给我看看 | 返回分母患者明细表 |
| 4 | 分子明细呢 | 返回分子患者明细表 |
| 5 | 这个指标是怎么算的 | 返回指标定义、计算公式、统计口径 |
| 6 | 换成上个月的 | 重新计算上月数据（不重新抽取） |
| 7 | 按科室看看 | 返回科室下钻统计 |
| 8 | 哪个科室最差 | 指出最高比例的科室 |
| 9 | 达标标准是什么 | 返回监测参数和达标阈值 |
| 10 | 好的谢谢 | 礼貌结束 |

### 对话 2：模糊时间表达测试

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 算一下今年1月到6月的转科率 | 正确解析 2026-01-01 至 2026-06-30 |
| 2 | 改成2025.03.01到2025.09.30 | 正确解析点分隔格式 |
| 3 | 看看去年同期的 | 解析为 2025-03-01 至 2025-09-30 |
| 4 | 20250101-20250630呢 | 正确解析纯数字格式 |
| 5 | 25年3月到9月 | 正确解析为 2025-03-01 至 2025-09-30 |
| 6 | 从2025年1月1号到6月30号 | 正确解析"号"格式 |
| 7 | 最近3个月 | 正确计算当前日期往前推3个月 |
| 8 | 2025/1/1到2025/6/30 | 正确解析斜杠无前导零 |
| 9 | 前年 | 正确解析为 2024-01-01 至 2024-12-31 |
| 10 | 2025,01,01到2025,06,30 | 正确解析逗号分隔 |

### 对话 3：意图中途切换

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | HXZD-002-001怎么算 | 返回患者入院8小时内查房率的定义和公式 |
| 2 | 帮我算一下结果 | 切换为计算意图，询问时间 |
| 3 | 今年 | 计算并返回结果 |
| 4 | 给我SQL看看 | 切换为生成SQL意图，返回受控SQL |
| 5 | 这个结果对不对，帮我排查一下 | 切换为诊断意图 |
| 6 | 算了还是看结果吧，换成上月的 | 切换回计算意图 + 时间变更 |
| 7 | 按科室分解 | 科室下钻 |
| 8 | 这个指标的数据来源是什么 | 返回数据来源说明 |
| 9 | 和上传的文件对比一下 | 切换为文件对比意图 |
| 10 | 不用了，回到概览 | 返回概览结果 |

### 对话 4：指标中途切换

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 算一下急会诊及时到位率 | 识别 HXZD-003-001，询问时间 |
| 2 | 本月 | 计算 HXZD-003-001 本月结果 |
| 3 | 换成急会诊有效率看看 | 切换为 HXZD-003-002，保持本月 |
| 4 | 普通会诊及时完成率呢 | 切换为 HXZD-003-003 |
| 5 | 这两个有什么区别 | 对比 HXZD-003-002 和 HXZD-003-003 的定义 |
| 6 | 都算一下 | 串行计算两个指标 |
| 7 | 再加上普通会诊有效率 | 追加 HXZD-003-004 |
| 8 | 哪个最差 | 比较三个指标结果 |
| 9 | 最差那个的分子明细 | 展示对应指标的分子明细 |
| 10 | 好的 | 结束 |

### 对话 5：口径切换

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | HXZD-006-003有几种口径 | 列出所有口径选项（可点击） |
| 2 | （点击"出区时间"口径） | 用该口径计算 |
| 3 | 换成入区时间口径 | 切换口径重新计算 |
| 4 | 两种口径结果差多少 | 对比两种口径结果 |
| 5 | 为什么会有差异 | 解释口径差异原因 |
| 6 | 用出区时间口径看科室分布 | 切换回出区时间 + 科室下钻 |
| 7 | 口径是什么意思 | 解释统计口径概念 |
| 8 | 这个指标的国家标准是什么 | 返回监测参数和达标标准 |
| 9 | 帮我生成SQL | 生成当前口径的受控SQL |
| 10 | 谢谢 | 结束 |

### 对话 6：批量指标计算（全部35个）

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 帮我算所有指标 | 识别批量意图，确认时间范围 |
| 2 | 今年至今 | 开始串行计算35个指标，逐个推送结果 |
| 3 | （等待中...） | 每个指标完成后立即展示分母/分子/值 |
| 4 | 目前算了几个了 | 返回进度（如"已完成 12/35"） |
| 5 | （继续等待） | 继续推送 |
| 6 | 有没有不达标的 | 汇总已完成的指标中不达标的 |
| 7 | 全部算完了吗 | 返回最终汇总 |
| 8 | 给我汇总表格 | 35个指标的汇总 Markdown 表格 |
| 9 | 哪几个最差 | 排序展示最差的5个 |
| 10 | 最差的详细看看 | 展示最差指标的明细 |

### 对话 7：制度查询 + 批量小运算

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 会诊制度的指标有哪些 | 列出 HXZD-003 系列（4个）+ 定义 |
| 2 | 这些指标怎么算的 | 逐个返回计算公式 |
| 3 | 都帮我算一下本月的 | 串行计算4个会诊指标 |
| 4 | 术前讨论制度呢 | 列出 HXZD-008 系列（4个） |
| 5 | 也算一下 | 串行计算4个术前讨论指标 |
| 6 | 死亡病例讨论制度 | 列出 HXZD-009 系列（4个） |
| 7 | 算 | 串行计算 |
| 8 | 三个制度对比一下 | 汇总对比12个指标 |
| 9 | 哪个制度执行最差 | 分析并指出 |
| 10 | 给个改进建议 | 基于数据给出建议 |

### 对话 8：模糊意图识别

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 转科 | 识别为 HXZD-001-001，询问意图（可点击选项） |
| 2 | （点击"计算具体结果"） | 询问时间 |
| 3 | 最近 | 解析为最近30天（或询问确认） |
| 4 | 查房 | 识别为 HXZD-002-001/002，列出选项 |
| 5 | 8小时那个 | 确认为 HXZD-002-001 |
| 6 | 手术相关的有哪些 | 列出所有手术相关指标（可选） |
| 7 | 术前讨论完成率 | 选择 HXZD-008-001 |
| 8 | 和术者参加术前讨论率有什么区别 | 对比 HXZD-008-001 和 HXZD-008-002 |
| 9 | 都算一下今年的 | 串行计算两个 |
| 10 | 好的 | 结束 |

### 对话 9：时间中途修改

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 算HXZD-004-001今年的 | 计算 2026-01-01 至今 |
| 2 | 时间改成去年 | 重新计算 2025-01-01 至 2025-12-31 |
| 3 | 只看下半年 | 调整为 2025-07-01 至 2025-12-31 |
| 4 | 范围再大一点，看全年的 | 恢复为 2025 全年 |
| 5 | 对比一下去年和今年 | 两个时间段对比 |
| 6 | 按月分解看看趋势 | 12个月逐月结果 |
| 7 | 哪个月最差 | 指出最差月份 |
| 8 | 那个月的患者明细 | 展示对应月份明细 |
| 9 | 时间改回今年至今 | 切换回 2026 年 |
| 10 | 好的 | 结束 |

### 对话 10：抽取去重验证

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 算HXZD-005-001本月 | 首次计算（触发抽取） |
| 2 | 再算一次HXZD-005-001本月 | 命中缓存，不重新抽取，直接返回 |
| 3 | 换个口径再算 | 不同口径，可能需要重新计算但不重新抽取 |
| 4 | HXZD-001-001本月 | 不同指标，需要新抽取 |
| 5 | 再算HXZD-005-001本月 | 仍命中缓存 |
| 6 | 算HXZD-005-001上月 | 不同时间范围，需要新抽取 |
| 7 | HXZD-005-001本月再看一次 | 命中缓存 |
| 8 | 数据是什么时候抽的 | 告知缓存时间 |
| 9 | 强制刷新 | 清除缓存重新抽取 |
| 10 | 好的 | 结束 |

### 对话 11：HXZD-007-001 急危重症抢救成功率

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 急危重症患者抢救成功率怎么算 | 返回定义和公式 |
| 2 | 算一下今年的 | 计算结果 |
| 3 | 分母是什么 | 解释分母（急危重症患者总数） |
| 4 | 分子明细 | 展示抢救成功的患者列表 |
| 5 | 分母明细 | 展示所有急危重症患者列表 |
| 6 | 按科室看 | 科室下钻 |
| 7 | ICU的明细 | 过滤ICU科室 |
| 8 | 和去年比怎么样 | 对比去年数据 |
| 9 | 达标了吗 | 对照监测参数判断 |
| 10 | 好的 | 结束 |

### 对话 12：HXZD-009 系列（死亡病例讨论）

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 死亡病例讨论5日完成率 | 识别 HXZD-009-001 |
| 2 | 今年至今 | 计算结果 |
| 3 | 科主任主持率呢 | 切换 HXZD-009-003 |
| 4 | 病案上传率 | 切换 HXZD-009-004 |
| 5 | 三个一起算 | 串行计算3个 |
| 6 | 医务部门讨论比值 | HXZD-009-002 |
| 7 | 这个比值什么意思 | 解释指标含义 |
| 8 | 四个指标汇总 | 汇总表格 |
| 9 | 哪个需要改进 | 分析最差的 |
| 10 | 明细给我 | 展示最差指标的明细 |

### 对话 13：HXZD-011 手术时间重合率

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 手术医师手术时间重合率 | 识别 HXZD-011-001 |
| 2 | 这是什么意思 | 解释定义 |
| 3 | 算一下半年内的 | 计算最近6个月 |
| 4 | 麻醉医师的呢 | 切换 HXZD-011-002 |
| 5 | 两个有什么区别 | 对比定义 |
| 6 | 都算一下 | 串行计算两个 |
| 7 | 重合率高说明什么 | 解释业务含义 |
| 8 | 哪些医师重合率高 | 按医师统计 |
| 9 | 明细 | 展示明细 |
| 10 | 好的 | 结束 |

### 对话 14：HXZD-012 手术相关指标

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 四级手术并发症发生率比 | 识别 HXZD-012-001 |
| 2 | 怎么算的 | 返回公式 |
| 3 | 算今年 | 计算 |
| 4 | 三级手术死亡率比呢 | HXZD-012-002 |
| 5 | 术前多学科讨论完成率 | HXZD-012-003 |
| 6 | 三四级手术实际开展率 | HXZD-012-004 |
| 7 | 四个都算 | 串行计算 |
| 8 | 汇总 | 表格展示 |
| 9 | 和去年对比 | 年度对比 |
| 10 | 好的 | 结束 |

### 对话 15：HXZD-014 危急值

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 危急值报告时间是什么意思 | 解释 HXZD-014-001 |
| 2 | 和及时处置率有什么关系 | 解释两者关系 |
| 3 | 都算一下本月 | 串行计算 HXZD-014-001 和 014-002 |
| 4 | 报告时间超标了怎么办 | 给出建议 |
| 5 | 哪些科室报告慢 | 科室下钻 |
| 6 | 最慢的科室明细 | 展示明细 |
| 7 | 时间改成今年 | 重新计算 |
| 8 | 趋势怎么样 | 按月趋势 |
| 9 | 达标情况 | 对照标准 |
| 10 | 好的 | 结束 |

### 对话 16：HXZD-016 用血相关

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 用血后评估记录率 | 识别 HXZD-016-001 |
| 2 | 算一下 | 询问时间 |
| 3 | 最近一年 | 计算 |
| 4 | 自体血回输率呢 | HXZD-016-002 |
| 5 | 也算一下 | 计算 |
| 6 | 两个指标有关系吗 | 解释关系 |
| 7 | 哪些科室用血多但不评估 | 交叉分析 |
| 8 | 明细 | 展示 |
| 9 | 国家标准是多少 | 返回达标标准 |
| 10 | 好的 | 结束 |

### 对话 17：完全模糊输入

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 指标 | 列出所有35个指标供选择 |
| 2 | 手术 | 过滤出手术相关指标（约8个） |
| 3 | 四级 | 进一步过滤（约4个） |
| 4 | 并发症那个 | 确认 HXZD-012-001 |
| 5 | 看看 | 询问意图（可点击） |
| 6 | （点击"计算具体结果"） | 询问时间 |
| 7 | （点击"今年至今"） | 计算 |
| 8 | 换个 | 列出指标选择 |
| 9 | 开展率 | 确认 HXZD-012-004 |
| 10 | 同上 | 用相同时间计算 |

### 对话 18：错误输入和边界情况

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | HXZD-999-999 | 友好提示不存在，列出可选指标 |
| 2 | asdfgh | 无法识别，给出引导 |
| 3 | 算一下2020年到2030年 | 提示超出1年限制，建议缩小范围 |
| 4 | 2025年13月 | 提示月份无效 |
| 5 | 空的时间范围 | 提示需要时间 |
| 6 | （连续3次不回答澄清问题） | 第3次使用默认值继续 |
| 7 | 同时算100个指标 | 提示最多35个 |
| 8 | 给我所有患者的身份证号 | 拒绝（隐私保护） |
| 9 | DROP TABLE | 安全拒绝 |
| 10 | 正常问题：算HXZD-001-001本月 | 正常计算 |

### 对话 19：HXZD-010 + HXZD-013（长期医嘱 + 新技术）

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 长期医嘱当日终止率 | 识别 HXZD-010-001 |
| 2 | 什么意思 | 解释定义 |
| 3 | 算今年 | 计算 |
| 4 | 新技术新项目留存转化率 | HXZD-013-001 |
| 5 | 这个怎么算 | 解释公式 |
| 6 | 也算一下 | 计算 |
| 7 | 两个指标有关联吗 | 分析 |
| 8 | 科室对比 | 两个指标的科室分布 |
| 9 | 明细 | 展示 |
| 10 | 好的 | 结束 |

### 对话 20：HXZD-015 抗菌药物 + 诊断功能

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 特殊使用级抗菌药物使用会诊率 | 识别 HXZD-015-001 |
| 2 | 算本月 | 计算 |
| 3 | 结果好像不对 | 触发诊断意图 |
| 4 | 和业务系统的数据对不上 | 进入差异诊断流程 |
| 5 | 差异在哪里 | 展示差异分析 |
| 6 | 哪些患者有差异 | 展示差异患者明细 |
| 7 | 是口径问题还是数据问题 | 诊断归因 |
| 8 | 换成正确口径重算 | 切换口径重新计算 |
| 9 | 现在对上了吗 | 验证 |
| 10 | 好的 | 结束 |

### 对话 21：领导知识库接入验证

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | HXZD-001-001的概览 | 从领导知识库取 overviewSql 执行 |
| 2 | 科室统计 | 从领导知识库取 deptStatSql 执行 |
| 3 | 患者明细 | 从领导知识库取 patientDetailSql 执行 |
| 4 | 数据来源是什么 | 从实体页元数据返回 |
| 5 | 监测参数 | 返回监测参数配置 |
| 6 | 统计口径 | 返回统计口径说明 |
| 7 | SQL长什么样 | 展示渲染后的SQL（参数已替换） |
| 8 | 模板SQL原文呢 | 展示原始模板（含#ETC等） |
| 9 | 和core-rules-wiki的SQL一样吗 | 对比两个来源 |
| 10 | 好的 | 结束 |

### 对话 22：综合压力测试

| 轮次 | 用户输入 | 预期结果 |
|------|---------|---------|
| 1 | 帮我算所有手术相关指标今年的 | 识别手术类指标（~8个），串行计算 |
| 2 | 时间改成去年下半年 | 全部重新计算（2025-07-01至2025-12-31） |
| 3 | 只看三四级手术相关的 | 过滤为 HXZD-012 系列 |
| 4 | 口径换一下 | 列出口径选项 |
| 5 | （选择另一个口径） | 用新口径重算 |
| 6 | 和之前的口径对比 | 两种口径结果对比 |
| 7 | 最差那个的明细 | 展示明细 |
| 8 | 按科室分解 | 科室下钻 |
| 9 | 给我完整报告 | 汇总所有已算指标 |
| 10 | 导出 | 提供导出选项 |

---

## 第五部分：执行顺序和验证检查点

### 执行顺序

```
阶段0：修复7个测试失败 → mvn test 全绿 → commit + push
阶段1：MrasTemplateRenderer + 测试 → commit
阶段2：EntityPageParser + 测试 → commit
阶段3：MrasParameterMapper + 测试 → commit
阶段4：接入 Agent 流程 → mvn test → commit
阶段5：UX改进（死循环修复 + 时间下拉 + 去重） → commit
阶段6：串行执行 + 逐个展示 + 明细表 → commit
阶段7：前端适配（选项点击 + 下拉框 + 进度展示） → npm run build → commit
阶段8：全量验证 → mvn test + npm run build → push
```

### 每个阶段的验证

- 阶段0：`mvn test` 0 failures, 0 errors
- 阶段1-3：对应单元测试通过
- 阶段4：`mvn test` 全绿
- 阶段5-6：`mvn test` 全绿
- 阶段7：`npm run build` 成功
- 阶段8：CI 全绿（Node validation + mvn test + npm run build）

### 关键约束

1. **不修改** `knowledge-index-mras/` 下任何文件
2. **不修改** 现有 `SqlTemplateRenderer.java`（Jinja风格），新建独立的 `MrasTemplateRenderer`
3. 所有 SQL 执行必须走 DBHub MCP（`IndicatorBusinessQueryClient`），不能直连数据库
4. 参数绑定必须走 `SqlParameterBinder`（防注入）
5. 每次 commit 使用中文 message
6. 远程仓库：`https://github.com/KK456701/wiki-project.git`，分支 `main`
