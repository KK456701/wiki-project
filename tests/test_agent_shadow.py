import asyncio

from app.agent_runtime import AgentRunResult, AgentRunState
from app.agent_runtime.shadow import AgentShadowService, run_shadow_safely
from app.agent_tools.catalog import build_agent_shadow_tool_registry
from app.agent_tools.read_tools import ReadToolServices
from app.hospital_auth.models import HospitalPrincipal


class EmptyCaliber:
    pass


class FakeRecorder:
    def __init__(self):
        self.started = []
        self.nodes = []
        self.finished = []

    def start_trace(self, *args, **kwargs):
        self.started.append((args, kwargs))

    def record_node(self, *args, **kwargs):
        self.nodes.append((args, kwargs))

    def finish_trace(self, *args, **kwargs):
        self.finished.append((args, kwargs))


class FakeRunner:
    def __init__(self, callback, *, error=None):
        self.callback = callback
        self.error = error

    async def run(self, query, context):
        if self.error:
            raise self.error
        self.callback({
            "event": "tool_call",
            "tool_name": "search_indicator_rules",
            "arguments": {"query": query},
        })
        self.callback({
            "event": "tool_result",
            "tool_name": "search_indicator_rules",
            "result": {
                "ok": True,
                "code": "RULE_SEARCHED",
                "evidence": [{"fact_types": ["rule_identity"]}],
            },
        })
        return AgentRunResult(
            answer="该内容不得写入 Shadow 对比。",
            stop_reason="final_answer",
            state=AgentRunState(step_count=2),
            model="fake",
        )


def _principal(role="implementer"):
    permissions = {"indicator_detail_view"}
    if role == "implementer":
        permissions.add("indicator_detail_export")
    return HospitalPrincipal(
        user_id="u1",
        account_id="a1",
        hospital_id="h1",
        permissions=frozenset(permissions),
        must_change_password=False,
        session_id="s1",
    )


def test_shadow_registry_contains_only_three_read_tools() -> None:
    registry = build_agent_shadow_tool_registry(
        ReadToolServices(caliber=EmptyCaliber())
    )

    assert [tool.name for tool in registry.all()] == [
        "search_indicator_rules",
        "get_effective_rule",
        "inspect_indicator_implementation",
    ]
    forbidden = {
        "prepare_indicator_sql",
        "trial_run_indicator_sql",
        "diagnose_indicator_issue",
        "create_indicator_draft",
        "preview_rule_change",
    }
    assert forbidden.isdisjoint(tool.name for tool in registry.all())


def test_shadow_records_only_comparison_metadata_not_answers() -> None:
    recorder = FakeRecorder()
    service = AgentShadowService(
        runner_factory=lambda callback: FakeRunner(callback),
        trace_recorder_factory=lambda: recorder,
        timeout_seconds=1,
    )

    result = asyncio.run(service.run(
        query="急会诊及时到位率怎么算？",
        principal=_principal(),
        request_id="REQ_001",
        legacy_result={"intent": "query", "rule_id": "MQSI2025_005"},
    ))

    assert result["called_tools"] == ["search_indicator_rules"]
    assert result["rule_evidence"] is True
    assert result["stop_reason"] == "final_answer"
    assert "answer" not in result
    serialized = str(recorder.nodes)
    assert "该内容不得写入" not in serialized
    assert "search_indicator_rules" in serialized
    assert recorder.finished[0][1]["intent"] == "agent_shadow_compare"


def test_shadow_failure_isolated_from_legacy_response() -> None:
    service = AgentShadowService(
        runner_factory=lambda callback: FakeRunner(
            callback, error=RuntimeError("secret internal")
        ),
        trace_recorder_factory=FakeRecorder,
        timeout_seconds=1,
    )

    result = asyncio.run(run_shadow_safely(
        service,
        query="查询指标",
        principal=_principal(),
        request_id="REQ_001",
        legacy_result={},
    ))

    assert result is None
