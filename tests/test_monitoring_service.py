import unittest
from datetime import datetime
from types import SimpleNamespace
from unittest.mock import patch

from app.db.repositories import list_recovery_tasks
from app.monitoring.repository import MonitoringRepository
from tests.test_monitoring_repository import _monitoring_engine, _result_payload


class _Contract(dict):
    def model_dump(self, **kwargs):
        return dict(self)


class _FakeOrchestrator:
    def __init__(self, generation=None, diagnose_result=None, diagnose_error=None):
        self.generation = generation or {
            "status": "success",
            "sql_id": "SQL_MONITOR_001",
            "trial_run": {
                "run_id": "RUN_MONITOR_001",
                "status": "success",
                "result_value": 66.67,
                "no_sample": False,
                "duration_ms": 12,
                "error_message": None,
            },
        }
        self.diagnose_result = diagnose_result or {
            "diagnose_status": "warning",
            "report_id": "DR_AUTO_001",
            "layers": [],
        }
        self.diagnose_error = diagnose_error
        self.generate_calls = []
        self.diagnose_calls = []

    def prepare_rule_request(self, **kwargs):
        return SimpleNamespace(
            rule_id=kwargs["rule_id"],
            hospital_id=kwargs["hospital_id"],
            effective_rule=_Contract(
                {
                    "rule_id": kwargs["rule_id"],
                    "effective_level": "hospital",
                    "national_version": "2025",
                    "hospital_version": 1,
                }
            ),
            field_mapping=_Contract(
                {
                    "db_name": "hospital_demo_data",
                    "main_table": "consult_record",
                }
            ),
        )

    def generate_indicator(self, prepared, **kwargs):
        self.generate_calls.append(kwargs)
        return dict(self.generation)

    def diagnose(self, prepared, **kwargs):
        self.diagnose_calls.append(kwargs)
        if self.diagnose_error:
            raise self.diagnose_error
        return dict(self.diagnose_result)


class _FakeTraceRecorder:
    def __init__(self) -> None:
        self.started = []
        self.nodes = []
        self.finished = []

    def start_trace(self, trace_id, session_id, hospital_id, user_query):
        self.started.append((trace_id, hospital_id, user_query))

    def record_node(self, trace_id, node_name, node_type, status, **kwargs):
        self.nodes.append(
            {
                "trace_id": trace_id,
                "node_name": node_name,
                "node_type": node_type,
                "status": status,
                **kwargs,
            }
        )

    def finish_trace(self, trace_id, final_status, summary, **kwargs):
        self.finished.append((trace_id, final_status, summary))


def _plan(repository: MonitoringRepository, **overrides):
    payload = {
        "plan_id": "PLAN_001",
        "hospital_id": "hospital_001",
        "rule_id": "MQSI2025_005",
        "plan_name": "急会诊月报",
        "frequency": "monthly",
        "run_time": "02:00",
        "mom_threshold_pct": 20,
        "yoy_threshold_pct": 30,
        "created_by": "admin",
    }
    payload.update(overrides)
    return repository.create_plan(payload)


def _service(orchestrator=None, trace_recorder=None):
    from app.monitoring.service import IndicatorRunService

    engine = _monitoring_engine()
    repository = MonitoringRepository(engine)
    _plan(repository)
    service = IndicatorRunService(
        runtime_engine=engine,
        repository=repository,
        orchestrator=orchestrator or _FakeOrchestrator(),
        worker_id="worker-test",
        trace_recorder=trace_recorder,
    )
    return engine, repository, service


class MonitoringRunServiceTest(unittest.TestCase):
    def test_factory_applies_configured_database_lease_seconds(self) -> None:
        from app.monitoring.factory import create_monitoring_service

        engine = _monitoring_engine()
        with patch(
            "app.api.main._create_agent_orchestrator",
            return_value=_FakeOrchestrator(),
        ), patch(
            "app.api.main.create_business_db_client", return_value=object()
        ), patch(
            "app.api.main.create_dbhub_metadata_provider", return_value=object()
        ), patch(
            "app.rules.repository.create_rule_repository", return_value=object()
        ), patch("app.config.get_int", return_value=777):
            service = create_monitoring_service(engine)

        self.assertEqual(service.lease_seconds, 777)

    def test_each_monitoring_run_records_safe_workflow_trace(self) -> None:
        recorder = _FakeTraceRecorder()
        _, repository, service = _service(trace_recorder=recorder)
        repository.create_run_result(
            _result_payload(
                run_key="trace-baseline",
                plan_id="PLAN_001",
                trigger_type="manual",
                stat_start_time=datetime(2026, 6, 1),
                stat_end_time=datetime(2026, 7, 1),
                stat_period="2026-06-01 00:00:00~2026-07-01 00:00:00",
                result_value=50.0,
            )
        )

        result = service.run_plan(
            "PLAN_001",
            stat_period="2026-07-01~2026-07-31",
            trigger_type="manual",
            request_id="REQ_TRACE_MONITOR",
        )

        self.assertTrue(result["trace_id"].startswith("TRACE_"))
        self.assertEqual(
            [node["node_name"] for node in recorder.nodes],
            [
                "monitor_plan_load",
                "monitor_lease_acquire",
                "monitor_period_resolve",
                "monitor_indicator_execute_mcp",
                "monitor_wave_detect",
                "monitor_alert_create",
                "monitor_auto_diagnose",
            ],
        )
        self.assertEqual(recorder.finished[0][1], "success")
        serialized = str(recorder.nodes)
        self.assertNotIn("SELECT", serialized.upper())
        self.assertNotIn("patient_id", serialized.lower())

    def test_manual_alert_diagnosis_reuses_original_result_context(self) -> None:
        orchestrator = _FakeOrchestrator()
        _, repository, service = _service(orchestrator)
        result = repository.create_run_result(
            _result_payload(
                plan_id="PLAN_001",
                run_key="manual-diagnose",
                stat_start_time=datetime(2026, 7, 1),
                stat_end_time=datetime(2026, 8, 1),
                stat_period="2026-07-01 00:00:00~2026-08-01 00:00:00",
            )
        )
        repository.create_alert(
            {
                "alert_id": "ALERT_MANUAL_001",
                "hospital_id": "hospital_001",
                "rule_id": "MQSI2025_005",
                "plan_id": "PLAN_001",
                "result_id": result["id"],
                "alert_type": "wave",
                "conclusion_code": "mom_threshold_exceeded",
            }
        )

        alert = service.diagnose_alert(
            "ALERT_MANUAL_001", "hospital_001"
        )

        self.assertEqual(alert["diagnose_status"], "completed")
        self.assertEqual(alert["diagnose_report_id"], "DR_AUTO_001")
        self.assertEqual(
            orchestrator.diagnose_calls[0]["stat_period"],
            "2026-07-01 00:00:00~2026-08-01 00:00:00",
        )

    def test_wave_alert_triggers_diagnosis_and_persists_audit(self) -> None:
        engine, repository, service = _service()
        repository.create_run_result(
            _result_payload(
                run_key="baseline:june",
                plan_id="PLAN_001",
                trigger_type="manual",
                stat_start_time=datetime(2026, 6, 1),
                stat_end_time=datetime(2026, 7, 1),
                stat_period="2026-06-01 00:00:00~2026-07-01 00:00:00",
                result_value=50.0,
            )
        )

        result = service.run_plan(
            "PLAN_001",
            stat_period="2026-07-01~2026-07-31",
            trigger_type="manual",
            request_id="REQ_001",
        )

        self.assertEqual(result["run_status"], "success")
        self.assertEqual(result["result_value"], 66.67)
        self.assertEqual(result["wave_status"], "mom_threshold_exceeded")
        self.assertEqual(result["mom_change_rate"], 33.34)
        self.assertTrue(result["is_abnormal"])
        self.assertEqual(result["effective_level"], "hospital")
        self.assertEqual(result["hospital_version"], 1)
        self.assertEqual(result["data_source"], "hospital_demo_data")
        self.assertEqual(result["alert"]["diagnose_status"], "completed")
        self.assertEqual(result["alert"]["diagnose_report_id"], "DR_AUTO_001")
        self.assertNotIn("SELECT", str(result).upper())
        self.assertNotIn("patient_id", str(result).lower())

    def test_first_run_has_insufficient_baseline_without_alert(self) -> None:
        _, _, service = _service()

        result = service.run_plan(
            "PLAN_001",
            stat_period="2026-07-01~2026-07-31",
            trigger_type="manual",
            request_id="REQ_FIRST",
        )

        self.assertEqual(result["wave_status"], "baseline_insufficient")
        self.assertFalse(result["is_abnormal"])
        self.assertIsNone(result.get("alert"))

    def test_no_sample_does_not_create_alert(self) -> None:
        orchestrator = _FakeOrchestrator(
            generation={
                "status": "success",
                "sql_id": "SQL_EMPTY",
                "trial_run": {
                    "run_id": "RUN_EMPTY",
                    "status": "success",
                    "result_value": 0.0,
                    "no_sample": True,
                    "duration_ms": 3,
                },
            }
        )
        _, _, service = _service(orchestrator)

        result = service.run_plan(
            "PLAN_001",
            stat_period="2026-07-01~2026-07-31",
            trigger_type="manual",
            request_id="REQ_EMPTY",
        )

        self.assertEqual(result["run_status"], "no_sample")
        self.assertEqual(result["wave_status"], "no_sample")
        self.assertIsNone(result.get("alert"))

    def test_execution_failure_creates_alert_and_recovery_task(self) -> None:
        orchestrator = _FakeOrchestrator(
            generation={
                "status": "field_precheck_failed",
                "message": "missing arrive_time",
            }
        )
        engine, _, service = _service(orchestrator)

        result = service.run_plan(
            "PLAN_001",
            stat_period="2026-07-01~2026-07-31",
            trigger_type="manual",
            request_id="REQ_FAILED",
        )

        self.assertEqual(result["run_status"], "failed")
        self.assertEqual(result["alert"]["alert_type"], "execution_failed")
        tasks = list_recovery_tasks(engine)
        self.assertEqual(tasks[0]["task_type"], "indicator_recompute")
        self.assertNotIn("sql", tasks[0]["payload"])

    def test_diagnosis_failure_does_not_remove_alert(self) -> None:
        orchestrator = _FakeOrchestrator(diagnose_error=RuntimeError("diagnose down"))
        _, repository, service = _service(orchestrator)
        repository.create_run_result(
            _result_payload(
                run_key="baseline:june",
                trigger_type="manual",
                stat_start_time=datetime(2026, 6, 1),
                stat_end_time=datetime(2026, 7, 1),
                stat_period="2026-06-01 00:00:00~2026-07-01 00:00:00",
                result_value=50.0,
            )
        )

        result = service.run_plan(
            "PLAN_001",
            stat_period="2026-07-01~2026-07-31",
            trigger_type="manual",
            request_id="REQ_DIAG_FAIL",
        )

        self.assertEqual(result["alert"]["status"], "open")
        self.assertEqual(result["alert"]["diagnose_status"], "failed")

    def test_scheduled_run_key_is_idempotent(self) -> None:
        orchestrator = _FakeOrchestrator()
        _, _, service = _service(orchestrator)

        first = service.run_plan(
            "PLAN_001",
            stat_period="2026-07-01~2026-07-31",
            trigger_type="scheduled",
        )
        second = service.run_plan(
            "PLAN_001",
            stat_period="2026-07-01~2026-07-31",
            trigger_type="scheduled",
        )

        self.assertEqual(first["id"], second["id"])
        self.assertEqual(len(orchestrator.generate_calls), 1)

    def test_retry_links_original_failure(self) -> None:
        orchestrator = _FakeOrchestrator(
            generation={"status": "field_precheck_failed", "message": "missing"}
        )
        _, repository, service = _service(orchestrator)
        failed = service.run_plan(
            "PLAN_001",
            stat_period="2026-07-01~2026-07-31",
            trigger_type="manual",
            request_id="REQ_FAIL_FIRST",
        )
        orchestrator.generation = _FakeOrchestrator().generation

        retry = service.retry_result(failed["id"], "REQ_RETRY")

        self.assertEqual(retry["retry_of_result_id"], failed["id"])
        self.assertEqual(retry["trigger_type"], "retry")
        self.assertNotEqual(retry["run_key"], failed["run_key"])


if __name__ == "__main__":
    unittest.main()
