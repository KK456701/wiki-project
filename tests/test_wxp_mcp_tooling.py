from __future__ import annotations

import os
import json
import subprocess
import shutil
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOL_ROOT = ROOT / "tools" / "wxp-mcp"

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
      protocolVersion: "2024-11-05",
      capabilities: { tools: {} },
      serverInfo: { name: "fake-wxp", version: "1.0.0" }
    } }) + "\n");
  } else if (msg.method === "tools/list") {
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: { tools } }) + "\n");
  } else if (msg.method === "tools/call") {
    const result = msg.params.name === "model_query_class_id"
      ? { classId: "CLASS-1" }
      : {
          tableName: msg.params.arguments.tableName,
          fields: [{ name: "id", relations: [], references: [] }],
          indexes: []
        };
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: {
      content: [{ type: "text", text: JSON.stringify(result) }]
    } }) + "\n");
  }
}
'''


def run_powershell(
    script: Path, env: dict[str, str] | None = None
) -> subprocess.CompletedProcess[str]:
    merged = os.environ.copy()
    merged.update(env or {})
    return subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(script),
        ],
        cwd=ROOT,
        env=merged,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )


def write_fake_server(directory: Path) -> Path:
    path = directory / "fake-wxp-server.mjs"
    path.write_text(FAKE_SERVER, encoding="utf-8")
    return path


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


def test_powershell_scripts_use_utf8_bom_for_windows_powershell() -> None:
    for name in (
        "wxp-tooling.ps1",
        "install-wxp-mcp.ps1",
        "test-wxp-mcp.ps1",
        "start-wxp-mcp.ps1",
    ):
        assert (TOOL_ROOT / name).read_bytes().startswith(b"\xef\xbb\xbf"), name


def test_node_smoke_client_completes_basic_mcp_check() -> None:
    with tempfile.TemporaryDirectory() as raw:
        entrypoint = write_fake_server(Path(raw))
        result = subprocess.run(
            [
                "node",
                str(TOOL_ROOT / "mcp-smoke-test.mjs"),
                "--mode",
                "basic",
                "--entrypoint",
                str(entrypoint),
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


def test_node_smoke_client_platform_summary_does_not_expose_session() -> None:
    secret = "platform-secret-session"
    with tempfile.TemporaryDirectory() as raw:
        entrypoint = write_fake_server(Path(raw))
        env = os.environ.copy()
        env["WXP_TENANTSESSION"] = secret
        result = subprocess.run(
            [
                "node",
                str(TOOL_ROOT / "mcp-smoke-test.mjs"),
                "--mode",
                "platform",
                "--entrypoint",
                str(entrypoint),
                "--project",
                "WiNEX",
                "--module",
                "门急诊",
                "--table",
                "consult_record",
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


def test_powershell_smoke_wrapper_requires_installed_entrypoint() -> None:
    with tempfile.TemporaryDirectory() as raw:
        isolated = Path(raw)
        for name in (
            "test-wxp-mcp.ps1",
            "wxp-tooling.ps1",
            "mcp-smoke-test.mjs",
        ):
            shutil.copy2(TOOL_ROOT / name, isolated / name)
        result = run_powershell(isolated / "test-wxp-mcp.ps1")
    output = result.stdout + result.stderr
    assert result.returncode != 0
    assert "尚未构建 wxp-mcp" in output


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
    text = (TOOL_ROOT / "start-wxp-mcp.ps1").read_text(encoding="utf-8-sig")
    assert "Import-WxpLocalEnvironment" in text
    assert "WXP_TENANTSESSION=" not in text
    assert "dist\\index.js" in text


def test_project_readme_links_company_model_tool() -> None:
    text = (ROOT / "README.md").read_text(encoding="utf-8")
    assert "公司表模型工具（仅公司侧实施）" in text
    assert "tools/wxp-mcp/README.md" in text
