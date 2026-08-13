$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$frontendDist = Join-Path $projectRoot 'winning-webui-mras-aima-develop\dist'
$backendStatic = Join-Path $projectRoot 'backend-java\src\main\resources\static'
$targetApp = Join-Path $backendStatic 'webui-mras-aima'
$legacyAssets = Join-Path $backendStatic 'assets'
$legacyIndex = Join-Path $backendStatic 'index.html'

if (-not (Test-Path -LiteralPath (Join-Path $frontendDist 'index.html') -PathType Leaf)) {
    throw 'Frontend dist/index.html is missing. Run the frontend build first.'
}
if (-not (Test-Path -LiteralPath (Join-Path $frontendDist 'assets') -PathType Container)) {
    throw 'Frontend dist/assets is missing. Run the frontend build first.'
}
$resolvedRoot = [System.IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\'
$resolvedTarget = [System.IO.Path]::GetFullPath($targetApp)
if (-not $resolvedTarget.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to replace frontend outside the workspace: $resolvedTarget"
}

New-Item -ItemType Directory -Path $backendStatic -Force | Out-Null
if (Test-Path -LiteralPath $targetApp) {
    Remove-Item -LiteralPath $targetApp -Recurse -Force
}
Copy-Item -LiteralPath $frontendDist -Destination $targetApp -Recurse -Force

if (Test-Path -LiteralPath $legacyAssets) {
    Remove-Item -LiteralPath $legacyAssets -Recurse -Force
}
if (Test-Path -LiteralPath $legacyIndex) {
    Remove-Item -LiteralPath $legacyIndex -Force
}
Write-Output "Frontend synchronized to: $targetApp"
