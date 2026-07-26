param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Prepare', 'Validate', 'Publish', 'Reclaim', 'Rollback', 'ExportHospital')]
    [string]$Action,
    # PowerShell 的 $Input 是自动变量，不能作为可靠的脚本参数存储位置；
    # 对外仍保留计划中的 -Input 名称，内部改用 InputPath。
    [Alias('Input')]
    [string]$InputPath,
    [ValidateSet('company', 'hospital')]
    [string]$Scope = 'company',
    [string]$HospitalId,
    [string]$ModelId = 'deepseek-v4-flash',
    [string]$Candidate,
    [string]$ReleaseId,
    [string]$Verification,
    [switch]$Confirmed
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$wikiRoot = Join-Path $projectRoot 'core-rules-wiki'
$releaseScript = Join-Path $PSScriptRoot 'knowledge-release.mjs'
$promptPath = Join-Path $wikiRoot 'prompts\knowledge-release-normalizer.md'
$runtimeRoot = Join-Path $projectRoot 'runtime\knowledge-release'

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Read-ZipText {
    param($Archive, [string]$Name)
    $entry = $Archive.GetEntry($Name)
    if ($null -eq $entry) { return $null }
    $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Get-ExcelExtraction {
    param([Parameter(Mandatory = $true)][string]$Path)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $shared = @()
        $sharedXmlText = Read-ZipText $archive 'xl/sharedStrings.xml'
        if ($sharedXmlText) {
            [xml]$sharedXml = $sharedXmlText
            foreach ($item in $sharedXml.SelectNodes('//*[local-name()="si"]')) {
                $parts = @($item.SelectNodes('.//*[local-name()="t"]') | ForEach-Object { $_.InnerText })
                $shared += ($parts -join '')
            }
        }

        [xml]$workbook = Read-ZipText $archive 'xl/workbook.xml'
        [xml]$relationships = Read-ZipText $archive 'xl/_rels/workbook.xml.rels'
        $targets = @{}
        foreach ($relation in $relationships.SelectNodes('//*[local-name()="Relationship"]')) {
            $targets[$relation.Id] = [string]$relation.Target
        }

        $fragments = New-Object System.Collections.Generic.List[object]
        $sqlBlocks = [ordered]@{}
        $sqlLocations = [ordered]@{}
        $sqlNumber = 0
        foreach ($sheet in $workbook.SelectNodes('//*[local-name()="sheet"]')) {
            $relationId = $sheet.GetAttribute('id', 'http://schemas.openxmlformats.org/officeDocument/2006/relationships')
            $target = $targets[$relationId]
            if (-not $target) { continue }
            $entryName = if ($target.StartsWith('/')) {
                $target.TrimStart('/')
            } elseif ($target.StartsWith('xl/')) {
                $target
            } else {
                'xl/' + $target.TrimStart('./')
            }
            [xml]$sheetXml = Read-ZipText $archive $entryName
            foreach ($cell in $sheetXml.SelectNodes('//*[local-name()="c"]')) {
                $reference = [string]$cell.r
                $type = [string]$cell.t
                $valueNode = $cell.SelectSingleNode('./*[local-name()="v"]')
                $inlineNodes = $cell.SelectNodes('.//*[local-name()="is"]//*[local-name()="t"]')
                $formulaNode = $cell.SelectSingleNode('./*[local-name()="f"]')
                $value = if ($inlineNodes.Count -gt 0) {
                    (@($inlineNodes | ForEach-Object { $_.InnerText }) -join '')
                } elseif ($null -ne $valueNode) {
                    $raw = $valueNode.InnerText
                    if ($type -eq 's' -and $raw -match '^\d+$' -and [int]$raw -lt $shared.Count) {
                        $shared[[int]$raw]
                    } else {
                        $raw
                    }
                } else {
                    ''
                }
                if ($formulaNode) {
                    $value = "[FORMULA=$($formulaNode.InnerText)] $value"
                }
                if ([string]::IsNullOrWhiteSpace($value)) { continue }
                $location = "$($sheet.name)!$reference"
                $looksLikeSql = $value -match '(?is)^\s*(?:select|with)\b' -or
                    ($value -match '(?is)\bselect\b[\s\S]{10,}\bfrom\b')
                if ($looksLikeSql) {
                    $sqlNumber++
                    $blockId = 'SQL_BLOCK_{0:D4}' -f $sqlNumber
                    $sqlBlocks[$blockId] = $value
                    $sqlLocations[$blockId] = $location
                    $fragments.Add([ordered]@{
                        source_ref = $location
                        text = "[$blockId] SQL原文由程序保管，模型只能引用。"
                    })
                } else {
                    $fragments.Add([ordered]@{ source_ref = $location; text = $value })
                }
            }
        }
        return [ordered]@{
            source_file = [System.IO.Path]::GetFileName($Path)
            source_sha256 = Get-Sha256 $Path
            fragments = @($fragments)
            sql_blocks = $sqlBlocks
            sql_locations = $sqlLocations
        }
    } finally {
        $archive.Dispose()
    }
}

function Get-MarkdownExtraction {
    param([Parameter(Mandatory = $true)][string]$Path)
    $lines = Get-Content -LiteralPath $Path
    $fragments = New-Object System.Collections.Generic.List[object]
    $sqlBlocks = [ordered]@{}
    $sqlLocations = [ordered]@{}
    $inSql = $false
    $sqlStart = 0
    $buffer = New-Object System.Collections.Generic.List[string]
    $sqlNumber = 0
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $lineNumber = $index + 1
        $line = [string]$lines[$index]
        if (-not $inSql -and $line -match '^\s*```sql\s*$') {
            $inSql = $true
            $sqlStart = $lineNumber
            $buffer.Clear()
            continue
        }
        if ($inSql -and $line -match '^\s*```\s*$') {
            $sqlNumber++
            $blockId = 'SQL_BLOCK_{0:D4}' -f $sqlNumber
            $sqlBlocks[$blockId] = ($buffer -join "`n")
            $sqlLocations[$blockId] = "lines:$sqlStart-$lineNumber"
            $fragments.Add([ordered]@{
                source_ref = "lines:$sqlStart-$lineNumber"
                text = "[$blockId] SQL原文由程序保管，模型只能引用。"
            })
            $inSql = $false
            continue
        }
        if ($inSql) {
            $buffer.Add($line)
        } elseif (-not [string]::IsNullOrWhiteSpace($line)) {
            $fragments.Add([ordered]@{ source_ref = "line:$lineNumber"; text = $line })
        }
    }
    if ($inSql) { throw "Markdown存在未闭合的SQL代码块（起始行$sqlStart）。" }
    return [ordered]@{
        source_file = [System.IO.Path]::GetFileName($Path)
        source_sha256 = Get-Sha256 $Path
        fragments = @($fragments)
        sql_blocks = $sqlBlocks
        sql_locations = $sqlLocations
    }
}

function Resolve-MaintenanceModel {
    param([string]$Id)
    switch -Regex ($Id) {
        '^deepseek-' {
            $key = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY')
            if (-not $key) { throw '缺少DEEPSEEK_API_KEY，无法使用大模型规范化资料。' }
            return @{
                BaseUrl = 'https://api.deepseek.com'
                Model = $Id
                ApiKey = $key
            }
        }
        '^(?:aliyun-)?qwen3-(?:14b|32b|235b)' {
            $key = [Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY')
            if (-not $key) { throw '缺少DASHSCOPE_API_KEY，无法使用百炼大模型规范化资料。' }
            $model = if ($Id.StartsWith('aliyun-')) { $Id.Substring(7) } else { $Id }
            return @{
                BaseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
                Model = $model
                ApiKey = $key
            }
        }
        default {
            throw '知识发版只允许DeepSeek或Qwen3 14B以上的大模型，禁止使用本地4B/8B。'
        }
    }
}

function Invoke-KnowledgeNormalizer {
    param($Extraction, [string]$SelectedModel)
    $model = Resolve-MaintenanceModel $SelectedModel
    $prompt = Get-Content -LiteralPath $promptPath -Raw
    $payload = [ordered]@{
        SOURCE_FILE = @{
            file_name = $Extraction.source_file
            sha256 = $Extraction.source_sha256
        }
        SOURCE_FRAGMENT = $Extraction.fragments
        SQL_BLOCK = @($Extraction.sql_locations.Keys | ForEach-Object {
            @{
                block_id = $_
                source_ref = $Extraction.sql_locations[$_]
                sha256 = [System.BitConverter]::ToString(
                    [System.Security.Cryptography.SHA256]::Create().ComputeHash(
                        [System.Text.Encoding]::UTF8.GetBytes([string]$Extraction.sql_blocks[$_])
                    )
                ).Replace('-', '').ToLowerInvariant()
            }
        })
        KNOWN_RULE_IDS = @()
    }
    $body = @{
        model = $model.Model
        temperature = 0
        messages = @(
            @{ role = 'system'; content = $prompt },
            @{ role = 'user'; content = ($payload | ConvertTo-Json -Depth 20 -Compress) }
        )
        response_format = @{ type = 'json_object' }
    } | ConvertTo-Json -Depth 25
    $headers = @{ Authorization = "Bearer $($model.ApiKey)" }
    $uri = $model.BaseUrl.TrimEnd('/') + '/chat/completions'
    $response = Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 300
    $content = [string]$response.choices[0].message.content
    $content = $content -replace '^\s*```(?:json)?\s*', '' -replace '\s*```\s*$', ''
    $draft = $content | ConvertFrom-Json
    if ($draft.schema_version -ne 'knowledge-draft-v2') {
        throw '大模型输出不是knowledge-draft-v2。'
    }
    # SQL正文由确定性提取器覆盖，杜绝模型修改或补造SQL。
    $draft.sql_blocks = [pscustomobject]$Extraction.sql_blocks
    $draft.source.file_name = $Extraction.source_file
    $draft.source.sha256 = $Extraction.source_sha256
    return $draft
}

function Invoke-ReleaseNode {
    param([string[]]$Arguments)
    & node $releaseScript @Arguments
    if ($LASTEXITCODE -ne 0) { throw "知识发版命令失败，退出码：$LASTEXITCODE" }
}

if ($Action -eq 'Prepare') {
    if (-not $InputPath) { throw 'Prepare必须提供-Input。' }
    $source = (Resolve-Path -LiteralPath $InputPath).Path
    $preparedInput = $source
    if ($source.EndsWith('.md', [System.StringComparison]::OrdinalIgnoreCase)) {
        & node (Join-Path $PSScriptRoot 'build-wiki-from-markdown.mjs') --input $source --check
        if ($LASTEXITCODE -ne 0) {
            $extraction = Get-MarkdownExtraction $source
            $draft = Invoke-KnowledgeNormalizer $extraction $ModelId
            New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
            $preparedInput = Join-Path $runtimeRoot ("draft-{0}.json" -f ([Guid]::NewGuid().ToString('N')))
            $draft | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $preparedInput -Encoding UTF8
        }
    } elseif ($source.EndsWith('.xlsx', [System.StringComparison]::OrdinalIgnoreCase)) {
        $extraction = Get-ExcelExtraction $source
        $draft = Invoke-KnowledgeNormalizer $extraction $ModelId
        New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
        $preparedInput = Join-Path $runtimeRoot ("draft-{0}.json" -f ([Guid]::NewGuid().ToString('N')))
        $draft | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $preparedInput -Encoding UTF8
    } elseif (-not $source.EndsWith('.json', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw '只支持.xlsx、.md或KnowledgeDraftV2 .json。'
    }
    $arguments = @('--action', 'prepare', '--input', $preparedInput, '--scope', $Scope, '--model-id', $ModelId)
    if ($HospitalId) { $arguments += @('--hospital-id', $HospitalId) }
    if ($ReleaseId) { $arguments += @('--release-id', $ReleaseId) }
    Invoke-ReleaseNode $arguments
    exit
}

if ($Action -eq 'Reclaim' -and $InputPath -and $InputPath.EndsWith('.zip', [System.StringComparison]::OrdinalIgnoreCase)) {
    $expanded = Join-Path $runtimeRoot ("reclaim-{0}" -f ([Guid]::NewGuid().ToString('N')))
    New-Item -ItemType Directory -Path $expanded -Force | Out-Null
    Expand-Archive -LiteralPath $InputPath -DestinationPath $expanded
    Invoke-ReleaseNode @('--action', 'reclaim', '--input', $expanded)
    exit
}

if ($Action -eq 'ExportHospital') {
    if (-not $HospitalId) { throw 'ExportHospital必须提供-HospitalId。' }
    if (-not $InputPath) { throw 'ExportHospital使用-Input指定输出zip路径。' }
    $packageDirectory = Join-Path $runtimeRoot ("hospital-package-{0}" -f ([Guid]::NewGuid().ToString('N')))
    New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
    Invoke-ReleaseNode @(
        '--action', 'export-hospital',
        '--hospital-id', $HospitalId,
        '--output', $packageDirectory
    )
    $zipPath = [System.IO.Path]::GetFullPath($InputPath)
    if (Test-Path -LiteralPath $zipPath) { throw "输出文件已存在：$zipPath" }
    Compress-Archive -Path (Join-Path $packageDirectory '*') -DestinationPath $zipPath
    Write-Host "医院知识差异包已导出：$zipPath"
    exit
}

$nodeArguments = @('--action', $Action.ToLowerInvariant())
if ($InputPath) { $nodeArguments += @('--input', $InputPath) }
if ($Candidate) { $nodeArguments += @('--candidate', $Candidate) }
if ($ReleaseId) { $nodeArguments += @('--release-id', $ReleaseId) }
if ($Verification) { $nodeArguments += @('--verification', $Verification) }
if ($HospitalId) { $nodeArguments += @('--hospital-id', $HospitalId) }
if ($Scope) { $nodeArguments += @('--scope', $Scope) }
if ($Confirmed) { $nodeArguments += '--confirmed' }
Invoke-ReleaseNode $nodeArguments
