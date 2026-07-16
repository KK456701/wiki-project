from app.agent_runtime import AgentRunState
from app.agent_tools.state_facts import (
    has_active_sql,
    has_verified_rule,
    verified_rule_ids,
)


def test_verified_rule_ids_ignore_failed_result_and_its_evidence() -> None:
    state = AgentRunState(last_tool_results=[{
        "ok": False,
        "data": {"rule_id": "BAD_DATA"},
        "evidence": [{
            "source_id": "BAD_EVIDENCE",
            "fact_types": ["rule_identity"],
        }],
    }])

    assert verified_rule_ids(state) == set()
    assert not has_verified_rule(state)


def test_verified_rule_ids_accept_successful_search_and_rule_data() -> None:
    state = AgentRunState(last_tool_results=[
        {
            "ok": True,
            "data": {"resolved_rule_id": "MQSI2025_005"},
            "evidence": [],
        },
        {
            "ok": True,
            "data": {"rule_id": "MQSI2025_001"},
            "evidence": [],
        },
    ])

    assert verified_rule_ids(state) == {"MQSI2025_001", "MQSI2025_005"}
    assert has_verified_rule(state, "MQSI2025_005")
    assert not has_verified_rule(state, "MQSI2025_014")


def test_verified_rule_ids_accept_direct_evidence_and_deduplicate() -> None:
    evidence = {
        "source": "mysql",
        "source_id": "MQSI2025_005",
        "fact_types": ["rule_identity"],
    }
    state = AgentRunState(evidence=[evidence, dict(evidence)])

    assert verified_rule_ids(state) == {"MQSI2025_005"}


def test_verified_rule_ids_ignore_empty_or_unrelated_evidence() -> None:
    state = AgentRunState(evidence=[
        {"source_id": "", "fact_types": ["rule_identity"]},
        {"source_id": "MQSI2025_005", "fact_types": ["formula"]},
    ])

    assert verified_rule_ids(state) == set()


def test_state_tracks_only_explicitly_validated_sql_ids() -> None:
    state = AgentRunState(validated_sql_ids=["SQL_001"])

    assert has_active_sql(state)
    assert has_active_sql(state, "SQL_001")
    assert not has_active_sql(state, "SQL_002")


def test_empty_state_has_no_active_sql() -> None:
    assert not has_active_sql(AgentRunState())
