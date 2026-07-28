# 开发模式启动后端（不打 jar 包，编译 class 后直接运行，带 devtools 热重启）
# 用法：
#   pwsh scripts/dev-run.ps1              # 启动服务（端口 8765）
#   服务运行中修改了代码后，另开一个终端执行：
#   pwsh scripts/dev-run.ps1 -Recompile   # 只增量编译，devtools 检测到 class 变化后自动热重启（秒级）
param(
    [switch]$Recompile,
    [int]$Port = 8765
)

$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot\..\backend-java"

# 防止终端残留的进程级 DBHUB_BIZ_MCP_URL 覆盖正确配置
# （曾出现被覆盖成本地 5420 端口，导致抽取静默失败、指标算的是中间表旧数据）
$userBizMcp = [Environment]::GetEnvironmentVariable('DBHUB_BIZ_MCP_URL', 'User')
if ($userBizMcp -and $env:DBHUB_BIZ_MCP_URL -ne $userBizMcp) {
    "检测到进程级 DBHUB_BIZ_MCP_URL=$($env:DBHUB_BIZ_MCP_URL)，已纠正为用户级配置：$userBizMcp"
    $env:DBHUB_BIZ_MCP_URL = $userBizMcp
}

if ($Recompile) {
    # 增量编译：devtools 监听 target/classes 变化，编译完成后服务自动重启
    mvn compile -q -DskipTests
    "编译完成，devtools 将自动热重启服务。"
    exit 0
}

# 首次启动：spring-boot:run 只编译不打包，跳过测试
mvn spring-boot:run -DskipTests "-Dspring-boot.run.arguments=--server.port=$Port"
