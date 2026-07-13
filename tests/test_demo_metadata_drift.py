import subprocess
import sys
import unittest
from pathlib import Path

from app.demo_data.metadata_drift import metadata_drift_sql


class DemoMetadataDriftTest(unittest.TestCase):
    def test_add_action_creates_optional_priority_field(self) -> None:
        sql = metadata_drift_sql("add")

        self.assertIn("ALTER TABLE consult_record", sql)
        self.assertIn("ADD COLUMN consult_priority VARCHAR(16)", sql)

    def test_modify_action_changes_priority_field_type(self) -> None:
        sql = metadata_drift_sql("modify")

        self.assertIn("MODIFY COLUMN consult_priority VARCHAR(64)", sql)

    def test_remove_and_restore_return_to_baseline_schema(self) -> None:
        self.assertIn("DROP COLUMN consult_priority", metadata_drift_sql("remove"))
        self.assertIn("DROP COLUMN consult_priority", metadata_drift_sql("restore"))

    def test_unknown_action_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "add、modify、remove 或 restore"):
            metadata_drift_sql("rename")

    def test_script_previews_without_changing_database(self) -> None:
        root = Path(__file__).resolve().parents[1]
        result = subprocess.run(
            [sys.executable, "scripts/simulate_metadata_drift.py", "add"],
            cwd=root,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("ADD COLUMN consult_priority", result.stdout)


if __name__ == "__main__":
    unittest.main()
