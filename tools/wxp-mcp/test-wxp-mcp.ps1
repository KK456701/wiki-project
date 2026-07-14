param(
    [ValidateSet("basic", "platform")][string]$Mode = "basic",
    [string]$Project = "",
    [string]$Module = "",
    [string]$Table = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "wxp-tooling.ps1")
Import-WxpLocalEnvironment
Assert-WxpCommand "node"

$entrypoint = Join-Path (Get-WxpSourceDirectory) "dist\index.js"
if (-not (Test-Path -LiteralPath $entrypoint)) {
    throw "尚未构建 wxp-mcp，请先运行 install-wxp-mcp.ps1。"
}

$arguments = @(
    (Join-Path $PSScriptRoot "mcp-smoke-test.mjs"),
    "--mode", $Mode,
    "--entrypoint", $entrypoint
)
if ($Mode -eq "platform") {
    if (-not $Project -or -not $Table) {
        throw "平台检测必须填写 Project 和 Table。"
    }
    $arguments += @(
        "--project", $Project,
        "--module", $Module,
        "--table", $Table
    )
}

& node @arguments
if ($LASTEXITCODE -ne 0) {
    throw "wxp-mcp 检测未通过，请根据上方提示处理。"
}
