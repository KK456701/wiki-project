from sqlalchemy import create_engine

from app.agent_runtime import AgentRunState, AgentRuntimeContext
from app.agent_tools.catalog import build_agent_tool_registry
from app.agent_tools.diagnosis_tools import DiagnosisToolServices
from app.agent_tools.preview_tools import PreviewToolServices
from app.agent_tools.read_tools import ReadToolServices
from app.agent_tools.sql_objects import AgentSqlObjectStore, ensure_agent_sql_object_schema
from app.agent_tools.sql_tools import SqlToolServices


class EmptyServices:
    pass


def _context(user_role="implementer"):
    return AgentRuntimeContext(
        user_id="u1",
        hospital_id="h1",
        session_id="s1",
        user_role=user_role,
        permissions=frozenset({"indicator_read"}),
        request_id="r1",
        trace_id="t1",
        db_source_id="hospital_db",
    )


def _state(*, active_sql=False):
    state = AgentRunState(evidence=[{
        "source_id": "MQSI2025_005",
        "fact_types": ["rule_identity"],
    }])
    if active_sql:
        state.validated_sql_ids.append("SQL_001")
    return state


def _registry():
    engine = create_engine("sqlite+pysqlite:///:memory:")
    ensure_agent_sql_object_schema(engine)
    empty = EmptyServices()
    return build_agent_tool_registry(
        read_services=ReadToolServices(caliber=empty),
        sql_services=SqlToolServices(
            orchestrator=empty,
            store=AgentSqlObjectStore(engine),
            runtime_engine=engine,
            business_db=empty,
        ),
        diagnosis_services=DiagnosisToolServices(orchestrator=empty),
        preview_services=PreviewToolServices(orchestrator=empty),
    )


def _names(state, user_role="implementer"):
    return [
        tool.name
        for tool in _registry().list_for_context(_context(user_role), state)
    ]


def test_catalog_registers_eight_tools_and_initially_shows_search_and_draft() -> None:
    registry = _registry()

    assert len(registry.all()) == 8
    assert _names(AgentRunState()) == [
        "search_indicator_rules",
        "create_indicator_draft",
    ]


def test_catalog_shows_six_rule_tools_before_sql_preparation() -> None:
    assert _names(_state()) == [
        "search_indicator_rules",
        "get_effective_rule",
        "inspect_indicator_implementation",
        "prepare_indicator_sql",
        "diagnose_indicator_issue",
        "preview_rule_change",
    ]


def test_catalog_replaces_prepare_with_trial_for_active_sql() -> None:
    names = _names(_state(active_sql=True))

    assert names == [
        "search_indicator_rules",
        "get_effective_rule",
        "inspect_indicator_implementation",
        "trial_run_indicator_sql",
        "diagnose_indicator_issue",
        "preview_rule_change",
    ]
    assert len(names) <= 6


def test_doctor_never_sees_preview_only_tools() -> None:
    assert "create_indicator_draft" not in _names(AgentRunState(), "doctor")
    assert "preview_rule_change" not in _names(_state(), "doctor")
