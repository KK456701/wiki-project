"""通过正式监控服务生成演示环境的历史基线。"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from typing import Any, Iterable


DEMO_MONITORING_RULES = {
    "MQSI2025_001": "患者入院48小时内转科月报",
    "MQSI2025_005": "急会诊及时到位率月报",
    "MQSI2025_014": "急危重症抢救成功率月报",
    "MQSI2025_035": "术中自体血回输率月报",
}


def json_safe(value: Any) -> Any:
    """Convert service results to values accepted by the standard JSON encoder."""
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, (date, datetime)):
        return value.isoformat()
    if isinstance(value, dict):
        return {key: json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    return value


def _add_months(value: date, months: int) -> date:
    month_index = value.year * 12 + value.month - 1 + months
    return date(month_index // 12, month_index % 12 + 1, 1)


def build_monitoring_periods(start_month: date, month_count: int) -> list[str]:
    if start_month.day != 1:
        raise ValueError("start_month 必须是月份第一天")
    if month_count < 1:
        raise ValueError("month_count 必须大于 0")
    periods = []
    for offset in range(month_count):
        start = _add_months(start_month, offset)
        end = _add_months(start_month, offset + 1)
        periods.append(f"{start:%Y-%m-%d} 00:00:00~{end:%Y-%m-%d} 00:00:00")
    return periods


def demo_plan_payload(rule_id: str, hospital_id: str = "hospital_001") -> dict[str, Any]:
    if rule_id not in DEMO_MONITORING_RULES:
        raise ValueError(f"不支持的演示指标：{rule_id}")
    return {
        "plan_id": f"DEMO_MONTHLY_{rule_id}",
        "hospital_id": hospital_id,
        "rule_id": rule_id,
        "plan_name": DEMO_MONITORING_RULES[rule_id],
        "frequency": "monthly",
        "run_time": "02:00",
        "day_of_month": 1,
        "timezone": "Asia/Shanghai",
        "mom_enabled": True,
        "mom_threshold_pct": 20.0,
        "yoy_enabled": True,
        "yoy_threshold_pct": 30.0,
        "status": "enabled",
        "created_by": "demo_seed",
    }


def ensure_demo_plans(
    repository: Any,
    rule_ids: Iterable[str] = DEMO_MONITORING_RULES,
    hospital_id: str = "hospital_001",
) -> dict[str, str]:
    plan_ids: dict[str, str] = {}
    for rule_id in rule_ids:
        payload = demo_plan_payload(rule_id, hospital_id)
        plan = repository.get_plan(payload["plan_id"])
        if plan is None:
            plan = repository.create_plan(payload)
        plan_ids[rule_id] = str(plan["plan_id"])
    return plan_ids


def seed_monitoring_baseline(
    service: Any,
    periods: Iterable[str],
    rule_ids: Iterable[str] = DEMO_MONITORING_RULES,
    hospital_id: str = "hospital_001",
) -> dict[str, Any]:
    selected_rules = list(rule_ids)
    selected_periods = list(periods)
    plan_ids = ensure_demo_plans(service.repository, selected_rules, hospital_id)
    status_counts: dict[str, int] = {}
    abnormal_count = 0
    alert_count = 0
    results: list[dict[str, Any]] = []
    for period in selected_periods:
        for rule_id in selected_rules:
            result = service.run_plan(
                plan_ids[rule_id],
                stat_period=period,
                trigger_type="manual",
                request_id=f"demo-baseline:{rule_id}:{period[:7]}",
            )
            status = str(result.get("run_status") or result.get("status") or "unknown")
            status_counts[status] = status_counts.get(status, 0) + 1
            abnormal_count += int(bool(result.get("is_abnormal")))
            alert_count += int(bool(result.get("alert")))
            results.append(
                {
                    "rule_id": rule_id,
                    "period": period,
                    "result_id": result.get("id"),
                    "run_status": status,
                    "result_value": result.get("result_value"),
                    "wave_status": result.get("wave_status"),
                    "trace_id": result.get("trace_id"),
                }
            )
    return {
        "plan_ids": plan_ids,
        "period_count": len(selected_periods),
        "run_count": len(results),
        "status_counts": status_counts,
        "abnormal_count": abnormal_count,
        "alert_count": alert_count,
        "results": results,
    }
