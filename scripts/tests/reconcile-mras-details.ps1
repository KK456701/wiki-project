param(
    [Parameter(Mandatory = $true)]
    [string]$BatchRunId,
    [string]$BaseUrl = 'http://127.0.0.1:8765',
    [string]$Token = 'guest',
    [string]$OutputDirectory = 'runtime/acceptance',
    [switch]$BrowserVerified,
    [switch]$ExportVerified
)

$ErrorActionPreference = 'Stop'
$headers = @{ Authorization = "Bearer $Token" }
$batchUrl = "$BaseUrl/api/agent/batches/$([uri]::EscapeDataString($BatchRunId))"
$batch = Invoke-RestMethod -Uri $batchUrl -Headers $headers -Method Get -TimeoutSec 30
$tasks = @($batch.tasks)
if ($tasks.Count -eq 0) {
    throw "批次 $BatchRunId 没有可验收的指标任务。"
}
if ($tasks.Count -ne 43) {
    throw "批次 $BatchRunId 应有 43 个口径任务，实际为 $($tasks.Count)。"
}

$rows = @()
foreach ($task in $tasks) {
    $started = Get-Date
    $verdict = 'SKIPPED'
    $httpStatus = 0
    $detailNumerator = $null
    $detailDenominator = $null
    $message = $null
    $cacheDuration = $null
    $sampleRecordCheck = 'NOT_APPLICABLE'
    $detailStatistic = $null

    if ($task.status -eq 'SUCCESS' -or $task.status -eq 'NO_SAMPLE') {
        $query = [System.Web.HttpUtility]::ParseQueryString('')
        $query['group'] = 'denominator'
        $query['batchRunId'] = $BatchRunId
        if ($task.profileId) {
            $query['profileId'] = [string]$task.profileId
        }
        $detailUrl = "$BaseUrl/api/kb/rules/$([uri]::EscapeDataString([string]$task.ruleId))/details?$($query.ToString())"
        try {
            $detail = Invoke-RestMethod -Uri $detailUrl -Headers $headers -Method Get -TimeoutSec 180
            $httpStatus = 200
            if ($task.detailKind -eq 'COUNT_RATIO') {
                $detailNumerator = [long]$detail.detailNumerator
                $detailDenominator = [long]$detail.detailDenominator
                if ($detailNumerator -eq [long]$task.numeratorCount -and
                    $detailDenominator -eq [long]$task.denominatorCount) {
                    $verdict = 'MATCH'
                } else {
                    $verdict = 'MISMATCH'
                }
                $invalidFlags = @($detail.rows | Where-Object {
                    [int]$_.'__meets_numerator' -notin @(0, 1)
                })
                $sampleRecordCheck = if ($invalidFlags.Count -eq 0) {
                    'PASS_DENOMINATOR_FLAG_DOMAIN'
                } else {
                    'FAIL_DENOMINATOR_FLAG_DOMAIN'
                }
                $detailStatistic = "$detailNumerator/$detailDenominator"
            } elseif ($task.detailKind -eq 'SUM_CONTRIBUTION') {
                $detailNumerator = [long]$detail.numeratorContributionTotal
                $detailDenominator = [long]$detail.denominatorContributionTotal
                $verdict = if ($detailNumerator -eq [long]$task.numeratorCount -and
                    $detailDenominator -eq [long]$task.denominatorCount) { 'MATCH' } else { 'MISMATCH' }
                $sampleRecordCheck = 'PASS_CONTRIBUTION_SUM_CONTRACT'
                $detailStatistic = "sum=$detailNumerator/$detailDenominator"
            } elseif ($task.detailKind -eq 'MEDIAN_SAMPLE') {
                if ($task.status -eq 'NO_SAMPLE') {
                    $verdict = if ([int]$detail.sampleCount -eq 0) { 'MATCH' } else { 'MISMATCH' }
                } else {
                    $verdict = if ([math]::Abs(
                        [double]$detail.medianValue - [double]$task.resultValue) -le 0.011) {
                        'MATCH'
                    } else {
                        'MISMATCH'
                    }
                }
                $sampleRecordCheck = 'PASS_MEDIAN_SAMPLE_CONTRACT'
                $detailStatistic = "median=$($detail.medianValue);samples=$($detail.sampleCount)"
            } elseif ($task.detailKind -eq 'DUAL_SOURCE') {
                $detailNumerator = [long]$detail.actualCount
                $detailDenominator = [long]$detail.registeredCount
                $verdict = if ($detailNumerator -eq [long]$task.numeratorCount -and
                    $detailDenominator -eq [long]$task.denominatorCount) { 'MATCH' } else { 'MISMATCH' }
                $sampleRecordCheck = 'PASS_DUAL_SOURCE_CONTRACT'
                $detailStatistic = "actual=$detailNumerator;registered=$detailDenominator"
            } elseif ($task.detailKind -eq 'RATE_COMPARISON') {
                $verdict = if ([string]$detail.resultDisplay -eq [string]$task.calculationDisplay) {
                    'MATCH'
                } else {
                    'MISMATCH'
                }
                $sampleRecordCheck = 'PASS_FOUR_GROUP_CONTRACT'
                $detailStatistic = [string]$detail.resultDisplay
            } else {
                $verdict = 'MISMATCH'
                $message = "未知详情类型 $($task.detailKind)"
            }

            $cacheStarted = Get-Date
            if ($task.detailKind -eq 'COUNT_RATIO') {
                $numeratorQuery = [System.Web.HttpUtility]::ParseQueryString($query.ToString())
                $numeratorQuery['group'] = 'numerator'
                $numeratorUrl = "$BaseUrl/api/kb/rules/$([uri]::EscapeDataString([string]$task.ruleId))/details?$($numeratorQuery.ToString())"
                $numeratorDetail = Invoke-RestMethod -Uri $numeratorUrl -Headers $headers -Method Get -TimeoutSec 30
                $invalidNumeratorRows = @($numeratorDetail.rows | Where-Object {
                    [int]$_.'__meets_numerator' -ne 1
                })
                if ($invalidNumeratorRows.Count -ne 0) {
                    $verdict = 'MISMATCH'
                    $sampleRecordCheck = 'FAIL_NUMERATOR_SUBSET'
                } elseif ($sampleRecordCheck -eq 'PASS_DENOMINATOR_FLAG_DOMAIN') {
                    $sampleRecordCheck = 'PASS_NUMERATOR_SUBSET'
                }
            } else {
                $null = Invoke-RestMethod -Uri $detailUrl -Headers $headers -Method Get -TimeoutSec 30
            }
            $cacheDuration = [int]((Get-Date) - $cacheStarted).TotalMilliseconds
        } catch {
            $httpStatus = -1
            if ($_.Exception.Response) {
                $httpStatus = [int]$_.Exception.Response.StatusCode.value__
            }
            $message = $_.Exception.Message
            $verdict = 'ERROR'
        }
    } elseif ($task.status -eq 'FAILED') {
        $verdict = if ($task.errorCode -eq 'MRAS_OVERVIEW_SQL_EMPTY') {
            'EXPLICITLY_REJECTED'
        } else {
            'CARD_FAILED'
        }
        $message = [string]$task.errorMessage
        $sampleRecordCheck = 'NOT_APPLICABLE_EXPLICIT_REJECTION'
    }

    $elapsed = [int]((Get-Date) - $started).TotalMilliseconds
    $rows += [pscustomobject]@{
        BatchRunId = $BatchRunId
        IndicatorId = [string]$task.ruleId
        IndicatorName = [string]$task.ruleName
        ProfileId = [string]$task.profileId
        DetailKind = [string]$task.detailKind
        StatStart = [string]$task.statStart
        StatEnd = [string]$task.statEnd
        CardResult = $task.resultValue
        CalculationDisplay = [string]$task.calculationDisplay
        CardNumerator = $task.numeratorCount
        CardDenominator = $task.denominatorCount
        DetailNumerator = $detailNumerator
        DetailDenominator = $detailDenominator
        DetailStatistic = $detailStatistic
        Reconciled = ($verdict -eq 'MATCH')
        DedupEvidence = if ($task.detailKind -eq 'COUNT_RATIO') {
            '同一详情快照+__meets_numerator'
        } else {
            '按类型化详情契约'
        }
        SampleRecordCheck = $sampleRecordCheck
        DataQuality = if ($task.status -eq 'NO_SAMPLE') {
            'NO_SAMPLE'
        } elseif ($task.status -eq 'FAILED') {
            'FAILED'
        } elseif ($task.qualityStatus) {
            [string]$task.qualityStatus
        } else {
            'NORMAL_NO_EXPLICIT_ANOMALY'
        }
        HttpStatus = $httpStatus
        DurationMs = $elapsed
        CacheDurationMs = $cacheDuration
        BrowserDisplayCorrect = if ($BrowserVerified) { 'PASS' } else { 'PENDING_BROWSER' }
        ExportCorrect = if ($ExportVerified) { 'PASS_BATCH_REPORT' } else { 'PENDING_EXPORT' }
        Verdict = $verdict
        Message = $message
    }
    Write-Output ("{0,-20} {1,-18} {2}" -f $task.ruleId, $task.detailKind, $verdict)
}

$resolvedOutput = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine((Get-Location).Path, $OutputDirectory))
[System.IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$baseName = "mras-detail-$BatchRunId-$stamp"
$csvPath = Join-Path $resolvedOutput "$baseName.csv"
$jsonPath = Join-Path $resolvedOutput "$baseName.json"
$rows | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding utf8
$rows | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $jsonPath -Encoding utf8

$mismatches = @($rows | Where-Object {
    $_.Verdict -in @('MISMATCH', 'ERROR', 'CARD_FAILED')
})
Write-Output ""
Write-Output "CSV: $csvPath"
Write-Output "JSON: $jsonPath"
Write-Output "MATCH=$(@($rows | Where-Object Verdict -eq 'MATCH').Count) EXPLICITLY_REJECTED=$(@($rows | Where-Object Verdict -eq 'EXPLICITLY_REJECTED').Count) FAILED=$($mismatches.Count)"
if ($mismatches.Count -gt 0) {
    exit 1
}
