$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "wxp-tooling.ps1")
Import-WxpLocalEnvironment

$pinnedCommit = "79e6a6e2b0f7150d4f88e0c3766c0171c50cac73"
$repositoryUrl = $env:WXP_MCP_REPOSITORY_URL
$targetCommit = if ($env:WXP_MCP_COMMIT) { $env:WXP_MCP_COMMIT } else { $pinnedCommit }
$sourceDirectory = Get-WxpSourceDirectory

if (-not $repositoryUrl) {
    throw "未配置 WXP_MCP_REPOSITORY_URL。请复制 wxp.env.example 为 wxp.local.env 并填写公司内部仓库地址。"
}
if ($targetCommit -notmatch "^[0-9a-fA-F]{40}$") {
    throw "WXP_MCP_COMMIT 必须是 40 位 Git 提交哈希。"
}

Assert-WxpCommand "git"
Assert-WxpCommand "node"
Assert-WxpCommand "npm"

$nodeVersion = (& node --version).Trim().TrimStart("v")
if ([int]($nodeVersion.Split(".")[0]) -lt 18) {
    throw "wxp-mcp 要求 Node.js 18 或更高版本，当前版本为 $nodeVersion。"
}

New-Item -ItemType Directory -Force -Path (Split-Path $sourceDirectory) | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $sourceDirectory ".git"))) {
    Write-Host "正在下载公司表模型工具..."
    & git clone --no-checkout --quiet $repositoryUrl $sourceDirectory 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "下载 wxp-mcp 失败，请检查公司网络和仓库权限。"
    }
} else {
    Write-Host "已发现本地源码，正在检查固定版本..."
}

& git -C $sourceDirectory fetch --quiet origin $targetCommit 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "无法获取固定版本，请检查仓库权限或提交哈希。"
}
& git -C $sourceDirectory checkout --quiet --detach $targetCommit
if ($LASTEXITCODE -ne 0) {
    throw "无法切换到固定版本。"
}

$actualCommit = (& git -C $sourceDirectory rev-parse HEAD).Trim()
if ($actualCommit -ne $targetCommit) {
    throw "版本校验失败，当前版本与固定版本不一致。"
}

Push-Location $sourceDirectory
try {
    Write-Host "正在安装依赖并构建..."
    & npm ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) {
        throw "npm ci 失败，请检查 npm 网络或锁文件。"
    }
    & npm run build
    if ($LASTEXITCODE -ne 0) {
        throw "wxp-mcp 构建失败。"
    }
} finally {
    Pop-Location
}

$entrypoint = Join-Path $sourceDirectory "dist\index.js"
if (-not (Test-Path -LiteralPath $entrypoint)) {
    throw "构建完成但未找到 dist/index.js。"
}

Write-Host "wxp-mcp 已安装并通过版本校验。"
Write-Host "版本：$actualCommit"
