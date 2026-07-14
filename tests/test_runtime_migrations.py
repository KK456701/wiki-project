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
    def test_hospital_auth_migration_is_idempotent(self) -> None:
        import importlib.util

        self.assertIsNotNone(
            importlib.util.find_spec("app.hospital_auth.schema"),
            "医院账号认证迁移尚未实现",
        )
        from app.hospital_auth.schema import AUTH_TABLES, ensure_hospital_auth_schema

        engine = create_engine("sqlite://")

        first = ensure_hospital_auth_schema(engine)
        second = ensure_hospital_auth_schema(engine)

        self.assertEqual(first["created_tables"], list(AUTH_TABLES))
        self.assertEqual(second, {"created_tables": []})
        self.assertTrue(set(AUTH_TABLES).issubset(inspect(engine).get_table_names()))

    def test_rule_lineage_migration_is_idempotent(self) -> None:
        from app.rules.schema import ensure_rule_lineage_schema

        engine = create_engine("sqlite://")
        with engine.begin() as conn:
            conn.execute(
                text(
                    """
                    CREATE TABLE med_index_standard (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      index_code TEXT NOT NULL UNIQUE
                    )
                    """
                )
            )
            conn.execute(
                text(
                    """
                    CREATE TABLE med_index_hospital_custom (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      hospital_id TEXT NOT NULL,
                      index_code TEXT NOT NULL
                    )
                    """
                )
            )

        first = ensure_rule_lineage_schema(engine)
        second = ensure_rule_lineage_schema(engine)
        standard_columns = {
            item["name"]
            for item in inspect(engine).get_columns("med_index_standard")
        }
        custom_columns = {
            item["name"]
            for item in inspect(engine).get_columns("med_index_hospital_custom")
        }
        relation_columns = {
            item["name"]
            for item in inspect(engine).get_columns("med_table_relation")
        }

        self.assertEqual(
            first,
            {
                "added_columns": [
                    "med_index_standard.calculation_definition",
                    "med_index_hospital_custom.custom_calculation_patch",
                ],
                "created_tables": ["med_table_relation"],
            },
        )
        self.assertEqual(second, {"added_columns": [], "created_tables": []})
        self.assertIn("calculation_definition", standard_columns)
        self.assertIn("custom_calculation_patch", custom_columns)
        self.assertTrue(
            {
                "hospital_id",
                "db_name",
                "left_table",
                "left_column",
                "right_table",
                "right_column",
                "join_type",
                "relation_source",
                "status",
            }.issubset(relation_columns)
        )

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
