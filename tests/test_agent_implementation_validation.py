from datetime import datetime

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import ToolEvidence, ToolResult
from app.implementation_validation import (
    ImplementationValidationServices,
    ImplementationValidationWorkflow,
    ValidationStageStatus,
)


def _context():
    return AgentRuntimeContext(
        user_id="u1",
        hospital_id="hospital_001",
        session_id="s1",
        user_role="implementer",
        permissions=frozenset({"indicator_read", "indicator_detail_view"}),
        request_id="r1",
        trace_id="t1",
        db_source_id="hospital_db",
    )


def _state():
    return AgentRunState(
        current_rule_id="RULE_1",
        evidence=[{
            "source": "rules",
            "source_id": "RULE_1",
            "fact_types": ["rule_identity"],
        }],
        last_tool_results=[
            ToolResult(
                ok=True,
                status="success",
                code="EFFECTIVE_RULE_FOUND",
                summary="ok",
                data={
                    "rule_id": "RULE_1",
                    "rule_name": "测试指标",
                    "definition": "定义",
                    "formula": "分子/分母",
                    "effective_level": "hospital",
                    "national_version": "2025",
                    "hospital_version": 1,
                },
                evidence=[ToolEvidence(
                    source="rules",
                    source_id="RULE_1",
                    fact_types=["effective_rule"],
                )],
            ).model_dump(mode="json"),
            ToolResult(
                ok=True,
                status="success",
                code="IMPLEMENTATION_INSPECTED",
                summary="ok",
                data={
                    "rule_id": "RULE_1",
                    "status": "confirmed",
                    "main_table": "main_table",
                    "dialect": "mysql",
                    "required_business_fields": ["id", "event_time"],
                    "mapped_fields": ["id", "event_time"],
                    "missing_mappings": [],
                    "unconfirmed_mappings": [],
                },
                evidence=[ToolEvidence(
                    source="mapping",
                    source_id="RULE_1",
                    fact_types=["implementation_status", "field_mapping"],
                )],
            ).model_dump(mode="json"),
        ],
    )


def _services(*, upload_services=None, trace_callback=None):
    return ImplementationValidationServices(
        sql_services=object(),
        upload_services=upload_services,
        trace_callback=trace_callback,
    )


def test_workflow_runs_l1_l4_l5_and_skips_optional_l6(monkeypatch):
    def fake_prepare(arguments, context, state, services):
        del arguments, context, services
        state.validated_sql_ids.append("SQL_1")
        return ToolResult(
            ok=True,
            status="success",
            code="SQL_OBJECT_PREPARED",
            summary="prepared",
            data={
                "sql_id": "SQL_1",
                "rule_id": "RULE_1",
                "hospital_id": "hospital_001",
                "db_source_id": "hospital_db",
                "context_digest": "digest",
                "validation_status": "validated",
                "stat_start": "2026-01-01 00:00:00",
                "stat_end": "2026-04-01 00:00:00",
            },
            evidence=[ToolEvidence(
                source="sql",
                source_id="SQL_1",
                fact_types=["sql_validation"],
            )],
        )

    def fake_trial(arguments, context, state, services):
        del arguments, context, state, services
        return ToolResult(
            ok=True,
            status="success",
            code="TRIAL_RUN_COMPLETED",
            summary="trial",
            data={
                "sql_id": "SQL_1",
                "run_id": "RUN_1",
                "rule_id": "RULE_1",
                "hospital_id": "hospital_001",
                "db_source_id": "hospital_db",
                "context_digest": "digest",
                "status": "success",
                "stat_start": "2026-01-01 00:00:00",
                "stat_end": "2026-04-01 00:00:00",
                "numerator_count": 8,
                "denominator_count": 122,
                "result_value": 6.56,
            },
            evidence=[ToolEvidence(
                source="db",
                source_id="RUN_1",
                fact_types=["trial_run", "aggregate_result"],
            )],
        )

    monkeypatch.setattr(
        "app.implementation_validation.workflow.prepare_indicator_sql",
        fake_prepare,
    )
    monkeypatch.setattr(
        "app.implementation_validation.workflow.trial_run_indicator_sql",
        fake_trial,
    )
    state = _state()

    report = ImplementationValidationWorkflow(_services()).run(
        rule_id="RULE_1",
        stat_start_time=datetime(2026, 1, 1),
        stat_end_time=datetime(2026, 4, 1),
        file_key=None,
        context=_context(),
        state=state,
    )

    assert report.overall_status is ValidationStageStatus.PASSED
    assert [stage.status for stage in report.stages] == [
        ValidationStageStatus.PASSED,
        ValidationStageStatus.PASSED,
        ValidationStageStatus.PASSED,
        ValidationStageStatus.SKIPPED,
    ]
    assert report.sql_id == "SQL_1"
    assert report.run_id == "RUN_1"
    assert report.result_value == 6.56


def test_workflow_reports_business_failure_without_appending_failed_tool(monkeypatch):
    failed = ToolResult(
        ok=False,
        status="validation_failed",
        code="FIELD_PRECHECK_FAILED",
        summary="字段预检失败",
    )
    monkeypatch.setattr(
        "app.implementation_validation.workflow.prepare_indicator_sql",
        lambda *args, **kwargs: failed,
    )
    state = _state()
    initial_count = len(state.last_tool_results)

    report = ImplementationValidationWorkflow(_services()).run(
        rule_id="RULE_1",
        stat_start_time=datetime(2026, 1, 1),
        stat_end_time=datetime(2026, 4, 1),
        file_key=None,
        context=_context(),
        state=state,
    )

    assert report.overall_status is ValidationStageStatus.FAILED
    assert report.stages[2].finding_codes == ["FIELD_PRECHECK_FAILED"]
    assert len(state.last_tool_results) == initial_count


def test_optional_l6_reports_row_differences_and_trace(monkeypatch):
    monkeypatch.setattr(
        "app.implementation_validation.workflow.analyze_uploaded_indicators",
        lambda *args, **kwargs: ToolResult(
            ok=True,
            status="success",
            code="UPLOAD_ANALYZED",
            summary="compared",
            data={
                "file_name": "report.xlsx",
                "total_rows": 10,
                "row_comparison": {
                    "comparison_status": "row_level_compared",
                    "both_count": 8,
                    "system_only_count": 1,
                    "uploaded_only_count": 1,
                    "field_difference_count": 0,
                },
            },
            evidence=[ToolEvidence(
                source="upload",
                source_id="hospital_001_report.xlsx",
                fact_types=["file_analysis"],
            )],
        ),
    )
    events = []
    workflow = ImplementationValidationWorkflow(_services(
        upload_services=object(),
        trace_callback=events.append,
    ))
    state = _state()

    stage = workflow._stage_l6(
        file_key="hospital_001_report.xlsx",
        context=_context(),
        state=state,
    )

    assert stage.status is ValidationStageStatus.WARNING
    assert stage.finding_codes == ["ROW_COMPARISON_DIFFERENCES"]
    assert stage.safe_details["both_count"] == 8
    assert events[0]["node_name"] == "implementation_validation_l6"
    assert events[0]["status"] == "warning"
