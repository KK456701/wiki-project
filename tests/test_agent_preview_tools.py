import pytest
from pydantic import ValidationError

from app.agent_runtime import AgentRunState, AgentRuntimeContext
from app.agent_tools.preview_tools import (
    CreateIndicatorDraftInput,
    PreviewToolServices,
    build_preview_tools,
    create_indicator_draft,
)


def _context(user_role="implementer"):
    return AgentRuntimeContext(
        user_id="u1",
        hospital_id="h1",
        session_id="s1",
        user_role=user_role,
        permissions=frozenset({"indicator_read"}),
        request_id="r1",
        trace_id="t1",
    )


class FakePreviewOrchestrator:
    def __init__(self, *, error=None) -> None:
        self.error = error
        self.draft_calls = []

    def create_indicator_draft(self, description, hospital_id, actor_id):
        self.draft_calls.append((description, hospital_id, actor_id))
        if self.error:
            raise self.error
        return {
            "draft_id": "DRAFT_001",
            "status": "requirements_pending",
            "current_version": 1,
            "index_name": "夜间急会诊15分钟到位率",
            "index_desc": "统计夜间急会诊15分钟内到位情况。",
            "stat_cycle": "month",
            "numerator_rule": "15分钟内到位例数",
            "denominator_rule": "夜间急会诊总例数",
            "filter_rule": "仅统计夜间急会诊",
            "exclude_rule": "",
            "metric_type": "ratio",
            "metadata_requirements": ["consult_id", "request_time", "arrival_time"],
            "sql_plan": {"main_table": "secret_table"},
            "current_sql": "SELECT patient_name FROM patient",
            "sql_params": {"password": "secret"},
            "trial_result": {"rows": [{"patient_name": "不应返回"}]},
        }


def test_create_draft_injects_context_and_returns_safe_projection() -> None:
    description = "创建夜间急会诊15分钟到位率，分母为夜间急会诊总数"
    orchestrator = FakePreviewOrchestrator()
    state = AgentRunState()

    result = create_indicator_draft(
        CreateIndicatorDraftInput(description=description),
        _context(),
        state,
        services=PreviewToolServices(orchestrator=orchestrator),
    )

    assert orchestrator.draft_calls == [(description, "h1", "u1")]
    assert result.ok
    assert result.code == "INDICATOR_DRAFT_CREATED"
    assert result.data == {
        "draft_id": "DRAFT_001",
        "status": "requirements_pending",
        "current_version": 1,
        "index_name": "夜间急会诊15分钟到位率",
        "index_desc": "统计夜间急会诊15分钟内到位情况。",
        "stat_cycle": "month",
        "numerator_rule": "15分钟内到位例数",
        "denominator_rule": "夜间急会诊总例数",
        "filter_rule": "仅统计夜间急会诊",
        "exclude_rule": "",
        "metric_type": "ratio",
        "metadata_requirements": ["consult_id", "request_time", "arrival_time"],
        "missing_information": [],
    }
    assert state.last_draft_id == "DRAFT_001"
    assert result.evidence[0].fact_types == ["indicator_draft"]


def test_create_draft_reports_missing_business_information() -> None:
    orchestrator = FakePreviewOrchestrator()
    original = orchestrator.create_indicator_draft
    def incomplete(*args):
        result = original(*args)
        result.update({
            "numerator_rule": "",
            "denominator_rule": "",
            "stat_cycle": "",
            "metadata_requirements": [],
        })
        return result
    orchestrator.create_indicator_draft = incomplete

    result = create_indicator_draft(
        CreateIndicatorDraftInput(description="创建一个尚未补全口径的指标草稿"),
        _context(),
        AgentRunState(),
        services=PreviewToolServices(orchestrator=orchestrator),
    )

    assert result.data["missing_information"] == [
        "分子规则",
        "分母规则",
        "统计周期",
        "字段需求",
    ]


def test_create_draft_input_forbids_tenant_actor_and_sql_fields() -> None:
    for extra in (
        {"hospital_id": "other"},
        {"actor_id": "admin"},
        {"sql_text": "SELECT 1"},
    ):
        with pytest.raises(ValidationError):
            CreateIndicatorDraftInput(
                description="创建一个完整的医院业务指标草稿",
                **extra,
            )


def test_create_draft_service_error_is_standardized() -> None:
    result = create_indicator_draft(
        CreateIndicatorDraftInput(description="创建一个完整的医院业务指标草稿"),
        _context(),
        AgentRunState(),
        services=PreviewToolServices(
            orchestrator=FakePreviewOrchestrator(
                error=RuntimeError("token=secret internal")
            )
        ),
    )

    assert not result.ok
    assert result.code == "DRAFT_CREATION_FAILED"
    assert "secret" not in result.summary
    assert result.evidence == []


def test_draft_tool_visibility_requires_implementation_role_and_no_rule() -> None:
    tool = build_preview_tools(
        PreviewToolServices(orchestrator=FakePreviewOrchestrator())
    )[0]
    rule_state = AgentRunState(evidence=[{
        "source_id": "MQSI2025_005",
        "fact_types": ["rule_identity"],
    }])

    assert tool.availability(_context(), AgentRunState()) is True
    assert tool.availability(_context("doctor"), AgentRunState()) is False
    assert tool.availability(_context(), rule_state) is False
