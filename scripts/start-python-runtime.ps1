param(
    [int]$Port = 8765,
    [string]$PythonCommand = 'python'
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

if (-not (Test-PortAvailable $Port)) {
    throw "端口 $Port 已被占用；脚本不会停止现有服务。"
}
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    Write-Output "Starting FastAPI rollback runtime on 127.0.0.1:$Port"
    & $PythonCommand -B -m uvicorn app.api.main:app --host 127.0.0.1 --port $Port
    if ($LASTEXITCODE -ne 0) { throw "FastAPI runtime exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
