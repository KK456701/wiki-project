import unittest
from pathlib import Path

from app.metadata.sync import diff_metadata_snapshots, find_affected_rules
from tests.test_kb_tools import make_minimal_kb, temp_kb_dir


class MetadataSyncDiffTest(unittest.TestCase):
    def test_diff_metadata_snapshots_detects_table_and_column_changes(self) -> None:
        previous = {
            "tables": [
                {"table_name": "consult_record", "table_comment": "", "table_type": "BASE TABLE"},
            ],
            "columns": [
                {"table_name": "consult_record", "column_name": "arrive_time", "data_type": "datetime", "column_type": "datetime", "is_nullable": "YES"},
                {"table_name": "consult_record", "column_name": "old_col", "data_type": "varchar", "column_type": "varchar(32)", "is_nullable": "YES"},
            ],
        }
        current = {
            "tables": [
                {"table_name": "consult_record", "table_comment": "", "table_type": "BASE TABLE"},
                {"table_name": "new_table", "table_comment": "", "table_type": "BASE TABLE"},
            ],
            "columns": [
                {"table_name": "consult_record", "column_name": "arrive_time", "data_type": "timestamp", "column_type": "timestamp", "is_nullable": "NO"},
                {"table_name": "consult_record", "column_name": "new_col", "data_type": "varchar", "column_type": "varchar(32)", "is_nullable": "YES"},
            ],
        }

        changes = diff_metadata_snapshots(previous, current)
        change_types = {(c["change_type"], c["table_name"], c.get("field_name", "")) for c in changes}

        self.assertIn(("table_added", "new_table", ""), change_types)
        self.assertIn(("column_added", "consult_record", "new_col"), change_types)
        self.assertIn(("column_deleted", "consult_record", "old_col"), change_types)
        self.assertIn(("column_type_changed", "consult_record", "arrive_time"), change_types)
        self.assertIn(("column_nullable_changed", "consult_record", "arrive_time"), change_types)

    def test_find_affected_rules_uses_hospital_mapping(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            mapping = root / "hospital-mappings" / "hospital_001" / "R001.yaml"
            mapping.parent.mkdir(parents=True, exist_ok=True)
            mapping.write_text("""db_name: hospital_demo_data
main_table: consult_record
fields:
  arrive_time: consult_record.arrive_time
""", encoding="utf-8")
            changes = [{"table_name": "consult_record", "field_name": "arrive_time", "change_type": "column_type_changed"}]

            impacted = find_affected_rules(root, "hospital_001", changes)

            self.assertEqual(impacted[0]["rule_id"], "R001")
            self.assertIn("arrive_time", impacted[0]["matched_columns"])


if __name__ == "__main__":
    unittest.main()
