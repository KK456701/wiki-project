import pytest
from pydantic import ValidationError

from app.agent_runtime import AgentRunState, AgentRuntimeContext
from app.agent_tools.preview_tools import (
    CreateIndicatorDraftInput,
    PreviewToolServices,
    PreviewRuleChangeInput,
    build_preview_tools,
    create_indicator_draft,
    preview_rule_change,
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
        self.prepare_calls = []
        self.preview_calls = []
        self.submit_calls = []

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

    def prepare_rule_request(self, **kwargs):
        self.prepare_calls.append(kwargs)
        return {"prepared": kwargs}

    def preview_feedback(self, prepared):
        self.preview_calls.append(prepared)
        if self.error:
            raise self.error
        return {
            "rule_id": "MQSI2025_005",
            "rule_name": "急会诊及时到位率",
            "hospital_id": "h1",
            "target_level": "hospital",
            "current_effective_level": "national",
            "requested": {
                "level": "hospital",
                "status": "requested",
                "definition": "急会诊在15分钟内到位的比例。",
                "formula": "15分钟内到位例数 / 急会诊总例数 × 100%",
                "source_text": "用户完整原文不应重复返回",
            },
            "current_effective": {
                "level": "national",
                "status": "effective",
                "definition": "急会诊在规定时间内到位的比例。",
                "formula": "10分钟内到位例数 / 急会诊总例数 × 100%",
            },
            "field_changes": [
                {
                    "field": "指标定义",
                    "requested": "急会诊在15分钟内到位的比例。",
                    "current": "急会诊在规定时间内到位的比例。",
                    "changed": False,
                    "internal": "drop-me",
                },
                {
                    "field": "计算公式",
                    "requested": "15分钟内到位例数 / 急会诊总例数 × 100%",
                    "current": "10分钟内到位例数 / 急会诊总例数 × 100%",
                    "changed": True,
                },
            ],
            "message": "检测到本院口径反馈，请确认差异。",
            "change_id": "CR_MUST_NOT_RETURN",
            "status": "pending",
            "approval": {"approver": "admin"},
        }

    def submit_change(self, payload):
        self.submit_calls.append(payload)
        raise AssertionError("预览工具不得提交变更")


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


def _rule_state():
    return AgentRunState(evidence=[{
        "source_id": "MQSI2025_005",
        "fact_types": ["rule_identity"],
    }])


def test_preview_rule_change_returns_safe_diff_without_submission() -> None:
    orchestrator = FakePreviewOrchestrator()
    result = preview_rule_change(
        PreviewRuleChangeInput(
            rule_id="MQSI2025_005",
            change_description="本院急会诊按15分钟内到位计算",
        ),
        _context(),
        _rule_state(),
        services=PreviewToolServices(orchestrator=orchestrator),
    )

    assert result.ok
    assert result.code == "RULE_CHANGE_PREVIEWED"
    assert orchestrator.prepare_calls[0] == {
        "query": "本院急会诊按15分钟内到位计算",
        "hospital_id": "h1",
        "intent": "feedback",
        "rule_id": "MQSI2025_005",
    }
    assert len(orchestrator.preview_calls) == 1
    assert orchestrator.submit_calls == []
    assert result.data["impact"] == {
        "changed_fields": ["计算公式"],
        "affects_definition": False,
        "affects_formula": True,
        "requires_field_review": False,
        "requires_sql_regeneration": True,
        "requires_version_increment": True,
    }
    assert result.data["requested"] == {
        "level": "hospital",
        "status": "requested",
        "definition": "急会诊在15分钟内到位的比例。",
        "formula": "15分钟内到位例数 / 急会诊总例数 × 100%",
    }
    assert "change_id" not in result.data
    assert "approval" not in result.data
    assert result.evidence[0].fact_types == ["rule_change_preview"]


def test_preview_rule_change_requires_matching_verified_rule() -> None:
    result = preview_rule_change(
        PreviewRuleChangeInput(
            rule_id="MQSI2025_005",
            change_description="本院按15分钟计算",
        ),
        _context(),
        AgentRunState(),
        services=PreviewToolServices(orchestrator=FakePreviewOrchestrator()),
    )

    assert not result.ok
    assert result.code == "RULE_NOT_VERIFIED"


def test_preview_rule_change_input_forbids_write_and_tenant_fields() -> None:
    for extra in (
        {"hospital_id": "other"},
        {"approver_id": "admin"},
        {"submit": True},
        {"version": 3},
    ):
        with pytest.raises(ValidationError):
            PreviewRuleChangeInput(
                rule_id="MQSI2025_005",
                change_description="本院按15分钟计算",
                **extra,
            )


def test_preview_rule_change_failure_is_standardized() -> None:
    result = preview_rule_change(
        PreviewRuleChangeInput(
            rule_id="MQSI2025_005",
            change_description="本院按15分钟计算",
        ),
        _context(),
        _rule_state(),
        services=PreviewToolServices(orchestrator=FakePreviewOrchestrator(
            error=RuntimeError("password=secret internal")
        )),
    )

    assert not result.ok
    assert result.code == "RULE_CHANGE_PREVIEW_FAILED"
    assert "secret" not in result.summary
    assert result.evidence == []
