# wxp-mcp 公司表模型工具接入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不向公开仓库提交公司源码、内部仓库地址或 Session 的前提下，为项目增加可复现的 wxp-mcp 下载、构建、启动和两级连通性检测能力。

**Architecture:** 公开仓库只保存 PowerShell 管理脚本、无依赖的 Node.js MCP 检测客户端、中文文档和自动化测试；公司源码克隆到 Git 忽略的 `tools/wxp-mcp/vendor/wxp-mcp`。基础检测只验证 MCP 握手与工具清单，平台检测再使用本机 `WXP_TENANTSESSION` 查询公司表模型，从而使临时平台停服不会阻塞本地安装验收。

**Tech Stack:** PowerShell 5.1+、Node.js 18+、MCP JSON-RPC stdio、Git、npm、Python pytest。

## Global Constraints

- wxp-mcp 固定到提交 `79e6a6e2b0f7150d4f88e0c3766c0171c50cac73`，真实上游分支是 `master`。
- `WXP_MCP_REPOSITORY_URL`、`WXP_TENANTSESSION` 和可选 `WXP_API_HOST` 只能来自进程环境变量或被忽略的 `wxp.local.env`。
- `tools/wxp-mcp/vendor/`、`wxp.local.env`、构建日志和查询结果不得进入 Git。
- 脚本输出不得包含 Session、内部仓库地址或完整公司模型查询结果。
- wxp-mcp 只作为公司侧实施工具，不进入医院生产运行链路和医院部署包。
- 不硬编码当前临时替代地址；平台修复后由本机 `WXP_API_HOST` 决定访问地址。
- 用户可见提示、README 和错误信息使用中文。
- 每个任务采用 TDD，验证通过后使用中文 Conventional Commit 提交并推送 `main`。

---

## 文件职责

| 文件 | 职责 |
| --- | --- |
| `.gitignore` | 忽略公司源码、凭据、日志和模型查询结果 |
| `tools/wxp-mcp/wxp.env.example` | 提供不含真实值的本机配置模板 |
| `tools/wxp-mcp/wxp-tooling.ps1` | 安全加载本机环境变量并提供路径、命令检查公共函数 |
| `tools/wxp-mcp/install-wxp-mcp.ps1` | 下载固定提交、安装依赖并构建公司 MCP |
| `tools/wxp-mcp/mcp-smoke-test.mjs` | 使用 Node.js 标准库执行 MCP 握手、工具清单和可选平台查询 |
| `tools/wxp-mcp/test-wxp-mcp.ps1` | 为实施人员提供中文基础检测和平台检测入口 |
| `tools/wxp-mcp/start-wxp-mcp.ps1` | 加载本机配置并以 stdio 模式启动 MCP |
| `tools/wxp-mcp/README.md` | 说明定位、安装、验证、更新、安全边界和故障处理 |
| `tests/test_wxp_mcp_tooling.py` | 验证忽略规则、配置加载、安装失败边界和检测客户端行为 |

---

### Task 1: 安全配置加载与固定版本安装器

**Files:**
- Modify: `.gitignore`
- Create: `tools/wxp-mcp/wxp.env.example`
- Create: `tools/wxp-mcp/wxp-tooling.ps1`
- Create: `tools/wxp-mcp/install-wxp-mcp.ps1`
- Create: `tests/test_wxp_mcp_tooling.py`

**Interfaces:**
- Consumes: `git`、`node`、`npm` 命令；本机 `WXP_MCP_REPOSITORY_URL` 和可选 `WXP_MCP_COMMIT`。
- Produces: `Import-WxpLocalEnvironment`、`Get-WxpSourceDirectory`、`Assert-WxpCommand` PowerShell 函数；构建产物 `tools/wxp-mcp/vendor/wxp-mcp/dist/index.js`。

- [ ] **Step 1: 编写忽略规则和安装失败路径测试**

在 `tests/test_wxp_mcp_tooling.py` 创建以下测试骨架：

```python
from __future__ import annotations

import os
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOL_ROOT = ROOT / "tools" / "wxp-mcp"


def run_powershell(script: Path, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    merged = os.environ.copy()
    merged.update(env or {})
    return subprocess.run(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)],
        cwd=ROOT,
        env=merged,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )


def test_wxp_sensitive_paths_are_git_ignored() -> None:
    rules = (ROOT / ".gitignore").read_text(encoding="utf-8")
    assert "tools/wxp-mcp/vendor/" in rules
    assert "tools/wxp-mcp/wxp.local.env" in rules
    assert "tools/wxp-mcp/*.local.log" in rules
    assert "tools/wxp-mcp/output/" in rules


def test_install_requires_repository_url_without_printing_session() -> None:
    secret = "session-must-not-appear"
    result = run_powershell(
        TOOL_ROOT / "install-wxp-mcp.ps1",
        {
            "WXP_MCP_REPOSITORY_URL": "",
            "WXP_TENANTSESSION": secret,
        },
    )
    output = result.stdout + result.stderr
    assert result.returncode != 0
    assert "WXP_MCP_REPOSITORY_URL" in output
    assert secret not in output
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
python -m pytest tests/test_wxp_mcp_tooling.py -q
```

Expected: 两项测试失败，原因分别是缺少忽略规则和安装脚本。

- [ ] **Step 3: 添加安全忽略规则与配置模板**

在 `.gitignore` 追加：

```gitignore
# 公司侧 wxp-mcp 本地源码、凭据和查询输出，不得提交到公开仓库。
tools/wxp-mcp/vendor/
tools/wxp-mcp/wxp.local.env
tools/wxp-mcp/*.local.log
tools/wxp-mcp/output/
```

创建 `tools/wxp-mcp/wxp.env.example`：

```dotenv
# 从公司 WinCode MCP 目录页复制源码仓库地址，只保存在本机。
WXP_MCP_REPOSITORY_URL=

# 默认固定版本。升级时先修改设计与测试，再更新此值。
WXP_MCP_COMMIT=79e6a6e2b0f7150d4f88e0c3766c0171c50cac73

# 从可用的 WxP 运营中心获取；基础 MCP 检测不需要填写。
WXP_TENANTSESSION=

# 可选。平台迁移或临时切换时在本机填写，不提交真实地址。
WXP_API_HOST=
```

- [ ] **Step 4: 实现公共配置函数**

创建 `tools/wxp-mcp/wxp-tooling.ps1`：

```powershell
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
```

- [ ] **Step 5: 实现固定版本安装器**

创建 `tools/wxp-mcp/install-wxp-mcp.ps1`，核心实现必须如下：

```powershell
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "wxp-tooling.ps1")
Import-WxpLocalEnvironment

$pinnedCommit = "79e6a6e2b0f7150d4f88e0c3766c0171c50cac73"
$repositoryUrl = $env:WXP_MCP_REPOSITORY_URL
$targetCommit = if ($env:WXP_MCP_COMMIT) { $env:WXP_MCP_COMMIT } else { $pinnedCommit }
$sourceDirectory = Get-WxpSourceDirectory

if (-not $repositoryUrl) {
    throw "未配置 WXP_MCP_REPOSITORY_URL。请复制 wxp.env.example 为 wxp.local.env 并填写公司内部仓库地址。"
}
if ($targetCommit -notmatch "^[0-9a-fA-F]{40}$") {
    throw "WXP_MCP_COMMIT 必须是 40 位 Git 提交哈希。"
}

Assert-WxpCommand "git"
Assert-WxpCommand "node"
Assert-WxpCommand "npm"

$nodeVersion = (& node --version).Trim().TrimStart("v")
if ([int]($nodeVersion.Split(".")[0]) -lt 18) {
    throw "wxp-mcp 要求 Node.js 18 或更高版本，当前版本为 $nodeVersion。"
}

New-Item -ItemType Directory -Force -Path (Split-Path $sourceDirectory) | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $sourceDirectory ".git"))) {
    Write-Host "正在下载公司表模型工具..."
    & git clone --no-checkout --quiet $repositoryUrl $sourceDirectory 2>$null
    if ($LASTEXITCODE -ne 0) { throw "下载 wxp-mcp 失败，请检查公司网络和仓库权限。" }
} else {
    Write-Host "已发现本地源码，正在检查固定版本..."
}

& git -C $sourceDirectory fetch --quiet origin $targetCommit 2>$null
if ($LASTEXITCODE -ne 0) { throw "无法获取固定版本，请检查仓库权限或提交哈希。" }
& git -C $sourceDirectory checkout --quiet --detach $targetCommit
if ($LASTEXITCODE -ne 0) { throw "无法切换到固定版本。" }

$actualCommit = (& git -C $sourceDirectory rev-parse HEAD).Trim()
if ($actualCommit -ne $targetCommit) {
    throw "版本校验失败，当前版本与固定版本不一致。"
}

Push-Location $sourceDirectory
try {
    Write-Host "正在安装依赖并构建..."
    & npm ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw "npm ci 失败，请检查 npm 网络或锁文件。" }
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw "wxp-mcp 构建失败。" }
} finally {
    Pop-Location
}

$entrypoint = Join-Path $sourceDirectory "dist\index.js"
if (-not (Test-Path -LiteralPath $entrypoint)) {
    throw "构建完成但未找到 dist/index.js。"
}

Write-Host "wxp-mcp 已安装并通过版本校验。"
Write-Host "版本：$actualCommit"
```

实现时不得输出 `$repositoryUrl`、`$env:WXP_TENANTSESSION` 或整个环境变量集合。

- [ ] **Step 6: 运行目标测试并确认通过**

Run:

```powershell
python -m pytest tests/test_wxp_mcp_tooling.py -q
```

Expected: `2 passed`。

- [ ] **Step 7: 检查差异并提交推送**

Run:

```powershell
git diff --check
git status --short
git add .gitignore tools/wxp-mcp/wxp.env.example tools/wxp-mcp/wxp-tooling.ps1 tools/wxp-mcp/install-wxp-mcp.ps1 tests/test_wxp_mcp_tooling.py
git commit -m "feat(tool): 增加公司模型工具安全安装器"
git push origin main
```

Expected: 只提交公开脚本、模板和测试；`vendor/` 与 `wxp.local.env` 不出现在提交中。

---

### Task 2: MCP 握手、工具清单与平台查询检测

**Files:**
- Create: `tools/wxp-mcp/mcp-smoke-test.mjs`
- Create: `tools/wxp-mcp/test-wxp-mcp.ps1`
- Modify: `tests/test_wxp_mcp_tooling.py`

**Interfaces:**
- Consumes: `tools/wxp-mcp/vendor/wxp-mcp/dist/index.js` 或测试传入的 `--entrypoint`；可选 `WXP_TENANTSESSION`、`WXP_API_HOST`。
- Produces: 进程退出码 `0` 表示检测成功；stdout 只输出中文摘要 JSON，不输出 Session 或完整模型内容。

- [ ] **Step 1: 编写假 MCP 服务与基础检测失败测试**

向 `tests/test_wxp_mcp_tooling.py` 添加一个临时假服务生成函数和基础测试：

```python
import json
import tempfile


FAKE_SERVER = r'''
import readline from "node:readline";
const rl = readline.createInterface({ input: process.stdin });
const tools = [
  { name: "table_analysis", inputSchema: { type: "object" } },
  { name: "model_query_class_id", inputSchema: { type: "object" } },
  { name: "model_analyze", inputSchema: { type: "object" } }
];
for await (const line of rl) {
  const msg = JSON.parse(line);
  if (msg.method === "notifications/initialized") continue;
  if (msg.method === "initialize") {
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: {
      protocolVersion: "2024-11-05", capabilities: { tools: {} }, serverInfo: { name: "fake-wxp", version: "1.0.0" }
    } }) + "\n");
  } else if (msg.method === "tools/list") {
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: { tools } }) + "\n");
  } else if (msg.method === "tools/call") {
    const result = msg.params.name === "model_query_class_id"
      ? { classId: "CLASS-1" }
      : { tableName: msg.params.arguments.tableName, fields: [{ name: "id" }], indexes: [], lineage: [] };
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: {
      content: [{ type: "text", text: JSON.stringify(result) }]
    } }) + "\n");
  }
}
'''


def write_fake_server(directory: Path) -> Path:
    path = directory / "fake-wxp-server.mjs"
    path.write_text(FAKE_SERVER, encoding="utf-8")
    return path


def test_node_smoke_client_completes_basic_mcp_check() -> None:
    with tempfile.TemporaryDirectory() as raw:
        entrypoint = write_fake_server(Path(raw))
        result = subprocess.run(
            [
                "node",
                str(TOOL_ROOT / "mcp-smoke-test.mjs"),
                "--mode", "basic",
                "--entrypoint", str(entrypoint),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
    assert result.returncode == 0, result.stderr
    summary = json.loads(result.stdout)
    assert summary["status"] == "ok"
    assert summary["mode"] == "basic"
    assert summary["requiredTools"] == [
        "model_analyze",
        "model_query_class_id",
        "table_analysis",
    ]
```

- [ ] **Step 2: 运行新增测试并确认失败**

Run:

```powershell
python -m pytest tests/test_wxp_mcp_tooling.py::test_node_smoke_client_completes_basic_mcp_check -q
```

Expected: FAIL，因为 `mcp-smoke-test.mjs` 尚不存在。

- [ ] **Step 3: 实现无依赖 MCP stdio 检测客户端**

创建 `tools/wxp-mcp/mcp-smoke-test.mjs`。实现必须包含以下接口：

```javascript
#!/usr/bin/env node
import { spawn } from "node:child_process";
import readline from "node:readline";

const REQUIRED_TOOLS = ["model_analyze", "model_query_class_id", "table_analysis"];

function parseArgs(argv) {
  const result = { mode: "basic", timeout: 10000 };
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index]?.replace(/^--/, "");
    const value = argv[index + 1];
    if (!key || value === undefined) throw new Error("参数必须使用 --名称 值 的形式。");
    result[key] = value;
  }
  result.timeout = Number(result.timeout);
  return result;
}

function extractJsonContent(result) {
  const text = result?.content?.find((item) => item?.type === "text")?.text;
  if (!text) return {};
  try { return JSON.parse(text); } catch { return {}; }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.entrypoint) throw new Error("未提供 --entrypoint。");
  if (!["basic", "platform"].includes(args.mode)) throw new Error("--mode 只能是 basic 或 platform。");
  if (args.mode === "platform" && (!args.project || !args.table)) {
    throw new Error("平台检测必须提供 --project 和 --table。");
  }
  if (args.mode === "platform" && !process.env.WXP_TENANTSESSION) {
    throw new Error("平台检测需要本机 WXP_TENANTSESSION。");
  }

  const child = spawn(process.execPath, [args.entrypoint], {
    env: process.env,
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true,
  });
  const pending = new Map();
  let nextId = 1;
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => { stderr += chunk; });

  const lines = readline.createInterface({ input: child.stdout });
  lines.on("line", (line) => {
    let message;
    try { message = JSON.parse(line); } catch { return; }
    if (message.id !== undefined && pending.has(message.id)) {
      const waiter = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) waiter.reject(new Error(message.error.message || "MCP 调用失败"));
      else waiter.resolve(message.result);
    }
  });

  function request(method, params = {}) {
    const id = nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`${method} 超时`));
      }, args.timeout);
      pending.set(id, {
        resolve: (value) => { clearTimeout(timer); resolve(value); },
        reject: (error) => { clearTimeout(timer); reject(error); },
      });
      child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
    });
  }

  try {
    await request("initialize", {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: { name: "wiki-project-wxp-check", version: "1.0.0" },
    });
    child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })}\n`);
    const listed = await request("tools/list");
    const names = (listed.tools || []).map((tool) => tool.name);
    const missing = REQUIRED_TOOLS.filter((name) => !names.includes(name));
    if (missing.length) throw new Error(`缺少核心工具：${missing.join("、")}`);

    const summary = {
      status: "ok",
      mode: args.mode,
      requiredTools: [...REQUIRED_TOOLS].sort(),
      toolCount: names.length,
    };

    if (args.mode === "platform") {
      const classResult = await request("tools/call", {
        name: "model_query_class_id",
        arguments: { projectName: args.project, moduleName: args.module || "", tableName: args.table },
      });
      const classData = extractJsonContent(classResult);
      if (!classData.classId) throw new Error("未返回 classId，请检查项目、模块和表名。");
      const tableResult = await request("tools/call", {
        name: "table_analysis",
        arguments: { projectName: args.project, moduleName: args.module || "", tableName: args.table },
      });
      const tableData = extractJsonContent(tableResult);
      summary.table = args.table;
      summary.fieldCount = Array.isArray(tableData.fields) ? tableData.fields.length : 0;
      summary.indexCount = Array.isArray(tableData.indexes) ? tableData.indexes.length : 0;
      summary.lineageCount = Array.isArray(tableData.fields)
        ? tableData.fields.reduce(
            (total, field) => total
              + (Array.isArray(field.relations) ? field.relations.length : 0)
              + (Array.isArray(field.references) ? field.references.length : 0),
            0,
          )
        : 0;
    }

    process.stdout.write(`${JSON.stringify(summary)}\n`);
  } finally {
    child.stdin.end();
    child.kill();
  }
}

main().catch((error) => {
  process.stderr.write(`wxp-mcp 检测失败：${error.message}\n`);
  process.exitCode = 1;
});
```

实现时如真实 `tools/list` schema 与当前固定版本存在差异，应以实际协议为准，但必须保持上述命令行接口和摘要字段稳定。

- [ ] **Step 4: 增加平台查询与凭据脱敏测试**

向 `tests/test_wxp_mcp_tooling.py` 添加：

```python
def test_node_smoke_client_platform_summary_does_not_expose_session() -> None:
    secret = "platform-secret-session"
    with tempfile.TemporaryDirectory() as raw:
        entrypoint = write_fake_server(Path(raw))
        env = os.environ.copy()
        env["WXP_TENANTSESSION"] = secret
        result = subprocess.run(
            [
                "node", str(TOOL_ROOT / "mcp-smoke-test.mjs"),
                "--mode", "platform",
                "--entrypoint", str(entrypoint),
                "--project", "WiNEX",
                "--module", "门急诊",
                "--table", "consult_record",
            ],
            cwd=ROOT,
            env=env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
    output = result.stdout + result.stderr
    assert result.returncode == 0, result.stderr
    assert secret not in output
    summary = json.loads(result.stdout)
    assert summary["table"] == "consult_record"
    assert summary["fieldCount"] == 1
```

- [ ] **Step 5: 实现中文 PowerShell 检测入口**

创建 `tools/wxp-mcp/test-wxp-mcp.ps1`：

```powershell
param(
    [ValidateSet("basic", "platform")][string]$Mode = "basic",
    [string]$Project = "",
    [string]$Module = "",
    [string]$Table = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "wxp-tooling.ps1")
Import-WxpLocalEnvironment
Assert-WxpCommand "node"

$entrypoint = Join-Path (Get-WxpSourceDirectory) "dist\index.js"
if (-not (Test-Path -LiteralPath $entrypoint)) {
    throw "尚未构建 wxp-mcp，请先运行 install-wxp-mcp.ps1。"
}

$arguments = @(
    (Join-Path $PSScriptRoot "mcp-smoke-test.mjs"),
    "--mode", $Mode,
    "--entrypoint", $entrypoint
)
if ($Mode -eq "platform") {
    if (-not $Project -or -not $Table) {
        throw "平台检测必须填写 Project 和 Table。"
    }
    $arguments += @("--project", $Project, "--module", $Module, "--table", $Table)
}

& node @arguments
if ($LASTEXITCODE -ne 0) {
    throw "wxp-mcp 检测未通过，请根据上方提示处理。"
}
```

- [ ] **Step 6: 运行本任务测试并确认通过**

Run:

```powershell
python -m pytest tests/test_wxp_mcp_tooling.py -q
```

Expected: `4 passed`。

- [ ] **Step 7: 提交并推送检测能力**

Run:

```powershell
git diff --check
git add tools/wxp-mcp/mcp-smoke-test.mjs tools/wxp-mcp/test-wxp-mcp.ps1 tests/test_wxp_mcp_tooling.py
git commit -m "feat(tool): 增加公司模型 MCP 连通性检测"
git push origin main
```

---

### Task 3: 启动入口、实施文档与真实构建验收

**Files:**
- Create: `tools/wxp-mcp/start-wxp-mcp.ps1`
- Create: `tools/wxp-mcp/README.md`
- Modify: `README.md`
- Modify: `tests/test_wxp_mcp_tooling.py`

**Interfaces:**
- Consumes: Task 1 的本机配置和构建产物；Task 2 的检测入口。
- Produces: 实施人员可直接运行的启动、安装和验证说明；项目根 README 的公司模型工具入口。

- [ ] **Step 1: 编写启动和文档边界测试**

向 `tests/test_wxp_mcp_tooling.py` 添加：

```python
def test_wxp_readme_explains_company_only_boundary_and_commands() -> None:
    text = (TOOL_ROOT / "README.md").read_text(encoding="utf-8")
    assert "公司侧实施工具" in text
    assert "不进入医院生产运行链路" in text
    assert ".\\install-wxp-mcp.ps1" in text
    assert ".\\test-wxp-mcp.ps1 -Mode basic" in text
    assert ".\\test-wxp-mcp.ps1 -Mode platform" in text
    assert "WXP_TENANTSESSION" in text
    assert "不得提交" in text


def test_start_script_uses_local_environment_without_embedded_secret() -> None:
    text = (TOOL_ROOT / "start-wxp-mcp.ps1").read_text(encoding="utf-8")
    assert "Import-WxpLocalEnvironment" in text
    assert "WXP_TENANTSESSION=" not in text
    assert "dist\\index.js" in text
```

- [ ] **Step 2: 运行新增测试并确认失败**

Run:

```powershell
python -m pytest tests/test_wxp_mcp_tooling.py -q
```

Expected: 两个新增测试失败，因为启动脚本和 README 尚不存在。

- [ ] **Step 3: 实现 stdio 启动入口**

创建 `tools/wxp-mcp/start-wxp-mcp.ps1`：

```powershell
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "wxp-tooling.ps1")
Import-WxpLocalEnvironment
Assert-WxpCommand "node"

$entrypoint = Join-Path (Get-WxpSourceDirectory) "dist\index.js"
if (-not (Test-Path -LiteralPath $entrypoint)) {
    throw "尚未构建 wxp-mcp，请先运行 install-wxp-mcp.ps1。"
}

Write-Host "正在以 stdio 模式启动公司表模型工具。"
Write-Host "该工具只用于公司侧实施，不连接医院患者数据。"
& node $entrypoint
if ($LASTEXITCODE -ne 0) {
    throw "wxp-mcp 进程异常退出，退出码：$LASTEXITCODE"
}
```

- [ ] **Step 4: 编写实施人员 README**

创建 `tools/wxp-mcp/README.md`，至少包含以下结构和明确内容：

```markdown
# wxp-mcp 公司表模型工具

这是公司侧实施工具，用于查看 WxP 标准表模型、字段、索引和数据血缘。它不进入医院生产运行链路，也不能替代医院本地 DBHub。

## 首次安装

1. 复制 `wxp.env.example` 为 `wxp.local.env`。
2. 在本机文件中填写 `WXP_MCP_REPOSITORY_URL`。
3. 运行：

```powershell
cd F:\A-wiki-project\tools\wxp-mcp
.\install-wxp-mcp.ps1
```

## 基础检测

```powershell
.\test-wxp-mcp.ps1 -Mode basic
```

基础检测不需要 Session，只验证 MCP 可以启动、完成协议握手并暴露核心工具。

## 公司平台检测

在 `wxp.local.env` 中配置有效的 `WXP_TENANTSESSION`；平台迁移期间可在本机配置 `WXP_API_HOST`。然后运行：

```powershell
.\test-wxp-mcp.ps1 -Mode platform -Project "WiNEX" -Module "" -Table "PARAMETER"
```

平台检测只输出命中表名和字段、索引、血缘数量，不保存完整模型结果。

## 如何用于指标设计稿

wxp-mcp 提供公司标准模型候选信息；实施人员仍需与医院 INFORMATION_SCHEMA、医院数据字典和指标口径逐项核对。确认后的表字段才能录入指标设计稿，并必须经过 SQL 试运行和审批发布。

## 安全要求

- `wxp.local.env`、`vendor/`、Session、内部仓库地址和查询结果不得提交 Git。
- 不要在聊天、截图、日志或错误反馈中粘贴 Session。
- 不要把 wxp-mcp 或公司模型数据放入医院部署包。
```

同时补充“版本固定与升级”和“故障处理”章节，分别说明当前固定提交、显式升级流程，以及网络不可达、Session 未配置/过期、模型不存在、构建失败的处理方式。

- [ ] **Step 5: 在项目根 README 增加工具入口**

在根 `README.md` 的本地工具或部署说明附近增加：

```markdown
### 公司表模型工具（仅公司侧实施）

`tools/wxp-mcp` 用于查询公司 WxP 标准表模型、字段、索引和数据血缘，帮助实施人员确认指标设计稿中的字段映射。它不进入医院生产环境，安装与验证见 [`tools/wxp-mcp/README.md`](tools/wxp-mcp/README.md)。
```

- [ ] **Step 6: 运行自动化测试**

Run:

```powershell
python -m pytest tests/test_wxp_mcp_tooling.py -q
```

Expected: `6 passed`。

- [ ] **Step 7: 执行真实本地安装与基础检测**

确认本机 `wxp.local.env` 已填写从 WinCode 目录页复制的内部仓库地址，然后运行：

```powershell
.\tools\wxp-mcp\install-wxp-mcp.ps1
.\tools\wxp-mcp\test-wxp-mcp.ps1 -Mode basic
```

Expected:

- 安装器输出固定提交 `79e6a6e2b0f7150d4f88e0c3766c0171c50cac73`。
- `dist/index.js` 存在。
- 基础检测返回 `status=ok`，核心工具全部存在。
- `git status --short` 不显示 `vendor/`、`wxp.local.env` 或模型输出。

由于 WxP 当前临时停服，本批不伪造“平台检测成功”。平台恢复并取得有效 Session 后执行：

```powershell
.\tools\wxp-mcp\test-wxp-mcp.ps1 -Mode platform -Project "WiNEX" -Module "" -Table "PARAMETER"
```

- [ ] **Step 8: 运行完整回归测试**

Run:

```powershell
python -m pytest -q
```

Expected: 当前项目全部测试通过，且没有新增 warning 或失败。

- [ ] **Step 9: 最终安全检查、提交并推送**

Run:

```powershell
git status --short
git diff --check
git ls-files tools/wxp-mcp/vendor tools/wxp-mcp/wxp.local.env
rg --pcre2 -n "WXP_TENANTSESSION=\S+" -g "!docs/superpowers/**" -g "!tools/wxp-mcp/wxp.env.example" -g "!tests/**"
git add tools/wxp-mcp/start-wxp-mcp.ps1 tools/wxp-mcp/README.md README.md tests/test_wxp_mcp_tooling.py
git commit -m "docs(tool): 补充公司模型工具实施说明"
git push origin main
```

Expected:

- `git ls-files` 不返回公司源码或本机配置。
- `git grep` 不返回真实 Session。
- 提交和推送成功。

---

## 完成后的人工验收

1. 新机器按 README 复制配置模板并填写内部仓库地址。
2. 执行安装脚本，确认固定版本和构建成功。
3. 不配置 Session 执行基础检测，确认 MCP 工具清单正常。
4. 平台恢复后配置 Session，使用一个已知模型表执行平台检测。
5. 在指标设计稿中只录入人工确认后的表字段，不自动发布公司模型映射。
6. 检查 GitHub 提交内容，确认不存在公司源码、内部地址、Session 或查询结果。
