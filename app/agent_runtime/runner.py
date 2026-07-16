"""模型驱动、工具观察式的最小 Agent 循环。"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time

from app.agent_runtime.contracts import AgentRunResult, AgentRunState, AgentRuntimeContext
from app.agent_runtime.events import AgentEventCallback, emit_agent_event
from app.agent_runtime.model_adapter import AgentModelAdapter, AgentModelError
from app.agent_runtime.prompts import (
    AGENT_SYSTEM_PROMPT,
    CHINESE_REQUIRED_PROMPT,
    EVIDENCE_REQUIRED_PROMPT,
    TRIAL_RUN_REQUIRED_PROMPT,
    executor_correction,
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
from app.prompts import prompt_version


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


def _compose_prepared_sql_answer(planning_execution, state: AgentRunState) -> str | None:
    outputs = {
        item.value if hasattr(item, "value") else str(item)
        for item in planning_execution.compiled_plan.requested_outputs
    }
    if "prepared_sql_handle" not in outputs or "trial_result" in outputs:
        return None
    prepared = next(
        (
            item
            for item in reversed(state.last_tool_results)
            if isinstance(item, dict)
            and item.get("ok") is True
            and item.get("code") == "SQL_OBJECT_PREPARED"
        ),
        None,
    )
    if prepared is None:
        return None
    data = prepared.get("data") or {}
    sql_preview = str(data.get("sql_preview") or "").strip()
    if not sql_preview:
        return None
    parameters = data.get("parameters") or {}
    parameter_lines = "\n".join(
        f"- `{key}`：`{value}`" for key, value in parameters.items()
    )
    sql_id = str(data.get("sql_id") or "").strip()
    sql_id_line = f"\n- SQL 对象：`{sql_id}`" if sql_id else ""
    target = planning_execution.request_plan.target_indicator.raw_name.strip()
    title = f"下面是“{target}”的已校验 SQL：" if target else "下面是已校验 SQL："
    return (
        f"{title}\n\n```sql\n{sql_preview}\n```\n\n"
        f"统计参数：\n{parameter_lines or '- 无额外参数'}"
        f"{sql_id_line}\n\n该请求只生成并校验 SQL，不会执行数据库。"
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
        trace_callback: AgentEventCallback | None = None,
        planning_runtime: AgentPlanningRuntime | None = None,
    ) -> None:
        self.adapter = adapter
        self.registry = registry
        self.gateway = gateway
        self.max_steps = max_steps
        self.max_tool_calls_per_step = max_tool_calls_per_step
        self.request_timeout_seconds = request_timeout_seconds
        self.event_callback = event_callback
        self.trace_callback = trace_callback
        self.planning_runtime = planning_runtime

    def _trace(self, **payload) -> None:
        if self.trace_callback is None:
            return
        try:
            self.trace_callback({"event": "trace_node", **payload})
        except Exception:
            return

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
                    controller_started = time.perf_counter()
                    decision = self.planning_runtime.next_decision(
                        planning_execution, run_state
                    )
                    decision_is_clarification = (
                        decision.action.value == "fallback"
                        and decision.fallback_category is not None
                        and decision.fallback_category.value
                        in {"USER_CLARIFICATION", "BUSINESS_CONFIRMATION"}
                    )
                    self._trace(
                        node_name="state_controller",
                        node_type="code",
                        status=(
                            "warning"
                            if decision_is_clarification
                            else "failed"
                            if decision.action.value == "fallback"
                            else "success"
                        ),
                        duration_ms=max(1, int((time.perf_counter() - controller_started) * 1000)),
                        input_data={
                            "compiled_plan": planning_execution.compiled_plan.model_dump(mode="json"),
                            "validation": planning_execution.validation.model_dump(mode="json"),
                            "state": run_state.model_dump(mode="json"),
                        },
                        output_data={"decision": decision.model_dump(mode="json")},
                        processing_data={
                            "description": "根据已完成事实选择下一业务能力，并只开放当前允许的工具。"
                        },
                        config_data={
                            "controller": type(self.planning_runtime.controller).__name__,
                            "prompt_file": "agent_executor_step.txt",
                            "prompt_version": prompt_version("agent_executor_step"),
                        },
                    )
                    if decision.action.value == "fallback":
                        clarification = decision_is_clarification
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
                    deterministic_answer = _compose_prepared_sql_answer(
                        planning_execution, run_state
                    )
                    if (
                        decision.action.value == "compose_answer"
                        and deterministic_answer is not None
                    ):
                        verify_started = time.perf_counter()
                        verification = self.planning_runtime.verify(
                            planning_execution, run_state, context
                        )
                        self._trace(
                            node_name="plan_verify",
                            node_type="code",
                            status="success" if verification.ok else "failed",
                            duration_ms=max(
                                1,
                                int((time.perf_counter() - verify_started) * 1000),
                            ),
                            input_data={
                                "compiled_plan": planning_execution.compiled_plan.model_dump(mode="json"),
                                "state": run_state.model_dump(mode="json"),
                                "context": context.model_dump(mode="json"),
                            },
                            output_data={
                                "verification": verification.model_dump(mode="json")
                            },
                            processing_data={
                                "description": "校验规则、医院、统计时间和 SQL 证据链一致性。"
                            },
                            config_data={
                                "verifier": type(self.planning_runtime.verifier).__name__
                            },
                        )
                        if not verification.ok:
                            run_state.stop_reason = "tool_error"
                            return AgentRunResult(
                                answer=verification.message,
                                stop_reason="tool_error",
                                state=run_state,
                                model=model_name,
                            )
                        self._trace(
                            node_name="response_guard",
                            node_type="code",
                            status="success",
                            duration_ms=1,
                            input_data={
                                "prepared_sql_result": next(
                                    item
                                    for item in reversed(run_state.last_tool_results)
                                    if isinstance(item, dict)
                                    and item.get("code") == "SQL_OBJECT_PREPARED"
                                )
                            },
                            output_data={"answer": deterministic_answer},
                            processing_data={
                                "description": "使用已校验 SQL 证据确定性生成回答，不再交由模型补充工具调用。"
                            },
                            config_data={"guard": "deterministic_sql_response"},
                        )
                        run_state.stop_reason = "final_answer"
                        return AgentRunResult(
                            answer=deterministic_answer,
                            stop_reason="final_answer",
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
                tool_schemas = self.registry.to_ollama_schema(available)
                model_input = {
                    "messages": list(run_state.messages),
                    "tools": tool_schemas,
                    "temperature": 0.0,
                }
                model_started = time.perf_counter()
                try:
                    response = await self.adapter.chat(
                        messages=run_state.messages,
                        tools=tool_schemas,
                        temperature=0.0,
                    )
                except AgentModelError as exc:
                    self._trace(
                        node_name="executor_llm",
                        node_type="llm",
                        status="failed",
                        duration_ms=max(1, int((time.perf_counter() - model_started) * 1000)),
                        input_data=model_input,
                        output_data={"error": str(exc)},
                        processing_data={"description": "根据当前计划和可见工具生成下一动作或最终回答。"},
                        config_data={"adapter": type(self.adapter).__name__, "step": run_state.step_count},
                        error_message=str(exc),
                    )
                    run_state.stop_reason = "tool_error"
                    return AgentRunResult(
                        answer=str(exc) or "模型服务暂时不可用，请稍后重试。",
                        stop_reason="tool_error",
                        state=run_state,
                    )
                except Exception:
                    self._trace(
                        node_name="executor_llm",
                        node_type="llm",
                        status="failed",
                        duration_ms=max(1, int((time.perf_counter() - model_started) * 1000)),
                        input_data=model_input,
                        output_data={"error": "模型调用异常"},
                        processing_data={"description": "根据当前计划和可见工具生成下一动作或最终回答。"},
                        config_data={"adapter": type(self.adapter).__name__, "step": run_state.step_count},
                        error_message="模型调用异常",
                    )
                    run_state.stop_reason = "tool_error"
                    return AgentRunResult(
                        answer="模型调用异常，请稍后重试。",
                        stop_reason="tool_error",
                        state=run_state,
                    )
                model_name = response.model or model_name
                self._trace(
                    node_name="executor_llm",
                    node_type="llm",
                    status="success",
                    duration_ms=max(1, int((time.perf_counter() - model_started) * 1000)),
                    input_data=model_input,
                    output_data={
                        "model": response.model,
                        "content": response.content,
                        "tool_calls": [call.model_dump(mode="json") for call in response.tool_calls],
                    },
                    processing_data={"description": "根据当前计划和可见工具生成下一动作或最终回答。"},
                    config_data={
                        "adapter": type(self.adapter).__name__,
                        "step": run_state.step_count,
                        "prompt_file": "agent_executor.txt",
                        "prompt_version": prompt_version("agent_executor"),
                    },
                )
                assistant_message = {
                    "role": "assistant",
                    "content": response.content,
                    "tool_calls": [call.model_dump(mode="json") for call in response.tool_calls],
                }
                run_state.messages.append(assistant_message)
                if not response.tool_calls:
                    guard_started = time.perf_counter()
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
                                    executor_correction(
                                        "premature_answer",
                                        tool_names="、".join(decision.tool_names),
                                    )
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
                        verify_started = time.perf_counter()
                        verification = self.planning_runtime.verify(
                            planning_execution, run_state, context
                        )
                        self._trace(
                            node_name="plan_verify",
                            node_type="code",
                            status="success" if verification.ok else "failed",
                            duration_ms=max(1, int((time.perf_counter() - verify_started) * 1000)),
                            input_data={
                                "compiled_plan": planning_execution.compiled_plan.model_dump(mode="json"),
                                "state": run_state.model_dump(mode="json"),
                                "context": context.model_dump(mode="json"),
                            },
                            output_data={"verification": verification.model_dump(mode="json")},
                            processing_data={
                                "description": "校验规则、医院、统计时间、SQL 对象和数值证据链一致性。"
                            },
                            config_data={"verifier": type(self.planning_runtime.verifier).__name__},
                        )
                        if not verification.ok:
                            run_state.stop_reason = "tool_error"
                            return AgentRunResult(
                                answer=verification.message,
                                stop_reason="tool_error",
                                state=run_state,
                                model=model_name,
                            )
                    self._trace(
                        node_name="response_guard",
                        node_type="code",
                        status="success",
                        duration_ms=max(1, int((time.perf_counter() - guard_started) * 1000)),
                        input_data={
                            "raw_content": response.content,
                            "evidence": run_state.evidence,
                        },
                        output_data={"answer": answer, "missing_fact_types": []},
                        processing_data={
                            "description": "规范 Markdown 和公式格式，并阻止缺少工具证据的完成性声明。"
                        },
                        config_data={"guard": "deterministic_response_guard"},
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
                            correction = executor_correction(
                                "tool_outside_plan",
                                tool_names="、".join(decision.tool_names),
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
