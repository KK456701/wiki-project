import asyncio

import pytest

from app.agent_runtime.contracts import AgentRunResult, AgentRunState
from app.agent_runtime.service import (
    AgentRunAccessError,
    AgentRuntimeService,
    context_from_principal,
)
from app.hospital_auth.models import HospitalPrincipal
from app.memory.contracts import ContextStorageError


def _principal(permissions):
    return HospitalPrincipal(
        user_id="u1",
        account_id="a1",
        hospital_id="h1",
        permissions=frozenset(permissions),
        must_change_password=False,
        session_id="s1",
    )


def test_context_maps_existing_read_permission_without_client_authority() -> None:
    context = context_from_principal(
        _principal({"indicator_detail_view"}),
        request_id="REQ_001",
        trace_id="TRACE_001",
        session_id="chat-1",
    )

    assert context.hospital_id == "h1"
    assert context.user_role == "doctor"
    assert "indicator_read" in context.permissions


def test_export_account_maps_to_implementation_role() -> None:
    context = context_from_principal(
        _principal({"indicator_detail_view", "indicator_detail_export"}),
        request_id="REQ_001",
        trace_id="TRACE_001",
    )

    assert context.user_role == "implementer"


class FakeTraceRecorder:
    def __init__(self, trace):
        self.trace = trace
        self.nodes = []

    def get_trace(self, trace_id):
        return dict(self.trace, trace_id=trace_id)

    def start_trace(self, **kwargs):
        self.started = kwargs

    def record_node(self, **kwargs):
        self.nodes.append(kwargs)

    def finish_trace(self, **kwargs):
        self.finished = kwargs


def test_run_lookup_enforces_hospital_scope() -> None:
    service = AgentRuntimeService(
        enabled=True,
        mode="tool_calling",
        model="fake",
        runner_factory=lambda *_: None,
        trace_recorder_factory=lambda: FakeTraceRecorder({"hospital_id": "h2"}),
    )

    with pytest.raises(AgentRunAccessError) as exc_info:
        service.get_run("TRACE_001", _principal({"indicator_detail_view"}))

    assert exc_info.value.status_code == 403


class FakeMemorySession:
    def __init__(self):
        self.state = AgentRunState(
            messages=[{"role": "system", "content": "包含最近 8 轮历史"}],
            current_rule_id="MQSI2025_005",
            evidence=[{
                "source": "conversation_memory",
                "source_id": "MQSI2025_005",
                "fact_types": ["rule_identity"],
            }],
        )
        self.user_queries = []
        self.completed = []

    def append_user(self, query):
        self.user_queries.append(query)

    def complete(self, query, answer, state):
        self.completed.append((query, answer, state))


class FakeMemory:
    def __init__(self):
        self.session = FakeMemorySession()
        self.contexts = []

    def open(self, context):
        self.contexts.append(context)
        return self.session


class FakeRunner:
    def __init__(self, callback):
        self.callback = callback
        self.states = []

    async def run(self, query, context, state=None):
        self.states.append(state)
        result = AgentRunResult(
            answer="已基于当前指标回答。",
            stop_reason="final_answer",
            state=state,
            model="fake",
        )
        self.callback({
            "event": "assistant_message",
            "answer": result.answer,
            "message": result.answer,
            "step_count": 1,
        })
        self.callback({
            "event": "agent_done",
            "answer": result.answer,
            "stop_reason": result.stop_reason,
            "step_count": 1,
        })
        return result


def test_chat_loads_and_completes_agent_conversation_memory() -> None:
    memory = FakeMemory()
    runners = []

    def runner_factory(callback):
        runner = FakeRunner(callback)
        runners.append(runner)
        return runner

    service = AgentRuntimeService(
        enabled=True,
        mode="tool_calling",
        model="fake",
        runner_factory=runner_factory,
        trace_recorder_factory=lambda: FakeTraceRecorder({"hospital_id": "h1"}),
        memory_factory=lambda: memory,
    )

    result = asyncio.run(service.chat(
        query="这个指标怎么算？",
        principal=_principal({"indicator_detail_view"}),
        request_id="REQ_001",
        session_id="chat-1",
    ))

    assert result["answer"] == "已基于当前指标回答。"
    assert memory.contexts[0].session_id == "chat-1"
    assert memory.session.user_queries == ["这个指标怎么算？"]
    assert runners[0].states == [memory.session.state]
    assert memory.session.completed[0][0:2] == (
        "这个指标怎么算？",
        "已基于当前指标回答。",
    )


def test_memory_save_failure_is_recorded_once_without_retrying_completion() -> None:
    class FailingMemorySession(FakeMemorySession):
        def __init__(self):
            super().__init__()
            self.complete_calls = 0

        def complete(self, query, answer, state):
            self.complete_calls += 1
            raise ContextStorageError("password=secret")

    memory = FakeMemory()
    memory.session = FailingMemorySession()
    recorder = FakeTraceRecorder({"hospital_id": "h1"})
    service = AgentRuntimeService(
        enabled=True,
        mode="tool_calling",
        model="fake",
        runner_factory=FakeRunner,
        trace_recorder_factory=lambda: recorder,
        memory_factory=lambda: memory,
    )

    result = asyncio.run(service.chat(
        query="这个指标怎么算？",
        principal=_principal({"indicator_detail_view"}),
        request_id="REQ_002",
        session_id="chat-2",
    ))

    assert result["answer"] == "已基于当前指标回答。"
    assert memory.session.complete_calls == 1
    failures = [node for node in recorder.nodes if node["node_name"] == "agent_memory"]
    assert len(failures) == 1
    assert "secret" not in str(failures)
