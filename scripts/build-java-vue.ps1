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
Write-Output "Single deployment JAR: $($jar.FullName)"
