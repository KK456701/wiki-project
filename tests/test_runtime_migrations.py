import unittest

from sqlalchemy import create_engine, inspect, text


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


if __name__ == "__main__":
    unittest.main()
