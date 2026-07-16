import os
import re

import pytest

from scripts.probe_agent_tool_calling import called_tools, run_probe


@pytest.mark.skipif(
    os.getenv("RUN_OLLAMA_AGENT_PROBE") != "1",
    reason="设置 RUN_OLLAMA_AGENT_PROBE=1 后运行真实 Ollama 业务探针",
)
def test_real_ollama_completes_indicator_tool_chain() -> None:
    import asyncio

    result = asyncio.run(run_probe())
    tools = called_tools(result)
    assert result.stop_reason == "final_answer"
    assert "search_indicator_rules" in tools
    assert "get_effective_rule" in tools
    assert re.search(r"[\u4e00-\u9fff]", result.answer)
