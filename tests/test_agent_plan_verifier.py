from __future__ import annotations

from app.agent_planning import PlanCompiler, RequestPlan
from app.agent_planning.verifier import EvidenceEnvelope, PlanVerifier
from app.agent_planning.time_resolver import ResolvedTimeRange
from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext


def _plan():
    return PlanCompiler().compile(RequestPlan.model_validate({
        "intent": "indicator_trial_run",
        "goal": "查询实际结果",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "time_expression": {
            "raw_text": "本月",
            "start_time": "2026-07-01T00:00:00+08:00",
            "end_time": "2026-07-17T00:00:00+08:00",
        },
        "requested_outputs": ["trial_result"],
    }))


def _context():
    return AgentRuntimeContext(
        user_id="u1",
        hospital_id="hospital_001",
        session_id="s1",
        user_role="implementer",
        permissions=frozenset({"indicator_read"}),
        request_id="req1",
        trace_id="trace1",
        db_source_id="db1",
    )


def _state(*, rate=90.0, trial_sql_id="SQL_1", denominator=20):
    facts = [
        {"source": "rules", "source_id": "RULE_1", "fact_types": ["rule_identity"]},
        {"source": "rules", "source_id": "RULE_1", "fact_types": ["definition", "formula"]},
        {"source": "sql", "source_id": "SQL_1", "fact_types": ["sql_validation"]},
        {"source": "db", "source_id": "RUN_1", "fact_types": ["trial_run"]},
    ]
    return AgentRunState(
        current_rule_id="RULE_1",
        validated_sql_ids=["SQL_1"],
        evidence=facts,
        last_tool_results=[
            {
                "ok": True,
                "status": "success",
                "code": "SQL_OBJECT_PREPARED",
                "data": {
                    "sql_id": "SQL_1",
                    "rule_id": "RULE_1",
                    "hospital_id": "hospital_001",
                    "db_source_id": "db1",
                    "stat_start": "2026-07-01 00:00:00",
                    "stat_end": "2026-07-17 00:00:00",
                    "context_digest": "digest1",
                },
            },
            {
                "ok": True,
                "status": "success",
                "code": "TRIAL_RUN_COMPLETED",
                "data": {
                    "sql_id": trial_sql_id,
                    "run_id": "RUN_1",
                    "rule_id": "RULE_1",
                    "hospital_id": "hospital_001",
                    "db_source_id": "db1",
                    "stat_start": "2026-07-01 00:00:00",
                    "stat_end": "2026-07-17 00:00:00",
                    "context_digest": "digest1",
                    "numerator_count": 18,
                    "denominator_count": denominator,
                    "result_value": rate,
                },
            },
        ],
    )


def test_evidence_envelope_requires_tenant_and_trace_provenance():
    envelope = EvidenceEnvelope(
        evidence_id="EV_1",
        trace_id="trace1",
        subtask_id="req1",
        fact_type="trial_run",
        hospital_id="hospital_001",
        rule_id="RULE_1",
        source_tool="trial_run_indicator_sql",
        source_object_id="RUN_1",
        input_fingerprint="a" * 64,
        result_fingerprint="b" * 64,
        safe_payload={"sql_id": "SQL_1", "run_id": "RUN_1"},
    )

    assert envelope.hospital_id == "hospital_001"
    assert envelope.trace_id == "trace1"
    assert envelope.safe_payload["sql_id"] == "SQL_1"


def test_missing_required_fact_blocks_completion():
    state = _state()
    state.evidence = [item for item in state.evidence if "trial_run" not in item["fact_types"]]

    result = PlanVerifier().verify(_plan(), state, _context())

    assert result.ok is False
    assert result.code == "REQUIRED_FACTS_MISSING"
    assert "trial_run" in result.missing_facts


def test_numeric_mismatch_blocks_completion():
    result = PlanVerifier().verify(_plan(), _state(rate=80.0), _context())

    assert result.ok is False
    assert result.code == "NUMERIC_RESULT_INCONSISTENT"


def test_zero_denominator_requires_empty_result():
    result = PlanVerifier().verify(_plan(), _state(rate=90.0, denominator=0), _context())

    assert result.ok is False
    assert result.code == "NUMERIC_RESULT_INCONSISTENT"


def test_sql_chain_mismatch_blocks_completion():
    result = PlanVerifier().verify(
        _plan(),
        _state(trial_sql_id="SQL_OLD"),
        _context(),
    )

    assert result.ok is False
    assert result.code == "SQL_CHAIN_INCONSISTENT"


def test_sql_period_must_match_requested_period():
    expected = ResolvedTimeRange(
        start_time="2026-06-01T00:00:00+08:00",
        end_time="2026-07-01T00:00:00+08:00",
        source_text="六月",
    )

    result = PlanVerifier().verify(
        _plan(),
        _state(),
        _context(),
        expected_time=expected,
    )

    assert result.ok is False
    assert result.code == "SQL_PERIOD_INCONSISTENT"


def test_consistent_trial_result_passes_verification():
    result = PlanVerifier().verify(_plan(), _state(), _context())

    assert result.ok is True
    assert result.code == "PLAN_VERIFIED"


def test_upload_analysis_legacy_evidence_satisfies_file_analysis_fact():
    plan = PlanCompiler().compile(RequestPlan.model_validate({
        "intent": "upload_analysis",
        "goal": "分析刚上传的指标文件",
        "requested_outputs": ["file_analysis"],
    }))
    state = AgentRunState(evidence=[{
        "source": "uploaded_excel",
        "source_id": "hospital_001_report.xlsx",
        "fact_types": ["upload_analysis"],
    }])

    result = PlanVerifier().verify(plan, state, _context())

    assert result.ok is True
    assert result.code == "PLAN_VERIFIED"
