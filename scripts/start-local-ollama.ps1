param(
    [int]$Port = 11434,
    [int]$StartupTimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeDirectory = Join-Path $projectRoot 'backend-java\runtime'
$ollamaExecutable = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe'

if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    Write-Output "Ollama is already listening on port $Port."
    exit 0
}

if (-not (Test-Path -LiteralPath $ollamaExecutable)) {
    Write-Output "Local Ollama is not installed; skipped local startup: $ollamaExecutable"
    exit 0
}

if (-not (Test-Path -LiteralPath $runtimeDirectory)) {
    New-Item -ItemType Directory -Path $runtimeDirectory | Out-Null
}

$stdout = Join-Path $runtimeDirectory 'ollama.stdout.log'
$stderr = Join-Path $runtimeDirectory 'ollama.stderr.log'
Start-Process -FilePath $ollamaExecutable -ArgumentList 'serve' -WindowStyle Hidden `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr | Out-Null

$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
do {
    Start-Sleep -Milliseconds 500
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -First 1
} while (-not $listener -and (Get-Date) -lt $deadline)

if (-not $listener) {
    $errorTail = if (Test-Path -LiteralPath $stderr) {
        (Get-Content -LiteralPath $stderr -Tail 40 -ErrorAction SilentlyContinue) -join [Environment]::NewLine
    } else { 'No Ollama error log was produced.' }
    throw "Ollama did not listen on port $Port within $StartupTimeoutSeconds seconds.`n$errorTail"
}

$tags = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/tags" -TimeoutSec 10
$modelNames = @($tags.models | ForEach-Object { $_.name }) -join ', '
Write-Output "Ollama started: port=$Port, pid=$($listener.OwningProcess), models=$modelNames"
