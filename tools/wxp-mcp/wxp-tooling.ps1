$ErrorActionPreference = "Stop"

function Import-WxpLocalEnvironment {
    param([string]$Path = (Join-Path $PSScriptRoot "wxp.local.env"))

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        $parts = $trimmed.Split("=", 2)
        if ($parts.Count -ne 2 -or -not $parts[0].Trim()) {
            throw "本机配置格式错误，请使用 KEY=VALUE：$Path"
        }
        $name = $parts[0].Trim()
        if (-not [Environment]::GetEnvironmentVariable($name, "Process")) {
            [Environment]::SetEnvironmentVariable($name, $parts[1].Trim(), "Process")
        }
    }
}

function Get-WxpSourceDirectory {
    return Join-Path $PSScriptRoot "vendor\wxp-mcp"
}

function Assert-WxpCommand {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "缺少命令 $Name，请先安装后重试。"
    }
}
