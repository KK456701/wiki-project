param(
    [string]$BaseUrl = 'http://localhost:8765',
    [string]$SessionPrefix = 'd1',
    [string[]]$Queries
)

$ErrorActionPreference = 'Stop'
$session = "${SessionPrefix}_" + [int][double]::Parse((Get-Date -UFormat %s))
"会话: $session"
"=========================================="

function Invoke-Round {
    param([string]$Query, [string]$Sid, [int]$Round)
    $body = @{ query = $Query; sessionId = $Sid } | ConvertTo-Json -Compress
    $resp = Invoke-WebRequest -Uri "$BaseUrl/api/agent/chat/stream" -Method Post `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 240
    $lines = $resp.Content -split "`n"
    $events = @()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^event:(.+)$') {
            $evName = $Matches[1].Trim()
            $dataLine = ''
            if ($i + 1 -lt $lines.Count -and $lines[$i+1] -match '^data:(.+)$') {
                $dataLine = $Matches[1]
            }
            $events += [pscustomobject]@{ Name = $evName; Data = $dataLine }
        }
    }
    "---- 第 $Round 轮: $Query ----"
    "事件序列: " + (($events | ForEach-Object { $_.Name }) -join ', ')
    foreach ($ev in $events) {
        if ($ev.Name -eq 'clarification_required') {
            try {
                $j = $ev.Data | ConvertFrom-Json
                "  [澄清] code=$($j.code) kind=$($j.clarification.kind) 问题=$($j.clarification.question)"
                if ($j.clarification.options) {
                    "  选项: " + (($j.clarification.options | ForEach-Object { $_.label }) -join ' | ')
                }
            } catch { "  [澄清原始] $($ev.Data.Substring(0,[Math]::Min(200,$ev.Data.Length)))" }
        }
        elseif ($ev.Name -eq 'assistant_message') {
            try {
                $j = $ev.Data | ConvertFrom-Json
                $c = $j.data.content
                if (-not $c) { $c = $j.content }
                "  [回答] " + ($c -replace "`n", ' ').Substring(0, [Math]::Min(300, $c.Length))
            } catch { "  [回答原始] $($ev.Data.Substring(0,[Math]::Min(300,$ev.Data.Length)))" }
        }
        elseif ($ev.Name -eq 'batch_progress' -or $ev.Name -eq 'indicator_result') {
            "  [批量] $($ev.Data.Substring(0,[Math]::Min(150,$ev.Data.Length)))"
        }
        elseif ($ev.Name -eq 'error' -or $ev.Name -eq 'agent_error') {
            "  [错误] $($ev.Data.Substring(0,[Math]::Min(300,$ev.Data.Length)))"
        }
    }
    ""
}

$round = 1
foreach ($q in $Queries) {
    Invoke-Round -Query $q -Sid $session -Round $round
    $round++
}
"=========================================="
"完成。会话: $session"
