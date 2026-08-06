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

# 抽取地址已改用项目专属变量 WIKI_DBHUB_BIZ_MCP_URL（application.yml），
# 终端残留的通用 DBHUB_BIZ_MCP_URL 不再生效；存在覆盖时启动前显式提示，避免静默指向错误地址。
if ($env:WIKI_DBHUB_BIZ_MCP_URL) {
    "检测到 WIKI_DBHUB_BIZ_MCP_URL=$($env:WIKI_DBHUB_BIZ_MCP_URL)，将覆盖 yml 默认抽取地址。"
}
if ($env:DBHUB_BIZ_MCP_URL) {
    "提示：终端残留 DBHUB_BIZ_MCP_URL=$($env:DBHUB_BIZ_MCP_URL)，后端已不再读取该变量，可忽略。"
}

if ($Recompile) {
    # 增量编译：devtools 监听 target/classes 变化，编译完成后服务自动重启
    mvn compile -q -DskipTests
    "编译完成，devtools 将自动热重启服务。"
    exit 0
}

# 首次启动：spring-boot:run 只编译不打包，跳过测试。
# 带 dev profile：知识库指向用户实际维护的 knowledge-index_backup 备份目录
# （application-dev.yml），DB 连接凭据仍优先读 WIKI_BIZDB_*/WIKI_SQLSERVER_* 环境变量。
$profiles = if ($env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE } else { 'dev' }
mvn spring-boot:run -DskipTests "-Dspring-boot.run.jvmArguments=-Dspring.profiles.active=$profiles" "-Dspring-boot.run.arguments=--server.port=$Port"
