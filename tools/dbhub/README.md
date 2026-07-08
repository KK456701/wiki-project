# DBHub MCP 本地数据库工具

DBHub 在本项目中作为医院本地部署的数据库 MCP sidecar 使用。它的作用是让 Agent 通过受控工具查看业务库有哪些表、字段、字段类型和注释，并在只读权限下执行必要的元数据查询。

DBHub 不是本项目的 Python 模块，也不建议把 DBHub 源码或 `node_modules` 直接提交到仓库。医院部署时应把它作为独立本地服务启动，FastAPI 通过 `config.yaml` 中的 `dbhub_mcp_url` 调用它。

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
dbhub_execute_tool_hospital_demo_data: "execute_sql_hospital_demo_data"
dbhub_source_id_hospital_demo_data: "hospital_demo_data"
dbhub_execute_tool_wiki_agent_runtime: "execute_sql_wiki_agent_runtime"
dbhub_source_id_wiki_agent_runtime: "wiki_agent_runtime"
```

当前模板默认配置两个数据库：

- `hospital_demo_data`：医院业务明细库，用于看业务表、字段和样例数据。
- `wiki_agent_runtime`：Agent 运行库，用于看元数据缓存、审批记录、诊断报告等运行数据。

两个库都只暴露只读 SQL 工具。DBHub 当前版本的 TOML 里必须使用内置工具名 `execute_sql` 和 `search_objects`；多数据源启动后，DBHub 会对外暴露成 `execute_sql_hospital_demo_data`、`execute_sql_wiki_agent_runtime` 这类带数据源后缀的工具名。查“有什么表、有什么字段”本质上也是执行只读 SQL 查询 `INFORMATION_SCHEMA`，所以 `execute_sql_*` 工具已经能满足元数据同步、表结构查看和字段查看。`search_objects_*` 作为补充工具保留，后续如果需要自然语言搜索数据库对象再接入。

## 离线部署建议

医院内网无法联网时，不要在本仓库提交 `node_modules`。推荐在外网机器提前下载 DBHub npm 包和依赖包，放入医院内部软件仓库，再在内网执行本地安装。

## 项目如何使用

FastAPI 已支持：

```http
POST /api/metadata/sync
```

请求示例：

```json
{
  "hospital_id": "hospital_001",
  "db_name": "hospital_demo_data",
  "source": "dbhub"
}
```

这会通过 DBHub MCP 调用对应数据库的 `execute_sql_*` 工具，读取 `INFORMATION_SCHEMA`，同步业务库表结构和字段信息，并生成结构变更记录与受影响指标分析。
