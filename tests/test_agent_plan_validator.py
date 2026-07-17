from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from app.agent_planning import RequestPlan
from app.agent_planning.validator import FallbackCategory, PlanValidator


NOW = datetime(2026, 7, 16, 15, 30, tzinfo=ZoneInfo("Asia/Shanghai"))


def _plan(raw_text: str, *, constraints=None, outputs=None):
    return RequestPlan.model_validate({
        "intent": "indicator_trial_run",
        "goal": "查询指标实际结果",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "time_expression": {"raw_text": raw_text},
        "requested_outputs": outputs or ["trial_result"],
        "constraints": constraints or [],
    })


def test_resolves_current_month_as_left_closed_right_open_range():
    result = PlanValidator().validate(_plan("这个月"), now=NOW)

    assert result.ok is True
    assert result.resolved_time.start_time.isoformat() == "2026-07-01T00:00:00+08:00"
    assert result.resolved_time.end_time.isoformat() == NOW.isoformat()
    assert result.resolved_time.interval == "left_closed_right_open"


def test_resolves_previous_month():
    result = PlanValidator().validate(_plan("上个月"), now=NOW)

    assert result.resolved_time.start_time.isoformat() == "2026-06-01T00:00:00+08:00"
    assert result.resolved_time.end_time.isoformat() == "2026-07-01T00:00:00+08:00"


def test_resolves_current_year_month_to_now():
    result = PlanValidator().validate(_plan("今年1月到现在"), now=NOW)

    assert result.resolved_time.start_time.isoformat() == "2026-01-01T00:00:00+08:00"
    assert result.resolved_time.end_time.isoformat() == NOW.isoformat()


def test_resolves_from_month_to_now_as_current_year():
    result = PlanValidator().validate(_plan("从1月到现在"), now=NOW)

    assert result.resolved_time.start_time.isoformat() == "2026-01-01T00:00:00+08:00"
    assert result.resolved_time.end_time.isoformat() == NOW.isoformat()


def test_resolves_month_to_now_when_planner_keeps_query_suffix():
    result = PlanValidator().validate(_plan("从1月到现在的结果"), now=NOW)

    assert result.resolved_time.start_time.isoformat() == "2026-01-01T00:00:00+08:00"
    assert result.resolved_time.end_time.isoformat() == NOW.isoformat()


def test_resolves_two_digit_chinese_date_start_to_now():
    result = PlanValidator().validate(_plan("从26年6月1号开始"), now=NOW)

    assert result.resolved_time.start_time.isoformat() == "2026-06-01T00:00:00+08:00"
    assert result.resolved_time.end_time.isoformat() == NOW.isoformat()


def test_resolves_explicit_inclusive_date_range():
    result = PlanValidator().validate(
        _plan("2026-06-01 到 2026-06-30"),
        now=NOW,
    )

    assert result.resolved_time.start_time.isoformat() == "2026-06-01T00:00:00+08:00"
    assert result.resolved_time.end_time.isoformat() == "2026-07-01T00:00:00+08:00"


def test_missing_time_returns_user_clarification():
    result = PlanValidator().validate(_plan("尽快"), now=NOW)

    assert result.ok is False
    assert result.fallback_category is FallbackCategory.USER_CLARIFICATION
    assert result.code == "TIME_RANGE_AMBIGUOUS"


def test_trial_result_without_indicator_returns_user_clarification():
    plan = RequestPlan.model_validate({
        "intent": "indicator_trial_run",
        "goal": "对比上传文件与本院指标结果",
        "target_indicator": {},
        "time_expression": {"raw_text": "从1月到现在"},
        "requested_outputs": ["file_analysis", "trial_result"],
    })

    result = PlanValidator().validate(plan, now=NOW)

    assert result.ok is False
    assert result.fallback_category is FallbackCategory.USER_CLARIFICATION
    assert result.code == "TARGET_INDICATOR_AMBIGUOUS"
    assert "指标名称" in result.message


def test_no_database_constraint_conflicts_with_trial_result():
    result = PlanValidator().validate(
        _plan("这个月", constraints=["no_database_access"]),
        now=NOW,
    )

    assert result.ok is False
    assert result.fallback_category is FallbackCategory.BUSINESS_CONFIRMATION
    assert result.code == "DATABASE_ACCESS_CONFLICT"


def test_prepared_sql_without_trial_still_resolves_time_and_allows_no_database_constraint():
    plan = RequestPlan.model_validate({
        "intent": "rule_explanation",
        "goal": "只生成 SQL，不执行",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "time_expression": {"raw_text": "从1月到现在"},
        "requested_outputs": ["prepared_sql_handle"],
        "constraints": ["no_database_access"],
    })

    result = PlanValidator().validate(plan, now=NOW)

    assert result.ok is True
    assert result.resolved_time.start_time.isoformat() == "2026-01-01T00:00:00+08:00"
    assert result.resolved_time.end_time.isoformat() == NOW.isoformat()


def test_patient_detail_request_is_security_denial():
    plan = RequestPlan.model_validate({
        "intent": "indicator_trial_run",
        "goal": "返回患者明细",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "time_expression": {"raw_text": "这个月"},
        "requested_outputs": ["trial_result"],
        "constraints": ["patient_level_detail"],
    })

    result = PlanValidator().validate(plan, now=NOW)

    assert result.ok is False
    assert result.fallback_category is FallbackCategory.SECURITY_DENIAL
    assert result.code == "PATIENT_DETAIL_FORBIDDEN"
