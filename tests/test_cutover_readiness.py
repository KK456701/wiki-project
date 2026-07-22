from scripts.cutover_readiness import (
    ReadinessRunner,
    build_report,
    canonical_concepts,
    canonical_drafts,
    canonical_plan_ids,
    safe_agent_result,
)


def test_safe_agent_result_rejects_protocol_leak() -> None:
    result = safe_agent_result(
        {
            "answer": '<｜｜DSML｜｜tool_calls>',
            "stop_reason": "final_answer",
            "trace_id": "TRACE_1",
            "session_id": "SESSION_1",
            "step_count": 2,
        }
    )
    assert result["contract_ok"] is True
    assert result["protocol_clean"] is False


def test_canonical_workbench_values_ignore_order_and_extra_fields() -> None:
    drafts = canonical_drafts([
        {"draft_id": "D2", "status": "published", "current_version": 2, "secret": "x"},
        {"draft_id": "D1", "status": "sql_ready", "current_version": 1},
    ])
    assert [item["draft_id"] for item in drafts] == ["D1", "D2"]
    assert canonical_concepts({"items": [{"concept_code": "B"}, {"concept_code": "A"}]}) == ["A", "B"]
    assert canonical_plan_ids({"items": [{"plan_id": "P2"}, {"plan_id": "P1"}]}) == ["P1", "P2"]


def test_report_requires_zero_failures_and_zero_skips() -> None:
    runner = ReadinessRunner(
        python_url="http://python", java_url="http://java", hospital_id="hospital_001",
        hospital_token="secret", python_admin_token="python-secret",
        java_admin_token="java-secret",
        model_id=None, suite={}, include_agent=False,
    )
    report = build_report(
        runner,
        [
            {"check_id": "a", "status": "passed"},
            {"check_id": "b", "status": "skipped"},
        ],
        suite_version="v1",
        allow_skips=False,
    )
    assert report["status"] == "not_ready"
    assert "secret" not in str(report)
