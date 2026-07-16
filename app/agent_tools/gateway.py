"""模型工具调用的唯一执行边界。"""

from __future__ import annotations

import asyncio
import inspect
import time
from collections.abc import Callable
from typing import Any

from pydantic import ValidationError

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import ToolResult
from app.agent_tools.policy import (
    RepeatDecision,
    ToolExecutionPolicy,
    redact_payload,
    tool_call_fingerprint,
)
from app.agent_tools.registry import ToolRegistry, ToolRegistryError


TraceCallback = Callable[[dict[str, Any]], None]


class ToolGateway:
    def __init__(
        self,
        registry: ToolRegistry,
        *,
        policy: ToolExecutionPolicy | None = None,
        trace_callback: TraceCallback | None = None,
    ) -> None:
        self.registry = registry
        self.policy = policy or ToolExecutionPolicy()
        self.trace_callback = trace_callback

    async def execute(
        self,
        tool_name: str,
        raw_arguments: dict[str, Any],
        context: AgentRuntimeContext,
        state: AgentRunState,
    ) -> ToolResult:
        try:
            tool = self.registry.get(tool_name)
        except ToolRegistryError:
            return ToolResult(
                ok=False,
                status="not_found",
                code="TOOL_NOT_FOUND",
                summary=f"工具不可用：{tool_name}",
            )

        if not tool.required_permissions.issubset(context.permissions):
            return ToolResult(
                ok=False,
                status="forbidden",
                code="PERMISSION_DENIED",
                summary="当前用户没有执行该工具所需的权限。",
            )

        if tool.availability is not None:
            try:
                available = bool(tool.availability(context, state))
            except Exception:
                available = False
            if not available:
                return ToolResult(
                    ok=False,
                    status="unavailable",
                    code="TOOL_UNAVAILABLE",
                    summary="当前运行状态不允许执行该工具。",
                )

        try:
            arguments = tool.input_model.model_validate(raw_arguments)
        except ValidationError as exc:
            return ToolResult(
                ok=False,
                status="validation_failed",
                code="INVALID_TOOL_ARGUMENTS",
                summary="工具参数不符合约束。",
                data={"errors": exc.errors(include_url=False, include_input=False)},
            )

        fingerprint = tool_call_fingerprint(tool_name, raw_arguments)
        decision = self.policy.note_call(state, tool_name, raw_arguments)
        if decision is RepeatDecision.DUPLICATE:
            cached = state.tool_result_cache.get(fingerprint)
            if cached is not None:
                result = ToolResult.model_validate(cached)
                self._emit({
                    "event": "tool_result",
                    "tool_name": tool.name,
                    "duration_ms": 0,
                    "reused": True,
                    "result": redact_payload(result.model_dump(mode="json")),
                })
                return result
            result = ToolResult(
                ok=False,
                status="validation_failed",
                code="AGENT_REPEATED_TOOL_CALL",
                summary="该工具已使用相同参数调用过，请根据已有结果选择下一步。",
                retryable=True,
            )
            self._emit({
                "event": "tool_result",
                "tool_name": tool.name,
                "duration_ms": 0,
                "reused": False,
                "result": redact_payload(result.model_dump(mode="json")),
            })
            return result
        if decision is RepeatDecision.STOP:
            result = ToolResult(
                ok=False,
                status="validation_failed",
                code="AGENT_REPEATED_TOOL_CALL",
                summary="工具被重复调用，已停止本次 Agent 循环。",
                retryable=False,
            )
            self._emit({
                "event": "tool_result",
                "tool_name": tool.name,
                "duration_ms": 0,
                "reused": False,
                "result": redact_payload(result.model_dump(mode="json")),
            })
            return result

        self._emit(
            {
                "event": "tool_call",
                "tool_name": tool.name,
                "arguments": redact_payload(raw_arguments),
                "risk_level": tool.risk_level.value,
            }
        )
        started_at = time.perf_counter()
        try:
            value = await asyncio.wait_for(
                self._invoke(tool.handler, arguments, context, state),
                timeout=tool.timeout_seconds,
            )
            result = value if isinstance(value, ToolResult) else ToolResult.model_validate(value)
        except TimeoutError:
            result = ToolResult(
                ok=False,
                status="timeout",
                code="TOOL_TIMEOUT",
                summary="工具执行超时，未获得可用结果。",
                retryable=True,
            )
        except Exception:
            result = ToolResult(
                ok=False,
                status="error",
                code="TOOL_EXECUTION_FAILED",
                summary="工具执行失败，内部错误已记录。",
                retryable=False,
            )

        self._emit(
            {
                "event": "tool_result",
                "tool_name": tool.name,
                "duration_ms": max(
                    0,
                    int((time.perf_counter() - started_at) * 1000),
                ),
                "reused": False,
                "result": redact_payload(result.model_dump(mode="json")),
            }
        )
        state.tool_result_cache[fingerprint] = result.model_dump(mode="json")
        return result

    @staticmethod
    async def _invoke(handler, arguments, context, state):
        if inspect.iscoroutinefunction(handler):
            return await handler(arguments, context, state)
        value = await asyncio.to_thread(handler, arguments, context, state)
        if inspect.isawaitable(value):
            return await value
        return value

    def _emit(self, event: dict[str, Any]) -> None:
        if self.trace_callback is None:
            return
        try:
            self.trace_callback(event)
        except Exception:
            return
