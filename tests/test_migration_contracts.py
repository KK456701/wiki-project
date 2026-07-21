import json
from pathlib import Path

from app.agent_runtime.events import AGENT_EVENT_NAMES
from app.api.agent_routes import AgentChatRequest, AgentChatResponse, UploadResponse
from app.agent_planning.contracts import CompiledPlan, PlanNode, RequestPlan, TargetIndicator, TimeExpression


CONTRACT_ROOT = Path(__file__).resolve().parents[1] / "contracts" / "migration" / "v1"


def _load(name: str) -> dict:
    return json.loads((CONTRACT_ROOT / name).read_text(encoding="utf-8"))


def test_agent_api_contract_matches_python_boundary_models() -> None:
    frozen = _load("agent-api.schema.json")["$defs"]

    for model in (AgentChatRequest, AgentChatResponse, UploadResponse):
        current = model.model_json_schema()
        expected = frozen[model.__name__]
        assert current.get("required", []) == expected.get("required", [])
        assert set(current["properties"]) == set(expected["properties"])

    request = AgentChatRequest.model_json_schema()
    expected_request = frozen["AgentChatRequest"]
    assert request["additionalProperties"] is False
    for field in ("query", "session_id", "model_id", "file_key"):
        current_field = request["properties"][field]
        expected_field = expected_request["properties"][field]
        current_string = next(
            (item for item in current_field.get("anyOf", []) if item.get("type") == "string"),
            current_field,
        )
        expected_string = expected_field
        for key in ("minLength", "maxLength", "pattern"):
            assert current_string.get(key) == expected_string.get(key)


def test_agent_sse_contract_lists_every_public_event() -> None:
    frozen = _load("agent-sse.schema.json")

    assert set(frozen["properties"]["event"]["enum"]) == set(AGENT_EVENT_NAMES)
    assert frozen["additionalProperties"] is False
    assert frozen["required"] == ["event", "trace_id"]


def test_agent_plan_ir_contract_matches_python_models() -> None:
    frozen = _load("agent-plan-ir.schema.json")["$defs"]
    models = {
        "request_plan": RequestPlan,
        "compiled_plan": CompiledPlan,
        "plan_node": PlanNode,
        "target_indicator": TargetIndicator,
        "time_expression": TimeExpression,
    }

    for contract_name, model in models.items():
        current = model.model_json_schema()
        expected = frozen[contract_name]
        assert current.get("required", []) == expected.get("required", [])
        assert set(current["properties"]) == set(expected["properties"])
        assert expected["additionalProperties"] is False
