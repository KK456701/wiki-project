$ErrorActionPreference = "Stop"

$config = Join-Path $PSScriptRoot "dbhub.local.toml"
if (-not (Test-Path $config)) {
  throw "Missing dbhub.local.toml. Copy dbhub.toml.example to dbhub.local.toml and fill read-only database DSNs."
}

$hostName = if ($env:DBHUB_HOST) { $env:DBHUB_HOST } else { "127.0.0.1" }
$port = if ($env:DBHUB_PORT) { $env:DBHUB_PORT } else { "8080" }

Write-Host "Starting DBHub MCP sidecar: http://$hostName`:$port/mcp"
Write-Host "Make sure dbhub.local.toml uses read-only database accounts in production."
npx @bytebase/dbhub@latest --transport http --host $hostName --port $port --config $config
