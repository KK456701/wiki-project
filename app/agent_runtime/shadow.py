"""不影响旧回答的低成本只读 Agent Shadow。"""

from __future__ import annotations

import asyncio
import hashlib
import uuid
from collections.abc import Callable
from typing import Any

from app.agent_runtime.service import context_from_principal
from app.hospital_auth.models import HospitalPrincipal


class AgentShadowService:
    def __init__(
        self,
        *,
        runner_factory: Callable[..., Any] | None = None,
        trace_recorder_factory: Callable[[], Any] | None = None,
        timeout_seconds: float = 20.0,
    ) -> None:
        self.runner_factory = runner_factory or self._build_runner
        self.trace_recorder_factory = (
            trace_recorder_factory or self._build_trace_recorder
        )
        self.timeout_seconds = timeout_seconds

    async def run(
        self,
        *,
        query: str,
        principal: HospitalPrincipal,
        request_id: str,
        legacy_result: dict[str, Any],
    ) -> dict[str, Any]:
        trace_id = f"TRACE_{uuid.uuid4().hex[:12]}"
        context = context_from_principal(
            principal,
            request_id=request_id,
            trace_id=trace_id,
        )
        recorder = self.trace_recorder_factory()
        query_digest = hashlib.sha256(query.encode("utf-8")).hexdigest()
        recorder.start_trace(
            trace_id=trace_id,
            session_id=context.session_id,
            hospital_id=context.hospital_id,
            user_query=f"shadow_query_sha256:{query_digest}",
            workflow_id="agent_shadow",
        )
        called_tools: list[str] = []
        rule_evidence = False

        def handle(event: dict[str, Any]) -> None:
            nonlocal rule_evidence
            if event.get("event") == "tool_call":
                tool_name = str(event.get("tool_name") or "")
                if tool_name and tool_name not in called_tools:
                    called_tools.append(tool_name)
            if event.get("event") == "tool_result":
                result = event.get("result") or {}
                rule_evidence = rule_evidence or any(
                    "rule_identity" in (item.get("fact_types") or [])
                    for item in (result.get("evidence") or [])
                    if isinstance(item, dict)
                )

        try:
            result = await asyncio.wait_for(
                self.runner_factory(handle).run(query, context),
                timeout=self.timeout_seconds,
            )
        except Exception:
            recorder.record_node(
                trace_id=trace_id,
                node_name="shadow_compare",
                node_type="agent_shadow",
                status="failed",
                error_code="AGENT_SHADOW_FAILED",
                error_message="Shadow 只读对比未完成。",
                config_data={"called_tools": called_tools},
            )
            recorder.finish_trace(
                trace_id=trace_id,
                final_status="failed",
                final_answer_summary="shadow_failed",
                intent="agent_shadow_compare",
                error_count=1,
            )
            raise

        comparison = {
            "trace_id": trace_id,
            "called_tools": called_tools,
            "rule_evidence": rule_evidence,
            "legacy_rule_evidence": bool(
                legacy_result.get("rule_id")
                or legacy_result.get("resolved_rule_id")
            ),
            "stop_reason": result.stop_reason,
        }
        recorder.record_node(
            trace_id=trace_id,
            node_name="shadow_compare",
            node_type="agent_shadow",
            status="success",
            output_summary=result.stop_reason,
            config_data=comparison,
        )
        recorder.finish_trace(
            trace_id=trace_id,
            final_status="success",
            final_answer_summary=result.stop_reason,
            intent="agent_shadow_compare",
        )
        return comparison

    @staticmethod
    def _build_runner(event_callback):
        from app.agent_runtime.runner import AgentRunner
        from app.agent_tools import ToolGateway, build_agent_shadow_tool_registry
        from app.agent_tools.read_tools import ReadToolServices
        from app.agents.caliber_adaptation import CaliberAdaptationAgent
        from app.db.engine import create_runtime_engine
        from app.kb.tools import DEFAULT_KB_ROOT
        from app.llm.ollama_tools import OllamaToolCallingAdapter
        from app.rules.repository import create_rule_repository
        from app.terminology.normalizer import TerminologyNormalizer
        from app.terminology.repository import TerminologyRepository

        engine = create_runtime_engine()
        terminology_repository = TerminologyRepository(engine)
        registry = build_agent_shadow_tool_registry(ReadToolServices(
            caliber=CaliberAdaptationAgent(
                create_rule_repository(engine, DEFAULT_KB_ROOT)
            ),
            terminology=TerminologyNormalizer(terminology_repository),
        ))
        return AgentRunner(
            OllamaToolCallingAdapter(),
            registry,
            ToolGateway(registry, trace_callback=event_callback),
            max_steps=3,
            request_timeout_seconds=20,
            event_callback=event_callback,
        )

    @staticmethod
    def _build_trace_recorder():
        from app.db.engine import create_runtime_engine
        from app.observability.trace import TraceRecorder

        return TraceRecorder(create_runtime_engine())


async def run_shadow_safely(
    service: AgentShadowService,
    **kwargs: Any,
) -> dict[str, Any] | None:
    try:
        return await service.run(**kwargs)
    except Exception:
        return None
