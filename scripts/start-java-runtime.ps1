param(
    [string]$JarPath = '',
    [string]$ConfigPath = '',
    [int]$Port = 8765
)

$ErrorActionPreference = 'Stop'

function Get-YamlScalar {
    param(
        [Parameter(Mandatory = $true)][string]$Content,
        [Parameter(Mandatory = $true)][string]$Name
    )
    # 启动器只读取顶层标量；模型列表等复杂配置由 Spring Boot 自己维护。
    $pattern = '(?m)^' + [Regex]::Escape($Name) + '\s*:\s*(?<value>[^#\r\n]*)'
    $match = [Regex]::Match($Content, $pattern)
    if (-not $match.Success) { return '' }
    $value = $match.Groups['value'].Value.Trim()
    if ($value.Length -ge 2) {
        $first = $value.Substring(0, 1)
        $last = $value.Substring($value.Length - 1, 1)
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
            return $value.Substring(1, $value.Length - 2)
        }
    }
    return $value
}

function Set-EnvironmentDefault {
    param([string]$Name, [string]$Value)
    if ($Value -and -not [Environment]::GetEnvironmentVariable($Name, 'Process')) {
        [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
    }
}

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

function Resolve-JavaExecutable {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $temurinRoot = 'F:\kaifa\temurin17'
    $home = Get-ChildItem -LiteralPath $temurinRoot -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($home) {
        $env:JAVA_HOME = $home.FullName
        return Join-Path $home.FullName 'bin\java.exe'
    }
    return 'java.exe'
}

function Test-BundledVue {
    param([string]$Path)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        return $null -ne $archive.GetEntry('BOOT-INF/classes/static/index.html')
    } finally {
        $archive.Dispose()
    }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $ConfigPath) { $ConfigPath = Join-Path $projectRoot 'config.yaml' }
if (Test-Path -LiteralPath $ConfigPath) {
    $config = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8
    $runtimeUrl = Get-YamlScalar $config 'runtime_db_url'
    if ($runtimeUrl) {
        if ($runtimeUrl.StartsWith('sqlite+pysqlite:///')) {
            $databasePath = $runtimeUrl.Substring('sqlite+pysqlite:///'.Length)
        } elseif ($runtimeUrl.StartsWith('jdbc:sqlite:')) {
            $databasePath = $runtimeUrl.Substring('jdbc:sqlite:'.Length)
        } else {
            throw 'Java 单运行时只支持 SQLite 运行库，请检查 runtime_db_url。'
        }
        if (-not [System.IO.Path]::IsPathRooted($databasePath)) {
            $databasePath = Join-Path $projectRoot $databasePath
        }
        $databasePath = [System.IO.Path]::GetFullPath($databasePath)
        $databaseDirectory = Split-Path -Parent $databasePath
        if (-not (Test-Path -LiteralPath $databaseDirectory)) {
            New-Item -ItemType Directory -Path $databaseDirectory | Out-Null
        }
        Set-EnvironmentDefault 'WIKI_RUNTIME_DB_URL' ('jdbc:sqlite:' + $databasePath)
    }
    Set-EnvironmentDefault 'WIKI_ADMIN_PASSWORD' (Get-YamlScalar $config 'admin_password')
    Set-EnvironmentDefault 'AGENT_DEFAULT_MODEL' (Get-YamlScalar $config 'default_model')
    Set-EnvironmentDefault 'OLLAMA_MODEL' (Get-YamlScalar $config 'ollama_model')
    Set-EnvironmentDefault 'OLLAMA_BASE_URL' (Get-YamlScalar $config 'ollama_base_url')
    Set-EnvironmentDefault 'DBHUB_API_URL' (Get-YamlScalar $config 'dbhub_api_url')
    Set-EnvironmentDefault 'DBHUB_MCP_URL' (Get-YamlScalar $config 'dbhub_mcp_url')
    Set-EnvironmentDefault 'BUSINESS_DB_DATABASE' (Get-YamlScalar $config 'business_db_database')
    Set-EnvironmentDefault 'BUSINESS_DB_SCHEMA' (Get-YamlScalar $config 'business_db_schema')
    $sourceId = Get-YamlScalar $config 'business_db_source_id'
    if ($sourceId) {
        $suffix = $sourceId.ToUpperInvariant()
        Set-EnvironmentDefault ('DBHUB_SOURCE_ID_' + $suffix) $sourceId
        Set-EnvironmentDefault ('DBHUB_EXECUTE_TOOL_' + $suffix) (Get-YamlScalar $config ('dbhub_execute_tool_' + $sourceId))
    }
    $proxy = Get-YamlScalar $config 'java_http_proxy_url'
    if ($proxy) {
        $uri = New-Object System.Uri($proxy)
        $proxyOptions = '-Dhttp.proxyHost={0} -Dhttp.proxyPort={1} -Dhttps.proxyHost={0} -Dhttps.proxyPort={1} -Dhttp.nonProxyHosts="localhost|127.*"' -f $uri.Host, $uri.Port
        $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS, $proxyOptions) -join ' ').Trim()
    }
}

Set-EnvironmentDefault 'WIKI_KNOWLEDGE_ROOT' (Join-Path $projectRoot 'core-rules-wiki')
$env:SERVER_PORT = [string]$Port

if (-not $JarPath) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'backend-java\target') -Filter 'wiki-agent-java-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) { throw '未找到 Java JAR，请先运行 scripts\build-java-vue.ps1。' }
    $JarPath = $jar.FullName
}
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path
if (-not (Test-BundledVue $JarPath)) {
    throw 'JAR 未包含 Vue 页面，请使用 scripts\build-java-vue.ps1 重新构建。'
}
if (-not (Test-PortAvailable $Port)) {
    throw "端口 $Port 已被占用；启动器不会自动结束现有进程。"
}

$java = Resolve-JavaExecutable
Write-Output "正在启动 Java 单运行时：port=$Port jar=$JarPath"
& $java -jar $JarPath
if ($LASTEXITCODE -ne 0) { throw "Java 服务退出，代码：$LASTEXITCODE" }
