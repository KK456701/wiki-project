from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Literal

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field, field_validator


router = APIRouter(prefix="/api/monitoring", tags=["monitoring"])


class PlanCreateRequest(BaseModel):
    plan_id: str | None = None
    hospital_id: str
    rule_id: str
    plan_name: str
    frequency: Literal["daily", "monthly"]
    run_time: str = "02:00"
    day_of_month: int = Field(default=1, ge=1, le=28)
    timezone: str = "Asia/Shanghai"
    mom_enabled: bool = True
    mom_threshold_pct: float = Field(default=20.0, gt=0, le=10000)
    yoy_enabled: bool = True
    yoy_threshold_pct: float = Field(default=30.0, gt=0, le=10000)
    created_by: str = "admin"

    @field_validator("run_time")
    @classmethod
    def validate_run_time(cls, value: str) -> str:
        if not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", value):
            raise ValueError("run_time 必须是 HH:mm")
        return value


class PlanUpdateRequest(BaseModel):
    hospital_id: str
    plan_name: str | None = None
    frequency: Literal["daily", "monthly"] | None = None
    run_time: str | None = None
    day_of_month: int | None = Field(default=None, ge=1, le=28)
    timezone: str | None = None
    mom_enabled: bool | None = None
    mom_threshold_pct: float | None = Field(default=None, gt=0, le=10000)
    yoy_enabled: bool | None = None
    yoy_threshold_pct: float | None = Field(default=None, gt=0, le=10000)

    @field_validator("run_time")
    @classmethod
    def validate_run_time(cls, value: str | None) -> str | None:
        if value is not None and not re.fullmatch(
            r"(?:[01]\d|2[0-3]):[0-5]\d", value
        ):
            raise ValueError("run_time 必须是 HH:mm")
        return value


class PlanRunRequest(BaseModel):
    hospital_id: str
    stat_period: str | None = None


class HospitalActionRequest(BaseModel):
    hospital_id: str
    actor_id: str = "admin"


class SchedulerScanRequest(BaseModel):
    now: datetime | None = None


@dataclass
class MonitoringContext:
    repository: Any
    service: Any
    scheduler: Any | None


def _create_monitoring_context() -> MonitoringContext:
    from app.db.engine import create_runtime_engine
    from app.monitoring.factory import create_monitoring_service
    from app.monitoring.repository import MonitoringRepository
    from app.monitoring.runtime import get_monitoring_scheduler

    engine = create_runtime_engine()
    return MonitoringContext(
        repository=MonitoringRepository(engine),
        service=create_monitoring_service(engine),
        scheduler=get_monitoring_scheduler(),
    )


def _require_admin(authorization: str | None = Header(None)) -> str:
    from app.api.main import _require_admin as main_require_admin

    return main_require_admin(authorization)


def _plan_for_hospital(context: MonitoringContext, plan_id: str, hospital_id: str):
    plan = context.repository.get_plan(plan_id)
    if plan is None or str(plan.get("hospital_id")) != hospital_id:
        raise HTTPException(status_code=404, detail="运行计划不存在")
    return plan


def _sync(context: MonitoringContext, plan_id: str) -> None:
    if context.scheduler is not None:
        context.scheduler.sync_plan(plan_id)


def _call(action):
    try:
        return action()
    except HTTPException:
        raise
    except LookupError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.post("/plans")
def create_plan(
    body: PlanCreateRequest,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)

    def action():
        context = _create_monitoring_context()
        plan = context.repository.create_plan(body.model_dump(exclude_none=True))
        _sync(context, str(plan["plan_id"]))
        return plan

    return _call(action)


@router.get("/plans")
def list_plans(
    hospital_id: str,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)
    return {"items": _create_monitoring_context().repository.list_plans(hospital_id)}


@router.put("/plans/{plan_id}")
def update_plan(
    plan_id: str,
    body: PlanUpdateRequest,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)

    def action():
        context = _create_monitoring_context()
        _plan_for_hospital(context, plan_id, body.hospital_id)
        payload = body.model_dump(exclude_unset=True, exclude_none=True)
        payload.pop("hospital_id", None)
        plan = context.repository.update_plan(plan_id, payload)
        _sync(context, plan_id)
        return plan

    return _call(action)


def _set_plan_status(plan_id: str, hospital_id: str, status: str):
    context = _create_monitoring_context()
    _plan_for_hospital(context, plan_id, hospital_id)
    plan = context.repository.set_plan_status(plan_id, status)
    _sync(context, plan_id)
    return plan


@router.post("/plans/{plan_id}/enable")
def enable_plan(
    plan_id: str,
    hospital_id: str,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)
    return _call(lambda: _set_plan_status(plan_id, hospital_id, "enabled"))


@router.post("/plans/{plan_id}/disable")
def disable_plan(
    plan_id: str,
    hospital_id: str,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)
    return _call(lambda: _set_plan_status(plan_id, hospital_id, "disabled"))


@router.post("/plans/{plan_id}/run")
def run_plan(
    plan_id: str,
    body: PlanRunRequest,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)

    def action():
        context = _create_monitoring_context()
        _plan_for_hospital(context, plan_id, body.hospital_id)
        return context.service.run_plan(
            plan_id,
            stat_period=body.stat_period,
            trigger_type="manual",
            request_id=f"REQ_{uuid.uuid4().hex[:12]}",
        )

    return _call(action)


@router.post("/scheduler/scan")
def scan_scheduler(
    body: SchedulerScanRequest | None = None,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)
    context = _create_monitoring_context()
    if context.scheduler is None:
        raise HTTPException(status_code=503, detail="指标调度器未启动")
    return {"items": _call(lambda: context.scheduler.scan_due(body.now if body else None))}


@router.get("/results")
def list_results(
    hospital_id: str,
    rule_id: str | None = None,
    limit: int = 100,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)
    items = _create_monitoring_context().repository.list_results(
        hospital_id, rule_id=rule_id, limit=min(max(limit, 1), 500)
    )
    return {"items": items}


@router.get("/results/{result_id}")
def get_result(
    result_id: int,
    hospital_id: str,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)
    result = _create_monitoring_context().repository.get_result(
        result_id, hospital_id
    )
    if result is None:
        raise HTTPException(status_code=404, detail="运行结果不存在")
    return result


@router.get("/alerts")
def list_alerts(
    hospital_id: str,
    status: str | None = None,
    limit: int = 100,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)
    return {
        "items": _create_monitoring_context().repository.list_alerts(
            hospital_id, status=status, limit=min(max(limit, 1), 500)
        )
    }


def _update_alert(
    alert_id: str, hospital_id: str, status: str, actor_id: str
):
    context = _create_monitoring_context()
    alert = context.repository.get_alert(alert_id, hospital_id)
    if alert is None:
        raise HTTPException(status_code=404, detail="指标预警不存在")
    payload: dict[str, Any] = {"status": status}
    if status == "acknowledged":
        payload.update(
            {"acknowledged_by": actor_id, "acknowledged_at": datetime.now()}
        )
    if status == "closed":
        payload["closed_at"] = datetime.now()
    return context.repository.update_alert(alert_id, hospital_id, payload)


@router.post("/alerts/{alert_id}/acknowledge")
def acknowledge_alert(
    alert_id: str,
    body: HospitalActionRequest,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)
    return _call(
        lambda: _update_alert(
            alert_id, body.hospital_id, "acknowledged", body.actor_id
        )
    )


@router.post("/alerts/{alert_id}/close")
def close_alert(
    alert_id: str,
    body: HospitalActionRequest,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)
    return _call(
        lambda: _update_alert(alert_id, body.hospital_id, "closed", body.actor_id)
    )


@router.post("/alerts/{alert_id}/diagnose")
def diagnose_alert(
    alert_id: str,
    body: HospitalActionRequest,
    authorization: str | None = Header(None, alias="Authorization"),
):
    _require_admin(authorization)
    return _call(
        lambda: _create_monitoring_context().service.diagnose_alert(
            alert_id, body.hospital_id
        )
    )
