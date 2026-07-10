import unittest
from datetime import datetime
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.api.main import app
from app.monitoring.repository import MonitoringRepository
from tests.test_monitoring_repository import _monitoring_engine, _result_payload


class _FakeService:
    def __init__(self) -> None:
        self.run_calls = []
        self.diagnose_calls = []

    def run_plan(self, plan_id, **kwargs):
        self.run_calls.append((plan_id, kwargs))
        return {
            "id": 9,
            "plan_id": plan_id,
            "hospital_id": "hospital_001",
            "rule_id": "MQSI2025_005",
            "run_status": "success",
            "stat_period": kwargs.get("stat_period"),
            "trace_id": "TRACE_MONITOR_API",
        }

    def diagnose_alert(self, alert_id, hospital_id):
        self.diagnose_calls.append((alert_id, hospital_id))
        return {"alert_id": alert_id, "diagnose_status": "completed"}


class _FakeScheduler:
    def __init__(self) -> None:
        self.synced = []
        self.scan_calls = []

    def sync_plan(self, plan_id):
        self.synced.append(plan_id)

    def scan_due(self, now=None):
        self.scan_calls.append(now)
        return [{"status": "success"}]


class MonitoringApiTest(unittest.TestCase):
    def setUp(self) -> None:
        from app.api import monitoring

        self.engine = _monitoring_engine()
        self.repository = MonitoringRepository(self.engine)
        self.service = _FakeService()
        self.scheduler = _FakeScheduler()
        self.context = monitoring.MonitoringContext(
            repository=self.repository,
            service=self.service,
            scheduler=self.scheduler,
        )
        self.context_patch = patch.object(
            monitoring, "_create_monitoring_context", return_value=self.context
        )
        self.context_patch.start()
        self.client = TestClient(app)
        login = self.client.post(
            "/api/admin/login", json={"password": "admin123"}
        )
        self.headers = {
            "Authorization": f"Bearer {login.json()['token']}"
        }

    def tearDown(self) -> None:
        self.context_patch.stop()

    def _create_plan(self):
        response = self.client.post(
            "/api/monitoring/plans",
            headers=self.headers,
            json={
                "plan_id": "PLAN_API_001",
                "hospital_id": "hospital_001",
                "rule_id": "MQSI2025_005",
                "plan_name": "急会诊月报",
                "frequency": "monthly",
                "run_time": "02:00",
            },
        )
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def test_plan_create_list_update_status_and_manual_run(self):
        plan = self._create_plan()
        self.assertEqual(plan["mom_threshold_pct"], 20.0)
        self.assertEqual(plan["yoy_threshold_pct"], 30.0)

        listed = self.client.get(
            "/api/monitoring/plans?hospital_id=hospital_001",
            headers=self.headers,
        )
        self.assertEqual(len(listed.json()["items"]), 1)

        updated = self.client.put(
            "/api/monitoring/plans/PLAN_API_001",
            headers=self.headers,
            json={
                "hospital_id": "hospital_001",
                "plan_name": "急会诊每日监控",
                "frequency": "daily",
                "run_time": "01:30",
            },
        )
        self.assertEqual(updated.status_code, 200, updated.text)
        self.assertEqual(updated.json()["frequency"], "daily")

        disabled = self.client.post(
            "/api/monitoring/plans/PLAN_API_001/disable?hospital_id=hospital_001",
            headers=self.headers,
        )
        self.assertEqual(disabled.json()["status"], "disabled")
        enabled = self.client.post(
            "/api/monitoring/plans/PLAN_API_001/enable?hospital_id=hospital_001",
            headers=self.headers,
        )
        self.assertEqual(enabled.json()["status"], "enabled")

        run = self.client.post(
            "/api/monitoring/plans/PLAN_API_001/run",
            headers=self.headers,
            json={
                "hospital_id": "hospital_001",
                "stat_period": "2026-07-01~2026-07-31",
            },
        )
        self.assertEqual(run.status_code, 200, run.text)
        self.assertEqual(run.json()["trace_id"], "TRACE_MONITOR_API")
        self.assertEqual(self.scheduler.synced.count("PLAN_API_001"), 4)

    def test_mutation_requires_admin_and_validates_schedule(self):
        unauthorized = self.client.post(
            "/api/monitoring/plans",
            json={
                "hospital_id": "hospital_001",
                "rule_id": "MQSI2025_005",
                "plan_name": "未授权计划",
                "frequency": "monthly",
            },
        )
        self.assertEqual(unauthorized.status_code, 401)

        invalid = self.client.post(
            "/api/monitoring/plans",
            headers=self.headers,
            json={
                "hospital_id": "hospital_001",
                "rule_id": "MQSI2025_005",
                "plan_name": "错误计划",
                "frequency": "weekly",
                "run_time": "25:90",
                "mom_threshold_pct": 0,
            },
        )
        self.assertEqual(invalid.status_code, 422)

    def test_result_and_alert_reads_are_hospital_scoped(self):
        self._create_plan()
        result = self.repository.create_run_result(
            _result_payload(
                plan_id="PLAN_API_001",
                run_key="api:result",
                stat_start_time=datetime(2026, 7, 1),
                stat_end_time=datetime(2026, 8, 1),
            )
        )
        alert = self.repository.create_alert(
            {
                "alert_id": "ALERT_API_001",
                "hospital_id": "hospital_001",
                "rule_id": "MQSI2025_005",
                "plan_id": "PLAN_API_001",
                "result_id": result["id"],
                "alert_type": "wave",
                "conclusion_code": "mom_threshold_exceeded",
            }
        )

        allowed = self.client.get(
            f"/api/monitoring/results/{result['id']}?hospital_id=hospital_001",
            headers=self.headers,
        )
        denied = self.client.get(
            f"/api/monitoring/results/{result['id']}?hospital_id=hospital_002",
            headers=self.headers,
        )
        self.assertEqual(allowed.status_code, 200)
        self.assertEqual(denied.status_code, 404)

        acknowledged = self.client.post(
            f"/api/monitoring/alerts/{alert['alert_id']}/acknowledge",
            headers=self.headers,
            json={"hospital_id": "hospital_001", "actor_id": "admin_001"},
        )
        self.assertEqual(acknowledged.json()["status"], "acknowledged")
        self.assertEqual(acknowledged.json()["acknowledged_by"], "admin_001")
        self.assertIsNotNone(acknowledged.json()["acknowledged_at"])
        diagnosed = self.client.post(
            f"/api/monitoring/alerts/{alert['alert_id']}/diagnose",
            headers=self.headers,
            json={"hospital_id": "hospital_001"},
        )
        self.assertEqual(diagnosed.json()["diagnose_status"], "completed")
        closed = self.client.post(
            f"/api/monitoring/alerts/{alert['alert_id']}/close",
            headers=self.headers,
            json={"hospital_id": "hospital_001", "actor_id": "admin_001"},
        )
        self.assertEqual(closed.json()["status"], "closed")
        self.assertIsNotNone(closed.json()["closed_at"])


if __name__ == "__main__":
    unittest.main()
