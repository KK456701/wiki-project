import json

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.agent_routes import get_agent_runtime_service, router
from app.agent_runtime.events import public_agent_event
from app.hospital_auth.dependencies import require_hospital_session
from app.hospital_auth.models import HospitalPrincipal


class FakeStreamService:
    def ensure_available(self):
        return None

    async def stream(self, **kwargs):
        assert kwargs["principal"].hospital_id == "h1"
        yield {"event": "agent_start", "trace_id": "TRACE_001", "step": 0}
        yield {
            "event": "tool_call",
            "trace_id": "TRACE_001",
            "tool_name": "search_indicator_rules",
            "message": "正在调用工具。",
        }
        yield {
            "event": "assistant_message",
            "trace_id": "TRACE_001",
            "message": "已获取指标规则。",
        }
        yield {
            "event": "agent_done",
            "trace_id": "TRACE_001",
            "stop_reason": "final_answer",
        }


def _client():
    app = FastAPI()
    app.include_router(router)
    service = FakeStreamService()
    principal = HospitalPrincipal(
        user_id="u1",
        account_id="a1",
        hospital_id="h1",
        permissions=frozenset({"indicator_detail_view"}),
        must_change_password=False,
        session_id="s1",
    )
    app.dependency_overrides[get_agent_runtime_service] = lambda: service
    app.dependency_overrides[require_hospital_session] = lambda: principal
    return TestClient(app)


def _events(text):
    result = []
    event_name = None
    for line in text.splitlines():
        if line.startswith("event: "):
            event_name = line.removeprefix("event: ")
        elif line.startswith("data: "):
            payload = json.loads(line.removeprefix("data: "))
            result.append((event_name, payload))
    return result


def test_stream_returns_ordered_business_events_and_safe_headers() -> None:
    response = _client().post(
        "/api/agent/chat/stream",
        json={"query": "急会诊及时到位率怎么算？"},
    )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert response.headers["cache-control"] == "no-cache"
    assert response.headers["x-accel-buffering"] == "no"
    events = _events(response.text)
    assert [name for name, _ in events] == [
        "agent_start",
        "tool_call",
        "assistant_message",
        "agent_done",
    ]
    assert {payload["trace_id"] for _, payload in events} == {"TRACE_001"}


def test_public_event_projection_removes_arguments_results_and_sql() -> None:
    event = public_agent_event({
        "event": "tool_result",
        "tool_name": "trial_run_indicator_sql",
        "duration_ms": 12,
        "arguments": {"sql_text": "SELECT patient_name"},
        "result": {
            "ok": True,
            "status": "success",
            "code": "TRIAL_RUN_COMPLETED",
            "summary": "只读试运行完成。",
            "data": {"patient_name": "不应返回"},
        },
    }, trace_id="TRACE_001")

    assert event == {
        "event": "tool_result",
        "trace_id": "TRACE_001",
        "tool_name": "trial_run_indicator_sql",
        "status": "success",
        "code": "TRIAL_RUN_COMPLETED",
        "message": "只读试运行完成。",
        "retryable": False,
        "duration_ms": 12,
        "reused": False,
    }
    assert "SELECT" not in json.dumps(event, ensure_ascii=False)
