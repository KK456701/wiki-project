$ErrorActionPreference = "Stop"

$config = Join-Path $PSScriptRoot "dbhub.local.toml"
if (-not (Test-Path $config)) {
  throw "Missing dbhub.local.toml. Copy dbhub.toml.example to dbhub.local.toml and fill read-only database DSNs."
}

$hostName = if ($env:DBHUB_HOST) { $env:DBHUB_HOST } else { "127.0.0.1" }
$port = if ($env:DBHUB_PORT) { $env:DBHUB_PORT } else { "8080" }

Write-Host "Starting DBHub MCP sidecar: http://$hostName`:$port/mcp"
Write-Host "Make sure dbhub.local.toml uses read-only database accounts in production."
$localEntry = Join-Path $PSScriptRoot "node_modules\@bytebase\dbhub\dist\index.js"
if (-not (Test-Path -LiteralPath $localEntry)) {
  throw "Missing local DBHub package. Run npm install in tools/dbhub first."
}

# 固定使用项目已安装版本，避免 npx @latest 联网解析、启动延迟和孤儿进程。
& node $localEntry --transport http --host $hostName --port $port --config $config
exit $LASTEXITCODE
