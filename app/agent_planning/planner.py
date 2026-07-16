from __future__ import annotations

import json
import re
import time
from datetime import datetime
from typing import Any, Protocol

from pydantic import ValidationError

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_runtime.model_adapter import AgentModelAdapter, AgentModelError
from app.prompts import format_prompt, load_prompt, prompt_version

from .contracts import RequestPlan


class AgentPlanningError(RuntimeError):
    pass


class RequestPlanner(Protocol):
    async def plan(
        self,
        *,
        query: str,
        context: AgentRuntimeContext,
        state: AgentRunState | None,
        now: datetime,
    ) -> RequestPlan: ...


_PLANNER_PROMPT = load_prompt("agent_planner").strip()


_FOLLOWUP_SELECTION_SUFFIX = re.compile(
    r"[”\"’']?(?:就)?(?:这个|后者|第二个)\s*[。！？]?$"
)


def _normalize_followup_query(query: str) -> str:
    """Resolve an explicit final-option selection without asking a small model to guess."""
    text = str(query or "").strip()
    if not _FOLLOWUP_SELECTION_SUFFIX.search(text):
        return text
    options = re.split(r"(?:或者|或)", text)
    if len(options) < 2:
        return text
    candidate = _FOLLOWUP_SELECTION_SUFFIX.sub("", options[-1]).strip()
    candidate = candidate.strip(" \t\r\n“”\"'‘’")
    if not re.search(
        r"(?:(?:\d{2}|\d{4})年)?(?:1[0-2]|[1-9])月|本月|上月|今年|至今|到现在",
        candidate,
    ):
        return text
    return f"{candidate}的结果"


def _json_object(content: str) -> dict:
    text = str(content or "").strip()
    fenced = re.fullmatch(r"```(?:json)?\s*(.*?)\s*```", text, re.DOTALL | re.IGNORECASE)
    if fenced:
        text = fenced.group(1)
    try:
        value = json.loads(text)
    except json.JSONDecodeError as exc:
        raise AgentPlanningError("Planner 返回的计划不是有效 JSON。") from exc
    if not isinstance(value, dict):
        raise AgentPlanningError("Planner 必须返回 JSON 对象。")
    return value


def _normalize_container_shapes(value: dict) -> dict:
    """Repair only common scalar/container mistakes without dropping fields."""
    normalized = dict(value)
    if normalized.get("intent") == "general_chat" and not str(
        normalized.get("goal") or ""
    ).strip():
        normalized["goal"] = "回应普通问候或帮助请求"
    target = normalized.get("target_indicator")
    if target is None:
        normalized["target_indicator"] = {}
    elif isinstance(target, str):
        normalized["target_indicator"] = {"raw_name": target}
    time_expression = normalized.get("time_expression")
    if time_expression is None:
        normalized["time_expression"] = {}
    elif isinstance(time_expression, str):
        normalized["time_expression"] = {
            "raw_text": "" if time_expression == "raw_text" else time_expression
        }
    requested = normalized.get("requested_outputs")
    if isinstance(requested, str):
        normalized["requested_outputs"] = [requested]
    constraints = normalized.get("constraints")
    if isinstance(constraints, str):
        normalized["constraints"] = [constraints] if constraints else []
    ambiguities = normalized.get("semantic_ambiguities")
    if ambiguities == "":
        normalized["semantic_ambiguities"] = []
    elif isinstance(ambiguities, list):
        normalized["semantic_ambiguities"] = [
            {"field": "unspecified", "description": item.strip()}
            if isinstance(item, str) and item.strip()
            else item
            for item in ambiguities
            if not isinstance(item, str) or item.strip()
        ]
    return normalized


class ModelRequestPlanner:
    def __init__(self, adapter: AgentModelAdapter, trace_callback=None) -> None:
        self.adapter = adapter
        self.trace_callback = trace_callback

    def _trace(self, **payload: Any) -> None:
        if self.trace_callback is None:
            return
        try:
            self.trace_callback({"event": "trace_node", **payload})
        except Exception:
            return

    async def plan(
        self,
        *,
        query: str,
        context: AgentRuntimeContext,
        state: AgentRunState | None,
        now: datetime,
    ) -> RequestPlan:
        del context
        state_context = "当前没有已确认指标或统计周期。"
        history_context = ""
        if state is not None:
            values = []
            if state.current_rule_id:
                values.append(f"当前已确认 rule_id：{state.current_rule_id}")
            if state.current_stat_start and state.current_stat_end:
                values.append(
                    "当前统计周期："
                    f"{state.current_stat_start} 至 {state.current_stat_end}"
                )
            if values:
                state_context = "；".join(values) + "。追问中的这个指标或直接给结果优先复用该状态。"
            if state.recent_history:
                history_context = (
                    "\n最近对话只用于理解本轮指代，不得覆盖上述结构化状态：\n"
                    f"{state.recent_history}"
                )
        normalized_query = _normalize_followup_query(query)
        messages = [
            {
                "role": "system",
                "content": format_prompt(
                    "agent_planner_context",
                    planner_prompt=_PLANNER_PROMPT,
                    current_date=now.date().isoformat(),
                    state_context=state_context,
                    history_context=history_context,
                ),
            },
            {"role": "user", "content": normalized_query},
        ]
        last_error = ""
        for attempt in range(2):
            started = time.perf_counter()
            raw_content = ""
            try:
                response = await self.adapter.chat(
                    messages=messages,
                    tools=[],
                    temperature=0.0,
                )
                raw_content = response.content
                normalized = _normalize_container_shapes(_json_object(raw_content))
                plan = RequestPlan.model_validate(normalized)
                self._trace(
                    node_name="planner_llm",
                    node_type="llm",
                    status="success",
                    duration_ms=max(1, int((time.perf_counter() - started) * 1000)),
                    input_data={"messages": messages, "tools": [], "temperature": 0.0},
                    output_data={
                        "raw_content": raw_content,
                        "normalized_plan": plan.model_dump(mode="json"),
                        "attempt": attempt + 1,
                    },
                    processing_data={
                        "description": "调用 Planner 模型，解析 JSON，并校验 RequestPlan 合约。"
                    },
                    config_data={
                        "prompt_file": "agent_planner.txt",
                        "prompt_version": prompt_version("agent_planner"),
                        "context_prompt_file": "agent_planner_context.txt",
                        "context_prompt_version": prompt_version("agent_planner_context"),
                        "repair_prompt_file": "agent_planner_repair.txt",
                    },
                )
                return plan
            except (AgentPlanningError, ValidationError, AgentModelError) as exc:
                last_error = str(exc)
                self._trace(
                    node_name="planner_llm",
                    node_type="llm",
                    status="failed" if attempt == 1 else "warning",
                    duration_ms=max(1, int((time.perf_counter() - started) * 1000)),
                    input_data={"messages": messages, "tools": [], "temperature": 0.0},
                    output_data={
                        "raw_content": raw_content,
                        "validation_error": last_error,
                        "attempt": attempt + 1,
                    },
                    processing_data={
                        "description": "调用 Planner 模型并校验计划；失败时最多追加一次修复提示。"
                    },
                    config_data={
                        "prompt_file": "agent_planner.txt",
                        "prompt_version": prompt_version("agent_planner"),
                        "context_prompt_file": "agent_planner_context.txt",
                        "context_prompt_version": prompt_version("agent_planner_context"),
                        "repair_prompt_file": "agent_planner_repair.txt",
                        "repair_prompt_version": prompt_version("agent_planner_repair"),
                    },
                    error_message=last_error,
                )
                if attempt == 0:
                    messages.append({
                        "role": "system",
                        "content": format_prompt(
                            "agent_planner_repair",
                            validation_error=last_error,
                        ),
                    })
        raise AgentPlanningError(
            "无法生成有效业务计划，请重新描述目标。"
            + (f"（{last_error}）" if last_error else "")
        )

    async def replan(
        self,
        *,
        query: str,
        context: AgentRuntimeContext,
        state: AgentRunState,
        now: datetime,
        original_plan: RequestPlan,
        failure_code: str,
        failure_reason: str,
    ) -> RequestPlan:
        failure_context = json.dumps(
            {
                "original_plan": original_plan.model_dump(mode="json"),
                "failure_code": failure_code,
                "failure_reason": failure_reason,
                "verified_rule_id": state.current_rule_id,
                "failed_plan_fingerprints": state.failed_plan_fingerprints,
                "remaining_replan_count": max(0, 1 - state.replan_count),
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        return await self.plan(
            query=format_prompt(
                "agent_replanner",
                query=query,
                failure_context=failure_context,
            ),
            context=context,
            state=state,
            now=now,
        )
