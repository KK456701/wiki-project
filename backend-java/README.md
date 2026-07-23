# Java 运行时

`backend-java` 是项目唯一后端，基于 Java 17、Spring Boot 3.5.16 和 Spring AI 1.1.8。Vue 3 生产资源会打包到同一个 JAR；运行库使用内嵌 SQLite，医院业务数据只通过 DBHub sidecar 只读访问。

## 模块职责

- `agent/ir`：`RequestPlan`、`CompiledPlanIR` 和失败分类。
- `agent/planning`：能力注册、计划编译与校验、状态控制、确定性分派、统一失败路由和一次受限 Replan。
- `agent/runtime`：单指标执行循环、多指标 fan-out/fan-in 和 Trace 事件。
- `agent/tools`：工具注册、类型化上下文、策略判断和调用网关。
- `agent/evidence`：Evidence 记录、验证、过期和跨医院隔离。
- `agent/sql`：Wiki SQL 规格渲染、只读校验、对象保存和 DBHub 试运行。
- `details`：分子分母明细快照、分页、Excel 和上传逐条比较。
- `implementation`：新增指标草稿、字段映射、试运行、审批、发布和恢复。
- `metadata`、`terminology`、`monitoring`：元数据、医学术语和指标监控工作台。
- `api`：供 Vue 使用的 HTTP/SSE 接口。

每个生产包都有 `package-info.java` 中文职责与边界说明，每个顶层类型至少说明职责和禁止事项；核心状态机、安全边界和非直观业务分支使用方法 Javadoc 或原因型行内注释。`DocumentationConventionTest` 会阻止缺失、过短或放置位置错误的类型注释。不要为简单赋值和 getter 添加无信息注释。

## 测试

```powershell
cd F:\A-wiki-project\backend-java
mvn.cmd -s .\maven-settings.xml test
```

## 构建单 JAR

从项目根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-java-vue.ps1
```

输出 JAR 必须包含 `BOOT-INF/classes/static/index.html`。启动器会检查该入口，避免误用不含 Vue 的后端-only JAR。

## 启动

```powershell
cd F:\A-wiki-project
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-java-runtime.ps1 -Port 8765
```

启动配置优先来自当前进程环境，其次由脚本读取根目录 `config.yaml` 的必要顶层标量。详细配置、接口和安全边界见根目录 [`README.md`](../README.md)。

常用地址：

- Vue 页面：`http://127.0.0.1:8765/`
- 健康检查：`GET /api/health`
- Java 运行时状态：`GET /api/runtime/status`
- Agent：`POST /api/agent/chat`、`POST /api/agent/chat/stream`
- Trace：`GET /api/agent/runs/{trace_id}`

## 运行约束

- 不直连医院 SQL Server；只调用 DBHub 配置好的只读工具。
- 不允许模型或浏览器提交任意 SQL。
- Final Answer 只消费经过 Verifier 的 Evidence。
- Trace、Evidence 和会话不得保存密码、令牌、SQL 正文或患者原始行。
- 本地 Ollama 复合任务保持串行，API 模型默认最多并发 2。
