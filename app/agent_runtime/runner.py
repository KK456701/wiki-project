"""模型驱动、工具观察式的最小 Agent 循环。"""

from __future__ import annotations

import json

from app.agent_runtime.contracts import AgentRunResult, AgentRunState, AgentRuntimeContext
from app.agent_runtime.model_adapter import AgentModelAdapter, AgentModelError
from app.agent_runtime.prompts import AGENT_SYSTEM_PROMPT
from app.agent_tools.gateway import ToolGateway
from app.agent_tools.registry import ToolRegistry


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
        if not run_state.messages:
            run_state.messages.append({"role": "system", "content": AGENT_SYSTEM_PROMPT})
        run_state.messages.append({"role": "user", "content": user_message})
        model_name: str | None = None
        for _ in range(self.max_steps):
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
                run_state.stop_reason = "final_answer"
                return AgentRunResult(
                    answer=response.content,
                    stop_reason="final_answer",
                    state=run_state,
                    model=model_name,
                )
            for call in response.tool_calls:
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
