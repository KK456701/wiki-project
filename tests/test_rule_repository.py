import unittest
from pathlib import Path


class RuntimeRuleSchemaTest(unittest.TestCase):
    def test_runtime_schema_contains_rule_store_tables(self) -> None:
        ddl = Path("scripts/init_runtime_db.sql").read_text(encoding="utf-8")
        for table in (
            "med_index_standard",
            "med_index_hospital_custom",
            "med_index_hospital_custom_version",
        ):
            self.assertIn(f"CREATE TABLE IF NOT EXISTS {table}", ddl)
        for column in (
            "standard_sql LONGTEXT",
            "rule_params JSON",
            "custom_params JSON",
            "approval_status VARCHAR(32)",
            "effective_from DATETIME",
            "effective_to DATETIME",
            "snapshot_json JSON",
        ):
            self.assertIn(column, ddl)


if __name__ == "__main__":
    unittest.main()
