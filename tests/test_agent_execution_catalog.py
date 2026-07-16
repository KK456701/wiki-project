import unittest

from sqlalchemy import create_engine

from app.agent_runtime import AgentRunState, AgentRuntimeContext
from app.agent_tools import ToolGateway
from app.agent_tools.catalog import build_agent_tool_registry
from app.agent_tools.diagnosis_tools import DiagnosisToolServices
from app.agent_tools.read_tools import ReadToolServices
from app.agent_tools.sql_objects import AgentSqlObjectStore, ensure_agent_sql_object_schema
from app.agent_tools.sql_tools import SqlToolServices


class EmptyServices:
    pass


def _context(permissions=frozenset({"indicator_read"})):
    return AgentRuntimeContext(
        user_id="u1",
        hospital_id="h1",
        session_id="s1",
        user_role="implementer",
        permissions=permissions,
        request_id="r1",
        trace_id="t1",
        db_source_id="hospital_db",
    )


def _rule_state():
    return AgentRunState(evidence=[{
        "source": "mysql",
        "source_id": "MQSI2025_005",
        "fact_types": ["rule_identity"],
    }])


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
    )


def _names(registry, state, context=None):
    return [
        tool.name
        for tool in registry.list_for_context(context or _context(), state)
    ]


def test_catalog_initially_exposes_only_search() -> None:
    assert _names(_registry(), AgentRunState()) == ["search_indicator_rules"]


def test_catalog_exposes_rule_bound_tools_after_rule_verification() -> None:
    assert _names(_registry(), _rule_state()) == [
        "search_indicator_rules",
        "get_effective_rule",
        "inspect_indicator_implementation",
        "prepare_indicator_sql",
        "diagnose_indicator_issue",
    ]


def test_catalog_adds_trial_only_for_active_sql_and_never_exceeds_six() -> None:
    state = _rule_state()
    state.validated_sql_ids.append("SQL_001")

    names = _names(_registry(), state)

    assert names == [
        "search_indicator_rules",
        "get_effective_rule",
        "inspect_indicator_implementation",
        "prepare_indicator_sql",
        "trial_run_indicator_sql",
        "diagnose_indicator_issue",
    ]
    assert len(names) == 6


def test_catalog_hides_all_tools_without_indicator_permission() -> None:
    assert _names(
        _registry(),
        _rule_state(),
        _context(permissions=frozenset()),
    ) == []


class ExecutionCatalogGatewayTest(unittest.IsolatedAsyncioTestCase):
    async def test_gateway_rechecks_unavailable_tool(self) -> None:
        registry = _registry()
        result = await ToolGateway(registry).execute(
            "prepare_indicator_sql",
            {
                "rule_id": "MQSI2025_005",
                "stat_start_time": "2026-07-01T00:00:00",
                "stat_end_time": "2026-08-01T00:00:00",
            },
            _context(),
            AgentRunState(),
        )

        self.assertFalse(result.ok)
        self.assertEqual(result.code, "TOOL_UNAVAILABLE")
