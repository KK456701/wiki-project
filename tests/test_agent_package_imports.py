import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_agent_tools_can_be_imported_before_agent_runtime() -> None:
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            (
                "import app.agent_tools; "
                "from app.agent_runtime import AgentRunner; "
                "print(AgentRunner.__name__)"
            ),
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )

    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == "AgentRunner"
