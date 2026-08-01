param(
    [string]$BaseUrl = 'http://127.0.0.1:8765',
    [string]$Token = 'guest',
    [string]$SessionId = ('readiness-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$ModelId = 'aliyun-qwen-plus',
    [string]$Query = '计算去年全部指标的结果'
)

$ErrorActionPreference = 'Stop'
$headers = @{ Authorization = "Bearer $Token" }
$body = @{
    query = $Query
    sessionId = $SessionId
    modelId = $ModelId
} | ConvertTo-Json

$result = Invoke-RestMethod `
    -Uri "$BaseUrl/api/agent/chat" `
    -Headers $headers `
    -ContentType 'application/json; charset=utf-8' `
    -Method Post `
    -Body ([Text.Encoding]::UTF8.GetBytes($body)) `
    -TimeoutSec 1800

$result | ConvertTo-Json -Depth 12
