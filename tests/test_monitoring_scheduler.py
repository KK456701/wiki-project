import unittest
from datetime import datetime


class _FakeRepository:
    def __init__(self) -> None:
        self.next_runs = {}
        self.plans = {
            "PLAN_001": {
                "plan_id": "PLAN_001",
                "status": "enabled",
                "frequency": "monthly",
                "run_time": "02:15",
                "day_of_month": 3,
                "timezone": "Asia/Shanghai",
            },
            "PLAN_002": {
                "plan_id": "PLAN_002",
                "status": "disabled",
                "frequency": "daily",
                "run_time": "01:30",
                "day_of_month": 1,
                "timezone": "Asia/Shanghai",
            },
        }

    def list_enabled_plans(self):
        return [plan for plan in self.plans.values() if plan["status"] == "enabled"]

    def get_plan(self, plan_id):
        return self.plans.get(plan_id)

    def list_due_plans(self, now):
        return [self.plans["PLAN_001"]]

    def set_plan_next_run(self, plan_id, next_run_at):
        self.next_runs[plan_id] = next_run_at


class _FakeBackend:
    def __init__(self) -> None:
        self.jobs = {}
        self.started = 0
        self.stopped = 0
        self.running = False

    def add_job(self, func, trigger, **kwargs):
        self.jobs[kwargs["id"]] = {
            "func": func,
            "trigger": trigger,
            **kwargs,
        }

    def remove_job(self, job_id):
        self.jobs.pop(job_id, None)

    def get_job(self, job_id):
        return self.jobs.get(job_id)

    def get_jobs(self):
        return list(self.jobs.values())

    def start(self):
        self.started += 1
        self.running = True

    def shutdown(self, wait=False):
        self.stopped += 1
        self.running = False


class _FakeService:
    def __init__(self) -> None:
        self.calls = []

    def run_plan(self, plan_id):
        self.calls.append(plan_id)
        return {"plan_id": plan_id, "status": "success"}


class MonitoringSchedulerTest(unittest.TestCase):
    def _scheduler(self):
        from app.tasks.scheduler import MonitoringScheduler

        repository = _FakeRepository()
        backend = _FakeBackend()
        service = _FakeService()
        scheduler = MonitoringScheduler(
            repository,
            service_factory=lambda: service,
            backend=backend,
            trigger_factory=lambda plan: {
                "frequency": plan["frequency"],
                "run_time": plan["run_time"],
            },
        )
        return scheduler, repository, backend, service

    def test_start_registers_enabled_plan_with_safe_job_options(self):
        scheduler, _, backend, _ = self._scheduler()

        scheduler.start()

        self.assertEqual(backend.started, 1)
        self.assertIn("monitor:PLAN_001", backend.jobs)
        self.assertNotIn("monitor:PLAN_002", backend.jobs)
        job = backend.jobs["monitor:PLAN_001"]
        self.assertTrue(job["replace_existing"])
        self.assertTrue(job["coalesce"])
        self.assertEqual(job["max_instances"], 1)
        self.assertEqual(job["misfire_grace_time"], 600)
        self.assertEqual(job["args"], ["PLAN_001"])

    def test_sync_removes_disabled_plan_and_shutdown_is_idempotent(self):
        scheduler, repository, backend, _ = self._scheduler()
        scheduler.start()
        repository.plans["PLAN_001"]["status"] = "disabled"

        scheduler.sync_plan("PLAN_001")
        scheduler.shutdown()
        scheduler.shutdown()

        self.assertNotIn("monitor:PLAN_001", backend.jobs)
        self.assertEqual(backend.stopped, 1)

    def test_scan_due_runs_each_due_plan_once(self):
        scheduler, _, _, service = self._scheduler()

        results = scheduler.scan_due(datetime(2026, 8, 1, 2, 0, 0))

        self.assertEqual(service.calls, ["PLAN_001"])
        self.assertEqual(results[0]["status"], "success")

    def test_status_reports_runtime_and_plan_counts(self):
        scheduler, _, _, _ = self._scheduler()
        scheduler.start()

        status = scheduler.status()

        self.assertTrue(status["ok"])
        self.assertEqual(status["code"], "OK")
        self.assertEqual(status["enabled_plan_count"], 1)
        self.assertEqual(status["job_count"], 1)

    def test_register_persists_next_run_time_for_restart_recovery(self):
        from app.tasks.scheduler import MonitoringScheduler

        class Trigger:
            timezone = None

            def get_next_fire_time(self, previous_fire_time, now):
                return datetime(2026, 8, 3, 2, 15, 0)

        repository = _FakeRepository()
        scheduler = MonitoringScheduler(
            repository,
            service_factory=lambda: _FakeService(),
            backend=_FakeBackend(),
            trigger_factory=lambda plan: Trigger(),
        )

        scheduler.start()

        self.assertEqual(
            repository.next_runs["PLAN_001"],
            datetime(2026, 8, 3, 2, 15, 0),
        )


if __name__ == "__main__":
    unittest.main()
