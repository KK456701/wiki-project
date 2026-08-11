$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$frontendDist = Join-Path $projectRoot 'frontend-vue\dist'
$backendStatic = Join-Path $projectRoot 'backend-java\src\main\resources\static'
$sourceAssets = Join-Path $frontendDist 'assets'
$targetAssets = Join-Path $backendStatic 'assets'

if (-not (Test-Path -LiteralPath (Join-Path $frontendDist 'index.html') -PathType Leaf)) {
    throw 'Frontend dist/index.html is missing. Run the frontend build first.'
}
if (-not (Test-Path -LiteralPath $sourceAssets -PathType Container)) {
    throw 'Frontend dist/assets is missing. Run the frontend build first.'
}
$resolvedRoot = [System.IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\'
$resolvedTarget = [System.IO.Path]::GetFullPath($targetAssets)
if (-not $resolvedTarget.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to replace assets outside the workspace: $resolvedTarget"
}

New-Item -ItemType Directory -Path $backendStatic -Force | Out-Null
if (Test-Path -LiteralPath $targetAssets) {
    Remove-Item -LiteralPath $targetAssets -Recurse -Force
}
Copy-Item -LiteralPath $sourceAssets -Destination $targetAssets -Recurse -Force
Copy-Item -LiteralPath (Join-Path $frontendDist 'index.html') -Destination (Join-Path $backendStatic 'index.html') -Force

if (-not (Test-Path -LiteralPath (Join-Path $backendStatic 'webui-mras-aima') -PathType Container)) {
    Write-Warning 'Historical webui-mras-aima directory was not found; it was not created or modified by this script.'
}
Write-Output "Frontend synchronized to: $backendStatic"
