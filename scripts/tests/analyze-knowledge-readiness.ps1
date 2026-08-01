param(
    [string]$KnowledgeRoot = 'backend-java/src/main/resources/knowledge-index',
    [string]$OutputPath = 'docs/去年全部指标上游数据就绪度检查报告_2026-07-31.md',
    [string]$JsonOutputPath = 'runtime/acceptance/knowledge-readiness-2025.json',
    [string]$McpUrl = 'http://127.0.0.1:8080/mcp',
    [string]$ExecuteTool = 'execute_sql_winex_all_dev',
    [string]$PreferredSchema = 'WINDBA_GN',
    [string]$StatStart = '2025-01-01',
    [string]$StatEnd = '2026-01-01',
    [string]$BatchRunId = '',
    [string]$RuntimeDbPath = 'backend-java/runtime/wiki_agent_runtime.db'
)

$ErrorActionPreference = 'Stop'

function Get-Section {
    param([string]$Text, [string]$Heading)
    $escaped = [regex]::Escape($Heading)
    $match = [regex]::Match(
        $Text,
        "(?ms)^##\s+$escaped\s*\r?\n(.*?)(?=^##\s+|\z)")
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}

function Get-SqlFromSection {
    param([string]$Section)
    $blocks = [regex]::Matches($Section, '(?ms)```(?:sql)?\s*(.*?)```')
    if ($blocks.Count -eq 0) { return '' }
    return (($blocks | ForEach-Object { $_.Groups[1].Value.Trim() }) -join "`n")
}

function Quote-SqlIdentifier {
    param([string]$Value)
    return '[' + $Value.Replace(']', ']]') + ']'
}

function Quote-SqlLiteral {
    param([string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function Invoke-DbHubSql {
    param([string]$Sql)
    $payload = @{
        jsonrpc = '2.0'
        id = [guid]::NewGuid().ToString('N')
        method = 'tools/call'
        params = @{
            name = $ExecuteTool
            arguments = @{ sql = $Sql }
        }
    } | ConvertTo-Json -Depth 8
    $response = Invoke-WebRequest `
        -Uri $McpUrl `
        -Method Post `
        -ContentType 'application/json' `
        -Headers @{ Accept = 'application/json, text/event-stream' } `
        -Body $payload `
        -TimeoutSec 180 `
        -UseBasicParsing
    $body = $response.Content.Trim()
    if ($body.StartsWith('data:')) {
        $body = (($body -split "\r?\n") |
            Where-Object { $_.StartsWith('data:') } |
            ForEach-Object { $_.Substring(5).Trim() }) -join "`n"
    }
    $rpc = $body | ConvertFrom-Json
    if ($rpc.error) {
        throw "DBHub MCP error: $($rpc.error.message)"
    }
    $text = @($rpc.result.content |
        Where-Object { $_.type -eq 'text' } |
        Select-Object -First 1).text
    if (-not $text) { return @() }
    $inner = $text | ConvertFrom-Json
    if (-not $inner.success) {
        throw "DBHub query failed: $($inner.error)"
    }
    return @($inner.data.rows)
}

function Get-SourceAnalysis {
    param([string]$Sql)
    $optionalSql = (([regex]::Matches($Sql, '(?is)#ETC\{(?<body>.*?)\}') |
        ForEach-Object { $_.Groups['body'].Value }) -join "`n")
    $clean = [regex]::Replace($Sql, '(?is)#ETC\{.*?\}', ' ')
    $clean = [regex]::Replace($clean, '(?ms)/\*.*?\*/', ' ')
    $clean = [regex]::Replace($clean, '(?m)--.*$', ' ')
    $cteNames = @{}
    foreach ($match in [regex]::Matches(
        $clean,
        '(?is)(?:\bWITH\b|,)\s*\[?([A-Za-z_][A-Za-z0-9_]*)\]?\s+AS\s*\(')) {
        $cteNames[$match.Groups[1].Value.ToUpperInvariant()] = $true
    }

    $reserved = @(
        'WHERE','ON','LEFT','RIGHT','INNER','OUTER','FULL','CROSS','JOIN',
        'GROUP','ORDER','UNION','HAVING','WITH','AND','OR','OFFSET','FETCH'
    )
    $tables = [ordered]@{}
    $aliases = @{}
    $tablePattern = '(?is)\b(?:FROM|JOIN)\s+(?!\()' +
        '(?<ref>(?:\[?[A-Za-z_][A-Za-z0-9_]*\]?\.){0,2}\[?[A-Za-z_][A-Za-z0-9_]*\]?)' +
        '(?:\s+WITH\s*\([^)]*\))?' +
        '(?:\s+(?:AS\s+)?(?<alias>\[?[A-Za-z_][A-Za-z0-9_]*\]?))?'
    foreach ($match in [regex]::Matches($clean, $tablePattern)) {
        $reference = $match.Groups['ref'].Value.Replace('[', '').Replace(']', '')
        $parts = $reference.Split('.')
        $table = $parts[$parts.Length - 1]
        $upper = $table.ToUpperInvariant()
        if ($cteNames.ContainsKey($upper)) { continue }
        if ($upper -in @('SELECT','VALUES','DUAL')) { continue }
        $schema = ''
        if ($parts.Length -ge 2) { $schema = $parts[$parts.Length - 2] }
        if (-not $tables.Contains($upper)) {
            $tables[$upper] = [ordered]@{
                table = $table
                declaredSchema = $schema
                fields = [ordered]@{}
                optionalFields = [ordered]@{}
                timeFields = [ordered]@{}
            }
        }
        $alias = $match.Groups['alias'].Value.Replace('[', '').Replace(']', '')
        if (-not $alias -or $reserved -contains $alias.ToUpperInvariant()) {
            $alias = $table
        }
        foreach ($aliasKey in @($alias.ToUpperInvariant(), $table.ToUpperInvariant())) {
            if (-not $aliases.ContainsKey($aliasKey)) {
                $aliases[$aliasKey] = [ordered]@{}
            }
            $aliases[$aliasKey][$upper] = $true
        }
    }

    $derivedAliases = @{}
    foreach ($match in [regex]::Matches(
        $clean,
        '(?is)\)\s+(?:AS\s+)?(?<alias>[A-Za-z_][A-Za-z0-9_]*)\s+' +
        '(?:ON|WHERE|LEFT|RIGHT|INNER|OUTER|FULL|CROSS|JOIN|GROUP|ORDER|UNION)\b')) {
        $derivedAliases[$match.Groups['alias'].Value.ToUpperInvariant()] = $true
    }

    foreach ($match in [regex]::Matches(
        $clean,
        '(?is)\b(?<alias>[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*\[?(?<field>[A-Za-z_][A-Za-z0-9_]*)\]?')) {
        $alias = $match.Groups['alias'].Value.ToUpperInvariant()
        if ($derivedAliases.ContainsKey($alias)) { continue }
        if (-not $aliases.ContainsKey($alias)) { continue }
        $candidateTables = @($aliases[$alias].Keys)
        # 同一份 SQL 的不同子查询可能复用 t1/event 等别名。没有完整 AST
        # 时无法安全判断字段属于哪一层，宁可跳过，也不能误报成某张表缺字段。
        if ($candidateTables.Count -ne 1) { continue }
        $tableKey = $candidateTables[0]
        $field = $match.Groups['field'].Value
        $tables[$tableKey].fields[$field.ToUpperInvariant()] = $field
    }

    foreach ($match in [regex]::Matches(
        $optionalSql,
        '(?is)\b(?<alias>[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*\[?(?<field>[A-Za-z_][A-Za-z0-9_]*)\]?')) {
        $alias = $match.Groups['alias'].Value.ToUpperInvariant()
        if (-not $aliases.ContainsKey($alias)) { continue }
        $candidateTables = @($aliases[$alias].Keys)
        if ($candidateTables.Count -ne 1) { continue }
        $tableKey = $candidateTables[0]
        $field = $match.Groups['field'].Value
        $tables[$tableKey].optionalFields[$field.ToUpperInvariant()] = $field
    }

    $timeParameter = '(?:startTime|endTime|marptBeginAt|marptEndAt|beginTime|statStart|statEnd)'
    foreach ($match in [regex]::Matches(
        $clean,
        "(?is)\b(?<alias>[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*\[?(?<field>[A-Za-z_][A-Za-z0-9_]*)\]?" +
        "\s*(?:BETWEEN\s+:$timeParameter|(?:>=|>|<=|<)\s*:$timeParameter)")) {
        $alias = $match.Groups['alias'].Value.ToUpperInvariant()
        if ($derivedAliases.ContainsKey($alias)) { continue }
        if (-not $aliases.ContainsKey($alias)) { continue }
        $candidateTables = @($aliases[$alias].Keys)
        if ($candidateTables.Count -ne 1) { continue }
        $tableKey = $candidateTables[0]
        $field = $match.Groups['field'].Value
        $tables[$tableKey].timeFields[$field.ToUpperInvariant()] = $field
    }

    # 兼容子查询中没有别名的 “FROM TABLE WHERE FIELD BETWEEN :startTime ...”
    foreach ($tableKey in @($tables.Keys)) {
        $name = [regex]::Escape([string]$tables[$tableKey].table)
        $pattern = "(?is)\bFROM\s+(?:\w+\.)?\[?$name\]?(?:\s+\w+)?\s+WHERE.{0,300}?" +
            "\b\[?(?<field>[A-Za-z_][A-Za-z0-9_]*)\]?\s+BETWEEN\s+:$timeParameter"
        foreach ($match in [regex]::Matches($clean, $pattern)) {
            $field = $match.Groups['field'].Value
            $tables[$tableKey].timeFields[$field.ToUpperInvariant()] = $field
            $tables[$tableKey].fields[$field.ToUpperInvariant()] = $field
        }
    }
    return @($tables.Values)
}

$knowledgePath = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine((Get-Location).Path, $KnowledgeRoot))
$entityPath = Join-Path $knowledgePath 'entities'
$entityFiles = @(Get-ChildItem -LiteralPath $entityPath -Filter '*.md' | Sort-Object Name)
if ($entityFiles.Count -ne 43) {
    throw "Expected 43 knowledge entities, found $($entityFiles.Count)."
}

$profiles = @()
$allTableNames = [ordered]@{}
foreach ($file in $entityFiles) {
    $text = Get-Content -LiteralPath $file.FullName -Raw
    $heading = [regex]::Match(
        $text,
        '(?m)^#\s+(?<profile>HXZD-\d{3}-\d{3}(?:_\d{3})?)\s+—\s+(?<name>.+)$')
    if (-not $heading.Success) {
        throw "Cannot parse entity heading: $($file.Name)"
    }
    $profileId = $heading.Groups['profile'].Value
    $ruleId = [regex]::Match($profileId, '^HXZD-\d{3}-\d{3}').Value
    $name = $heading.Groups['name'].Value.Trim()
    $sourceSection = Get-Section $text '源表'
    $sourceSql = Get-SqlFromSection $sourceSection
    $dataSourceSection = Get-Section $text '数据来源'
    $targetMatch = [regex]::Match(
        $dataSourceSection,
        '(?m)^\|\s*目标表-概览\s*\|\s*`?([^`|\r\n]+)`?\s*\|')
    $timeMatch = [regex]::Match(
        $text,
        '(?m)^\|\s*时间维度\s*\|\s*([^|\r\n]+)\s*\|')
    $planMatch = [regex]::Match(
        $text,
        '(?m)^\|\s*方案类型\s*\|\s*([^|\r\n]+)\s*\|')
    $analysis = if ($sourceSql) { Get-SourceAnalysis $sourceSql } else { @() }
    foreach ($dependency in $analysis) {
        $allTableNames[$dependency.table.ToUpperInvariant()] = $dependency.table
    }
    $profiles += [pscustomobject]@{
        RuleId = $ruleId
        ProfileId = $profileId
        RuleName = $name
        PlanType = $planMatch.Groups[1].Value.Trim()
        TimeDimension = $timeMatch.Groups[1].Value.Trim()
        TargetTable = $targetMatch.Groups[1].Value.Trim()
        SourceSqlPresent = -not [string]::IsNullOrWhiteSpace($sourceSql)
        SourceSqlHash = if ($sourceSql) {
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try {
                ([BitConverter]::ToString(
                    $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($sourceSql))) -replace '-', '').ToLowerInvariant()
            } finally {
                $sha.Dispose()
            }
        } else { '' }
        Dependencies = @($analysis)
        FileName = $file.Name
    }
}

$tableListSql = @($allTableNames.Values |
    ForEach-Object { Quote-SqlLiteral $_ }) -join ','
$metadataSql = @"
SELECT
    s.name AS schema_name,
    o.name AS object_name,
    o.type_desc,
    c.name AS column_name,
    ty.name AS data_type
FROM sys.objects o
JOIN sys.schemas s ON o.schema_id = s.schema_id
LEFT JOIN sys.columns c ON o.object_id = c.object_id
LEFT JOIN sys.types ty ON c.user_type_id = ty.user_type_id
WHERE o.type IN ('U','V')
  AND o.name IN ($tableListSql)
ORDER BY
    CASE WHEN s.name = $(Quote-SqlLiteral $PreferredSchema) THEN 0 ELSE 1 END,
    o.name,
    c.column_id
"@
$metadataRows = @(Invoke-DbHubSql $metadataSql)
$objectsByName = @{}
foreach ($row in $metadataRows) {
    $key = ([string]$row.object_name).ToUpperInvariant()
    if (-not $objectsByName.ContainsKey($key)) {
        $objectsByName[$key] = @()
    }
    $objectsByName[$key] += $row
}

$statCache = @{}
$results = @()
$profileIndex = 0
foreach ($profile in $profiles) {
    $profileIndex++
    Write-Output ("[{0}/43] {1}" -f $profileIndex, $profile.ProfileId)
    $dependencyResults = @()
    foreach ($dependency in $profile.Dependencies) {
        $tableKey = $dependency.table.ToUpperInvariant()
        $objectRows = @($objectsByName[$tableKey])
        if ($objectRows.Count -eq 0) {
            $dependencyResults += [pscustomobject]@{
                Table = $dependency.table
                Schema = ''
                Exists = $false
                ReferencedFields = @($dependency.fields.Values)
                MissingFields = @($dependency.fields.Values)
                OptionalReferencedFields = @($dependency.optionalFields.Values)
                MissingOptionalFields = @($dependency.optionalFields.Values)
                TimeField = ''
                CheckedRows = $null
                NullStats = @()
                CheckError = ''
            }
            continue
        }
        $schemas = @($objectRows | Group-Object schema_name)
        $selected = @($objectRows |
            Where-Object { $_.schema_name -eq $PreferredSchema })
        if ($selected.Count -eq 0) {
            $selected = @($objectRows | Where-Object {
                $_.schema_name -eq $dependency.declaredSchema
            })
        }
        if ($selected.Count -eq 0) {
            $selected = @($objectRows | Where-Object {
                $_.schema_name -eq $schemas[0].Name
            })
        }
        $schema = [string]$selected[0].schema_name
        $columnMap = @{}
        foreach ($column in $selected) {
            if ($column.column_name) {
                $columnMap[([string]$column.column_name).ToUpperInvariant()] = [string]$column.column_name
            }
        }
        $referenced = @($dependency.fields.Values | Sort-Object -Unique)
        $missingFields = @($referenced | Where-Object {
            -not $columnMap.ContainsKey($_.ToUpperInvariant())
        })
        $existingFields = @($referenced | Where-Object {
            $columnMap.ContainsKey($_.ToUpperInvariant())
        })
        $optionalReferenced = @($dependency.optionalFields.Values | Sort-Object -Unique)
        $missingOptionalFields = @($optionalReferenced | Where-Object {
            -not $columnMap.ContainsKey($_.ToUpperInvariant())
        })
        $existingOptionalFields = @($optionalReferenced | Where-Object {
            $columnMap.ContainsKey($_.ToUpperInvariant())
        })
        $timeField = @($dependency.timeFields.Values | Where-Object {
            $columnMap.ContainsKey($_.ToUpperInvariant())
        } | Select-Object -First 1)
        if ($timeField.Count -gt 0) { $timeField = [string]$timeField[0] } else { $timeField = '' }

        # 单次聚合最多检查 24 个 SQL 引用字段，防止生成过长查询。
        $fieldsToCheck = @(@($existingFields) + @($existingOptionalFields) |
            Sort-Object -Unique |
            Select-Object -First 24)
        $cacheKey = "$schema|$($dependency.table)|$timeField|$($fieldsToCheck -join ',')"
        $checkedRows = $null
        $nullStats = @()
        $checkError = ''
        if ($statCache.ContainsKey($cacheKey)) {
            $cached = $statCache[$cacheKey]
            $checkedRows = $cached.CheckedRows
            $nullStats = $cached.NullStats
            $checkError = $cached.CheckError
        } else {
            try {
                $selects = @('COUNT_BIG(1) AS checked_rows')
                $fieldAliases = @()
                $fieldNo = 0
                foreach ($field in $fieldsToCheck) {
                    $alias = "null_$fieldNo"
                    $selects += "SUM(CASE WHEN $(Quote-SqlIdentifier $field) IS NULL THEN 1 ELSE 0 END) AS $alias"
                    $fieldAliases += [pscustomobject]@{ Field = $field; Alias = $alias }
                    $fieldNo++
                }
                $where = ''
                if ($timeField) {
                    $where = " WHERE $(Quote-SqlIdentifier $timeField) >= CAST($(Quote-SqlLiteral $StatStart) AS datetime2)" +
                        " AND $(Quote-SqlIdentifier $timeField) < CAST($(Quote-SqlLiteral $StatEnd) AS datetime2)"
                }
                $statSql = "SELECT $($selects -join ', ') FROM " +
                    "$(Quote-SqlIdentifier $schema).$(Quote-SqlIdentifier $dependency.table)$where"
                $statRows = @(Invoke-DbHubSql $statSql)
                if ($statRows.Count -gt 0) {
                    $checkedRows = [long]$statRows[0].checked_rows
                    foreach ($fieldAlias in $fieldAliases) {
                        $nullValue = $statRows[0].($fieldAlias.Alias)
                        $nullCount = if ($null -eq $nullValue) { 0L } else { [long]$nullValue }
                        $rate = if ($checkedRows -gt 0) {
                            [math]::Round($nullCount * 100.0 / $checkedRows, 2)
                        } else { $null }
                        $nullStats += [pscustomobject]@{
                            Field = $fieldAlias.Field
                            NullCount = $nullCount
                            NullRate = $rate
                        }
                    }
                }
            } catch {
                $checkError = $_.Exception.Message
            }
            $statCache[$cacheKey] = [pscustomobject]@{
                CheckedRows = $checkedRows
                NullStats = $nullStats
                CheckError = $checkError
            }
        }
        $dependencyResults += [pscustomobject]@{
            Table = $dependency.table
            Schema = $schema
            Exists = $true
            ReferencedFields = $referenced
            MissingFields = $missingFields
            OptionalReferencedFields = $optionalReferenced
            MissingOptionalFields = $missingOptionalFields
            TimeField = $timeField
            CheckedRows = $checkedRows
            NullStats = $nullStats
            CheckError = $checkError
        }
    }

    $missingTables = @($dependencyResults | Where-Object { -not $_.Exists })
    $missingColumns = @($dependencyResults | ForEach-Object {
        $dep = $_
        @($dep.MissingFields | ForEach-Object { "$($dep.Table).$_" })
    })
    $missingOptionalColumns = @($dependencyResults | ForEach-Object {
        $dep = $_
        @($dep.MissingOptionalFields | ForEach-Object { "$($dep.Table).$_" })
    })
    $emptyTables = @($dependencyResults | Where-Object {
        $_.Exists -and $null -ne $_.CheckedRows -and $_.CheckedRows -eq 0
    })
    $nullProblems = @($dependencyResults | ForEach-Object {
        $dep = $_
        @($dep.NullStats | Where-Object {
            $null -ne $_.NullRate -and $_.NullRate -gt 0
        } | ForEach-Object {
            [pscustomobject]@{
                Table = $dep.Table
                Field = $_.Field
                NullCount = $_.NullCount
                NullRate = $_.NullRate
                CheckedRows = $dep.CheckedRows
            }
        })
    })
    $errors = @($dependencyResults | Where-Object { $_.CheckError })
    $status = if (-not $profile.SourceSqlPresent) {
        '知识库未配置源表SQL'
    } elseif ($dependencyResults.Count -eq 0) {
        'SQL依赖无法解析'
    } elseif ($missingTables.Count -gt 0) {
        '缺表'
    } elseif ($missingColumns.Count -gt 0) {
        '缺字段'
    } elseif ($missingOptionalColumns.Count -gt 0) {
        '可选过滤字段缺失'
    } elseif ($errors.Count -gt 0) {
        '数据检查失败'
    } elseif ($emptyTables.Count -gt 0) {
        '发现上游表无数据'
    } elseif ($nullProblems.Count -gt 0) {
        '有字段空值'
    } else {
        '未发现结构或空值问题'
    }

    $results += [pscustomobject]@{
        RuleId = $profile.RuleId
        ProfileId = $profile.ProfileId
        RuleName = $profile.RuleName
        PlanType = $profile.PlanType
        TimeDimension = $profile.TimeDimension
        TargetTable = $profile.TargetTable
        SourceSqlPresent = $profile.SourceSqlPresent
        SourceSqlHash = $profile.SourceSqlHash
        Status = $status
        MissingTables = @($missingTables | ForEach-Object { $_.Table })
        MissingColumns = $missingColumns
        MissingOptionalColumns = $missingOptionalColumns
        EmptyTables = @($emptyTables | ForEach-Object { $_.Table })
        NullProblems = $nullProblems
        Dependencies = $dependencyResults
        Errors = @($errors | ForEach-Object { "$($_.Table): $($_.CheckError)" })
        KnowledgeFile = $profile.FileName
    }
}

$resolvedJson = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine((Get-Location).Path, $JsonOutputPath))
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($resolvedJson)) | Out-Null
$results | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $resolvedJson -Encoding utf8

$batchJob = $null
$batchTasks = @()
if ($BatchRunId) {
    if ($BatchRunId -notmatch '^BJOB_[A-Za-z0-9]+$') {
        throw "Invalid batch run id: $BatchRunId"
    }
    $resolvedRuntimeDb = [System.IO.Path]::GetFullPath(
        [System.IO.Path]::Combine((Get-Location).Path, $RuntimeDbPath))
    $sqlite = (Get-Command sqlite3 -ErrorAction Stop).Source
    $jobJson = & $sqlite -json $resolvedRuntimeDb @"
SELECT job_id, status, total, succeeded, no_sample, failed,
       stat_start, stat_end, created_at, finished_at
FROM med_agent_batch_job
WHERE job_id = '$BatchRunId';
"@
    $taskJson = & $sqlite -json $resolvedRuntimeDb @"
SELECT position, rule_id, rule_name, profile_id, profile_name, status,
       result_value, numerator_count, denominator_count, target_value,
       unit, quality_status, error_code, error_message, detail_kind,
       calculation_display, sample_count
FROM med_agent_batch_task
WHERE job_id = '$BatchRunId'
ORDER BY position;
"@
    $batchJob = @(($jobJson -join "`n") | ConvertFrom-Json)
    if ($batchJob.Count -ne 1) {
        throw "Batch run not found: $BatchRunId"
    }
    $batchJob = $batchJob[0]
    $batchTasks = @(($taskJson -join "`n") | ConvertFrom-Json)
    if ($batchTasks.Count -ne 43) {
        throw "Batch $BatchRunId expected 43 tasks, found $($batchTasks.Count)."
    }
}

$summary = @($results | Group-Object Status | Sort-Object Name)
$profilesWithMissingTables = @($results | Where-Object { $_.MissingTables.Count -gt 0 }).Count
$profilesWithMissingColumns = @($results | Where-Object { $_.MissingColumns.Count -gt 0 }).Count
$profilesWithMissingOptionalColumns = @($results | Where-Object {
    $_.MissingOptionalColumns.Count -gt 0
}).Count
$profilesWithoutSourceSql = @($results | Where-Object {
    -not $_.SourceSqlPresent
}).Count
$profilesWithEmptyDependencies = @($results | Where-Object {
    $_.EmptyTables.Count -gt 0
}).Count
$profilesWithNulls = @($results | Where-Object {
    $_.NullProblems.Count -gt 0
}).Count
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# 去年全部指标上游数据就绪度检查报告')
$lines.Add('')
$lines.Add('> 检查时间：{0}  ' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$lines.Add(('> 统计窗口：{0} 至 {1}（左闭右开）  ' -f $StatStart, $StatEnd))
$lines.Add('> 知识库：`backend-java/src/main/resources/knowledge-index`  ')
$lines.Add('> 业务数据源：`winex_all_dev / WiNEX_All_DEV / {0}`' -f $PreferredSchema)
$lines.Add('')
$lines.Add('## 1. 检查范围与限制')
$lines.Add('')
$lines.Add("- 共读取 **$($results.Count)** 个知识库实体，即当前 43 个运行口径。")
$lines.Add('- 表和字段依赖由知识库“源表”SQL静态分析得到，再与真实业务库元数据核对。')
$lines.Add('- 行数和空值率来自真实业务库只读聚合查询。发现0行只能说明该依赖在检查范围内无数据，不能单独证明模块未接入。')
$lines.Add('- 只有SQL中能可靠映射到物理表的 `别名.字段` 才纳入字段核对；复杂派生表和无别名字段会在限制项中说明。')
$lines.Add('- `#ETC{...}` 内的排除科室/患者等字段属于可选配置依赖，与核心必需字段分开列示；只有启用相应配置时才会阻断。')
$lines.Add('- 没有解析到统计时间字段的依赖按全表检查；解析到时间字段的依赖按去年窗口检查。')
$lines.Add('')
if ($batchJob) {
    $uniqueRuleCount = @($batchTasks.rule_id | Sort-Object -Unique).Count
    $lines.Add('## 2. 去年全量计算实测')
    $lines.Add('')
    $lines.Add('- 批次：`{0}`' -f $batchJob.job_id)
    $lines.Add(('- 覆盖：**{0} 个指标定义 / {1} 个运行口径**。' -f
        $uniqueRuleCount, $batchJob.total))
    $lines.Add(('- 结果：成功 **{0}**，无样本 **{1}**，失败 **{2}**；批次状态 `{3}`。' -f
        $batchJob.succeeded, $batchJob.no_sample, $batchJob.failed, $batchJob.status))
    $lines.Add(('- 窗口：{0} 至 {1}；开始 `{2}`，完成 `{3}`。' -f
        $batchJob.stat_start, $batchJob.stat_end, $batchJob.created_at, $batchJob.finished_at))
    $lines.Add('')
    $lines.Add('| 序号 | 运行口径 | 指标 | 计算状态 | 结果 | 计算构成 | 明细类型 | 失败原因 |')
    $lines.Add('|---:|---|---|---|---:|---:|---|---|')
    foreach ($task in $batchTasks) {
        $statusText = switch ([string]$task.status) {
            'SUCCESS' { '成功' }
            'NO_SAMPLE' { '无样本' }
            'FAILED' { '失败' }
            default { [string]$task.status }
        }
        $resultText = '—'
        if ($task.calculation_display) {
            $resultText = [string]$task.calculation_display
        } elseif ($null -ne $task.result_value) {
            $rounded = [math]::Round([double]$task.result_value, 4)
            $resultText = if ($task.unit -eq 'percentage') { "$rounded%" } else { [string]$rounded }
        }
        $composition = if ($null -ne $task.numerator_count -and
            $null -ne $task.denominator_count) {
            "$($task.numerator_count) / $($task.denominator_count)"
        } else { '—' }
        $errorText = if ($task.error_code) {
            "$($task.error_code)：$($task.error_message)"
        } else { '—' }
        $profileText = ([string]$task.profile_id).Replace('|', '\|')
        $nameText = ([string]$task.rule_name).Replace('|', '\|')
        $resultText = $resultText.Replace('|', '\|')
        $errorText = $errorText.Replace('|', '\|')
        $lines.Add("| $([int]$task.position + 1) | $profileText | $nameText | $statusText | $resultText | $composition | $($task.detail_kind) | $errorText |")
    }
    $lines.Add('')
}

$summarySection = if ($batchJob) { 3 } else { 2 }
$detailSection = $summarySection + 1
$interpretationSection = $detailSection + 1
$evidenceSection = $interpretationSection + 1
$lines.Add("## $summarySection. 上游数据就绪度总体结果")
$lines.Add('')
$lines.Add('下表各检查项彼此独立，不应相加。特别是“指标无样本”与“上游物理表0行”不是同一件事。')
$lines.Add('')
$lines.Add('| 检查项 | 涉及口径数 | 本次结论 |')
$lines.Add('|---|---:|---|')
$lines.Add("| 核心缺表 | $profilesWithMissingTables | 知识库核心SQL引用的物理表均能在业务库定位 |")
$lines.Add("| 核心缺字段 | $profilesWithMissingColumns | 已可靠映射的核心字段均存在 |")
$lines.Add("| 可选过滤字段缺失 | $profilesWithMissingOptionalColumns | 启用对应 `#ETC` 排除配置时需修正字段映射 |")
$lines.Add("| 知识库未配置源表SQL | $profilesWithoutSourceSql | 无法从当前知识库继续追溯业务源表 |")
$lines.Add("| 已解析上游表检查行数为0 | $profilesWithEmptyDependencies | 本次没有发现物理上游表整表/窗口0行 |")
$lines.Add("| SQL引用字段存在空值 | $profilesWithNulls | 逐表给出空值数、母数和空值率 |")
if ($batchJob) {
    $lines.Add("| 实际指标计算无样本 | $($batchJob.no_sample) | 需继续查筛选条件、JOIN覆盖率或中间抽取，不可直接写成源表无数据 |")
}
$lines.Add('')
$lines.Add("## $detailSection. 逐口径上游检查")
$lines.Add('')
$index = 0
foreach ($result in $results) {
    $index++
    $lines.Add("### $index. $($result.ProfileId) $($result.RuleName)")
    $lines.Add('')
    $lines.Add("- 结论：**$($result.Status)**")
    if ($result.PlanType) { $lines.Add("- 方案：$($result.PlanType)") }
    if ($result.TimeDimension) { $lines.Add("- 知识库时间维度：$($result.TimeDimension)") }
    if ($result.TargetTable) { $lines.Add('- 目标中间表：`{0}`' -f $result.TargetTable) }
    $lines.Add('- 知识文件：`{0}`' -f $result.KnowledgeFile)
    if ($result.MissingTables.Count -gt 0) {
        $formattedMissingTables = @($result.MissingTables | ForEach-Object {
            '`{0}`' -f $_
        }) -join '、'
        $lines.Add('- 缺失表：{0}' -f $formattedMissingTables)
    }
    if ($result.MissingColumns.Count -gt 0) {
        $formattedMissingColumns = @($result.MissingColumns | ForEach-Object {
            '`{0}`' -f $_
        }) -join '、'
        $lines.Add('- 缺失字段：{0}' -f $formattedMissingColumns)
    }
    if ($result.MissingOptionalColumns.Count -gt 0) {
        $formattedMissingOptionalColumns = @($result.MissingOptionalColumns | ForEach-Object {
            '`{0}`' -f $_
        }) -join '、'
        $lines.Add('- 可选过滤字段缺失（启用对应 `#ETC` 配置时才影响）：{0}' -f $formattedMissingOptionalColumns)
    }
    if ($result.EmptyTables.Count -gt 0) {
        $formattedEmptyTables = @($result.EmptyTables | ForEach-Object {
            '`{0}`' -f $_
        }) -join '、'
        $lines.Add('- 无数据依赖：{0}' -f $formattedEmptyTables)
    }
    if ($result.Errors.Count -gt 0) {
        $lines.Add("- 查询限制：$($result.Errors -join '；')")
    }
    $lines.Add('')
    $lines.Add('| 上游表 | 存在 | 检查范围 | 检查行数 | 核心缺失字段 | 可选过滤缺失字段 | 有空值字段 |')
    $lines.Add('|---|---|---|---:|---|---|---|')
    foreach ($dep in $result.Dependencies) {
        $range = if ($dep.TimeField) { "去年（$($dep.TimeField)）" } else { '全表' }
        $rows = if ($null -eq $dep.CheckedRows) { '—' } else { [string]$dep.CheckedRows }
        $missing = if ($dep.MissingFields.Count -gt 0) {
            (@($dep.MissingFields | ForEach-Object { '`{0}`' -f $_ })) -join '、'
        } else { '—' }
        $optionalMissing = if ($dep.MissingOptionalFields.Count -gt 0) {
            (@($dep.MissingOptionalFields | ForEach-Object { '`{0}`' -f $_ })) -join '、'
        } else { '—' }
        $nulls = @($dep.NullStats | Where-Object {
            $null -ne $_.NullRate -and $_.NullRate -gt 0
        } | Sort-Object NullRate -Descending | Select-Object -First 6 | ForEach-Object {
            '`{0}` {1}/{2}（{3}%）' -f $_.Field, $_.NullCount, $dep.CheckedRows, $_.NullRate
        })
        $nullText = if ($nulls.Count -gt 0) { $nulls -join '；' } else { '—' }
        $exists = if ($dep.Exists) { '是' } else { '**否**' }
        $tableDisplay = if ($dep.Schema) {
            '`{0}.{1}`' -f $dep.Schema, $dep.Table
        } else {
            '`{0}`' -f $dep.Table
        }
        $lines.Add("| $tableDisplay | $exists | $range | $rows | $missing | $optionalMissing | $nullText |")
    }
    if ($result.Dependencies.Count -eq 0) {
        $lines.Add('| — | — | — | — | 源表SQL没有解析出可核对依赖 | — | — |')
    }
    $lines.Add('')
}
$lines.Add("## $interpretationSection. 如何理解报告")
$lines.Add('')
$lines.Add('- **缺表/缺字段**：真实业务库元数据与知识库SQL不一致，需要确认知识库名称、本院schema或源系统版本。')
$lines.Add('- **可选过滤字段缺失**：核心SQL可运行，但启用对应排除科室/患者配置时可能失败；应修正字段映射或禁用该可选过滤。')
$lines.Add('- **发现上游表无数据**：只说明对应表在去年窗口或全表检查中为0；还要结合分母母集和模块启用情况，不能直接写“未埋点”。')
$lines.Add('- **有字段空值**：报告给出空值数、检查母数和比例，可用于形成“缺失760条、完整度62%”等有证据的说明。')
$lines.Add('- **知识库未配置源表SQL**：虽然目标中间表仍可能返回“无样本”，但仅凭当前知识库无法追溯到业务源表，初始化检查必须明确标记为不可推导。')
$lines.Add('')
$lines.Add("## $evidenceSection. 原始证据")
$lines.Add('')
$lines.Add('- JSON：`{0}`' -f $JsonOutputPath)
if ($batchJob) {
    $lines.Add(('- 全量计算批次：`{0}`（任务明细持久化于 `{1}`）。' -f
        $batchJob.job_id, $RuntimeDbPath))
}
$lines.Add('- 所有数据库访问均为只读元数据和聚合查询，没有清表、复制表或写入医院业务库。')

$resolvedOutput = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine((Get-Location).Path, $OutputPath))
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($resolvedOutput)) | Out-Null
$lines | Set-Content -LiteralPath $resolvedOutput -Encoding utf8

Write-Output ''
Write-Output "Markdown: $resolvedOutput"
Write-Output "JSON: $resolvedJson"
$summary | ForEach-Object { Write-Output "$($_.Name)=$($_.Count)" }
