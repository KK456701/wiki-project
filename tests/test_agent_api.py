from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

from app.api.agent_routes import get_agent_runtime_service, router
from app.hospital_auth.dependencies import require_hospital_session
from app.hospital_auth.models import HospitalPrincipal


class FakeAgentService:
    def __init__(self):
        self.calls = []

    async def chat(self, *, query, principal, request_id, session_id=None):
        self.calls.append((query, principal, request_id, session_id))
        return {
            "answer": "已完成指标说明。",
            "stop_reason": "final_answer",
            "trace_id": "TRACE_001",
            "session_id": session_id or principal.session_id,
            "step_count": 2,
        }

    def capabilities(self):
        return {
            "enabled": True,
            "mode": "tool_calling",
            "model": "fake",
            "streaming": True,
            "max_steps": 8,
            "formal_writes": False,
        }

    def get_run(self, trace_id, principal):
        return {"trace_id": trace_id, "hospital_id": principal.hospital_id}


def _principal():
    return HospitalPrincipal(
        user_id="u1",
        account_id="a1",
        hospital_id="h1",
        permissions=frozenset({"indicator_detail_view"}),
        must_change_password=False,
        session_id="auth-session",
    )


def _client(service=None, authenticated=True):
    app = FastAPI()
    app.include_router(router)
    fake = service or FakeAgentService()
    app.dependency_overrides[get_agent_runtime_service] = lambda: fake
    if authenticated:
        app.dependency_overrides[require_hospital_session] = _principal
    else:
        def reject():
            raise HTTPException(status_code=401, detail="请先登录后继续操作")
        app.dependency_overrides[require_hospital_session] = reject
    return TestClient(app), fake


def test_chat_uses_authenticated_principal_and_server_request_id() -> None:
    client, service = _client()

    response = client.post(
        "/api/agent/chat",
        json={"query": "急会诊及时到位率怎么算？", "session_id": "chat-1"},
        headers={"X-Request-ID": "REQ_001"},
    )

    assert response.status_code == 200
    assert response.json() == {
        "answer": "已完成指标说明。",
        "stop_reason": "final_answer",
        "trace_id": "TRACE_001",
        "session_id": "chat-1",
        "step_count": 2,
    }
    assert service.calls[0][1].hospital_id == "h1"
    assert service.calls[0][2] == "REQ_001"


def test_chat_rejects_tenant_identity_and_authority_fields() -> None:
    client, service = _client()

    for field, value in (
        ("hospital_id", "h2"),
        ("user_id", "admin"),
        ("user_role", "admin"),
        ("permissions", ["all"]),
        ("trace_id", "TRACE_FAKE"),
    ):
        response = client.post(
            "/api/agent/chat",
            json={"query": "查询指标", field: value},
        )
        assert response.status_code == 422
    assert service.calls == []


def test_all_agent_endpoints_require_login() -> None:
    client, service = _client(authenticated=False)

    assert client.post("/api/agent/chat", json={"query": "查询指标"}).status_code == 401
    assert client.get("/api/agent/capabilities").status_code == 401
    assert client.get("/api/agent/runs/TRACE_001").status_code == 401
    assert service.calls == []


def test_capabilities_and_run_are_scoped_to_principal() -> None:
    client, _ = _client()

    capabilities = client.get("/api/agent/capabilities")
    run = client.get("/api/agent/runs/TRACE_001")

    assert capabilities.status_code == 200
    assert capabilities.json()["formal_writes"] is False
    assert run.json() == {"trace_id": "TRACE_001", "hospital_id": "h1"}
