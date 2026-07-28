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

if ($Recompile) {
    # 增量编译：devtools 监听 target/classes 变化，编译完成后服务自动重启
    mvn compile -q -DskipTests
    "编译完成，devtools 将自动热重启服务。"
    exit 0
}

# 首次启动：spring-boot:run 只编译不打包，跳过测试
mvn spring-boot:run -DskipTests "-Dspring-boot.run.arguments=--server.port=$Port"
