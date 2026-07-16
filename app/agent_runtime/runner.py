"""模型驱动、工具观察式的最小 Agent 循环。"""

from __future__ import annotations

import asyncio
import json
import re

from app.agent_runtime.contracts import AgentRunResult, AgentRunState, AgentRuntimeContext
from app.agent_runtime.model_adapter import AgentModelAdapter, AgentModelError
from app.agent_runtime.prompts import (
    AGENT_SYSTEM_PROMPT,
    CHINESE_REQUIRED_PROMPT,
    EVIDENCE_REQUIRED_PROMPT,
)
from app.agent_runtime.response_guard import (
    evidence_correction_prompt,
    missing_fact_types,
)
from app.agent_tools.gateway import ToolGateway
from app.agent_tools.registry import ToolRegistry


def _contains_chinese(text: str) -> bool:
    return bool(re.search(r"[\u4e00-\u9fff]", text))


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
    ) -> None:
        self.adapter = adapter
        self.registry = registry
        self.gateway = gateway
        self.max_steps = max_steps
        self.max_tool_calls_per_step = max_tool_calls_per_step
        self.request_timeout_seconds = request_timeout_seconds

    async def run(
        self,
        user_message: str,
        context: AgentRuntimeContext,
        state: AgentRunState | None = None,
    ) -> AgentRunResult:
        run_state = state or AgentRunState()
        if run_state.cancelled:
            run_state.stop_reason = "cancelled"
            return AgentRunResult(
                answer="本次运行已取消。",
                stop_reason="cancelled",
                state=run_state,
            )
        try:
            return await asyncio.wait_for(
                self._run(user_message, context, run_state),
                timeout=self.request_timeout_seconds,
            )
        except TimeoutError:
            run_state.stop_reason = "request_timeout"
            return AgentRunResult(
                answer="本次请求处理超时，请稍后重试。",
                stop_reason="request_timeout",
                state=run_state,
            )

    async def _run(
        self,
        user_message: str,
        context: AgentRuntimeContext,
        run_state: AgentRunState,
    ) -> AgentRunResult:
        if not run_state.messages:
            run_state.messages.append({"role": "system", "content": AGENT_SYSTEM_PROMPT})
        run_state.messages.append({"role": "user", "content": user_message})
        model_name: str | None = None
        for _ in range(self.max_steps):
            if run_state.cancelled:
                run_state.stop_reason = "cancelled"
                return AgentRunResult(
                    answer="本次运行已取消。",
                    stop_reason="cancelled",
                    state=run_state,
                    model=model_name,
                )
            run_state.step_count += 1
            available = self.registry.list_for_context(context, run_state)
            try:
                response = await self.adapter.chat(
                    messages=run_state.messages,
                    tools=self.registry.to_ollama_schema(available),
                    temperature=0.0,
                )
            except AgentModelError:
                run_state.stop_reason = "tool_error"
                return AgentRunResult(
                    answer="模型服务暂时不可用，请稍后重试。",
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
                if not run_state.evidence:
                    run_state.messages.append({
                        "role": "system",
                        "content": EVIDENCE_REQUIRED_PROMPT,
                    })
                    continue
                if not _contains_chinese(response.content):
                    run_state.messages.append({
                        "role": "system",
                        "content": CHINESE_REQUIRED_PROMPT,
                    })
                    continue
                missing = missing_fact_types(response.content, run_state.evidence)
                if missing:
                    run_state.messages.append({
                        "role": "system",
                        "content": evidence_correction_prompt(missing),
                    })
                    continue
                run_state.stop_reason = "final_answer"
                return AgentRunResult(
                    answer=response.content,
                    stop_reason="final_answer",
                    state=run_state,
                    model=model_name,
                )
            if len(response.tool_calls) > self.max_tool_calls_per_step:
                run_state.stop_reason = "tool_error"
                return AgentRunResult(
                    answer="单步工具调用数量超过限制，本次运行已停止。",
                    stop_reason="tool_error",
                    state=run_state,
                    model=model_name,
                )
            for call in response.tool_calls:
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
                if result.status == "need_clarification":
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
        run_state.stop_reason = "max_steps"
        return AgentRunResult(
            answer="已达到最大处理步骤，请缩小问题范围后重试。",
            stop_reason="max_steps",
            state=run_state,
            model=model_name,
        )
