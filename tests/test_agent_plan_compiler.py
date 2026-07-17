from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.agent_planning import (
    PlanCapability,
    PlanCompiler,
    RequestPlan,
)


def _trial_plan(**overrides):
    payload = {
        "intent": "indicator_trial_run",
        "goal": "计算急会诊及时到位率本月结果",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "time_expression": {"raw_text": "这个月"},
        "requested_outputs": ["trial_result", "explanation"],
        "constraints": [],
        "semantic_ambiguities": [],
    }
    payload.update(overrides)
    return RequestPlan.model_validate(payload)


@pytest.mark.parametrize("field", ["steps", "proposed_steps"])
def test_request_plan_rejects_model_generated_steps(field):
    payload = _trial_plan().model_dump(mode="json")
    payload[field] = ["resolve_indicator"]

    with pytest.raises(ValidationError):
        RequestPlan.model_validate(payload)


def test_request_plan_rejects_unknown_tool_fields():
    payload = _trial_plan().model_dump(mode="json")
    payload["tool"] = "prepare_indicator_sql"

    with pytest.raises(ValidationError):
        RequestPlan.model_validate(payload)


def test_trial_run_plan_is_compiled_from_business_goal():
    compiled = PlanCompiler().compile(_trial_plan())

    assert [node.capability for node in compiled.nodes] == [
        PlanCapability.RESOLVE_INDICATOR,
        PlanCapability.RESOLVE_EFFECTIVE_RULE,
        PlanCapability.RESOLVE_TIME_RANGE,
        PlanCapability.PREPARE_VERIFIED_SQL,
        PlanCapability.EXECUTE_TRIAL_RUN,
        PlanCapability.COMPOSE_ANSWER,
    ]
    assert compiled.required_facts == {
        "rule_identity",
        "effective_rule",
        "stat_period",
        "sql_validation",
        "trial_run",
    }
    assert all("tool" not in node.model_dump() for node in compiled.nodes)


def test_rule_explanation_plan_does_not_compile_sql_steps():
    plan = RequestPlan.model_validate({
        "intent": "rule_explanation",
        "goal": "解释急会诊及时到位率公式",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "requested_outputs": ["formula", "explanation"],
    })

    compiled = PlanCompiler().compile(plan)

    assert [node.capability for node in compiled.nodes] == [
        PlanCapability.RESOLVE_INDICATOR,
        PlanCapability.RESOLVE_EFFECTIVE_RULE,
        PlanCapability.COMPOSE_ANSWER,
    ]
    assert compiled.required_facts == {
        "rule_identity",
        "effective_rule",
        "formula",
    }


def test_prepared_sql_output_never_means_sql_text():
    compiled = PlanCompiler().compile(_trial_plan(
        requested_outputs=["prepared_sql_handle"],
    ))

    assert "sql_validation" in compiled.required_facts
    assert "sql_text" not in compiled.required_facts


def test_prepared_sql_output_never_compiles_trial_run_when_intent_is_imprecise():
    compiled = PlanCompiler().compile(_trial_plan(
        requested_outputs=["prepared_sql_handle"],
        time_expression={"raw_text": "从1月到现在"},
    ))

    capabilities = [node.capability for node in compiled.nodes]
    assert PlanCapability.PREPARE_VERIFIED_SQL in capabilities
    assert PlanCapability.EXECUTE_TRIAL_RUN not in capabilities


def test_requested_trial_output_compiles_required_chain_even_if_intent_is_imprecise():
    plan = RequestPlan.model_validate({
        "intent": "rule_explanation",
        "goal": "给出本月实际结果",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "time_expression": {"raw_text": "本月"},
        "requested_outputs": ["trial_result"],
    })

    compiled = PlanCompiler().compile(plan)

    assert PlanCapability.PREPARE_VERIFIED_SQL in [
        node.capability for node in compiled.nodes
    ]
    assert PlanCapability.EXECUTE_TRIAL_RUN in [
        node.capability for node in compiled.nodes
    ]


def test_upload_and_system_comparison_compiles_both_evidence_chains():
    plan = RequestPlan.model_validate({
        "intent": "indicator_trial_run",
        "goal": "对比上传文件与本院指标结果",
        "target_indicator": {"raw_name": "患者入院 48 小时内转科的比例"},
        "time_expression": {"raw_text": "从1月到现在"},
        "requested_outputs": ["file_analysis", "trial_result"],
    })

    compiled = PlanCompiler().compile(plan)

    assert [node.capability for node in compiled.nodes] == [
        PlanCapability.RESOLVE_INDICATOR,
        PlanCapability.RESOLVE_EFFECTIVE_RULE,
        PlanCapability.RESOLVE_TIME_RANGE,
        PlanCapability.PREPARE_VERIFIED_SQL,
        PlanCapability.EXECUTE_TRIAL_RUN,
        PlanCapability.ANALYZE_UPLOADED_FILE,
        PlanCapability.COMPOSE_ANSWER,
    ]
    assert {"file_analysis", "trial_run"} <= compiled.required_facts
