import unittest

from sqlalchemy import create_engine

from app.agent_runtime import AgentRunState, AgentRuntimeContext
from app.agent_tools import ToolGateway
from app.agent_tools.catalog import build_agent_tool_registry
from app.agent_tools.diagnosis_tools import DiagnosisToolServices
from app.agent_tools.preview_tools import PreviewToolServices
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
        preview_services=PreviewToolServices(orchestrator=empty),
    )


def _names(registry, state, context=None):
    return [
        tool.name
        for tool in registry.list_for_context(context or _context(), state)
    ]


def _tool_description(name: str) -> str:
    return _registry().get(name).description


def test_catalog_initially_exposes_search_and_draft() -> None:
    assert _names(_registry(), AgentRunState()) == [
        "search_indicator_rules",
        "create_indicator_draft",
    ]


def test_catalog_exposes_rule_bound_tools_after_rule_verification() -> None:
    assert _names(_registry(), _rule_state()) == [
        "search_indicator_rules",
        "get_effective_rule",
        "inspect_indicator_implementation",
        "prepare_indicator_sql",
        "diagnose_indicator_issue",
        "preview_rule_change",
    ]


def test_catalog_adds_trial_only_for_active_sql_and_never_exceeds_six() -> None:
    state = _rule_state()
    state.validated_sql_ids.append("SQL_001")

    names = _names(_registry(), state)

    assert names == [
        "search_indicator_rules",
        "get_effective_rule",
        "inspect_indicator_implementation",
        "trial_run_indicator_sql",
        "diagnose_indicator_issue",
        "preview_rule_change",
    ]
    assert len(names) == 6


def test_catalog_hides_all_tools_without_indicator_permission() -> None:
    assert _names(
        _registry(),
        _rule_state(),
        _context(permissions=frozenset()),
    ) == []


def test_tool_descriptions_explain_call_and_do_not_call_boundaries() -> None:
    diagnosis = _tool_description("diagnose_indicator_issue")
    assert "仅当用户明确要求排查异常、诊断原因、解释结果不一致或算不对时调用" in diagnosis
    assert "不要用于普通公式解释、统计周期变更、结果试运行、SQL 生成" in diagnosis
    assert "从某日期开始怎么算" in diagnosis

    prepare_sql = _tool_description("prepare_indicator_sql")
    assert "当用户询问某指标在某统计周期的结果、多少、从某日期到某日期怎么算时调用" in prepare_sql
    assert "调用前必须已确认指标规则" in prepare_sql


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
