"""提示词加载模块。

所有提示词模板存放在 app/prompts/ 目录下的 .txt 文件中，
通过 Python str.format() 进行变量替换。
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path

_PROMPTS_DIR = Path(__file__).resolve().parent


def load_prompt(name: str) -> str:
    path = _PROMPTS_DIR / f"{name}.txt"
    if not path.exists():
        raise FileNotFoundError(f"提示词文件不存在: {path}")
    return path.read_text(encoding="utf-8")


def format_prompt(name: str, **values: object) -> str:
    """Replace only ``{{named}}`` placeholders and preserve JSON braces."""
    template = load_prompt(name)

    def replace(match: re.Match[str]) -> str:
        key = match.group(1)
        return str(values.get(key, match.group(0)))

    return re.sub(r"\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}", replace, template)


def prompt_version(name: str) -> str:
    return hashlib.sha256(load_prompt(name).encode("utf-8")).hexdigest()[:12]


def intent_prompt_system() -> str:
    """旧聊天流程的意图识别模板；当前 Agent Runtime 不使用。"""
    return load_prompt("legacy_chat_intent")


def answer_prompt_template() -> str:
    """旧聊天流程的答案生成模板；当前 Agent Runtime 不使用。

    可用占位符: {query}, {steps}, {rule_name}, {rule_id}, {effective_level},
    {definition}, {formula}, {implementation_status}, {field_status}, {sql_status}, {warnings}
    """
    return load_prompt("legacy_chat_answer")
