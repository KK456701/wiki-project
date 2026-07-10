from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict


class MonitoringContract(BaseModel):
    model_config = ConfigDict(extra="allow")


class RunPlan(MonitoringContract):
    plan_id: str
    hospital_id: str
    rule_id: str
    plan_name: str
    frequency: Literal["daily", "monthly"]
    run_time: str = "02:00"
    day_of_month: int = 1
    timezone: str = "Asia/Shanghai"
    mom_enabled: bool = True
    mom_threshold_pct: float = 20.0
    yoy_enabled: bool = True
    yoy_threshold_pct: float = 30.0
    status: Literal["enabled", "disabled"] = "enabled"
    next_run_at: datetime | None = None
    last_run_at: datetime | None = None
    locked_until: datetime | None = None
    locked_by: str = ""


class RunResult(MonitoringContract):
    id: int | None = None
    run_key: str
    plan_id: str | None = None
    retry_of_result_id: int | None = None
    hospital_id: str
    rule_id: str
    trigger_type: Literal["scheduled", "manual", "retry"]
    stat_start_time: datetime
    stat_end_time: datetime
    stat_period: str
    run_status: Literal["running", "success", "failed", "no_sample"]
    result_value: float | None = None
    effective_level: str = ""
    national_version: str | None = None
    hospital_version: int | None = None
    data_source: str = ""
    duration_ms: int = 0
    error_code: str = ""
    error_message: str = ""
    mom_baseline_result_id: int | None = None
    mom_change_rate: float | None = None
    yoy_baseline_result_id: int | None = None
    yoy_change_rate: float | None = None
    wave_status: str = "baseline_insufficient"
    is_abnormal: bool = False


class IndicatorAlert(MonitoringContract):
    alert_id: str
    hospital_id: str
    rule_id: str
    result_id: int
    alert_type: Literal["wave", "execution_failed"]
    conclusion_code: str
    diagnose_status: str = "pending"
    diagnose_report_id: str | None = None
    status: Literal["open", "acknowledged", "closed"] = "open"
