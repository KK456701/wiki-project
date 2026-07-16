"""使用固定只读数据验证真实 Ollama Agent 工具调用闭环。"""

from __future__ import annotations

import asyncio

from app.agent_runtime import AgentRuntimeContext
from app.agent_runtime.runner import AgentRunner
from app.agent_tools import ToolGateway
from app.agent_tools.read_tools import ReadToolServices, build_read_tool_registry
from app.agents.contracts import EffectiveRule, FieldMapping, RuleSearchResult
from app.llm.ollama_tools import OllamaToolCallingAdapter


class ProbeCaliber:
    def search_for_hospital_contract(self, query, hospital_id, limit=5):
        return RuleSearchResult(
            query=query,
            resolved_rule_id="MQSI2025_005",
            matches=[{
                "rule_id": "MQSI2025_005",
                "rule_name": "急会诊及时到位率",
            }],
            rule_source="probe_fixture",
        )

    def resolve_contract(self, rule_id, hospital_id):
        return EffectiveRule.model_validate({
            "rule_id": rule_id,
            "rule_name": "急会诊及时到位率",
            "definition": "急会诊在规定时间内到位的比例。",
            "formula": "及时到位例数 / 急会诊总例数 × 100%",
            "effective_level": "national",
            "national_version": "2025",
            "sql_status": "available",
            "rule_source": "probe_fixture",
        })

    def field_mapping_contract(self, rule_id, hospital_id):
        return FieldMapping(
            rule_id=rule_id,
            hospital_id=hospital_id,
            status="confirmed",
        )


async def run_probe():
    registry = build_read_tool_registry(ReadToolServices(caliber=ProbeCaliber()))
    runner = AgentRunner(
        OllamaToolCallingAdapter(),
        registry,
        ToolGateway(registry),
    )
    return await runner.run(
        "急会诊及时到位率怎么算？",
        AgentRuntimeContext(
            user_id="probe_user",
            hospital_id="probe_hospital",
            session_id="probe_session",
            user_role="implementer",
            permissions=frozenset({"indicator_read"}),
            request_id="probe_request",
            trace_id="probe_trace",
        ),
    )


def called_tools(result) -> list[str]:
    return [
        call["name"]
        for message in result.state.messages
        if message.get("role") == "assistant"
        for call in message.get("tool_calls") or []
    ]


if __name__ == "__main__":
    probe_result = asyncio.run(run_probe())
    print({
        "stop_reason": probe_result.stop_reason,
        "called_tools": called_tools(probe_result),
        "answer": probe_result.answer,
    })
