import json
import unittest
from datetime import datetime, timezone

from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool

from app.agent_runtime import (
    AgentModelResponse,
    AgentRunState,
    AgentRuntimeContext,
    AgentToolCall,
)
from app.agent_runtime.runner import (
    AgentRunner,
    _append_trial_detail_export,
    _compose_upload_comparison_answer,
)
from app.agent_runtime.response_guard import normalize_agent_answer
from app.agent_tools import ToolGateway
from app.agent_tools.catalog import build_agent_tool_registry
from app.agent_tools.diagnosis_tools import DiagnosisToolServices
from app.agent_tools.preview_tools import PreviewToolServices
from app.agent_tools.read_tools import ReadToolServices
from app.agent_tools.sql_objects import AgentSqlObjectStore, ensure_agent_sql_object_schema
from app.agent_tools.sql_tools import SqlToolServices
from app.agents.contracts import EffectiveRule, FieldMapping, PreparedRequest, RuleSearchResult


NOW = datetime(2026, 7, 16, 2, 0, tzinfo=timezone.utc)


class SequenceAdapter:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    async def chat(self, **kwargs):
        self.calls.append(kwargs)
        return self.responses.pop(0)


def test_upload_comparison_export_replaces_regular_detail_export() -> None:
    answer = _append_trial_detail_export(
        "对比完成。{{detail_export:RUN_OLD}}",
        [
            {
                "ok": True,
                "code": "TRIAL_RUN_COMPLETED",
                "data": {"run_id": "RUN_001", "status": "success"},
            },
            {
                "ok": True,
                "code": "UPLOAD_ANALYZED",
                "data": {
                    "file_key": "hospital_001_无标题.xlsx",
                    "aggregate_comparison": {"different_count": 3},
                },
            },
        ],
    )

    assert "upload_comparison_export:RUN_001:" in answer
    assert "导出文件与系统的汇总差异表" in answer
    assert "detail_export" not in answer


def test_upload_aggregate_comparison_does_not_guess_causes() -> None:
    answer = _compose_upload_comparison_answer([{
        "ok": True,
        "code": "UPLOAD_ANALYZED",
        "data": {
            "sheets": [{"headers": ["denominator", "numerator", "rate_pct"]}],
            "aggregate_comparison": {
                "metrics": [
                    {"metric": "分母", "system_value": 389, "uploaded_value": 522, "difference": 133, "unit": "人次", "match": False},
                    {"metric": "分子", "system_value": 11, "uploaded_value": 30, "difference": 19, "unit": "人次", "match": False},
                    {"metric": "指标率", "system_value": 2.83, "uploaded_value": 5.75, "difference": 2.92, "unit": "百分点", "match": False},
                ],
                "cause_analysis_note": "当前文件没有逐条业务记录，不能确认具体原因。",
                "required_fields_for_cause_analysis": ["admission_id", "from_dept_id", "to_dept_id"],
            },
        },
    }])

    assert "分母 | 389人次 | 522人次 | 133人次 | 不一致" in answer
    assert "分子 | 11人次 | 30人次 | 19人次 | 不一致" in answer
    assert "只能确认以上数值不同" in answer
    assert "不能确认具体原因" in answer
    assert "可能" not in answer
    assert "admission_id" in answer


class FakeDomainServices:
    def __init__(self) -> None:
        self.definition = "急会诊在规定时间内到位的比例。"
        self.trial_calls = []

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

    def resolve_contract(self, rule_id, hospital_id):
        return EffectiveRule.model_validate({
            "rule_id": rule_id,
            "rule_name": "急会诊及时到位率",
            "definition": self.definition,
            "formula": "及时到位例数 / 急会诊总例数 × 100%",
            "effective_level": "hospital",
            "national_version": "2025",
            "hospital_version": 2,
            "standard_sql": "SELECT protected_template",
            "calculation_definition": {"measure": "ratio"},
            "sql_status": "available",
            "rule_source": "fixture",
        })

    def field_mapping_contract(self, rule_id, hospital_id):
        return FieldMapping.model_validate({
            "rule_id": rule_id,
            "hospital_id": hospital_id,
            "db_name": "hospital_db",
            "main_table": "consult_record",
            "dialect": "sqlserver",
            "status": "confirmed",
            "fields": {"arrival_time": "arrival_time"},
        })

    def prepare_rule_request(self, **kwargs):
        return PreparedRequest(
            query=kwargs["query"],
            hospital_id=kwargs["hospital_id"],
            intent=kwargs["intent"],
            rule_id=kwargs["rule_id"],
            effective_rule=self.resolve_contract(
                kwargs["rule_id"], kwargs["hospital_id"]
            ),
            field_mapping=self.field_mapping_contract(
                kwargs["rule_id"], kwargs["hospital_id"]
            ),
        )

    def generate_indicator(self, prepared, **kwargs):
        return {
            "status": "success",
            "sql_id": "SQL_001",
            "sql_text": "SELECT 92.5 AS index_value",
            "sql_status": "validated",
            "validation": {"ok": True, "message": "只读校验通过"},
            "precheck": {"ok": True},
            "dialect": "sqlserver",
            "params": {"threshold_minutes": 10},
            "field_mapping": prepared.field_mapping.model_dump(by_alias=True),
            "calculation_definition": {"measure": "ratio"},
            "execution_context": {},
        }

    def run_trial(self, **kwargs):
        self.trial_calls.append(kwargs)
        return {
            "sql_id": "SQL_001",
            "run_id": "RUN_001",
            "status": "success",
            "result_value": 92.5,
            "numerator_count": 37,
            "denominator_count": 40,
            "no_sample": False,
            "duration_ms": 18,
            "source": "hospital_db",
        }

    def diagnose(self, prepared, **kwargs):
        return {
            "ok": True,
            "diagnose_status": "success",
            "report_id": "DR_001",
            "summary": "未发现异常。",
            "layers": [],
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


def _runner(adapter, domain=None, max_steps=8):
    domain = domain or FakeDomainServices()
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
            store=AgentSqlObjectStore(engine, now_provider=lambda: NOW),
            runtime_engine=engine,
            business_db=object(),
            now_provider=lambda: NOW,
            trial_executor=domain.run_trial,
            sql_validator=lambda *_: {"ok": True, "message": "只读校验通过"},
        ),
        diagnosis_services=DiagnosisToolServices(orchestrator=domain),
        preview_services=PreviewToolServices(orchestrator=domain),
    )
    return AgentRunner(adapter, registry, ToolGateway(registry), max_steps=max_steps), domain


class AgentExecutionLoopTest(unittest.IsolatedAsyncioTestCase):
    def test_normalize_agent_answer_converts_common_latex_ratio(self) -> None:
        answer = """计算公式：
$$
\\frac{\\text{及时次数}}{\\text{总次数}} \\times 100\\%
$$"""

        self.assertEqual(
            normalize_agent_answer(answer),
            "计算公式：\n及时次数 ÷ 总次数 × 100%",
        )

    def test_normalize_agent_answer_keeps_plain_markdown(self) -> None:
        answer = "### 计算口径\n及时次数 ÷ 总次数 × 100%"

        self.assertEqual(normalize_agent_answer(answer), answer)

    async def test_final_answer_is_normalized_before_returning(self) -> None:
        adapter = SequenceAdapter([AgentModelResponse(
            content="$$\\frac{\\text{及时次数}}{\\text{总次数}} \\times 100\\%$$",
        )])
        runner, _ = _runner(adapter, max_steps=1)
        state = AgentRunState(evidence=[{
            "source": "mysql",
            "source_id": "MQSI2025_005",
            "fact_types": ["rule_identity", "formula"],
        }])

        result = await runner.run("怎么算？", _context(), state)

        self.assertEqual(result.stop_reason, "final_answer")
        self.assertEqual(result.answer, "及时次数 ÷ 总次数 × 100%")
        self.assertEqual(result.state.messages[-1]["content"], result.answer)

    async def test_search_prepare_trial_and_answer_closed_loop(self) -> None:
        adapter = SequenceAdapter([
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="search_indicator_rules",
                arguments={"query": "急会诊及时到位率"},
            )]),
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="get_effective_rule",
                arguments={"rule_id": "MQSI2025_005"},
            )]),
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="prepare_indicator_sql",
                arguments={
                    "rule_id": "MQSI2025_005",
                    "stat_start_time": "2026-07-01T00:00:00",
                    "stat_end_time": "2026-08-01T00:00:00",
                },
            )]),
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="trial_run_indicator_sql",
                arguments={"sql_id": "SQL_001"},
            )]),
            AgentModelResponse(content=(
                "SQL 已校验通过。本次试运行分子 37、分母 40，指标值 92.5%。"
            )),
        ])
        runner, domain = _runner(adapter)

        result = await runner.run("试运行急会诊及时到位率", _context())

        self.assertEqual(result.stop_reason, "final_answer")
        self.assertEqual(result.state.last_run_id, "RUN_001")
        self.assertIn("{{detail_export:RUN_001}}", result.answer)
        self.assertIn("查看分子、分母明细并导出 Excel", result.answer)
        self.assertEqual(
            {fact for item in result.state.evidence for fact in item["fact_types"]},
            {
                "rule_identity",
                "definition",
                "formula",
                "effective_level",
                "implementation_status",
                "sql_object",
                "sql_validation",
                "trial_run",
                "aggregate_result",
            },
        )
        self.assertEqual(domain.trial_calls[0]["sql_id"], "SQL_001")

        schemas = [
            [tool["function"]["name"] for tool in call["tools"]]
            for call in adapter.calls
        ]
        self.assertEqual(schemas[0], [
            "search_indicator_rules",
            "create_indicator_draft",
        ])
        self.assertLessEqual(max(map(len, schemas)), 6)
        self.assertIn("trial_run_indicator_sql", schemas[3])

        called_arguments = [
            tool_call["arguments"]
            for message in result.state.messages
            if message.get("role") == "assistant"
            for tool_call in message.get("tool_calls") or []
            if tool_call["name"] == "trial_run_indicator_sql"
        ]
        self.assertEqual(called_arguments, [{"sql_id": "SQL_001"}])
        observations = json.dumps(result.state.messages, ensure_ascii=False)
        self.assertIn("SELECT 92.5 AS index_value", observations)
        self.assertNotIn("patient_name", observations)
        self.assertNotIn("protected_template", observations)

    async def test_final_answer_without_current_trial_does_not_show_detail_export(self) -> None:
        adapter = SequenceAdapter([AgentModelResponse(
            content="这是指标公式说明。{{detail_export:RUN_OLD}}",
        )])
        runner, _ = _runner(adapter, max_steps=1)
        state = AgentRunState(
            last_run_id="RUN_OLD",
            evidence=[{
                "source": "mysql",
                "source_id": "MQSI2025_005",
                "fact_types": ["rule_identity", "formula"],
            }],
        )

        result = await runner.run("这个指标怎么算？", _context(), state)

        self.assertEqual(result.stop_reason, "final_answer")
        self.assertNotIn("detail_export", result.answer)

    async def test_period_followup_hides_diagnosis_tool(self) -> None:
        adapter = SequenceAdapter([AgentModelResponse(content="需要重新试运行该统计区间。")])
        runner, _ = _runner(adapter, max_steps=1)
        state = AgentRunState(
            current_rule_id="MQSI2025_005",
            evidence=[{
                "source": "conversation_memory",
                "source_id": "MQSI2025_005",
                "fact_types": ["rule_identity"],
            }],
        )

        await runner.run("统计时间要是从 26 年 6 月 1 号开始怎么算", _context(), state)

        names = [
            tool["function"]["name"]
            for tool in adapter.calls[0]["tools"]
        ]
        self.assertIn("prepare_indicator_sql", names)
        self.assertNotIn("diagnose_indicator_issue", names)

    async def test_explicit_diagnosis_request_keeps_diagnosis_tool(self) -> None:
        adapter = SequenceAdapter([AgentModelResponse(content="需要诊断该指标。")])
        runner, _ = _runner(adapter, max_steps=1)
        state = AgentRunState(
            current_rule_id="MQSI2025_005",
            evidence=[{
                "source": "conversation_memory",
                "source_id": "MQSI2025_005",
                "fact_types": ["rule_identity"],
            }],
        )

        await runner.run("这个指标结果异常，帮我排查原因", _context(), state)

        names = [
            tool["function"]["name"]
            for tool in adapter.calls[0]["tools"]
        ]
        self.assertIn("diagnose_indicator_issue", names)

    async def test_concrete_trial_numbers_without_trial_evidence_are_rejected(self) -> None:
        adapter = SequenceAdapter([AgentModelResponse(
            content="本次试运行分子 37、分母 40，指标值 92.5%。"
        )])
        runner, _ = _runner(adapter, max_steps=1)
        state = AgentRunState(evidence=[{
            "source": "mysql",
            "source_id": "MQSI2025_005",
            "fact_types": ["rule_identity", "formula"],
        }])

        result = await runner.run("结果是多少？", _context(), state)

        self.assertEqual(result.stop_reason, "max_steps")
        self.assertTrue(any(
            "试运行聚合结果" in message.get("content", "")
            for message in result.state.messages
        ))

    async def test_result_followup_does_not_accept_formula_without_trial_evidence(self) -> None:
        adapter = SequenceAdapter([
            AgentModelResponse(content=(
                "指标率 = 入院后0至48小时内转科人数 ÷ 同期入院患者总人次数 × 100%"
            )),
            AgentModelResponse(content="请先提供统计周期，例如 2026年6月1日至今。"),
        ])
        runner, _ = _runner(adapter, max_steps=2)
        state = AgentRunState(evidence=[{
            "source": "mysql",
            "source_id": "MQSI2025_005",
            "fact_types": ["rule_identity", "formula"],
        }])

        result = await runner.run("直接给我结果", _context(), state)

        self.assertEqual(result.stop_reason, "final_answer")
        self.assertEqual(result.answer, "请先提供统计周期，例如 2026年6月1日至今。")
        self.assertTrue(any(
            "用户正在索要实际结果" in message.get("content", "")
            for message in result.state.messages
        ))

    async def test_context_conflict_stops_without_another_model_call(self) -> None:
        adapter = SequenceAdapter([
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="search_indicator_rules",
                arguments={"query": "急会诊及时到位率"},
            )]),
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="prepare_indicator_sql",
                arguments={
                    "rule_id": "MQSI2025_005",
                    "stat_start_time": "2026-07-01T00:00:00",
                    "stat_end_time": "2026-08-01T00:00:00",
                },
            )]),
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="trial_run_indicator_sql",
                arguments={"sql_id": "SQL_001"},
            )]),
        ])
        domain = FakeDomainServices()
        runner, _ = _runner(adapter, domain=domain)

        original_generate = domain.generate_indicator
        def generate_then_change(prepared, **kwargs):
            result = original_generate(prepared, **kwargs)
            domain.definition = "已变化的规则定义。"
            return result
        domain.generate_indicator = generate_then_change

        result = await runner.run("试运行急会诊及时到位率", _context())

        self.assertEqual(result.stop_reason, "context_conflict")
        self.assertEqual(len(adapter.calls), 3)
