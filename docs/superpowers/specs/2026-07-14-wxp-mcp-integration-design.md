# wxp-mcp 公司表模型工具接入设计

## 1. 目标

将公司内部 `wxp-mcp` 作为本项目的公司侧实施工具接入，使实施人员能够查询 WxP 运营平台中的表结构、字段、索引和数据血缘，并把确认后的信息用于指标设计稿中的医院字段映射。

本批只解决下载、构建、启动和连通性验证，不把 `wxp-mcp` 接入医院生产运行链路，也不让它直接生成或发布医院指标 SQL。

## 2. 已确认信息

- 源码仓库：公司内部 TFS，地址由本机 `WXP_MCP_REPOSITORY_URL` 配置提供，不写入公开仓库。
- npm 包名：`@winning/wxp-mcp`
- 当前版本：`1.1.0`
- Node.js 要求：`>=18`
- MCP 默认传输方式：`stdio`
- 当前固定提交：`79e6a6e2b0f7150d4f88e0c3766c0171c50cac73`
- 仓库真实默认分支：`master`
- WinCode 目录页标注的 `main` 已过期，安装脚本不得依赖该标注。
- 公司平台凭据通过 `WXP_TENANTSESSION` 提供。

核心工具包括：

- `table_analysis`：查询表字段、索引、数据血缘和关联产品线。
- `model_query_class_id`：根据项目、模块和表名查询模型实体 `classId`。
- `model_analyze`：根据 `classId` 查询实体、字段、关系和血缘分析。
- `standard_search`：搜索公司基准库。
- `standard_production_params`：读取基准库最近制作参数。
- `standard_production_logs`：读取基准库制作日志和失败原因。

## 3. 架构定位

`wxp-mcp` 只部署在公司侧实施环境，作用是帮助实施人员理解公司标准表模型。医院生产环境继续使用本地 DBHub MCP 读取医院 `INFORMATION_SCHEMA` 和只读业务数据，二者职责不同：

| 工具 | 使用位置 | 查询内容 | 是否进入医院生产运行链路 |
| --- | --- | --- | --- |
| `wxp-mcp` | 公司侧实施环境 | 公司 WxP 标准模型、字段、索引和血缘 | 否 |
| DBHub MCP | 医院本地环境 | 医院实际表结构和只读业务数据 | 是 |

公司模型只能提供候选标准语义，不能自动决定医院字段映射或指标口径。最终映射必须由实施人员和业务人员确认，并经过测试库试运行和审批发布。

## 4. 目录设计

```text
tools/wxp-mcp/
├── README.md
├── install-wxp-mcp.ps1
├── start-wxp-mcp.ps1
├── test-wxp-mcp.ps1
├── wxp.env.example
└── vendor/wxp-mcp/
```

- `README.md`：面向实施人员说明安装、配置、验证、更新和故障定位。
- `install-wxp-mcp.ps1`：检查环境，从 TFS 下载固定提交，安装依赖并构建。
- `start-wxp-mcp.ps1`：读取本机凭据，以 `stdio` 方式启动 MCP。
- `test-wxp-mcp.ps1`：执行基础协议检测和可选的公司平台查询检测。
- `wxp.env.example`：只保留变量名和占位值，不包含内部仓库地址或真实 Session。
- `vendor/wxp-mcp/`：本地下载的公司源码，整体忽略，不提交到当前公开仓库。

## 5. 安全边界

1. `vendor/`、`wxp.local.env`、构建日志和查询结果必须被 Git 忽略。
2. `WXP_MCP_REPOSITORY_URL` 和 `WXP_TENANTSESSION` 只能来自进程环境变量或本机忽略文件。
3. 安装、启动和检测脚本不得输出 Session 原文。
4. 公司内部源码不得复制或提交到当前公开 Git 仓库。
5. 表模型查询结果默认只显示必要摘要，不自动写入项目文件。
6. `wxp-mcp` 不接收、查询或传输患者明细数据。
7. 医院部署包不得包含 `wxp-mcp` 源码、公司地址、Session 或公司模型查询结果。

## 6. 安装流程

`install-wxp-mcp.ps1` 按以下顺序执行：

1. 检查 `git`、`node` 和 `npm` 是否可用。
2. 校验 Node.js 主版本不低于 18。
3. 从本机 `WXP_MCP_REPOSITORY_URL` 读取公司内部仓库地址，并将源码克隆到 `tools/wxp-mcp/vendor/wxp-mcp/`。
4. 获取远程更新并切换到固定提交 `79e6a6e2b0f7150d4f88e0c3766c0171c50cac73`。
5. 校验当前 `HEAD` 与固定提交一致。
6. 执行 `npm ci`，确保依赖与锁文件一致。
7. 执行 `npm run build`。
8. 检查 `dist/index.js` 是否存在，输出中文成功摘要。

脚本重复执行时应复用已有本地仓库，并恢复到固定提交，不自动跟随远程最新分支。后续升级必须显式修改固定提交并重新验证。

## 7. 启动与凭据

默认使用 `stdio` 传输启动：

```text
node tools/wxp-mcp/vendor/wxp-mcp/dist/index.js
```

凭据配置示例：

```text
WXP_TENANTSESSION=请填写从 WxP 运营中心复制的 Session
WXP_MCP_REPOSITORY_URL=请填写 WinCode 目录页提供的公司内部源码仓库地址
```

实施人员从 WxP 运营中心右上角工号菜单中的“查看Session”获取凭据。Session 过期后需要重新获取并更新本机配置，不修改项目文件。

## 8. 两级验证

### 8.1 基础 MCP 检测

该检测不要求有效 Session：

1. 启动 MCP 子进程。
2. 发送 MCP `initialize` 请求。
3. 发送 `notifications/initialized`。
4. 发送 `tools/list`。
5. 检查核心工具是否存在。
6. 正常关闭子进程。

成功标准：MCP 协议握手成功，且至少包含 `table_analysis`、`model_query_class_id` 和 `model_analyze`。

### 8.2 公司平台查询检测

该检测要求本机配置有效 `WXP_TENANTSESSION`，并由实施人员提供已知的项目名、模块名和表名：

1. 调用 `model_query_class_id` 获取目标表的 `classId`。
2. 调用 `table_analysis` 获取表字段、索引和血缘摘要。
3. 只输出工具调用状态、命中表名、字段数量、索引数量和血缘关系数量。

成功标准：两个工具均返回成功，且结果中包含目标表的结构信息。检测脚本不得把完整模型结果落盘。

## 9. 故障处理

检测脚本应区分并给出中文处理建议：

- 公司内网或 TFS 不可访问。
- Git、Node.js 或 npm 未安装。
- Node.js 版本低于 18。
- 固定提交不存在或下载不完整。
- `npm ci` 或 TypeScript 构建失败。
- MCP 进程未启动或协议握手超时。
- `WXP_TENANTSESSION` 未配置。
- Session 已过期或无访问权限。
- 项目、模块或表模型不存在。
- MCP 返回结构与检测脚本预期不一致。

失败后应保留可重试条件，但不得保留或打印敏感凭据。

## 10. 与指标设计稿的数据流

```text
公司 WxP 标准表模型
    ↓ wxp-mcp 查询
实施人员查看标准表、字段、索引和数据血缘
    ↓ 与医院 INFORMATION_SCHEMA 和数据字典对照
确认指标分子、分母所需的医院表字段
    ↓ 录入指标设计稿
生成指标 SQL
    ↓ 测试库试运行
审批发布
    ↓
医院部署包
```

`wxp-mcp` 查询结果只是字段映射的参考来源。系统不得因为字段名称相似就自动发布映射，也不得跳过业务确认、试运行或审批。

## 11. 本批范围

本批实现：

- 项目内可复现的下载、构建和启动脚本。
- 本地凭据示例和 Git 忽略规则。
- 基础 MCP 协议与工具清单检测。
- 可选的公司平台模型查询检测。
- 中文使用文档和脚本级自动化测试。

本批不实现：

- FastAPI 运行时调用 `wxp-mcp`。
- 医院前端直接查询公司 WxP 平台。
- 自动把公司字段映射写入指标设计稿。
- 自动生成或发布医院指标 SQL。
- 将公司 MCP 或公司模型数据打入医院部署包。

## 12. 验收标准

1. 一条 PowerShell 命令可以下载固定版本并完成构建。
2. 一条 PowerShell 命令可以完成 MCP 协议和工具清单检测。
3. 配置有效 Session 和已知表模型后，可以完成一次公司平台查询检测。
4. 重复安装不会无条件升级到未知版本。
5. 检测失败时能够显示可执行的中文处理建议。
6. Git 变更中不包含公司源码、Session、模型查询结果或患者数据。
7. README 能指导实施人员把确认后的模型信息用于指标设计稿，而不会误解为自动发布口径。
