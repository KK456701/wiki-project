import unittest
from datetime import datetime
from pathlib import Path

import yaml
from sqlalchemy import create_engine, event, text

from app.agents.contracts import CaliberComparisonContext, FieldMapping
from app.db_access.business_db import BusinessDBClient
from app.db_access.query_result import QueryResult


def _template() -> str:
    spec_dir = next(Path("core-rules-wiki/sql-specs").glob("MQSI2025_005_*"))
    return (spec_dir / "templates" / "mysql.sql.j2").read_text(encoding="utf-8")


def _mapping() -> FieldMapping:
    payload = yaml.safe_load(
        Path(
            "core-rules-wiki/hospital-mappings/hospital_demo/MQSI2025_005.yaml"
        ).read_text(encoding="utf-8")
    )
    return FieldMapping.model_validate(payload)


def _context(**overrides) -> CaliberComparisonContext:
    payload = {
        "rule_id": "MQSI2025_005",
        "hospital_id": "hospital_001",
        "applicable": True,
        "national_sql_template": _template(),
        "national_params": {
            "arrive_minutes_threshold": 10,
            "consult_type_value": "急会诊",
        },
        "national_version": "2025",
        "effective_sql_template": _template(),
        "effective_params": {
            "arrive_minutes_threshold": 20,
            "consult_type_value": "急会诊",
        },
        "hospital_version": 1,
        "overridden_fields": ["arrive_minutes_threshold"],
    }
    payload.update(overrides)
    return CaliberComparisonContext.model_validate(payload)


def _runtime_engine():
    engine = create_engine("sqlite://")

    @event.listens_for(engine, "connect")
    def register_now(dbapi_connection, _connection_record):
        dbapi_connection.create_function(
            "NOW", 0, lambda: "2026-07-10 12:00:00"
        )

    with engine.begin() as conn:
        conn.execute(
            text(
                """
                CREATE TABLE med_sql_run_log (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  run_id TEXT NOT NULL UNIQUE,
                  sql_id TEXT,
                  hospital_id TEXT NOT NULL,
                  rule_id TEXT NOT NULL,
                  stat_start_time TEXT,
                  stat_end_time TEXT,
                  run_status TEXT NOT NULL,
                  result_value REAL,
                  error_message TEXT,
                  duration_ms INTEGER,
                  run_by TEXT,
                  run_time TEXT NOT NULL
                )
                """
            )
        )
    return engine


def _sqlite_business_db() -> tuple[BusinessDBClient, list[str]]:
    engine = create_engine("sqlite://")
    executed: list[str] = []

    @event.listens_for(engine, "connect")
    def register_timestampdiff(dbapi_connection, _connection_record):
        def timestampdiff(unit, start, end):
            start_dt = datetime.fromisoformat(str(start))
            end_dt = datetime.fromisoformat(str(end))
            return int((end_dt - start_dt).total_seconds() / 60)

        dbapi_connection.create_function("TIMESTAMPDIFF", 3, timestampdiff)

    with engine.begin() as conn:
        conn.execute(
            text(
                """
                CREATE TABLE consult_record (
                  hospital_id TEXT, patient_id TEXT, consult_type TEXT,
                  request_time TEXT, arrive_time TEXT, status TEXT, dept_id TEXT
                )
                """
            )
        )
        conn.execute(
            text(
                """
                INSERT INTO consult_record VALUES
                  ('hospital_001','P001','急会诊','2026-07-01 10:00:00','2026-07-01 10:08:00','完成','D001'),
                  ('hospital_001','P002','急会诊','2026-07-01 11:00:00','2026-07-01 11:15:00','完成','D001'),
                  ('hospital_001','P003','急会诊','2026-07-01 12:00:00','2026-07-01 12:30:00','完成','D002')
                """
            )
        )

    def execute(sql: str):
        executed.append(sql)
        sqlite_sql = sql.replace(
            "TIMESTAMPDIFF(MINUTE,", "TIMESTAMPDIFF('MINUTE',"
        )
        with engine.connect() as conn:
            return [dict(row) for row in conn.execute(text(sqlite_sql)).mappings()]

    return (
        BusinessDBClient(
            execute,
            "hospital_demo_data",
            "execute_sql_hospital_demo_data",
        ),
        executed,
    )


class _SequencedBusinessDB:
    def __init__(self, outcomes):
        self.outcomes = list(outcomes)
        self.sql: list[str] = []

    def execute_select(self, sql: str) -> QueryResult:
        self.sql.append(sql)
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return QueryResult(
            rows=[outcome],
            row_count=1,
            source="hospital_demo_data",
            tool_name="execute_sql_hospital_demo_data",
            duration_ms=2,
        )


class DiagnosePeriodTest(unittest.TestCase):
    def test_defaults_to_current_month_and_accepts_inclusive_date_range(self) -> None:
        from app.diagnose.caliber_compare import parse_diagnose_period

        now = datetime(2026, 7, 10, 12, 0, 0)

        self.assertEqual(
            parse_diagnose_period(None, now),
            (
                "2026-07-01 00:00:00",
                "2026-08-01 00:00:00",
                "2026-07-01 00:00:00~2026-08-01 00:00:00",
            ),
        )
        self.assertEqual(
            parse_diagnose_period("2026-07-01~2026-07-31", now)[:2],
            ("2026-07-01 00:00:00", "2026-08-01 00:00:00"),
        )

    def test_rejects_invalid_or_reversed_period_before_execution(self) -> None:
        from app.diagnose.caliber_compare import (
            CaliberCompareError,
            parse_diagnose_period,
        )

        for value in ("2026/07/01", "2026-08-01~2026-07-01"):
            with self.subTest(value=value):
                with self.assertRaises(CaliberCompareError):
                    parse_diagnose_period(value)


class CaliberCompareExecutionTest(unittest.TestCase):
    def test_executes_national_and_hospital_calibers_with_same_scope(self) -> None:
        from app.diagnose.caliber_compare import execute_caliber_comparison

        runtime_engine = _runtime_engine()
        business_db, executed = _sqlite_business_db()

        result = execute_caliber_comparison(
            runtime_engine=runtime_engine,
            business_db=business_db,
            context=_context(),
            field_mapping=_mapping(),
            stat_period="2026-07-01~2026-07-31",
        )

        self.assertEqual(result["conclusion_code"], "caliber_result_diff")
        self.assertFalse(result["blocking"])
        self.assertEqual(result["national"]["result_value"], 33.33)
        self.assertEqual(result["hospital"]["result_value"], 66.67)
        self.assertEqual(result["absolute_delta"], 33.34)
        self.assertEqual(result["national"]["version"], "2025")
        self.assertEqual(result["hospital"]["version"], 1)
        self.assertEqual(len(executed), 2)
        self.assertTrue(all(sql.lower().startswith("select") for sql in executed))
        self.assertTrue(all(":hospital_id" not in sql for sql in executed))
        self.assertNotIn("sql", result["national"])
        with runtime_engine.connect() as conn:
            logs = conn.execute(
                text("SELECT run_status, result_value FROM med_sql_run_log ORDER BY id")
            ).all()
        self.assertEqual(len(logs), 2)
        self.assertEqual([row[0] for row in logs], ["success", "success"])

    def test_classifies_same_result_and_no_sample(self) -> None:
        from app.diagnose.caliber_compare import execute_caliber_comparison

        cases = [
            (
                [{"index_value": 50, "sample_count": 4}] * 2,
                "caliber_result_same",
            ),
            (
                [{"index_value": 0, "sample_count": 0}] * 2,
                "caliber_no_sample",
            ),
        ]
        for outcomes, expected in cases:
            with self.subTest(expected=expected):
                result = execute_caliber_comparison(
                    runtime_engine=_runtime_engine(),
                    business_db=_SequencedBusinessDB(outcomes),
                    context=_context(),
                    field_mapping=_mapping(),
                    stat_period="2026-07-01~2026-07-31",
                )
                self.assertEqual(result["conclusion_code"], expected)
                self.assertFalse(result["blocking"])

    def test_execution_failure_is_blocking_and_identifies_side(self) -> None:
        from app.diagnose.caliber_compare import execute_caliber_comparison

        cases = [
            (
                [
                    {"index_value": 50, "sample_count": 4},
                    RuntimeError("hospital failed"),
                ],
                "hospital_caliber_execution_failed",
            ),
            (
                [RuntimeError("national failed"), {"index_value": 50, "sample_count": 4}],
                "national_caliber_execution_failed",
            ),
            (
                [RuntimeError("national failed"), RuntimeError("hospital failed")],
                "shared_caliber_execution_failed",
            ),
        ]
        for outcomes, expected in cases:
            with self.subTest(expected=expected):
                result = execute_caliber_comparison(
                    runtime_engine=_runtime_engine(),
                    business_db=_SequencedBusinessDB(outcomes),
                    context=_context(),
                    field_mapping=_mapping(),
                    stat_period="2026-07-01~2026-07-31",
                )
                self.assertEqual(result["conclusion_code"], expected)
                self.assertTrue(result["blocking"])

    def test_missing_parameter_fails_before_business_database_call(self) -> None:
        from app.diagnose.caliber_compare import execute_caliber_comparison

        business_db = _SequencedBusinessDB([])
        result = execute_caliber_comparison(
            runtime_engine=_runtime_engine(),
            business_db=business_db,
            context=_context(national_params={}, effective_params={}),
            field_mapping=_mapping(),
            stat_period="2026-07-01~2026-07-31",
        )

        self.assertEqual(result["conclusion_code"], "shared_caliber_execution_failed")
        self.assertEqual(business_db.sql, [])
        self.assertEqual(result["national"]["error_code"], "sql_parameter_missing")

    def test_not_applicable_context_does_not_execute(self) -> None:
        from app.diagnose.caliber_compare import execute_caliber_comparison

        business_db = _SequencedBusinessDB([])
        result = execute_caliber_comparison(
            runtime_engine=_runtime_engine(),
            business_db=business_db,
            context=_context(applicable=False, reason="no_hospital_customization"),
            field_mapping=_mapping(),
            stat_period="2026-07-01~2026-07-31",
        )

        self.assertEqual(result["conclusion_code"], "caliber_compare_not_applicable")
        self.assertEqual(result["reason"], "no_hospital_customization")
        self.assertFalse(result["blocking"])
        self.assertEqual(business_db.sql, [])


if __name__ == "__main__":
    unittest.main()
