"""提示词加载模块。

所有提示词模板存放在 app/prompts/ 目录下的 .txt 文件中，
通过 Python str.format() 进行变量替换。
"""

from __future__ import annotations

from pathlib import Path

_PROMPTS_DIR = Path(__file__).resolve().parent


def _load(name: str) -> str:
    path = _PROMPTS_DIR / f"{name}.txt"
    if not path.exists():
        raise FileNotFoundError(f"提示词文件不存在: {path}")
    return path.read_text(encoding="utf-8")


def intent_prompt_system() -> str:
    """意图识别 prompt 模板（不含 history_block/query，由调用方拼接）。"""
    return _load("intent")


def answer_prompt_template() -> str:
    """答案生成 prompt 模板。

    可用占位符: {query}, {steps}, {rule_name}, {rule_id}, {effective_level},
    {definition}, {formula}, {implementation_status}, {field_status}, {sql_status}, {warnings}
    """
    return _load("answer")
