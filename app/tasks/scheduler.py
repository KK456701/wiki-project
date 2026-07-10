from __future__ import annotations

from datetime import datetime
from typing import Any, Callable


class MonitoringScheduler:
    def __init__(
        self,
        repository: Any,
        service_factory: Callable[[], Any],
        *,
        timezone_name: str = "Asia/Shanghai",
        backend: Any | None = None,
        trigger_factory: Callable[[dict[str, Any]], Any] | None = None,
    ) -> None:
        if backend is None:
            from apscheduler.schedulers.background import BackgroundScheduler

            backend = BackgroundScheduler(timezone=timezone_name)
        self.repository = repository
        self.service_factory = service_factory
        self.timezone_name = timezone_name
        self.backend = backend
        self.trigger_factory = trigger_factory or self._build_trigger
        self._started = False

    @staticmethod
    def _job_id(plan_id: str) -> str:
        return f"monitor:{plan_id}"

    def _build_trigger(self, plan: dict[str, Any]) -> Any:
        from apscheduler.triggers.cron import CronTrigger

        hour_text, minute_text = str(plan.get("run_time") or "02:00").split(":", 1)
        trigger_args: dict[str, Any] = {
            "hour": int(hour_text),
            "minute": int(minute_text),
            "timezone": str(plan.get("timezone") or self.timezone_name),
        }
        if plan.get("frequency") == "monthly":
            trigger_args["day"] = int(plan.get("day_of_month") or 1)
        return CronTrigger(**trigger_args)

    def _run_plan(self, plan_id: str) -> dict[str, Any]:
        try:
            return self.service_factory().run_plan(plan_id)
        finally:
            self._persist_next_run(plan_id)

    def _persist_next_run(self, plan_id: str, trigger: Any | None = None) -> None:
        next_run_at = None
        job = self.backend.get_job(self._job_id(plan_id))
        if job is not None and not isinstance(job, dict):
            next_run_at = getattr(job, "next_run_time", None)
        if next_run_at is None and trigger is not None and hasattr(
            trigger, "get_next_fire_time"
        ):
            timezone = getattr(trigger, "timezone", None)
            next_run_at = trigger.get_next_fire_time(None, datetime.now(timezone))
        self.repository.set_plan_next_run(plan_id, next_run_at)

    def _register_plan(self, plan: dict[str, Any]) -> None:
        plan_id = str(plan["plan_id"])
        trigger = self.trigger_factory(plan)
        self.backend.add_job(
            self._run_plan,
            trigger,
            id=self._job_id(plan_id),
            args=[plan_id],
            replace_existing=True,
            coalesce=True,
            max_instances=1,
            misfire_grace_time=600,
        )
        self._persist_next_run(plan_id, trigger)

    def start(self) -> None:
        if self._started:
            return
        self.reload_plans()
        self.backend.start()
        self._started = True

    def shutdown(self) -> None:
        if not self._started:
            return
        self.backend.shutdown(wait=False)
        self._started = False

    def reload_plans(self) -> None:
        enabled = {
            str(plan["plan_id"]): plan
            for plan in self.repository.list_enabled_plans()
        }
        for job in self.backend.get_jobs():
            job_id = str(job.get("id") if isinstance(job, dict) else job.id)
            if job_id.startswith("monitor:") and job_id.removeprefix("monitor:") not in enabled:
                self.backend.remove_job(job_id)
        for plan in enabled.values():
            self._register_plan(plan)

    def sync_plan(self, plan_id: str) -> None:
        plan = self.repository.get_plan(plan_id)
        job_id = self._job_id(plan_id)
        if plan is None or plan.get("status") != "enabled":
            if self.backend.get_job(job_id) is not None:
                self.backend.remove_job(job_id)
            return
        self._register_plan(plan)

    def scan_due(self, now: datetime | None = None) -> list[dict[str, Any]]:
        service = self.service_factory()
        return [
            service.run_plan(str(plan["plan_id"]))
            for plan in self.repository.list_due_plans(now or datetime.now())
        ]

    def status(self) -> dict[str, Any]:
        enabled_plan_count = len(self.repository.list_enabled_plans())
        job_count = len(
            [
                job
                for job in self.backend.get_jobs()
                if str(job.get("id") if isinstance(job, dict) else job.id).startswith(
                    "monitor:"
                )
            ]
        )
        return {
            "ok": self._started,
            "code": "OK" if self._started else "MONITORING_SCHEDULER_STOPPED",
            "critical": True,
            "enabled_plan_count": enabled_plan_count,
            "job_count": job_count,
        }
