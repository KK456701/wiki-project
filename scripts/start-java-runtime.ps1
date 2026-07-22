param(
    [ValidateSet('Shadow', 'Authority')]
    [string]$Mode = 'Shadow',
    [string]$JarPath = '',
    [string]$ReadinessReport = '',
    [int]$Port = 0,
    [int]$MaxReportAgeHours = 24,
    [switch]$ConfirmCutover
)

$ErrorActionPreference = 'Stop'

function Test-PortAvailable {
    param([int]$TargetPort)
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $TargetPort)
    try {
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        try { $listener.Stop() } catch { }
    }
}

function Set-ProcessEnvironment {
    param([string]$Name, [string]$Value, [hashtable]$Saved)
    $Saved[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JarPath) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'backend-java\target') -Filter 'wiki-agent-java-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) { throw 'Java JAR not found. Run scripts\build-java-vue.ps1 first.' }
    $JarPath = $jar.FullName
}
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path

$saved = @{}
try {
    if ($Mode -eq 'Authority') {
        if (-not $ConfirmCutover) {
            throw 'Authority mode requires -ConfirmCutover.'
        }
        if (-not $ReadinessReport) {
            throw 'Authority mode requires -ReadinessReport.'
        }
        $reportPath = (Resolve-Path -LiteralPath $ReadinessReport).Path
        $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($report.schema_version -ne 'java-cutover-readiness-v1' -or $report.status -ne 'ready') {
            throw 'Readiness report is not ready. Java authority start rejected.'
        }
        if ($report.summary.failed -ne 0 -or $report.summary.skipped -ne 0) {
            throw 'Readiness report contains failed or skipped checks.'
        }
        $generatedAt = [DateTimeOffset]::Parse([string]$report.generated_at)
        if ([DateTimeOffset]::UtcNow.Subtract($generatedAt).TotalHours -gt $MaxReportAgeHours) {
            throw "Readiness report is older than $MaxReportAgeHours hours."
        }
        if (-not $Port) { $Port = 8765 }
        Set-ProcessEnvironment 'MIGRATION_AUTHORITY_RUNTIME' 'java' $saved
        Set-ProcessEnvironment 'MIGRATION_CUTOVER_APPROVED' 'true' $saved
        Set-ProcessEnvironment 'MIGRATION_READINESS_REPORT_ID' ([string]$report.report_id) $saved
    } else {
        if (-not $Port) { $Port = 8766 }
        Set-ProcessEnvironment 'MIGRATION_AUTHORITY_RUNTIME' 'python' $saved
        Set-ProcessEnvironment 'MIGRATION_CUTOVER_APPROVED' 'false' $saved
        Set-ProcessEnvironment 'MIGRATION_READINESS_REPORT_ID' '' $saved
    }
    if (-not (Test-PortAvailable $Port)) {
        throw "Port $Port is already in use. Existing services will not be stopped."
    }
    Set-ProcessEnvironment 'SERVER_PORT' ([string]$Port) $saved
    Write-Output "Starting Java runtime: mode=$Mode port=$Port jar=$JarPath"
    & java -jar $JarPath
    if ($LASTEXITCODE -ne 0) { throw "Java runtime exited with code $LASTEXITCODE" }
} finally {
    foreach ($name in $saved.Keys) {
        [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
    }
}
