import unittest
from unittest.mock import patch

from sqlalchemy import create_engine, inspect, text


class DatabaseEnginePoolTest(unittest.TestCase):
    def test_runtime_engine_reuses_pool_for_same_database_url(self) -> None:
        from app.db import engine as engine_module

        engine_module._create_cached_engine.cache_clear()
        with patch.object(
            engine_module,
            "get",
            return_value="sqlite+pysqlite:///runtime-engine-cache-test.db",
        ):
            first = engine_module.create_runtime_engine()
            second = engine_module.create_runtime_engine()

        self.assertIs(first, second)
        first.dispose()
        engine_module._create_cached_engine.cache_clear()


class RuntimeMigrationTest(unittest.TestCase):
    def test_adds_diagnose_report_columns_idempotently(self) -> None:
        from app.db.migrations import ensure_diagnose_report_schema

        engine = create_engine("sqlite://")
        with engine.begin() as conn:
            conn.execute(
                text(
                    """
                    CREATE TABLE med_index_diagnose_report (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      report_id TEXT NOT NULL UNIQUE,
                      hospital_id TEXT NOT NULL,
                      rule_id TEXT NOT NULL,
                      diagnose_type TEXT NOT NULL,
                      problem_detail TEXT,
                      repair_suggest TEXT,
                      repair_sql TEXT,
                      diagnose_time TEXT NOT NULL,
                      status INTEGER NOT NULL DEFAULT 0
                    )
                    """
                )
            )

        first = ensure_diagnose_report_schema(engine)
        second = ensure_diagnose_report_schema(engine)
        columns = {
            item["name"]
            for item in inspect(engine).get_columns("med_index_diagnose_report")
        }

        self.assertEqual(
            first,
            [
                "trigger_type",
                "related_sql_id",
                "layer_results",
                "diagnose_status",
                "stat_period",
            ],
        )
        self.assertEqual(second, [])
        self.assertTrue(
            {
                "trigger_type",
                "related_sql_id",
                "layer_results",
                "diagnose_status",
                "stat_period",
            }.issubset(columns)
        )

    def test_monitoring_migration_reports_created_objects_once(self) -> None:
        from app.monitoring.schema import ensure_monitoring_schema

        engine = create_engine("sqlite://")
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

        first = ensure_monitoring_schema(engine)
        second = ensure_monitoring_schema(engine)

        self.assertEqual(
            first["created_tables"],
            ["med_indicator_run_plan", "med_indicator_alert"],
        )
        self.assertIn("run_key", first["added_result_columns"])
        self.assertEqual(second, {"created_tables": [], "added_result_columns": []})


if __name__ == "__main__":
    unittest.main()
