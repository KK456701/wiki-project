from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.agent_planning.compiler import PlanCompiler
from app.agent_planning.contracts import PlanCapability, RequestPlan
from app.agent_planning.controller import ControllerAction, ControllerDecision
from app.agent_planning.dispatch import (
    DeterministicDispatchError,
    build_deterministic_tool_call,
)
from app.agent_planning.runtime import PlanningExecution
from app.agent_planning.validator import PlanValidator
from app.agent_runtime.contracts import AgentRunState


NOW = datetime(2026, 7, 17, 12, 0, tzinfo=ZoneInfo("Asia/Shanghai"))


def _execution(payload: dict) -> PlanningExecution:
    plan = RequestPlan.model_validate(payload)
    return PlanningExecution(
        request_plan=plan,
        compiled_plan=PlanCompiler().compile(plan),
        validation=PlanValidator().validate(plan, now=NOW),
    )


def _decision(capability: PlanCapability, tool_name: str) -> ControllerDecision:
    return ControllerDecision(
        action=ControllerAction.EXECUTE_TOOL,
        capability=capability,
        tool_names=[tool_name],
        code="NEXT_CAPABILITY",
    )


def _rule_execution() -> PlanningExecution:
    return _execution({
        "intent": "rule_explanation",
        "goal": "解释指标公式",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "requested_outputs": ["formula"],
    })


def _trial_execution() -> PlanningExecution:
    return _execution({
        "intent": "indicator_trial_run",
        "goal": "计算指标结果",
        "target_indicator": {
            "raw_name": "患者入院 48 小时内转科的比例",
            "rule_id": "RULE_1",
        },
        "time_expression": {
            "raw_text": "从1月份开始算",
            "start_time": "2026-01-01 00:00:00",
            "end_time": "2026-07-18 00:00:00",
        },
        "requested_outputs": ["trial_result"],
    })


def test_builds_search_and_rule_calls_from_plan_and_state() -> None:
    execution = _rule_execution()
    state = AgentRunState(current_rule_id="RULE_1")

    search = build_deterministic_tool_call(
        execution,
        _decision(PlanCapability.RESOLVE_INDICATOR, "search_indicator_rules"),
        AgentRunState(),
        user_message="这个指标怎么算",
    )
    effective = build_deterministic_tool_call(
        execution,
        _decision(PlanCapability.RESOLVE_EFFECTIVE_RULE, "get_effective_rule"),
        state,
        user_message="这个指标怎么算",
    )

    assert search.name == "search_indicator_rules"
    assert search.arguments == {"query": "急会诊及时到位率", "limit": 5}
    assert effective.name == "get_effective_rule"
    assert effective.arguments == {"rule_id": "RULE_1"}


def test_builds_sql_prepare_and_trial_calls_from_validated_state() -> None:
    execution = _trial_execution()
    state = AgentRunState(
        current_rule_id="RULE_1",
        validated_sql_ids=["SQL_older", "SQL_latest"],
    )

    prepare = build_deterministic_tool_call(
        execution,
        _decision(PlanCapability.PREPARE_VERIFIED_SQL, "prepare_indicator_sql"),
        state,
        user_message="从1月份开始算",
    )
    trial = build_deterministic_tool_call(
        execution,
        _decision(PlanCapability.EXECUTE_TRIAL_RUN, "trial_run_indicator_sql"),
        state,
        user_message="从1月份开始算",
    )

    assert prepare.arguments == {
        "rule_id": "RULE_1",
        "stat_start_time": "2026-01-01T00:00:00+08:00",
        "stat_end_time": "2026-07-18T00:00:00+08:00",
    }
    assert trial.arguments == {"sql_id": "SQL_latest"}


def test_builds_diagnosis_and_change_preview_from_user_text() -> None:
    execution = _rule_execution()
    state = AgentRunState(current_rule_id="RULE_1")
    query = "为什么算不对？\n```sql\nDECLARE @start datetime='2026-01-01';\nSELECT 1;\n```"

    diagnosis = build_deterministic_tool_call(
        execution,
        _decision(PlanCapability.DIAGNOSE_INDICATOR, "diagnose_indicator_issue"),
        state,
        user_message=query,
    )
    preview = build_deterministic_tool_call(
        execution,
        _decision(PlanCapability.PREVIEW_RULE_CHANGE, "preview_rule_change"),
        state,
        user_message="本院改成15分钟内到位",
    )

    assert diagnosis.arguments["rule_id"] == "RULE_1"
    assert diagnosis.arguments["issue_description"] == "为什么算不对？"
    assert "SELECT 1" in diagnosis.arguments["pasted_sql"]
    assert diagnosis.arguments["declared_params"]["start"] == "2026-01-01"
    assert preview.arguments == {
        "rule_id": "RULE_1",
        "change_description": "本院改成15分钟内到位",
    }


def test_builds_upload_call_from_recent_file_number() -> None:
    execution = _execution({
        "intent": "upload_analysis",
        "goal": "分析上传文件",
        "requested_outputs": ["file_analysis"],
    })
    state = AgentRunState(
        recent_history="用户：已上传: 指标.xlsx\n文件编号: hospital_001_report.xlsx",
    )

    call = build_deterministic_tool_call(
        execution,
        _decision(PlanCapability.ANALYZE_UPLOADED_FILE, "analyze_uploaded_indicators"),
        state,
        user_message="帮我分析刚上传的文件",
    )

    assert call.arguments == {"file_key": "hospital_001_report.xlsx"}


def test_upload_call_prefers_structured_latest_file_key() -> None:
    execution = _execution({
        "intent": "upload_analysis",
        "goal": "分析刚上传文件",
        "requested_outputs": ["file_analysis"],
    })
    state = AgentRunState(
        current_upload_file_key="hospital_001_85a68d23d925_无标题.xlsx",
        recent_history=(
            "用户：已上传旧文件\n"
            "文件编号: hospital_001_old_report.xlsx"
        ),
    )

    call = build_deterministic_tool_call(
        execution,
        _decision(
            PlanCapability.ANALYZE_UPLOADED_FILE,
            "analyze_uploaded_indicators",
        ),
        state,
        user_message="帮我分析刚上传的文件",
    )

    assert call.arguments == {
        "file_key": "hospital_001_85a68d23d925_无标题.xlsx"
    }


def test_missing_required_dispatch_value_returns_explicit_error() -> None:
    execution = _trial_execution()

    with pytest.raises(DeterministicDispatchError) as exc_info:
        build_deterministic_tool_call(
            execution,
            _decision(PlanCapability.EXECUTE_TRIAL_RUN, "trial_run_indicator_sql"),
            AgentRunState(current_rule_id="RULE_1"),
            user_message="直接给结果",
        )

    assert exc_info.value.code == "VALIDATED_SQL_ID_MISSING"
