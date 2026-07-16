"""工具调用型 Agent 的受控中文提示。"""

from __future__ import annotations

import json
from datetime import datetime

from app.prompts import format_prompt, load_prompt


AGENT_SYSTEM_PROMPT = load_prompt("agent_executor").strip()
_CORRECTIONS = json.loads(load_prompt("agent_executor_corrections"))
EVIDENCE_REQUIRED_PROMPT = _CORRECTIONS["evidence_required"]
CHINESE_REQUIRED_PROMPT = _CORRECTIONS["chinese_required"]
TRIAL_RUN_REQUIRED_PROMPT = _CORRECTIONS["trial_run_required"]


def executor_correction(name: str, **values: object) -> str:
    template = str(_CORRECTIONS[name])
    for key, value in values.items():
        template = template.replace("{{" + key + "}}", str(value))
    return template


def build_agent_system_prompt(
    *,
    structured_summary: str,
    recent_history: str,
    now: datetime,
) -> str:
    history = recent_history or "当前没有历史对话。"
    return format_prompt(
        "agent_executor_context",
        executor_prompt=AGENT_SYSTEM_PROMPT,
        current_date=now.date().isoformat(),
        structured_summary=structured_summary,
        recent_history=history,
    ).strip()
