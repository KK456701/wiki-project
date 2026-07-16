import json

import pytest
from pydantic import ValidationError

from app.agent_runtime import AgentRunState, AgentRuntimeContext
from app.agent_tools.diagnosis_tools import (
    DiagnoseIndicatorIssueInput,
    DiagnosisToolServices,
    build_diagnosis_tools,
    diagnose_indicator_issue,
)
from app.agents.contracts import EffectiveRule, FieldMapping, PreparedRequest


def _context() -> AgentRuntimeContext:
    return AgentRuntimeContext(
        user_id="u1",
        hospital_id="h1",
        session_id="s1",
        user_role="implementer",
        permissions=frozenset({"indicator_read"}),
        request_id="r1",
        trace_id="t1",
        db_source_id="hospital_db",
    )


def _rule_state() -> AgentRunState:
    return AgentRunState(evidence=[{
        "source": "mysql",
        "source_id": "MQSI2025_005",
        "fact_types": ["rule_identity"],
    }])


class FakeDiagnosisOrchestrator:
    def __init__(self, result=None) -> None:
        self.prepare_calls = []
        self.diagnose_calls = []
        self.result = result or {
            "ok": True,
            "diagnose_status": "warning",
            "report_id": "DR_001",
            "summary": "发现字段映射风险。",
            "user_summary": "请实施人员核对到位时间字段。",
            "evidence": {
                "raw_text": "SELECT index_value FROM safe_view",
            },
            "execution_results": {
                "rows": [{"patient_name": "不应返回"}],
                "connection": "db.internal",
            },
            "trace_events": [{"prompt": "internal prompt"}],
            "layers": [{
                "layer": 1,
                "layer_name": "结构适配校验",
                "ok": False,
                "checks": [{
                    "status": "warn",
                    "message": "到位时间字段待核对",
                    "repair_suggest": "进入字段映射工作台确认",
                    "sql_text": "SELECT secret",
                    "rows": [{"patient_name": "不应返回"}],
                }],
            }],
        }

    def prepare_rule_request(self, **kwargs):
        self.prepare_calls.append(kwargs)
        return PreparedRequest(
            query=kwargs["query"],
            hospital_id=kwargs["hospital_id"],
            intent=kwargs["intent"],
            rule_id=kwargs["rule_id"],
            effective_rule=EffectiveRule(
                rule_id=kwargs["rule_id"],
                rule_name="急会诊及时到位率",
            ),
            field_mapping=FieldMapping(
                rule_id=kwargs["rule_id"],
                hospital_id=kwargs["hospital_id"],
                status="confirmed",
            ),
        )

    def diagnose(self, prepared, **kwargs):
        self.diagnose_calls.append({"prepared": prepared, **kwargs})
        return dict(self.result)


def test_diagnosis_reuses_orchestrator_and_returns_safe_projection() -> None:
    orchestrator = FakeDiagnosisOrchestrator()
    state = _rule_state()
    result = diagnose_indicator_issue(
        DiagnoseIndicatorIssueInput(
            rule_id="MQSI2025_005",
            issue_description="为什么本月结果下降？",
            pasted_sql="SELECT index_value FROM safe_view",
            declared_params={"threshold_minutes": 10},
            stat_period="2026-07-01~2026-07-31",
        ),
        _context(),
        state,
        services=DiagnosisToolServices(orchestrator=orchestrator),
    )

    assert result.ok
    assert result.code == "INDICATOR_DIAGNOSED"
    assert result.data["report_id"] == "DR_001"
    assert result.data["layers"][0]["checks"] == [{
        "status": "warn",
        "message": "到位时间字段待核对",
        "repair_suggest": "进入字段映射工作台确认",
    }]
    serialized = json.dumps(result.data, ensure_ascii=False)
    assert "SELECT index_value" not in serialized
    assert "SELECT secret" not in serialized
    assert "patient_name" not in serialized
    assert "db.internal" not in serialized
    assert "internal prompt" not in serialized
    assert state.last_diagnosis_id == "DR_001"
    assert any("diagnosis" in item.fact_types for item in result.evidence)

    query = orchestrator.prepare_calls[0]["query"]
    assert "为什么本月结果下降" in query
    assert "SELECT index_value FROM safe_view" in query
    assert '"threshold_minutes": 10' in query
    assert orchestrator.diagnose_calls[0]["trigger"] == "agent_tool"


def test_diagnosis_requires_matching_verified_rule() -> None:
    result = diagnose_indicator_issue(
        DiagnoseIndicatorIssueInput(
            rule_id="MQSI2025_005",
            issue_description="结果异常",
        ),
        _context(),
        AgentRunState(),
        services=DiagnosisToolServices(orchestrator=FakeDiagnosisOrchestrator()),
    )

    assert not result.ok
    assert result.code == "RULE_NOT_VERIFIED"


@pytest.mark.parametrize(
    "extra",
    [
        {"hospital_id": "other"},
        {"connection": "db.internal"},
        {"skip_validation": True},
    ],
)
def test_diagnosis_input_forbids_tenant_connection_and_bypass_fields(extra) -> None:
    with pytest.raises(ValidationError):
        DiagnoseIndicatorIssueInput(
            rule_id="MQSI2025_005",
            issue_description="结果异常",
            **extra,
        )


def test_diagnosis_input_rejects_sensitive_declared_parameter_names() -> None:
    with pytest.raises(ValidationError):
        DiagnoseIndicatorIssueInput(
            rule_id="MQSI2025_005",
            issue_description="结果异常",
            declared_params={"password": "secret"},
        )

    with pytest.raises(ValidationError):
        DiagnoseIndicatorIssueInput(
            rule_id="MQSI2025_005",
            issue_description="结果异常",
            declared_params={"auth": {"access_token": "secret"}},
        )


def test_diagnosis_input_limits_pasted_sql_length() -> None:
    with pytest.raises(ValidationError):
        DiagnoseIndicatorIssueInput(
            rule_id="MQSI2025_005",
            issue_description="结果异常",
            pasted_sql="S" * 20_001,
        )


def test_diagnosis_without_report_id_keeps_state_empty() -> None:
    orchestrator = FakeDiagnosisOrchestrator({
        "ok": True,
        "diagnose_status": "success",
        "summary": "未发现异常。",
        "layers": [],
    })
    state = _rule_state()

    result = diagnose_indicator_issue(
        DiagnoseIndicatorIssueInput(
            rule_id="MQSI2025_005",
            issue_description="检查当前指标",
        ),
        _context(),
        state,
        services=DiagnosisToolServices(orchestrator=orchestrator),
    )

    assert result.ok
    assert result.data["report_id"] is None
    assert state.last_diagnosis_id is None


def test_diagnosis_service_failure_returns_fixed_summary_without_evidence() -> None:
    orchestrator = FakeDiagnosisOrchestrator({
        "ok": False,
        "diagnose_status": "failed",
        "summary": "password=secret connection=db.internal",
        "layers": [],
    })

    result = diagnose_indicator_issue(
        DiagnoseIndicatorIssueInput(
            rule_id="MQSI2025_005",
            issue_description="检查当前指标",
        ),
        _context(),
        _rule_state(),
        services=DiagnosisToolServices(orchestrator=orchestrator),
    )

    assert not result.ok
    assert result.code == "DIAGNOSIS_FAILED"
    assert "secret" not in result.summary
    assert "internal" not in result.summary
    assert result.evidence == []


def test_diagnosis_tool_is_visible_only_after_rule_verification() -> None:
    tool = build_diagnosis_tools(
        DiagnosisToolServices(orchestrator=FakeDiagnosisOrchestrator())
    )[0]

    assert tool.availability(_context(), AgentRunState()) is False
    assert tool.availability(_context(), _rule_state()) is True
