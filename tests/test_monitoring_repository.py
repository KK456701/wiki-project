import unittest
from datetime import datetime, timedelta

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.pool import StaticPool


def _monitoring_engine():
    from app.monitoring.schema import ensure_monitoring_schema

    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                CREATE TABLE med_index_run_result (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  hospital_id TEXT NOT NULL,
                  rule_id TEXT NOT NULL,
                  stat_period TEXT NOT NULL,
                  result_value REAL,
                  previous_value REAL,
                  change_rate REAL,
                  is_abnormal INTEGER NOT NULL DEFAULT 0,
                  run_id TEXT,
                  created_at TEXT NOT NULL
                )
                """
            )
        )
    ensure_monitoring_schema(engine)
    return engine


def _result_payload(**overrides):
    payload = {
        "run_key": "PLAN_001:2026-07-01:2026-08-01",
        "plan_id": "PLAN_001",
        "hospital_id": "hospital_001",
        "rule_id": "MQSI2025_005",
        "trigger_type": "scheduled",
        "stat_start_time": datetime(2026, 7, 1),
        "stat_end_time": datetime(2026, 8, 1),
        "stat_period": "2026-07-01 00:00:00~2026-08-01 00:00:00",
        "run_status": "success",
        "result_value": 66.67,
        "effective_level": "hospital",
        "national_version": "2025",
        "hospital_version": 1,
        "data_source": "hospital_demo_data",
        "duration_ms": 25,
    }
    payload.update(overrides)
    return payload


class MonitoringSchemaTest(unittest.TestCase):
    def test_monitoring_schema_extends_old_result_table_idempotently(self) -> None:
        from app.monitoring.schema import RESULT_AUDIT_COLUMNS, ensure_monitoring_schema

        engine = _monitoring_engine()

        second = ensure_monitoring_schema(engine)
        tables = set(inspect(engine).get_table_names())
        result_columns = {
            column["name"]
            for column in inspect(engine).get_columns("med_index_run_result")
        }

        self.assertIn("med_indicator_run_plan", tables)
        self.assertIn("med_indicator_alert", tables)
        self.assertTrue(set(RESULT_AUDIT_COLUMNS).issubset(result_columns))
        self.assertEqual(second["created_tables"], [])
        self.assertEqual(second["added_result_columns"], [])


class MonitoringRepositoryTest(unittest.TestCase):
    def setUp(self) -> None:
        from app.monitoring.repository import MonitoringRepository

        self.engine = _monitoring_engine()
        self.repository = MonitoringRepository(self.engine)

    def _create_plan(self, **overrides):
        payload = {
            "hospital_id": "hospital_001",
            "rule_id": "MQSI2025_005",
            "plan_name": "急会诊月报",
            "frequency": "monthly",
            "run_time": "02:00",
            "day_of_month": 1,
            "created_by": "admin",
        }
        payload.update(overrides)
        return self.repository.create_plan(payload)

    def test_plan_defaults_and_hospital_scoped_listing(self) -> None:
        plan = self._create_plan()
        self._create_plan(
            hospital_id="hospital_002",
            plan_name="另一医院月报",
        )

        listed = self.repository.list_plans("hospital_001")

        self.assertEqual(plan["mom_threshold_pct"], 20.0)
        self.assertEqual(plan["yoy_threshold_pct"], 30.0)
        self.assertTrue(plan["mom_enabled"])
        self.assertTrue(plan["yoy_enabled"])
        self.assertEqual([item["hospital_id"] for item in listed], ["hospital_001"])

    def test_lease_is_atomic_and_can_be_reacquired_after_expiry(self) -> None:
        plan = self._create_plan()
        now = datetime(2026, 8, 1, 2, 0, 0)

        first = self.repository.try_acquire_lease(
            plan["plan_id"], "worker-a", now, lease_seconds=600
        )
        concurrent = self.repository.try_acquire_lease(
            plan["plan_id"], "worker-b", now, lease_seconds=600
        )
        expired = self.repository.try_acquire_lease(
            plan["plan_id"], "worker-b", now + timedelta(minutes=11), lease_seconds=600
        )

        self.assertTrue(first)
        self.assertFalse(concurrent)
        self.assertTrue(expired)

    def test_enabled_and_due_plan_queries_use_persisted_schedule(self) -> None:
        due = self._create_plan(next_run_at=datetime(2026, 8, 1, 2, 0, 0))
        self._create_plan(
            plan_name="未到期",
            next_run_at=datetime(2026, 8, 2, 2, 0, 0),
        )
        disabled = self._create_plan(plan_name="已停用")
        self.repository.set_plan_status(disabled["plan_id"], "disabled")

        enabled = self.repository.list_enabled_plans()
        due_items = self.repository.list_due_plans(datetime(2026, 8, 1, 3, 0, 0))

        self.assertEqual(len(enabled), 2)
        self.assertEqual([item["plan_id"] for item in due_items], [due["plan_id"]])

    def test_retry_uses_new_run_key_and_links_failed_result(self) -> None:
        failed = self.repository.create_run_result(
            _result_payload(run_status="failed", result_value=None)
        )
        retry = self.repository.create_run_result(
            _result_payload(
                run_key="retry:REQ_001",
                trigger_type="retry",
                retry_of_result_id=failed["id"],
            )
        )

        self.assertNotEqual(retry["run_key"], failed["run_key"])
        self.assertEqual(retry["retry_of_result_id"], failed["id"])
        self.assertEqual(
            self.repository.get_result_by_run_key(failed["run_key"])["id"],
            failed["id"],
        )

    def test_baseline_query_requires_exact_success_period(self) -> None:
        expected = self.repository.create_run_result(_result_payload())
        self.repository.create_run_result(
            _result_payload(
                run_key="manual:failed",
                run_status="failed",
                result_value=None,
            )
        )

        found = self.repository.find_success_result(
            "hospital_001",
            "MQSI2025_005",
            datetime(2026, 7, 1),
            datetime(2026, 8, 1),
        )

        self.assertEqual(found["id"], expected["id"])

    def test_alert_creation_is_idempotent_and_hospital_scoped(self) -> None:
        result = self.repository.create_run_result(_result_payload())
        payload = {
            "hospital_id": "hospital_001",
            "rule_id": "MQSI2025_005",
            "plan_id": "PLAN_001",
            "result_id": result["id"],
            "alert_type": "wave",
            "alert_level": "warning",
            "conclusion_code": "mom_threshold_exceeded",
            "current_value": 66.67,
            "mom_value": 50.0,
            "mom_change_rate": 33.34,
        }

        first = self.repository.create_alert(payload)
        duplicate = self.repository.create_alert(payload)

        self.assertEqual(first["alert_id"], duplicate["alert_id"])
        self.assertEqual(
            [item["alert_id"] for item in self.repository.list_alerts("hospital_001")],
            [first["alert_id"]],
        )
        self.assertIsNone(
            self.repository.get_alert(first["alert_id"], "hospital_002")
        )


if __name__ == "__main__":
    unittest.main()
