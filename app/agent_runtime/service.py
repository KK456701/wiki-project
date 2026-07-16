"""登录态 Agent 应用服务与默认运行时组装。"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from datetime import timedelta
from typing import Any

from app.agent_runtime.contracts import AgentRuntimeContext
from app.agent_runtime.tracing import AgentTraceBridge
from app.config import get, get_bool, get_int
from app.hospital_auth.models import (
    DETAIL_EXPORT_PERMISSION,
    DETAIL_VIEW_PERMISSION,
    HospitalPrincipal,
)


class AgentRuntimeUnavailable(RuntimeError):
    pass


class AgentRunAccessError(RuntimeError):
    def __init__(self, message: str, status_code: int) -> None:
        super().__init__(message)
        self.status_code = status_code


def context_from_principal(
    principal: HospitalPrincipal,
    *,
    request_id: str,
    trace_id: str,
    session_id: str | None = None,
    db_source_id: str | None = None,
) -> AgentRuntimeContext:
    permissions = set(principal.permissions)
    if DETAIL_VIEW_PERMISSION in permissions:
        permissions.add("indicator_read")
    role = "implementer" if DETAIL_EXPORT_PERMISSION in permissions else "doctor"
    return AgentRuntimeContext(
        user_id=principal.user_id,
        hospital_id=principal.hospital_id,
        session_id=session_id or principal.session_id,
        user_role=role,
        permissions=frozenset(permissions),
        request_id=request_id,
        trace_id=trace_id,
        db_source_id=db_source_id,
    )


class AgentRuntimeService:
    def __init__(
        self,
        *,
        enabled: bool,
        mode: str,
        model: str,
        runner_factory: Callable[..., Any] | None = None,
        trace_recorder_factory: Callable[[], Any] | None = None,
        max_steps: int = 8,
        request_timeout_seconds: int = 120,
    ) -> None:
        self.enabled = enabled
        self.mode = mode
        self.model = model
        self.max_steps = max_steps
        self.request_timeout_seconds = request_timeout_seconds
        self.runner_factory = runner_factory or self._build_runner
        self.trace_recorder_factory = trace_recorder_factory or self._build_trace_recorder

    @classmethod
    def from_config(cls) -> "AgentRuntimeService":
        return cls(
            enabled=get_bool("agent_enabled", False),
            mode=get("agent_mode", "legacy").strip().lower(),
            model=get("agent_model", get("ollama_model", "qwen3:4B-instruct")),
            max_steps=max(1, get_int("agent_max_steps", 8)),
            request_timeout_seconds=max(1, get_int("agent_request_timeout_seconds", 120)),
        )

    def capabilities(self) -> dict[str, Any]:
        return {
            "enabled": self.enabled and self.mode == "tool_calling",
            "mode": self.mode,
            "model": self.model,
            "streaming": True,
            "max_steps": self.max_steps,
            "formal_writes": False,
        }

    async def chat(
        self,
        *,
        query: str,
        principal: HospitalPrincipal,
        request_id: str,
        session_id: str | None = None,
    ) -> dict[str, Any]:
        if not self.enabled or self.mode != "tool_calling":
            raise AgentRuntimeUnavailable(
                "工具调用型 Agent 当前未启用，旧聊天入口仍可使用。"
            )
        trace_id = f"TRACE_{uuid.uuid4().hex[:12]}"
        context = context_from_principal(
            principal,
            request_id=request_id,
            trace_id=trace_id,
            session_id=session_id,
            db_source_id=self._db_source_id(),
        )
        bridge = AgentTraceBridge(self.trace_recorder_factory(), trace_id)
        bridge.start(
            session_id=context.session_id,
            hospital_id=context.hospital_id,
            user_query=query,
        )
        result = await self.runner_factory(bridge.handle).run(query, context)
        return {
            "answer": result.answer,
            "stop_reason": result.stop_reason,
            "trace_id": trace_id,
            "session_id": context.session_id,
            "step_count": result.state.step_count,
        }

    def get_run(self, trace_id: str, principal: HospitalPrincipal) -> dict[str, Any]:
        trace = self.trace_recorder_factory().get_trace(trace_id)
        if not trace or trace.get("trace_storage") == "none":
            raise AgentRunAccessError("未找到该 Agent 运行记录。", 404)
        if str(trace.get("hospital_id") or "") != principal.hospital_id:
            raise AgentRunAccessError("无权查看其他医院的 Agent 运行记录。", 403)
        return trace

    def _build_runner(self, event_callback):
        from app.agent_runtime.runner import AgentRunner
        from app.agent_tools import (
            AgentSqlObjectStore,
            DiagnosisToolServices,
            PreviewToolServices,
            ReadToolServices,
            SqlToolServices,
            ToolGateway,
            build_agent_tool_registry,
            ensure_agent_sql_object_schema,
        )
        from app.api.main import _create_agent_orchestrator, create_business_db_client
        from app.db.engine import create_runtime_engine
        from app.llm.ollama import OllamaClient
        from app.llm.ollama_tools import OllamaToolCallingAdapter

        engine = create_runtime_engine()
        ensure_agent_sql_object_schema(engine)
        business_db = create_business_db_client()
        orchestrator = _create_agent_orchestrator(
            runtime_engine=engine,
            business_db=business_db,
        )
        registry = build_agent_tool_registry(
            read_services=ReadToolServices(
                caliber=orchestrator.caliber,
                terminology=orchestrator.terminology_normalizer,
            ),
            sql_services=SqlToolServices(
                orchestrator=orchestrator,
                store=AgentSqlObjectStore(engine),
                runtime_engine=engine,
                business_db=business_db,
                ttl=timedelta(minutes=max(1, get_int("agent_sql_ttl_minutes", 30))),
            ),
            diagnosis_services=DiagnosisToolServices(orchestrator=orchestrator),
            preview_services=PreviewToolServices(orchestrator=orchestrator),
        )
        gateway = ToolGateway(registry, trace_callback=event_callback)
        adapter = OllamaToolCallingAdapter(OllamaClient(model=self.model))
        return AgentRunner(
            adapter,
            registry,
            gateway,
            max_steps=self.max_steps,
            request_timeout_seconds=self.request_timeout_seconds,
            event_callback=event_callback,
        )

    @staticmethod
    def _build_trace_recorder():
        from app.db.engine import create_runtime_engine
        from app.observability.trace import TraceRecorder

        return TraceRecorder(create_runtime_engine())

    @staticmethod
    def _db_source_id() -> str | None:
        try:
            from app.business_source import current_business_source

            return current_business_source().source_id
        except Exception:
            return None
