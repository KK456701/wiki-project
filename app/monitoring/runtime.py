from __future__ import annotations

from typing import Any


_scheduler: Any | None = None
_scheduler_error = ""
_scheduler_enabled = False


def set_monitoring_scheduler(scheduler: Any | None) -> None:
    global _scheduler, _scheduler_error, _scheduler_enabled
    _scheduler = scheduler
    _scheduler_error = ""
    _scheduler_enabled = scheduler is not None


def set_monitoring_scheduler_error(error: str) -> None:
    global _scheduler, _scheduler_error, _scheduler_enabled
    _scheduler = None
    _scheduler_error = str(error)
    _scheduler_enabled = True


def set_monitoring_scheduler_disabled() -> None:
    global _scheduler, _scheduler_error, _scheduler_enabled
    _scheduler = None
    _scheduler_error = ""
    _scheduler_enabled = False


def get_monitoring_scheduler() -> Any | None:
    return _scheduler


def monitoring_scheduler_status() -> dict[str, Any]:
    if not _scheduler_enabled:
        return {
            "ok": True,
            "code": "MONITORING_SCHEDULER_DISABLED",
            "critical": False,
            "enabled_plan_count": 0,
            "job_count": 0,
            "last_scan_at": None,
        }
    if _scheduler is not None:
        return dict(_scheduler.status())
    return {
        "ok": False,
        "code": "MONITORING_SCHEDULER_UNAVAILABLE",
        "critical": True,
        "error": _scheduler_error or "指标调度器尚未启动",
        "enabled_plan_count": 0,
        "job_count": 0,
        "last_scan_at": None,
    }


def reset_monitoring_scheduler() -> None:
    global _scheduler, _scheduler_error, _scheduler_enabled
    _scheduler = None
    _scheduler_error = ""
    _scheduler_enabled = False
