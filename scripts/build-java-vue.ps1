$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $projectRoot 'frontend-vue'
$backend = Join-Path $projectRoot 'backend-java'

if (-not $env:JAVA_HOME) {
    $temurinRoot = 'F:\kaifa\temurin17'
    $temurin = Get-ChildItem -LiteralPath $temurinRoot -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($temurin) {
        $env:JAVA_HOME = $temurin.FullName
        $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
    }
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
    )
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Command failed with exit code $LASTEXITCODE"
    }
}

$savedErrorPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersion = & java -version 2>&1
$javaExitCode = $LASTEXITCODE
$ErrorActionPreference = $savedErrorPreference
if ($javaExitCode -ne 0 -or ($javaVersion -join "`n") -notmatch 'version "17(?:[.]|")') {
    throw 'Java 17 is required.'
}

Push-Location $frontend
try {
    if (-not (Test-Path -LiteralPath (Join-Path $frontend 'node_modules'))) {
        Invoke-Checked 'npm.cmd' 'ci'
    }
    Invoke-Checked 'npm.cmd' 'run' 'build'
} finally {
    Pop-Location
}

& (Join-Path $PSScriptRoot 'sync-frontend-to-backend.ps1')
if ($LASTEXITCODE -ne 0) {
    throw "Frontend static resource synchronization failed with exit code $LASTEXITCODE"
}

Push-Location $backend
try {
    # 使用项目内 Maven settings，避免开发机全局镜像配置不同导致同一提交构建结果不一致。
    Invoke-Checked 'mvn.cmd' '-q' '-s' (Join-Path $backend 'maven-settings.xml') '-Pbundle-vue' 'clean' 'package'
} finally {
    Pop-Location
}

$jar = Get-ChildItem -LiteralPath (Join-Path $backend 'target') -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw 'Build completed but the Spring Boot JAR was not found.'
}
if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is required to inspect the deployment JAR.'
}
$jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
$jarEntries = & $jarTool 'tf' $jar.FullName
if ($LASTEXITCODE -ne 0 -or $jarEntries -notcontains 'BOOT-INF/classes/static/index.html') {
    throw 'The deployment JAR does not contain the latest static/index.html.'
}

$deployDirectory = Join-Path $backend 'target\deploy'
New-Item -ItemType Directory -Path (Join-Path $deployDirectory 'runtime\logs') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $deployDirectory 'scripts') -Force | Out-Null
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $deployDirectory $jar.Name) -Force
Copy-Item -LiteralPath (Join-Path $backend '查看实时日志.cmd') -Destination (Join-Path $deployDirectory '查看实时日志.cmd') -Force
Copy-Item -LiteralPath (Join-Path $backend 'scripts\watch-logs.ps1') -Destination (Join-Path $deployDirectory 'scripts\watch-logs.ps1') -Force
Write-Output "Single deployment JAR: $($jar.FullName)"
Write-Output "Deployment folder: $deployDirectory"
