import unittest
from pathlib import Path
from unittest.mock import patch

import yaml

from app.agent.graph import detect_intent
from app.db_access.query_result import QueryResult
from app.sqlgen.runner import run_sql_trial
from app.sqlgen.template_renderer import render_sql
from app.sqlgen.validator import validate_select_sql


class SqlGenerationSafetyTest(unittest.TestCase):
    def test_validator_rejects_main_table_only_in_comment(self) -> None:
        sql = "SELECT * FROM other_table WHERE x=:start_time AND y=:end_time -- consult_record"

        result = validate_select_sql(sql, "hospital_001", "consult_record")

        self.assertFalse(result["ok"])

    def test_validator_rejects_unparenthesized_or_in_where_clause(self) -> None:
        sql = (
            "SELECT * FROM consult_record "
            "WHERE hospital_id=:hospital_id "
            "AND request_time>=:start_time "
            "AND request_time<:end_time "
            "AND dept_id != 'ICU' OR '1'='1'"
        )

        result = validate_select_sql(sql, "hospital_001", "consult_record")

        self.assertFalse(result["ok"])

    def test_llm_filter_value_is_parameterized_not_inlined(self) -> None:
        spec_dir = next(Path("core-rules-wiki/sql-specs").glob("MQSI2025_005_*"))
        mapping = yaml.safe_load(
            Path("core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_005.yaml").read_text(encoding="utf-8")
        )
        template = (spec_dir / "templates" / "mysql.sql.j2").read_text(encoding="utf-8")

        sql = render_sql(
            template,
            mapping["fields"],
            mapping["main_table"],
            {"exclude_depts": ["ICU' OR '1'='1"], "count_multiple_transfers": False},
        )

        self.assertNotIn("ICU' OR '1'='1", sql)
        self.assertIn(":exclude_dept_0", sql)

    def test_transfer_template_distinct_count_names_the_identifier(self) -> None:
        spec_dir = next(Path("core-rules-wiki/sql-specs").glob("MQSI2025_001_*"))
        mapping = yaml.safe_load(
            Path("core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_001.yaml").read_text(encoding="utf-8")
        )
        template = (spec_dir / "templates" / "mysql.sql.j2").read_text(encoding="utf-8")
        rules = dict(mapping["custom_rules"])
        rules["count_multiple_transfers"] = False

        sql = render_sql(template, mapping["fields"], mapping["main_table"], rules)

        self.assertNotIn("COUNT(DISTINCT)", sql)
        self.assertIn("COUNT(DISTINCT inpatient_transfer_record.admission_id)", sql)

    def test_hospital_query_about_current_rule_is_not_feedback(self) -> None:
        query = "\u6211\u4eec\u533b\u9662\u5f53\u524d\u91c7\u7528\u54ea\u4e2a\u53e3\u5f84\uff1f"

        self.assertEqual(detect_intent(query), "query")

    def test_run_sql_trial_uses_business_db_mcp_and_binds_params(self) -> None:
        class FakeBusinessDB:
            def __init__(self) -> None:
                self.sql: list[str] = []

            def execute_select(self, sql: str) -> QueryResult:
                self.sql.append(sql)
                return QueryResult(
                    rows=[{"index_value": "50.0"}],
                    row_count=1,
                    source="hospital_demo_data",
                    tool_name="execute_sql_hospital_demo_data",
                    duration_ms=3,
                )

        fake_db = FakeBusinessDB()
        logs: list[tuple] = []
        sql = (
            "SELECT :arrive_minutes_threshold AS index_value "
            "FROM consult_record "
            "WHERE hospital_id = :hospital_id "
            "AND request_time >= :start_time "
            "AND request_time < :end_time "
            "AND consult_type = :consult_type_value"
        )

        with patch("app.sqlgen.runner.insert_sql_run_log", side_effect=lambda *args: logs.append(args)):
            result = run_sql_trial(
                object(),
                fake_db,
                "SQL_TEST",
                sql,
                "hospital_001",
                "MQSI2025_005",
                "2026-07-01",
                "2026-08-01",
                {"arrive_minutes_threshold": 20, "consult_type_value": "急会诊"},
                "tester",
            )

        self.assertEqual(result["status"], "success")
        self.assertEqual(result["result_value"], 50.0)
        self.assertEqual(len(fake_db.sql), 1)
        self.assertNotIn(":", fake_db.sql[0])
        self.assertIn("20 AS index_value", fake_db.sql[0])
        self.assertIn("hospital_id = 'hospital_001'", fake_db.sql[0])
        self.assertIn("consult_type = '急会诊'", fake_db.sql[0])
        self.assertEqual(logs[0][7], "success")

    def test_run_sql_trial_missing_param_fails_before_mcp(self) -> None:
        class FakeBusinessDB:
            def __init__(self) -> None:
                self.sql: list[str] = []

            def execute_select(self, sql: str) -> QueryResult:
                self.sql.append(sql)
                return QueryResult([], 0, "hospital_demo_data", "execute_sql_hospital_demo_data", 0)

        fake_db = FakeBusinessDB()
        logs: list[tuple] = []

        with patch("app.sqlgen.runner.insert_sql_run_log", side_effect=lambda *args: logs.append(args)):
            result = run_sql_trial(
                object(),
                fake_db,
                "SQL_TEST",
                "SELECT :missing_param AS index_value FROM consult_record",
                "hospital_001",
                "MQSI2025_005",
                "2026-07-01",
                "2026-08-01",
                {},
                "tester",
            )

        self.assertEqual(result["status"], "failed")
        self.assertIn("SQL 参数缺失", result["error_message"])
        self.assertEqual(fake_db.sql, [])
        self.assertEqual(logs[0][7], "failed")


if __name__ == "__main__":
    unittest.main()
