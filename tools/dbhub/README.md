# DBHub MCP 本地数据库工具（已由内嵌 Java MCP 替代）

> **2026-08-06 起：数据库 MCP 已内嵌进后端 jar。** 后端通过 Spring AI MCP Server
> （`spring-ai-starter-mcp-server-webmvc`，STATELESS 协议）在自身 `/mcp` 端点上
> 暴露 `execute_sql_winex_all_dev`、`execute_sql_winex_aima`、`search_objects`
> 三个工具，内部直接走 `wiki.bizdb` / `wiki.sqlserver` JDBC 直连（语句级只读
> 校验 + 行数/超时限制）。部署时不再需要启动本目录的 DBHub sidecar，
> 一个 jar 一键启动即可。本目录内容仅作为历史方案保留。

DBHub 在本项目中曾作为医院本地部署的数据库 MCP sidecar 使用。它的作用是让 Agent 通过受控工具查看业务库有哪些表、字段、字段类型和注释，并在只读权限下执行必要的元数据查询。

DBHub 不是本项目的 Java 模块，也不建议把 DBHub 源码或 `node_modules` 直接提交到仓库。医院部署时应把它作为独立本地服务启动，Spring Boot 通过 `config.yaml` 中的 `dbhub_mcp_url` 调用它。

## 安全要求

- 默认绑定 `127.0.0.1`，不要直接暴露到院内网或公网。
- 数据库连接必须使用只读账号。
- `execute_sql` 工具必须配置为只读模式。
- 不要把真实数据库连接串、账号、密码提交到 Git。
- 指标口径、SQL 校验、审批流程仍由本项目负责，DBHub 只提供数据库访问能力。

## 在线安装启动

```powershell
cd F:\A-wiki-project\tools\dbhub
Copy-Item dbhub.toml.example dbhub.local.toml
# 编辑 dbhub.local.toml，填入医院本地只读数据库账号
npm install
.\start-dbhub.ps1
```

启动后 MCP 地址默认是：

```text
http://127.0.0.1:8080/mcp
```

然后在项目根目录的 `config.yaml` 中配置：

```yaml
dbhub_mcp_url: "http://127.0.0.1:8080/mcp"
dbhub_timeout_seconds: 10
dbhub_business_source_id: "winex_all_dev"
dbhub_business_execute_tool: "execute_sql_winex_all_dev"
dbhub_real_source_id: "winex_aima"
dbhub_real_execute_tool: "execute_sql_winex_aima"
```

当前系统只允许两个数据库：

- `winex_all_dev`：业务库，用于源业务数据只读查询。
- `winex_aima`：真实库，用于抽取后数据的只读核对。

两个库都只暴露只读 SQL 工具。DBHub 当前版本的 TOML 里使用内置工具名 `execute_sql` 和 `search_objects`；多数据源启动后，对外工具名分别为 `execute_sql_winex_all_dev` 和 `execute_sql_winex_aima`。运行库继续使用应用本地 SQLite，不经 DBHub 访问。

## 离线部署建议

医院内网无法联网时，不要在本仓库提交 `node_modules`。推荐在外网机器提前下载 DBHub npm 包和依赖包，放入医院内部软件仓库，再在内网执行本地安装。

## 项目如何使用

Spring Boot 已支持：

```http
POST /api/metadata/sync
```

请求示例：

```json
{
  "hospital_id": "hospital_001",
  "db_name": "winex_all_dev",
  "source": "dbhub"
}
```

这会通过 DBHub MCP 调用对应数据库的 `execute_sql_*` 工具，读取 `INFORMATION_SCHEMA`，同步业务库表结构和字段信息，并生成结构变更记录与受影响指标分析。
