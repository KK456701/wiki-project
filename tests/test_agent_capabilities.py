import pytest

from app.agent_runtime.service import (
    AgentRunAccessError,
    AgentRuntimeService,
    context_from_principal,
)
from app.hospital_auth.models import HospitalPrincipal


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

    def get_trace(self, trace_id):
        return dict(self.trace, trace_id=trace_id)


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
