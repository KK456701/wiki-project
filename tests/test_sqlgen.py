import unittest
from pathlib import Path

import yaml

from app.agent.graph import detect_intent
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


if __name__ == "__main__":
    unittest.main()
