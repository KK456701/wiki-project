"""模型驱动、工具观察式的最小 Agent 循环。"""

from __future__ import annotations

import asyncio
import json
import logging
import re

from app.agent_runtime.contracts import AgentRunResult, AgentRunState, AgentRuntimeContext
from app.agent_runtime.events import AgentEventCallback, emit_agent_event
from app.agent_runtime.model_adapter import AgentModelAdapter, AgentModelError
from app.agent_runtime.prompts import (
    AGENT_SYSTEM_PROMPT,
    CHINESE_REQUIRED_PROMPT,
    EVIDENCE_REQUIRED_PROMPT,
    TRIAL_RUN_REQUIRED_PROMPT,
)
from app.agent_runtime.response_guard import (
    evidence_correction_prompt,
    missing_fact_types,
    normalize_agent_answer,
)
from app.agent_planning.runtime import AgentPlanningRuntime
from app.agent_planning.planner import AgentPlanningError
from app.agent_tools.gateway import ToolGateway
from app.agent_tools.registry import ToolRegistry


logger = logging.getLogger("wiki_agent.agent_runtime")


def _contains_chinese(text: str) -> bool:
    return bool(re.search(r"[\u4e00-\u9fff]", text))


_DIAGNOSIS_TERMS = (
    "异常",
    "诊断",
    "排查",
    "算不对",
    "算错",
    "不准确",
    "不准",
    "有问题",
    "问题在哪",
)

_TRIAL_RUN_TERMS = (
    "多少",
    "是多少",
    "统计",
    "统计时间",
    "统计周期",
    "试运行",
    "结果",
    "今年",
    "本月",
    "上月",
    "到现在",
    "从",
    "开始怎么算",
    "算一下",
)


def _classify_request_kind(user_message: str) -> str | None:
    compact = re.sub(r"\s+", "", user_message)
    if any(term in compact for term in _DIAGNOSIS_TERMS):
        return "diagnosis"
    if any(term in compact for term in _TRIAL_RUN_TERMS):
        return "trial_run"
    return None


def _has_fact_type(state: AgentRunState, fact_type: str) -> bool:
    return any(
        fact_type in (item.get("fact_types") or [])
        for item in state.evidence
        if isinstance(item, dict)
    )


def _asks_for_period_clarification(answer: str) -> bool:
    return bool(
        re.search(
            r"(?:请|需要|先|可以|能否).{0,20}"
            r"(?:提供|明确|选择|告诉|告知|指定|确认).{0,20}"
            r"(?:统计周期|统计时间|时间范围|起止时间|开始时间|结束时间"
            r"|时间段|起止日期|日期范围|哪个.{0,4}(?:时间|日期|段)"
            r"|查询.{0,4}(?:时间|日期|段))",
            answer,
        )
    )


class AgentRunner:
    def __init__(
        self,
        adapter: AgentModelAdapter,
        registry: ToolRegistry,
        gateway: ToolGateway,
        *,
        max_steps: int = 8,
        max_tool_calls_per_step: int = 3,
        request_timeout_seconds: float = 120.0,
        event_callback: AgentEventCallback | None = None,
        planning_runtime: AgentPlanningRuntime | None = None,
    ) -> None:
        self.adapter = adapter
        self.registry = registry
        self.gateway = gateway
        self.max_steps = max_steps
        self.max_tool_calls_per_step = max_tool_calls_per_step
        self.request_timeout_seconds = request_timeout_seconds
        self.event_callback = event_callback
        self.planning_runtime = planning_runtime

    async def run(
        self,
        user_message: str,
        context: AgentRuntimeContext,
        state: AgentRunState | None = None,
    ) -> AgentRunResult:
        run_state = state or AgentRunState()
        emit_agent_event(self.event_callback, "agent_start", step=0)
        if run_state.cancelled:
            run_state.stop_reason = "cancelled"
            result = AgentRunResult(
                answer="本次运行已取消。",
                stop_reason="cancelled",
                state=run_state,
            )
            self._emit_terminal(result)
            return result
        try:
            result = await asyncio.wait_for(
                self._run(user_message, context, run_state),
                timeout=self.request_timeout_seconds,
            )
        except TimeoutError:
            run_state.stop_reason = "request_timeout"
            result = AgentRunResult(
                answer="本次请求处理超时，请稍后重试。",
                stop_reason="request_timeout",
                state=run_state,
            )
        self._emit_terminal(result)
        return result

    def _emit_terminal(self, result: AgentRunResult) -> None:
        if result.answer:
            emit_agent_event(
                self.event_callback,
                "assistant_message",
                step=result.state.step_count,
                message=result.answer,
            )
        event = (
            "agent_error"
            if result.stop_reason
            in {"tool_error", "request_timeout", "context_conflict"}
            else "agent_done"
        )
        emit_agent_event(
            self.event_callback,
            event,
            stop_reason=result.stop_reason,
            step_count=result.state.step_count,
            model_name=result.model,
            answer=result.answer,
            fallback_category=result.state.fallback_category,
            failure_code=result.state.failure_code,
        )

    async def _run(
        self,
        user_message: str,
        context: AgentRuntimeContext,
        run_state: AgentRunState,
    ) -> AgentRunResult:
        planning_execution = None
        if self.planning_runtime is not None:
            try:
                planning_execution = await self.planning_runtime.prepare(
                    user_message, context, run_state
                )
            except AgentPlanningError:
                logger.warning("agent planner rejected model output", exc_info=True)
                run_state.stop_reason = "tool_error"
                return AgentRunResult(
                    answer="无法生成有效业务计划，请重新描述目标。",
                    stop_reason="tool_error",
                    state=run_state,
                )
            except Exception:
                logger.exception("agent planner failed")
                run_state.stop_reason = "tool_error"
                return AgentRunResult(
                    answer="业务计划生成失败，请稍后重试。",
                    stop_reason="tool_error",
                    state=run_state,
                )
        if not run_state.messages:
            run_state.messages.append({"role": "system", "content": AGENT_SYSTEM_PROMPT})
        run_state.current_request_kind = _classify_request_kind(user_message)
        run_state.messages.append({"role": "user", "content": user_message})
        model_name: str | None = None
        evidence_corrections = 0
        chinese_corrections = 0
        trial_run_corrections = 0
        fact_corrections = 0
        planned_tool_corrections = 0
        for _ in range(self.max_steps):
            replanned = False
            plan_corrected = False
            try:
                if run_state.cancelled:
                    run_state.stop_reason = "cancelled"
                    return AgentRunResult(
                        answer="本次运行已取消。",
                        stop_reason="cancelled",
                        state=run_state,
                        model=model_name,
                    )
                run_state.step_count += 1
                decision = None
                if planning_execution is not None:
                    decision = self.planning_runtime.next_decision(
                        planning_execution, run_state
                    )
                    if decision.action.value == "fallback":
                        clarification = (
                            decision.fallback_category is not None
                            and decision.fallback_category.value
                            in {"USER_CLARIFICATION", "BUSINESS_CONFIRMATION"}
                        )
                        stop_reason = "need_clarification" if clarification else "tool_error"
                        run_state.stop_reason = stop_reason
                        run_state.fallback_category = (
                            decision.fallback_category.value
                            if decision.fallback_category is not None
                            else None
                        )
                        run_state.failure_code = decision.code or None
                        if clarification:
                            emit_agent_event(
                                self.event_callback,
                                "clarification_required",
                                step=run_state.step_count,
                                message=decision.message,
                                fallback_category=run_state.fallback_category,
                                failure_code=run_state.failure_code,
                            )
                        return AgentRunResult(
                            answer=decision.message or "当前计划无法继续执行。",
                            stop_reason=stop_reason,
                            state=run_state,
                            model=model_name,
                        )
                    available = self.registry.list_for_names(
                        decision.tool_names, context, run_state
                    )
                    run_state.messages.append({
                        "role": "system",
                        "content": self.planning_runtime.instruction(
                            planning_execution, decision, run_state
                        ),
                    })
                else:
                    available = self.registry.list_for_context(context, run_state)
                emit_agent_event(
                    self.event_callback,
                    "model_start",
                    step=run_state.step_count,
                    model_name=model_name,
                    tool_count=len(available),
                )
                try:
                    response = await self.adapter.chat(
                        messages=run_state.messages,
                        tools=self.registry.to_ollama_schema(available),
                        temperature=0.0,
                    )
                except AgentModelError as exc:
                    run_state.stop_reason = "tool_error"
                    return AgentRunResult(
                        answer=str(exc) or "模型服务暂时不可用，请稍后重试。",
                        stop_reason="tool_error",
                        state=run_state,
                    )
                except Exception:
                    run_state.stop_reason = "tool_error"
                    return AgentRunResult(
                        answer="模型调用异常，请稍后重试。",
                        stop_reason="tool_error",
                        state=run_state,
                    )
                model_name = response.model or model_name
                assistant_message = {
                    "role": "assistant",
                    "content": response.content,
                    "tool_calls": [call.model_dump(mode="json") for call in response.tool_calls],
                }
                run_state.messages.append(assistant_message)
                if not response.tool_calls:
                    answer = normalize_agent_answer(response.content)
                    assistant_message["content"] = answer
                    if (
                        planning_execution is not None
                        and decision is not None
                        and decision.action.value == "execute_tool"
                    ):
                        if (
                            planned_tool_corrections < 1
                            and run_state.step_count < self.max_steps
                        ):
                            planned_tool_corrections += 1
                            run_state.messages.append({
                                "role": "system",
                                "content": (
                                    "当前回答过早，计划要求先调用："
                                    + "、".join(decision.tool_names)
                                    + "。请完成该受控步骤后再回答。"
                                ),
                            })
                            continue
                        run_state.stop_reason = "tool_error"
                        return AgentRunResult(
                            answer="当前计划需要先完成受控工具步骤，模型未按计划执行。",
                            stop_reason="tool_error",
                            state=run_state,
                            model=model_name,
                        )
                    requires_evidence = (
                        bool(planning_execution.compiled_plan.required_facts)
                        if planning_execution is not None
                        else True
                    )
                    if requires_evidence and not run_state.evidence:
                        evidence_corrections += 1
                        if evidence_corrections <= 1:
                            run_state.messages.append({
                                "role": "system",
                                "content": EVIDENCE_REQUIRED_PROMPT,
                            })
                            continue
                    if not _contains_chinese(answer):
                        chinese_corrections += 1
                        if chinese_corrections <= 1:
                            run_state.messages.append({
                                "role": "system",
                                "content": CHINESE_REQUIRED_PROMPT,
                            })
                            continue
                    if (
                        run_state.current_request_kind == "trial_run"
                        and not _has_fact_type(run_state, "trial_run")
                        and not _asks_for_period_clarification(answer)
                    ):
                        trial_run_corrections += 1
                        if trial_run_corrections <= 1:
                            run_state.messages.append({
                                "role": "system",
                                "content": TRIAL_RUN_REQUIRED_PROMPT,
                            })
                            continue
                    missing = missing_fact_types(answer, run_state.evidence)
                    if missing:
                        fact_corrections += 1
                        if fact_corrections <= 1:
                            run_state.messages.append({
                                "role": "system",
                                "content": evidence_correction_prompt(missing),
                            })
                            continue
                    if planning_execution is not None:
                        verification = self.planning_runtime.verify(
                            planning_execution, run_state, context
                        )
                        if not verification.ok:
                            run_state.stop_reason = "tool_error"
                            return AgentRunResult(
                                answer=verification.message,
                                stop_reason="tool_error",
                                state=run_state,
                                model=model_name,
                            )
                    run_state.stop_reason = "final_answer"
                    return AgentRunResult(
                        answer=answer,
                        stop_reason="final_answer",
                        state=run_state,
                        model=model_name,
                    )
            except Exception:
                run_state.stop_reason = "tool_error"
                return AgentRunResult(
                    answer="处理请求时发生内部错误，请稍后重试。",
                    stop_reason="tool_error",
                    state=run_state,
                    model=model_name,
                )
            call_limit = 1 if planning_execution is not None else self.max_tool_calls_per_step
            if len(response.tool_calls) > call_limit:
                run_state.stop_reason = "tool_error"
                return AgentRunResult(
                    answer="单步工具调用数量超过限制，本次运行已停止。",
                    stop_reason="tool_error",
                    state=run_state,
                    model=model_name,
                )
            for call in response.tool_calls:
                try:
                    if (
                        planning_execution is not None
                        and decision is not None
                        and call.name not in decision.tool_names
                    ):
                        if (
                            planned_tool_corrections < 1
                            and run_state.step_count < self.max_steps
                        ):
                            planned_tool_corrections += 1
                            correction = (
                                "当前计划未允许该工具。请只调用本步展示的工具："
                                + "、".join(decision.tool_names)
                                + "。"
                            )
                            run_state.messages.append({
                                "role": "tool",
                                "tool_name": call.name,
                                "content": json.dumps({
                                    "ok": False,
                                    "status": "unavailable",
                                    "code": "TOOL_OUTSIDE_PLAN",
                                    "summary": correction,
                                }, ensure_ascii=False),
                            })
                            run_state.messages.append({
                                "role": "system",
                                "content": correction,
                            })
                            plan_corrected = True
                            break
                        run_state.stop_reason = "tool_error"
                        return AgentRunResult(
                            answer="模型调用了当前计划未允许的工具，本次运行已停止。",
                            stop_reason="tool_error",
                            state=run_state,
                            model=model_name,
                        )
                    if run_state.cancelled:
                        run_state.stop_reason = "cancelled"
                        return AgentRunResult(
                            answer="本次运行已取消。",
                            stop_reason="cancelled",
                            state=run_state,
                            model=model_name,
                        )
                    result = await self.gateway.execute(
                        call.name, call.arguments, context, run_state
                    )
                    dumped = result.model_dump(mode="json")
                    run_state.last_tool_results.append(dumped)
                    if result.ok:
                        run_state.evidence.extend(
                            evidence.model_dump(mode="json") for evidence in result.evidence
                        )
                    run_state.messages.append({
                        "role": "tool",
                        "tool_name": call.name,
                        "content": json.dumps(dumped, ensure_ascii=False),
                    })
                    if planning_execution is not None and not result.ok:
                        replacement = await self.planning_runtime.try_replan(
                            planning_execution,
                            query=user_message,
                            context=context,
                            state=run_state,
                            failure_code=result.code,
                            failure_reason=result.summary,
                        )
                        if replacement is not None:
                            planning_execution = replacement
                            replanned = True
                            break
                    if result.status == "need_clarification":
                        emit_agent_event(
                            self.event_callback,
                            "clarification_required",
                            step=run_state.step_count,
                            message=result.summary,
                        )
                        run_state.stop_reason = "need_clarification"
                        return AgentRunResult(
                            answer=result.summary,
                            stop_reason="need_clarification",
                            state=run_state,
                            model=model_name,
                        )
                    if run_state.stop_reason == "context_conflict":
                        return AgentRunResult(
                            answer=result.summary,
                            stop_reason="context_conflict",
                            state=run_state,
                            model=model_name,
                        )
                    if (
                        result.code == "TOOL_NOT_FOUND"
                        or result.status
                        in {"forbidden", "unavailable", "cancelled", "error"}
                    ) and not result.retryable:
                        stop_reason = (
                            "cancelled" if result.status == "cancelled" else "tool_error"
                        )
                        run_state.stop_reason = stop_reason
                        return AgentRunResult(
                            answer=result.summary,
                            stop_reason=stop_reason,
                            state=run_state,
                            model=model_name,
                        )
                    if run_state.stop_reason == "repeated_tool_call":
                        return AgentRunResult(
                            answer="检测到重复工具调用，本次运行已停止。",
                            stop_reason="repeated_tool_call",
                            state=run_state,
                            model=model_name,
                        )
                except Exception:
                    run_state.stop_reason = "tool_error"
                    return AgentRunResult(
                        answer="工具执行异常，请稍后重试。",
                        stop_reason="tool_error",
                        state=run_state,
                        model=model_name,
                    )
            if replanned:
                continue
            if plan_corrected:
                continue
        run_state.stop_reason = "max_steps"
        return AgentRunResult(
            answer="已达到最大处理步骤，请缩小问题范围后重试。",
            stop_reason="max_steps",
            state=run_state,
            model=model_name,
        )
