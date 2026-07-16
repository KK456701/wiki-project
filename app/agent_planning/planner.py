from __future__ import annotations

import json
import re
from datetime import datetime
from typing import Protocol

from pydantic import ValidationError

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_runtime.model_adapter import AgentModelAdapter, AgentModelError

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


_PLANNER_PROMPT = """你是医院核心制度指标任务 Planner。只理解用户业务目标，不负责选择工具或生成执行步骤。
仅返回一个 JSON 对象，不要 Markdown。字段必须严格为：
intent、goal、target_indicator、time_expression、requested_outputs、constraints、semantic_ambiguities。
禁止输出 steps、proposed_steps、tool 或任何工具名称。
intent 只能是 general_chat、rule_explanation、indicator_trial_run、indicator_diagnosis、rule_change_preview、upload_analysis、unknown。
requested_outputs 只能使用 definition、formula、implementation_status、prepared_sql_handle、trial_result、diagnosis、change_preview、file_analysis、explanation。
target_indicator 包含 raw_name 和可选 rule_id。time_expression 保留 raw_text；只有用户明确给出绝对日期时才填写 start_time/end_time。
semantic_ambiguities 中每一项必须是 {"field":"字段名","description":"歧义说明"} 对象，不得直接输出字符串。
用户索要某时间段实际数值时使用 indicator_trial_run；普通公式解释使用 rule_explanation；明确排查异常时使用 indicator_diagnosis。
不要把 SQL 文本作为输出，受控 SQL 只能表示为 prepared_sql_handle。"""


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
    target = normalized.get("target_indicator")
    if isinstance(target, str):
        normalized["target_indicator"] = {"raw_name": target}
    time_expression = normalized.get("time_expression")
    if isinstance(time_expression, str):
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
    def __init__(self, adapter: AgentModelAdapter) -> None:
        self.adapter = adapter

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
                "content": (
                    f"{_PLANNER_PROMPT}\n当前日期：{now.date().isoformat()}。\n"
                    f"{state_context}{history_context}"
                ),
            },
            {"role": "user", "content": normalized_query},
        ]
        last_error = ""
        for attempt in range(2):
            try:
                response = await self.adapter.chat(
                    messages=messages,
                    tools=[],
                    temperature=0.0,
                )
                return RequestPlan.model_validate(
                    _normalize_container_shapes(_json_object(response.content))
                )
            except (AgentPlanningError, ValidationError, AgentModelError) as exc:
                last_error = str(exc)
                if attempt == 0:
                    messages.append({
                        "role": "system",
                        "content": (
                            "上一个计划不符合严格 JSON 合约。请重新输出完整 JSON；"
                            "不得包含步骤、工具名或额外字段；semantic_ambiguities "
                            "中的每一项必须是包含 field 和 description 的对象。"
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
            query=(
                f"{query}\n上一个业务计划失败，请根据失败上下文重新理解业务目标。"
                f"不得重复失败方向。失败上下文：{failure_context}"
            ),
            context=context,
            state=state,
            now=now,
        )
