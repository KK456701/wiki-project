"""为本地小模型构建有界、可解释的会话提示词上下文。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.memory.contracts import ConversationContext


@dataclass(frozen=True)
class PromptContext:
    structured_summary: str
    recent_history: str
    kept_turns: int
    trimmed_message_count: int
    estimated_tokens: int


def _estimate_tokens(text: str) -> int:
    # 医疗中文和字段名混排时按每字符约一个 token 保守估算。
    return len(text or "")


def _compact_content(content: str, max_chars: int = 420) -> str:
    normalized = str(content or "").strip()
    if len(normalized) <= max_chars:
        return normalized
    return f"{normalized[:max_chars]}\n...[内容过长，已压缩]"


def _structured_summary(context: ConversationContext | None) -> str:
    if context is None:
        return "当前无结构化会话状态。"
    lines = ["当前会话结构化状态（权威，优先于历史原文）："]
    if context.active_rule.rule_id or context.active_rule.rule_name:
        lines.append(
            f"- 当前指标：{context.active_rule.rule_name or '未命名'}"
            f"（{context.active_rule.rule_id or '无编码'}）"
        )
    if context.active_rule.hospital_id:
        lines.append(f"- 当前医院：{context.active_rule.hospital_id}")
    if context.stat_period.start_time and context.stat_period.end_time:
        lines.append(
            f"- 统计时间：{context.stat_period.start_time} 至 "
            f"{context.stat_period.end_time}（不含结束时刻）"
        )
    if context.working_caliber.overrides:
        lines.append("- 当前会话临时口径：")
        for item in context.working_caliber.overrides:
            field = f"，医院字段={item.hospital_field}" if item.hospital_field else ""
            lines.append(
                f"  - {item.key}={item.business_value}，状态={item.status}{field}"
            )
    else:
        lines.append("- 当前会话临时口径：无，使用本院生效口径。")
    if context.pending_clarifications:
        lines.append(
            "- 待用户确认："
            + "；".join(item.question for item in context.pending_clarifications)
        )
    return "\n".join(lines)


def build_prompt_context(
    messages: list[dict[str, Any]],
    structured_context: ConversationContext | None,
    *,
    max_turns: int = 8,
    token_budget: int = 12000,
) -> PromptContext:
    """保留结构化状态，并在剩余预算内加入最近若干轮原始消息。"""
    summary = _structured_summary(structured_context)
    eligible = [
        item
        for item in messages
        if str(item.get("role") or "") in {"user", "assistant"}
    ]
    selected = eligible[-max(0, max_turns) * 2 :]
    original_selected_count = len(selected)

    def render(items: list[dict[str, Any]]) -> str:
        labels = {"user": "用户", "assistant": "助手"}
        return "\n".join(
            f"{labels[str(item.get('role'))]}：{_compact_content(str(item.get('content') or ''))}"
            for item in items
        )

    history = render(selected)
    while selected and _estimate_tokens(summary + "\n" + history) > token_budget:
        remove_count = 2 if len(selected) > 2 else 1
        selected = selected[remove_count:]
        history = render(selected)
    estimated = _estimate_tokens(summary + ("\n" + history if history else ""))
    kept_turns = sum(1 for item in selected if item.get("role") == "user")
    return PromptContext(
        structured_summary=summary,
        recent_history=history,
        kept_turns=kept_turns,
        trimmed_message_count=len(eligible) - len(selected),
        estimated_tokens=estimated,
    )
