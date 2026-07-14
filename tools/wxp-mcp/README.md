# wxp-mcp 公司表模型工具

`wxp-mcp` 是公司侧实施工具，用于查看 WxP 标准表模型、字段、索引、术语值和数据血缘。它不进入医院生产运行链路，也不能替代医院本地 DBHub。

本目录只提交安装器、检测脚本和说明文档。公司源码、内部仓库地址、Session 和模型查询结果都保存在本机，不得提交到 Git。

## 使用前准备

- Windows PowerShell 5.1 或更高版本。
- Git。
- Node.js 18 或更高版本。
- 能够访问公司内部源码仓库的网络和权限。
- 只有执行公司平台检测时才需要有效的 `WXP_TENANTSESSION`。

## 首次安装

在项目根目录执行：

```powershell
cd F:\A-wiki-project\tools\wxp-mcp
Copy-Item wxp.env.example wxp.local.env
```

打开本机 `wxp.local.env`，填写从 WinCode MCP 目录页获得的 `WXP_MCP_REPOSITORY_URL`。不要把内部地址写入公开脚本或文档。

然后执行：

```powershell
.\install-wxp-mcp.ps1
```

安装器会把源码放到被 Git 忽略的 `vendor\wxp-mcp`，校验固定提交，执行 `npm ci` 和 `npm run build`。重复执行不会自动升级到未知版本。

## 基础检测

```powershell
.\test-wxp-mcp.ps1 -Mode basic
```

基础检测不需要 Session。它会启动 MCP，依次执行协议初始化和工具清单查询，并确认以下核心工具存在：

- `table_analysis`
- `model_query_class_id`
- `model_analyze`

检测成功会输出简短 JSON，其中包含状态、模式和工具数量，不包含公司模型详情。

## 公司平台检测

平台恢复可用后，在本机 `wxp.local.env` 中配置有效的 `WXP_TENANTSESSION`。如果平台地址发生迁移，可以同时配置本机 `WXP_API_HOST`，不要把真实地址提交到 Git。

使用一个已知模型表执行：

```powershell
.\test-wxp-mcp.ps1 -Mode platform -Project "WiNEX" -Module "" -Table "PARAMETER"
```

平台检测会先查询模型实体 `classId`，再读取表结构。输出只包含命中表名、字段数量、索引数量和血缘关系数量，不保存完整模型结果。

## 作为 MCP 服务启动

```powershell
.\start-wxp-mcp.ps1
```

该命令以 `stdio` 模式启动，供公司侧实施工具或 MCP 客户端使用。医院生产服务不需要启动它。

## 如何用于指标设计稿

`wxp-mcp` 提供公司标准模型的候选信息。实施人员还必须将这些信息与医院 `INFORMATION_SCHEMA`、医院数据字典和指标口径逐项核对。

只有经过人工确认的医院表字段才能录入指标设计稿，并继续执行 SQL 生成、测试库试运行和审批发布。字段名称相似不能作为自动发布映射的依据。

## 固定版本与升级

当前固定提交为：

```text
79e6a6e2b0f7150d4f88e0c3766c0171c50cac73
```

升级时应先查看公司仓库变更，验证工具名、输入参数和返回结构，再同步修改：

1. `wxp.env.example` 中的 `WXP_MCP_COMMIT`。
2. `install-wxp-mcp.ps1` 中的固定提交。
3. `mcp-smoke-test.mjs` 和自动化测试中的协议预期。
4. 接入设计、实施计划和本 README。

完成全部测试后才能提交升级。不要直接跟随远程 `master` 最新提交。

## 故障处理

### 未配置仓库地址

复制 `wxp.env.example` 为 `wxp.local.env`，仅在本机填写 `WXP_MCP_REPOSITORY_URL`。

### 下载或构建失败

先确认公司网络、仓库权限、Git、npm 和 Node.js 版本。Node.js 必须为 18 或更高版本。

### 基础检测失败

重新执行安装器，确认 `vendor\wxp-mcp\dist\index.js` 已生成。如果提示缺少核心工具，说明固定版本的 MCP 接口发生了变化，需要先更新检测契约。

### Session 未配置或已过期

基础检测不受影响。平台检测需要从当前可用的 WxP 运营中心重新获取 Session，并更新本机 `wxp.local.env`。

### 模型表未命中

检查 `Project`、`Module` 和 `Table`。确认使用的是公司模型中的英文表名，而不是医院本地自定义表名。

## 安全要求

- `wxp.local.env`、`vendor/`、Session、内部仓库地址和查询结果不得提交 Git。
- 不要在聊天、截图、日志或故障反馈中粘贴 Session。
- 不要把 wxp-mcp 或公司模型数据放入医院部署包。
- 不要使用该工具查询或传输患者明细数据。
