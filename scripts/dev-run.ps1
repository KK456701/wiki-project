# 开发模式启动后端（不打 jar 包，编译 class 后直接运行，带 devtools 热重启）
# 用法：
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/dev-run.ps1
#                                             # 启动服务（端口 8765）
#   服务运行中修改了代码后，另开一个终端执行：
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/dev-run.ps1 -Recompile
#                                             # 只增量编译，devtools 检测到 class 变化后自动热重启（秒级）
param(
    [switch]$Recompile,
    [int]$Port = 8765
)

$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot\..\backend-java"

# 抽取地址已改用项目专属变量 WIKI_DBHUB_BIZ_MCP_URL（application.yml），
# 终端残留的通用 DBHUB_BIZ_MCP_URL 不再生效；存在覆盖时启动前显式提示，避免静默指向错误地址。
if ($env:WIKI_DBHUB_BIZ_MCP_URL) {
    "WIKI_DBHUB_BIZ_MCP_URL override detected: $($env:WIKI_DBHUB_BIZ_MCP_URL)"
}
if ($env:DBHUB_BIZ_MCP_URL) {
    "Legacy DBHUB_BIZ_MCP_URL is ignored by the backend: $($env:DBHUB_BIZ_MCP_URL)"
}

if ($Recompile) {
    # 增量编译：devtools 监听 target/classes 变化，编译完成后服务自动重启
    mvn compile -q -DskipTests
    "Compilation complete; devtools will restart the service."
    exit 0
}

# 首次启动：spring-boot:run 只编译不打包，跳过测试。
# 带 dev profile：知识库指向用户实际维护的 knowledge-index_backup 备份目录
# （application-dev.yml）。模型和数据库连接由运行控制台持久化管理，不从环境变量读取。
# application-dev.yml 默认值是 classpath: 前缀（只读），保存医院草稿必须落在磁盘目录，
# 因此未显式配置 WIKI_KNOWLEDGE_INDEX_ROOT 时，默认指向本机的备份目录绝对路径。
if (-not $env:WIKI_KNOWLEDGE_INDEX_ROOT) {
    $knowledgeBackup = [IO.Path]::GetFullPath("$PSScriptRoot\..\backend-java\src\main\resources\knowledge-index_backup_20260801_150233")
    if (Test-Path -LiteralPath $knowledgeBackup) {
        $env:WIKI_KNOWLEDGE_INDEX_ROOT = $knowledgeBackup
    } else {
        "Knowledge backup directory is missing: $knowledgeBackup. Starting in classpath read-only mode."
    }
}
$profiles = if ($env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE } else { 'dev' }
mvn spring-boot:run -DskipTests "-Dspring-boot.run.jvmArguments=-Dspring.profiles.active=$profiles" "-Dspring-boot.run.arguments=--server.port=$Port"
