"""工具调用型 Agent 的安全会话记忆适配层。"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from app.memory.contracts import ConversationContext
from app.memory.prompt_context import build_prompt_context
from app.memory.store import ConversationMemory, DEFAULT_MEMORY_ROOT

from .contracts import AgentRunState, AgentRuntimeContext
from .prompts import build_agent_system_prompt


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _safe_object_ids(values: Any, prefix: str, limit: int = 8) -> list[str]:
    if not isinstance(values, list):
        return []
    return [
        value
        for value in (str(item) for item in values)
        if value.startswith(prefix)
    ][-limit:]


def _latest_agent_state(messages: list[dict[str, Any]]) -> dict[str, Any]:
    for message in reversed(messages):
        if message.get("role") != "assistant":
            continue
        metadata = message.get("metadata") or {}
        payload = metadata.get("agent_state") or {}
        if isinstance(payload, dict):
            return payload
    return {}


def _latest_rule_name(state: AgentRunState) -> str:
    for result in reversed(state.last_tool_results):
        if not isinstance(result, dict) or result.get("ok") is not True:
            continue
        data = result.get("data") or {}
        if not isinstance(data, dict):
            continue
        if state.current_rule_id and str(data.get("rule_id") or "") not in {
            "", state.current_rule_id
        }:
            continue
        rule_name = str(data.get("rule_name") or "")
        if rule_name:
            return rule_name
    return ""


def _latest_period(state: AgentRunState) -> tuple[str, str] | None:
    for result in reversed(state.last_tool_results):
        if not isinstance(result, dict) or result.get("ok") is not True:
            continue
        data = result.get("data") or {}
        if not isinstance(data, dict):
            continue
        start = str(data.get("stat_start") or data.get("stat_start_time") or "")
        end = str(data.get("stat_end") or data.get("stat_end_time") or "")
        if start and end:
            return start, end
    return None


def _safe_state_metadata(state: AgentRunState) -> dict[str, Any]:
    return {
        "current_rule_id": state.current_rule_id or "",
        "current_stat_start": state.current_stat_start,
        "current_stat_end": state.current_stat_end,
        "validated_sql_ids": _safe_object_ids(
            state.validated_sql_ids, "SQL_"
        ),
        "last_run_id": (
            state.last_run_id if str(state.last_run_id or "").startswith("RUN_") else None
        ),
        "last_diagnosis_id": (
            state.last_diagnosis_id
            if str(state.last_diagnosis_id or "").startswith(("DR_", "CMP_"))
            else None
        ),
        "last_draft_id": (
            state.last_draft_id
            if str(state.last_draft_id or "").startswith("DRAFT_")
            else None
        ),
    }


@dataclass
class AgentMemorySession:
    store: ConversationMemory
    storage_session_id: str
    context: ConversationContext
    state: AgentRunState
    source_message_id: int | None = field(default=None, init=False)

    def append_user(self, query: str) -> None:
        self.source_message_id = self.store.append_message(
            self.storage_session_id,
            "user",
            query,
            {"source": "agent_runtime"},
        )

    def complete(
        self,
        query: str,
        answer: str,
        state: AgentRunState,
    ) -> None:
        self.store.append_message(
            self.storage_session_id,
            "assistant",
            answer,
            {
                "source": "agent_runtime",
                "agent_state": _safe_state_metadata(state),
            },
        )
        updated = self.context.model_copy(deep=True)
        updated.last_action = str(query or "")[:200]
        if state.current_rule_id:
            updated.active_rule.rule_id = state.current_rule_id
            rule_name = _latest_rule_name(state)
            if rule_name:
                updated.active_rule.rule_name = rule_name
        period = _latest_period(state)
        if period is not None:
            updated.stat_period.start_time = period[0]
            updated.stat_period.end_time = period[1]
            updated.stat_period.source_message_id = self.source_message_id
        self.context = self.store.save_context(
            self.storage_session_id,
            updated,
            expected_version=self.context.context_version,
        )


class AgentConversationMemory:
    def __init__(
        self,
        *,
        store: ConversationMemory | None = None,
        max_turns: int = 8,
        token_budget: int = 12000,
        now_provider: Callable[[], datetime] = _utcnow,
    ) -> None:
        self.store = store or ConversationMemory(DEFAULT_MEMORY_ROOT)
        self.max_turns = max(1, max_turns)
        self.token_budget = max(1, token_budget)
        self.now_provider = now_provider

    @staticmethod
    def storage_key(context: AgentRuntimeContext) -> str:
        return (
            f"agent:{context.hospital_id}:{context.user_id}:"
            f"{context.session_id}"
        )

    def open(self, context: AgentRuntimeContext) -> AgentMemorySession:
        storage_session_id = self.storage_key(context)
        self.store.ensure_session(storage_session_id, context.hospital_id)
        structured = self.store.load_context(storage_session_id)
        structured.active_rule.hospital_id = context.hospital_id
        messages = self.store.recent_messages(
            storage_session_id,
            limit=self.max_turns * 2,
        )
        prompt = build_prompt_context(
            messages,
            structured,
            max_turns=self.max_turns,
            token_budget=self.token_budget,
        )
        safe = _latest_agent_state(messages)
        current_rule_id = str(
            structured.active_rule.rule_id
            or safe.get("current_rule_id")
            or ""
        )
        state = AgentRunState(
            messages=[{
                "role": "system",
                "content": build_agent_system_prompt(
                    structured_summary=prompt.structured_summary,
                    recent_history=prompt.recent_history,
                    now=self.now_provider(),
                ),
            }],
            recent_history=prompt.recent_history,
            current_rule_id=current_rule_id or None,
            current_stat_start=(
                structured.stat_period.start_time
                or safe.get("current_stat_start")
                or None
            ),
            current_stat_end=(
                structured.stat_period.end_time
                or safe.get("current_stat_end")
                or None
            ),
            validated_sql_ids=_safe_object_ids(
                safe.get("validated_sql_ids"), "SQL_"
            ),
            last_run_id=(
                str(safe.get("last_run_id"))
                if str(safe.get("last_run_id") or "").startswith("RUN_")
                else None
            ),
            last_diagnosis_id=(
                str(safe.get("last_diagnosis_id"))
                if str(safe.get("last_diagnosis_id") or "").startswith(("DR_", "CMP_"))
                else None
            ),
            last_draft_id=(
                str(safe.get("last_draft_id"))
                if str(safe.get("last_draft_id") or "").startswith("DRAFT_")
                else None
            ),
        )
        if current_rule_id:
            state.evidence.append({
                "source": "conversation_memory",
                "source_id": current_rule_id,
                "fact_types": ["rule_identity"],
            })
        return AgentMemorySession(
            store=self.store,
            storage_session_id=storage_session_id,
            context=structured,
            state=state,
        )
