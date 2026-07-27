# winning-winex-mras-aima Java 后端

本仓库直接以 Maven 工程根目录组织 Java 后端，不再套一层 `backend-java` 目录。运行时基于 Java 17、Spring Boot 3.5.16 和 Spring AI 1.1.8；审计与会话使用内嵌 SQLite，医院业务数据只通过 DBHub sidecar 访问。

## 模块职责

- `agent/ir`：`RequestPlan`、`CompiledPlanIR` 和失败分类。
- `agent/planning`：目标—计划一致性校验、能力注册、计划编译与校验、状态控制、确定性分派、统一失败路由和一次受限 Replan。
- `agent/runtime`：单指标执行循环、多指标 fan-out/fan-in 和 Trace 事件。
- `agent/tools`：工具注册、类型化上下文、策略判断和调用网关。
- `agent/evidence`：Evidence 记录、验证、过期和跨医院隔离。
- `agent/extraction`：源数据抽取的强类型网关与幂等契约；具体插入实现由业务抽取适配器提供。
- `agent/sql`：Wiki 当前/候选口径 SQL 规格渲染、只读校验、抽取后真实库计算，以及暂时保留的诊断执行能力。
- `details`：分子分母明细快照、分页、Excel 和上传逐条比较。
- `metadata`、`terminology`：双库元数据和医学术语能力。
- `api`：供 Vue 使用的 HTTP/SSE 接口。

每个生产包都有 `package-info.java` 中文职责与边界说明，每个顶层类型至少说明职责和禁止事项；核心状态机、安全边界和非直观业务分支使用方法 Javadoc 或原因型行内注释。`DocumentationConventionTest` 会阻止缺失、过短或放置位置错误的类型注释。不要为简单赋值和 getter 添加无信息注释。

## 测试

```powershell
cd F:\winning\winning-winex-mras-aima
$env:WIKI_KNOWLEDGE_ROOT = '知识库发布目录'
mvn.cmd -s .\maven-settings.xml test
```

知识库与迁移契约属于外部发布物，没有复制进本仓库。运行测试或服务时须通过 `WIKI_KNOWLEDGE_ROOT` 指向有效知识库；认证兼容测试还需要上层工程提供 `contracts/migration`。

## 构建与启动

```powershell
mvn.cmd -s .\maven-settings.xml package
java -jar .\target\wiki-agent-java-0.1.0-SNAPSHOT.jar
```

当前静态页面已经位于 `src/main/resources/static`，构建后随 JAR 一起发布。部署配置全部通过 `application.yml` 中声明的环境变量注入，不在仓库保存数据库连接或模型密钥。

常用地址：

- Vue 页面：`http://127.0.0.1:8765/`
- 健康检查：`GET /api/health`
- Java 运行时状态：`GET /api/runtime/status`
- Agent：`POST /api/agent/chat`、`POST /api/agent/chat/stream`
- Trace：`GET /api/agent/runs/{trace_id}`

## 运行约束

- 不直连医院 SQL Server；只调用 DBHub 配置好的只读工具。
- 单次统计周期最多一个月；普通指标计算固定为“受控抽取 → 真实库概览 SQL”，不再查询业务库或自动执行诊断明细。
- 普通计算要求抽取模式为 `required`；`SourceExtractionGateway` 写入失败时不得查询旧真实库快照，`disabled` 模式直接拒绝计算。
- 不允许模型或浏览器提交任意 SQL。
- Final Answer 只消费经过 Verifier 的 Evidence。
- Trace、Evidence 和会话不得保存密码、令牌、SQL 正文或患者原始行。
- 本地 Ollama 复合任务保持串行，API 模型默认最多并发 2。
