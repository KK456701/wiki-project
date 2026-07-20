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
from app.agent_evidence import EvidenceLedger
from app.agent_tools.access_policy import (
    PolicyDecisionService,
    ToolExecutionContext,
)
from app.agent_tools.policy import (
    RepeatDecision,
    ToolExecutionPolicy,
    tool_call_fingerprint,
)
from app.agent_tools.registry import ToolRegistry, ToolRegistryError


TraceCallback = Callable[[dict[str, Any]], None]
_DB_READ_TOOLS = {
    "search_indicator_rules",
    "get_effective_rule",
    "inspect_indicator_implementation",
    "prepare_indicator_sql",
    "trial_run_indicator_sql",
    "diagnose_indicator_issue",
}


class ToolGateway:
    def __init__(
        self,
        registry: ToolRegistry,
        *,
        policy: ToolExecutionPolicy | None = None,
        decision_service: PolicyDecisionService | None = None,
        evidence_ledger: EvidenceLedger | None = None,
        db_concurrency: int = 2,
        trace_callback: TraceCallback | None = None,
    ) -> None:
        self.registry = registry
        self.policy = policy or ToolExecutionPolicy()
        self.decision_service = decision_service or PolicyDecisionService()
        self.evidence_ledger = evidence_ledger
        self.db_semaphore = asyncio.Semaphore(max(1, int(db_concurrency)))
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

        policy_decision = self.decision_service.decide(tool, context, state)
        if not policy_decision.allowed:
            return ToolResult(
                ok=False,
                status=(
                    "forbidden"
                    if policy_decision.reason_code == "PERMISSION_DENIED"
                    else "unavailable"
                ),
                code=policy_decision.reason_code,
                summary=policy_decision.display_message,
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
                    "subtask_id": state.subtask_id or context.request_id,
                    "tool_name": tool.name,
                    "arguments": raw_arguments,
                    "duration_ms": 0,
                    "reused": True,
                    "result": result.model_dump(mode="json"),
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
                "subtask_id": state.subtask_id or context.request_id,
                "tool_name": tool.name,
                "arguments": raw_arguments,
                "duration_ms": 0,
                "reused": False,
                "result": result.model_dump(mode="json"),
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
                "subtask_id": state.subtask_id or context.request_id,
                "tool_name": tool.name,
                "arguments": raw_arguments,
                "duration_ms": 0,
                "reused": False,
                "result": result.model_dump(mode="json"),
            })
            return result

        self._emit(
            {
                "event": "tool_call",
                "subtask_id": state.subtask_id or context.request_id,
                "tool_name": tool.name,
                "arguments": raw_arguments,
                "risk_level": tool.risk_level.value,
            }
        )
        started_at = time.perf_counter()
        execution_context = ToolExecutionContext(
            agent_context=context,
            subtask_id=state.subtask_id or context.request_id,
            run_state=state,
            policy_decision=policy_decision,
        )
        try:
            async def invoke_tool():
                return await self._invoke(
                    tool.handler,
                    arguments,
                    execution_context,
                    state,
                )

            if tool.name in _DB_READ_TOOLS:
                async with self.db_semaphore:
                    value = await asyncio.wait_for(
                        invoke_tool(),
                        timeout=tool.timeout_seconds,
                    )
            else:
                value = await asyncio.wait_for(
                    invoke_tool(),
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

        evidence_ids: list[str] = []
        if self.evidence_ledger is not None:
            evidence_ids = self.evidence_ledger.record_tool_result(
                tool_name=tool.name,
                arguments=raw_arguments,
                result=result,
                context=context,
                state=state,
            )
            result.evidence_ids = evidence_ids
        self._emit(
            {
                "event": "tool_result",
                "subtask_id": state.subtask_id or context.request_id,
                "tool_name": tool.name,
                "arguments": raw_arguments,
                "duration_ms": max(
                    0,
                    int((time.perf_counter() - started_at) * 1000),
                ),
                "reused": False,
                "result": result.model_dump(mode="json"),
                "evidence_ids": evidence_ids,
                "policy_decision": policy_decision.model_dump(mode="json"),
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
