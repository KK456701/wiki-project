$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "wxp-tooling.ps1")
Import-WxpLocalEnvironment
Assert-WxpCommand "node"

$entrypoint = Join-Path (Get-WxpSourceDirectory) "dist\index.js"
if (-not (Test-Path -LiteralPath $entrypoint)) {
    throw "尚未构建 wxp-mcp，请先运行 install-wxp-mcp.ps1。"
}

Write-Host "正在以 stdio 模式启动公司表模型工具。"
Write-Host "该工具只用于公司侧实施，不连接医院患者数据。"
& node $entrypoint
if ($LASTEXITCODE -ne 0) {
    throw "wxp-mcp 进程异常退出，退出码：$LASTEXITCODE"
}
