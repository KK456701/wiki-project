param(
    [int]$Port = 8765,
    [int]$StartupTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$devRunScript = Join-Path $PSScriptRoot 'dev-run.ps1'
$ollamaStartupScript = Join-Path $PSScriptRoot 'start-local-ollama.ps1'
$launcher = 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
$runtimeDirectory = Join-Path $projectRoot 'backend-java\runtime'
$stdout = Join-Path $runtimeDirectory 'dev-run.stdout.log'
$stderr = Join-Path $runtimeDirectory 'dev-run.stderr.log'

if (-not (Test-Path -LiteralPath $launcher)) {
    throw "Stable PowerShell launcher not found: $launcher"
}
if (-not (Test-Path -LiteralPath $devRunScript)) {
    throw "Development runner not found: $devRunScript"
}
if (-not (Test-Path -LiteralPath $runtimeDirectory)) {
    New-Item -ItemType Directory -Path $runtimeDirectory | Out-Null
}

# 本地模型配置默认访问 127.0.0.1:11434。桌面 Ollama UI 进程存在并不代表
# API 端口仍在监听，因此每次开发服务重启前都进行一次幂等存活检查。
if (Test-Path -LiteralPath $ollamaStartupScript) {
    & $ollamaStartupScript
}

$listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
$processIds = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
foreach ($processId in $processIds) {
    $process = Get-Process -Id $processId -ErrorAction Stop
    if ($process.ProcessName -ne 'java') {
        throw "Port $Port is owned by a non-Java process: $($process.ProcessName) (PID $processId)"
    }
    Stop-Process -Id $processId -Force
}

$releaseDeadline = (Get-Date).AddSeconds(20)
while ((Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) `
        -and (Get-Date) -lt $releaseDeadline) {
    Start-Sleep -Milliseconds 250
}
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "Port $Port was not released within 20 seconds"
}

$arguments = @(
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', $devRunScript,
    '-Port', [string]$Port
)
$started = Start-Process -FilePath $launcher -ArgumentList $arguments `
    -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru

$startupDeadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
do {
    Start-Sleep -Seconds 2
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -First 1
} while (-not $listener -and (Get-Date) -lt $startupDeadline)

if (-not $listener) {
    $errorTail = if (Test-Path -LiteralPath $stderr) {
        (Get-Content -LiteralPath $stderr -Tail 80 -ErrorAction SilentlyContinue) -join [Environment]::NewLine
    } else { 'No error log was produced.' }
    throw "Service did not listen on port $Port within $StartupTimeoutSeconds seconds.`n$errorTail"
}

$health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 15
Write-Output "Service restarted: port=$Port, launcher=$launcher, hostPid=$($started.Id), javaPid=$($listener.OwningProcess), status=$($health.status)"
