"""登录态 Agent 应用服务与默认运行时组装。"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Callable
from datetime import timedelta
from typing import Any

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_runtime.memory import AgentConversationMemory
from app.agent_runtime.events import public_agent_event
from app.agent_runtime.model_adapter import AgentModelError
from app.agent_runtime.tracing import AgentTraceBridge
from app.config import get, get_bool, get_int
from app.hospital_auth.models import (
    DETAIL_EXPORT_PERMISSION,
    DETAIL_VIEW_PERMISSION,
    HospitalPrincipal,
)
from app.memory.contracts import ContextStorageError, ContextVersionConflict


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
        memory_factory: Callable[[], AgentConversationMemory] | None = None,
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
        self.memory_factory = memory_factory or self._build_memory

    @classmethod
    def from_config(cls) -> "AgentRuntimeService":
        from app.llm.model_registry import get_model_registry

        registry = get_model_registry()
        return cls(
            enabled=get_bool("agent_enabled", False),
            mode=get("agent_mode", "legacy").strip().lower(),
            model=registry.default_model_id,
            max_steps=max(1, get_int("agent_max_steps", 8)),
            request_timeout_seconds=max(1, get_int("agent_request_timeout_seconds", 120)),
        )

    def capabilities(self) -> dict[str, Any]:
        from app.llm.model_registry import get_model_registry

        registry = get_model_registry()
        return {
            "enabled": self.enabled and self.mode == "tool_calling",
            "mode": self.mode,
            "model": self.model,
            "models": [
                model.model_dump_public()
                for model in registry.list_models()
            ],
            "streaming": True,
            "max_steps": self.max_steps,
            "formal_writes": False,
        }

    def ensure_available(self) -> None:
        if not self.enabled or self.mode != "tool_calling":
            raise AgentRuntimeUnavailable(
                "工具调用型 Agent 当前未启用，旧聊天入口仍可使用。"
            )

    async def chat(
        self,
        *,
        query: str,
        principal: HospitalPrincipal,
        request_id: str,
        session_id: str | None = None,
        model_id: str | None = None,
    ) -> dict[str, Any]:
        self.ensure_available()
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
        memory_session = self._open_memory(context)
        memory_session.append_user(query)
        memory_completion_attempted = False

        def handle(event: dict[str, Any]) -> None:
            nonlocal memory_completion_attempted
            if (
                event.get("event") in {"agent_done", "agent_error"}
                and not memory_completion_attempted
            ):
                memory_completion_attempted = True
                self._complete_memory(
                    memory_session,
                    query,
                    str(event.get("answer") or ""),
                    memory_session.state,
                    bridge,
                )
            bridge.handle(event)

        result = await self._make_runner(handle, model_id=model_id).run(
            query, context, memory_session.state
        )
        if not memory_completion_attempted:
            memory_completion_attempted = True
            self._complete_memory(
                memory_session, query, result.answer, result.state, bridge
            )
        return {
            "answer": result.answer,
            "stop_reason": result.stop_reason,
            "trace_id": trace_id,
            "session_id": context.session_id,
            "step_count": result.state.step_count,
        }

    async def stream(
        self,
        *,
        query: str,
        principal: HospitalPrincipal,
        request_id: str,
        session_id: str | None = None,
        model_id: str | None = None,
    ):
        self.ensure_available()
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
        memory_session = self._open_memory(context)
        memory_session.append_user(query)
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        memory_completion_attempted = False

        def handle(event: dict[str, Any]) -> None:
            nonlocal memory_completion_attempted
            if (
                event.get("event") in {"agent_done", "agent_error"}
                and not memory_completion_attempted
            ):
                memory_completion_attempted = True
                self._complete_memory(
                    memory_session,
                    query,
                    str(event.get("answer") or ""),
                    memory_session.state,
                    bridge,
                )
            bridge.handle(event)
            queue.put_nowait(public_agent_event(event, trace_id=trace_id))

        state = memory_session.state
        runner = self._make_runner(handle, model_id=model_id)

        async def execute() -> None:
            try:
                await runner.run(query, context, state)
            except asyncio.CancelledError:
                raise
            except Exception:
                handle({
                    "event": "agent_error",
                    "stop_reason": "tool_error",
                    "step_count": state.step_count,
                })

        task = asyncio.create_task(execute())
        terminal_seen = False
        try:
            while not terminal_seen:
                event = await queue.get()
                terminal_seen = event["event"] in {"agent_done", "agent_error"}
                yield event
            await task
        finally:
            if not task.done():
                state.cancelled = True
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
                bridge.handle({
                    "event": "agent_done",
                    "stop_reason": "cancelled",
                    "step_count": state.step_count,
                })

    def _open_memory(self, context: AgentRuntimeContext):
        try:
            return self.memory_factory().open(context)
        except ContextStorageError as exc:
            raise AgentRuntimeUnavailable(
                "会话状态暂不可用，请稍后重试。"
            ) from exc

    @staticmethod
    def _complete_memory(
        memory_session,
        query: str,
        answer: str,
        state: AgentRunState,
        bridge: AgentTraceBridge,
    ) -> bool:
        try:
            memory_session.complete(query, answer, state)
            return True
        except (ContextStorageError, ContextVersionConflict) as exc:
            if hasattr(bridge, "record_memory_failure"):
                bridge.record_memory_failure(str(exc))
            return False

    def get_run(self, trace_id: str, principal: HospitalPrincipal) -> dict[str, Any]:
        trace = self.trace_recorder_factory().get_trace(trace_id)
        if not trace or trace.get("trace_storage") == "none":
            raise AgentRunAccessError("未找到该 Agent 运行记录。", 404)
        if str(trace.get("hospital_id") or "") != principal.hospital_id:
            raise AgentRunAccessError("无权查看其他医院的 Agent 运行记录。", 403)
        return trace

    def _make_runner(self, event_callback, *, model_id: str | None = None):
        try:
            try:
                return self.runner_factory(event_callback, model_id=model_id)
            except TypeError:
                return self.runner_factory(event_callback)
        except AgentModelError as exc:
            raise AgentRuntimeUnavailable(str(exc)) from exc

    def _build_runner(self, event_callback, model_id: str | None = None):
        from app.agent_runtime.runner import AgentRunner
        from app.agent_tools import (
            AgentSqlObjectStore,
            DiagnosisToolServices,
            PreviewToolServices,
            ReadToolServices,
            SqlToolServices,
            ToolGateway,
            UploadToolServices,
            build_agent_tool_registry,
            ensure_agent_sql_object_schema,
        )
        from app.api.main import _create_agent_orchestrator, create_business_db_client
        from app.db.engine import create_runtime_engine
        from app.llm.model_registry import get_model_registry

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
            upload_services=UploadToolServices(),
        )
        gateway = ToolGateway(registry, trace_callback=event_callback)
        adapter = get_model_registry().build_adapter(model_id or self.model)
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
    def _build_memory() -> AgentConversationMemory:
        return AgentConversationMemory(
            max_turns=max(1, get_int("ollama_history_turns", 8)),
            token_budget=max(1, get_int("ollama_prompt_budget_tokens", 12000)),
        )

    @staticmethod
    def _db_source_id() -> str | None:
        try:
            from app.business_source import current_business_source

            return current_business_source().source_id
        except Exception:
            return None
