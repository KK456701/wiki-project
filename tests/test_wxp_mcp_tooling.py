from __future__ import annotations

import os
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOL_ROOT = ROOT / "tools" / "wxp-mcp"


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
    for name in ("wxp-tooling.ps1", "install-wxp-mcp.ps1"):
        assert (TOOL_ROOT / name).read_bytes().startswith(b"\xef\xbb\xbf"), name
