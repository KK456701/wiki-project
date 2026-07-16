import json
import unittest

from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool

from app.agent_runtime import (
    AgentModelResponse,
    AgentRunState,
    AgentRuntimeContext,
    AgentToolCall,
)
from app.agent_runtime.runner import AgentRunner
from app.agent_tools import ToolGateway
from app.agent_tools.catalog import build_agent_tool_registry
from app.agent_tools.diagnosis_tools import DiagnosisToolServices
from app.agent_tools.preview_tools import PreviewToolServices
from app.agent_tools.read_tools import ReadToolServices
from app.agent_tools.sql_objects import AgentSqlObjectStore, ensure_agent_sql_object_schema
from app.agent_tools.sql_tools import SqlToolServices
from app.agents.contracts import RuleSearchResult


class SequenceAdapter:
    def __init__(self, responses):
        self.responses = list(responses)

    async def chat(self, **kwargs):
        del kwargs
        return self.responses.pop(0)


class FakePreviewDomain:
    def create_indicator_draft(self, description, hospital_id, actor_id):
        return {
            "draft_id": "DRAFT_001",
            "status": "requirements_pending",
            "current_version": 1,
            "index_name": "夜间急会诊15分钟到位率",
            "index_desc": description,
            "stat_cycle": "month",
            "numerator_rule": "15分钟内到位例数",
            "denominator_rule": "夜间急会诊总例数",
            "filter_rule": "仅统计夜间急会诊",
            "exclude_rule": "",
            "metric_type": "ratio",
            "metadata_requirements": ["arrival_time"],
            "current_sql": "SELECT patient_name FROM patient",
            "sql_plan": {"main_table": "patient"},
        }

    def search_for_hospital_contract(self, query, hospital_id, limit=5):
        return RuleSearchResult(
            query=query,
            resolved_rule_id="MQSI2025_005",
            matches=[{
                "rule_id": "MQSI2025_005",
                "rule_name": "急会诊及时到位率",
            }],
            rule_source="fixture",
        )

    def prepare_rule_request(self, **kwargs):
        return dict(kwargs)

    def preview_feedback(self, prepared):
        del prepared
        return {
            "rule_id": "MQSI2025_005",
            "rule_name": "急会诊及时到位率",
            "target_level": "hospital",
            "current_effective_level": "national",
            "requested": {
                "level": "hospital",
                "formula": "15分钟内到位例数 / 急会诊总例数 × 100%",
            },
            "current_effective": {
                "level": "national",
                "formula": "10分钟内到位例数 / 急会诊总例数 × 100%",
            },
            "field_changes": [{
                "field": "计算公式",
                "requested": "15分钟口径",
                "current": "10分钟口径",
                "changed": True,
            }],
            "message": "检测到计算公式变化。",
            "change_id": "CR_MUST_NOT_RETURN",
            "status": "pending",
        }


def _context():
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


def _runner(responses, max_steps=5):
    domain = FakePreviewDomain()
    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    ensure_agent_sql_object_schema(engine)
    registry = build_agent_tool_registry(
        read_services=ReadToolServices(caliber=domain),
        sql_services=SqlToolServices(
            orchestrator=domain,
            store=AgentSqlObjectStore(engine),
            runtime_engine=engine,
            business_db=object(),
        ),
        diagnosis_services=DiagnosisToolServices(orchestrator=domain),
        preview_services=PreviewToolServices(orchestrator=domain),
    )
    return AgentRunner(
        SequenceAdapter(responses),
        registry,
        ToolGateway(registry),
        max_steps=max_steps,
    )


class AgentPreviewLoopTest(unittest.IsolatedAsyncioTestCase):
    async def test_create_draft_and_answer_closed_loop(self) -> None:
        runner = _runner([
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="create_indicator_draft",
                arguments={
                    "description": "创建夜间急会诊15分钟到位率指标草稿",
                },
            )]),
            AgentModelResponse(
                content="已创建指标草稿 DRAFT_001，尚未提交审批。"
            ),
        ])

        result = await runner.run("帮我设计夜间急会诊指标", _context())

        self.assertEqual(result.stop_reason, "final_answer")
        self.assertEqual(result.state.last_draft_id, "DRAFT_001")
        observations = json.dumps(result.state.messages, ensure_ascii=False)
        self.assertNotIn("SELECT patient_name", observations)
        self.assertNotIn("sql_plan", observations)

    async def test_search_preview_and_answer_closed_loop(self) -> None:
        runner = _runner([
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="search_indicator_rules",
                arguments={"query": "急会诊及时到位率"},
            )]),
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="preview_rule_change",
                arguments={
                    "rule_id": "MQSI2025_005",
                    "change_description": "本院按15分钟内到位计算",
                },
            )]),
            AgentModelResponse(content=(
                "已生成本院口径变更预览，计算公式将变化，需要重新生成 SQL；"
                "尚未提交审批。"
            )),
        ])

        result = await runner.run("本院想改成15分钟，看看影响", _context())

        self.assertEqual(result.stop_reason, "final_answer")
        observations = json.dumps(result.state.messages, ensure_ascii=False)
        self.assertNotIn("CR_MUST_NOT_RETURN", observations)
        self.assertNotIn('"status": "pending"', observations)

    async def test_unbacked_draft_claim_is_rejected(self) -> None:
        runner = _runner([
            AgentModelResponse(content="已创建指标草稿 DRAFT_FAKE。"),
        ], max_steps=1)
        state = AgentRunState(evidence=[{
            "source_id": "MQSI2025_005",
            "fact_types": ["rule_identity"],
        }])

        result = await runner.run("创建指标", _context(), state)

        self.assertEqual(result.stop_reason, "max_steps")
        self.assertTrue(any(
            "指标工作草稿" in message.get("content", "")
            for message in result.state.messages
        ))

    async def test_formal_change_claim_is_always_rejected(self) -> None:
        runner = _runner([
            AgentModelResponse(content="已提交审批并发布本院版本。"),
        ], max_steps=1)
        state = AgentRunState(evidence=[{
            "source_id": "MQSI2025_005",
            "fact_types": ["rule_identity", "rule_change_preview"],
        }])

        result = await runner.run("发布本院版本", _context(), state)

        self.assertEqual(result.stop_reason, "max_steps")
        self.assertTrue(any(
            "正式提交、审批、发布或回退授权" in message.get("content", "")
            for message in result.state.messages
        ))
