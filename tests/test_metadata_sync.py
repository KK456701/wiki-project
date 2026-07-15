import unittest
from pathlib import Path

from sqlalchemy import create_engine, event, text
from sqlalchemy.pool import StaticPool

from app.metadata.sync import (
    collect_metadata_snapshot,
    diff_metadata_snapshots,
    find_affected_rules,
    sync_metadata_from_provider,
)
from tests.test_kb_tools import make_minimal_kb, temp_kb_dir


class MetadataSyncDiffTest(unittest.TestCase):
    def test_large_sync_persists_with_a_bounded_number_of_connections(self) -> None:
        engine = create_engine(
            "sqlite://",
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
        )
        with engine.begin() as conn:
            conn.execute(text("""CREATE TABLE med_metadata_table (
                hospital_id TEXT, db_name TEXT, table_name TEXT,
                table_comment TEXT, table_type TEXT, sync_batch_id TEXT,
                sync_time TEXT)"""))
            conn.execute(text("""CREATE TABLE med_metadata_column (
                hospital_id TEXT, db_name TEXT, table_name TEXT,
                column_name TEXT, data_type TEXT, column_type TEXT,
                is_nullable TEXT, column_key TEXT, column_default TEXT,
                column_comment TEXT, sync_batch_id TEXT, sync_time TEXT)"""))
            conn.execute(text("""CREATE TABLE med_metadata_sync_log (
                hospital_id TEXT, db_name TEXT, table_name TEXT,
                field_name TEXT, change_type TEXT, change_desc TEXT,
                sync_batch_id TEXT, sync_time TEXT)"""))
            conn.execute(text("""CREATE TABLE med_metadata_snapshot (
                hospital_id TEXT, db_name TEXT, metadata_source TEXT,
                sync_batch_id TEXT, snapshot_json TEXT, created_at TEXT)"""))

        class Provider:
            source_name = "dbhub"

            def list_tables(self, db_name):
                return [{"table_name": "T1", "table_type": "VIEW"}]

            def list_columns(self, db_name, table_name=None):
                return [
                    {
                        "table_name": "T1",
                        "column_name": f"COL_{index}",
                        "data_type": "numeric",
                    }
                    for index in range(100)
                ]

        connection_count = 0

        @event.listens_for(engine, "engine_connect")
        def count_connections(connection):
            nonlocal connection_count
            connection_count += 1

        result = sync_metadata_from_provider(
            engine, Provider(), "hospital_001", "TEST_DB"
        )

        self.assertEqual(result["column_count"], 100)
        self.assertLessEqual(connection_count, 6)

    def test_snapshot_refetches_mapped_tables_after_bulk_result_is_truncated(self) -> None:
        class Provider:
            source_name = "dbhub"
            mapped_scope_only = True

            def __init__(self) -> None:
                self.requested_tables: list[str | None] = []

            def list_tables(self, db_name):
                return [{"table_name": "EARLY_TABLE", "table_type": "VIEW"}]

            def list_columns(self, db_name, table_name=None):
                self.requested_tables.append(table_name)
                if table_name == "INPATIENT_CONSULT_APPLY":
                    return [
                        {
                            "table_name": table_name,
                            "column_name": "APPLY_CONSULT_SENT_AT",
                            "data_type": "datetime",
                        }
                    ]
                return [
                    {
                        "table_name": "EARLY_TABLE",
                        "column_name": "ID",
                        "data_type": "numeric",
                    }
                ]

        with temp_kb_dir() as tmp:
            root = Path(tmp)
            mapping = root / "hospital-mappings" / "hospital_001" / "R001.yaml"
            mapping.parent.mkdir(parents=True, exist_ok=True)
            mapping.write_text(
                """db_name: WIN60_QA_991827
main_table: INPATIENT_CONSULT_APPLY
fields:
  request_time: INPATIENT_CONSULT_APPLY.APPLY_CONSULT_SENT_AT
""",
                encoding="utf-8",
            )
            provider = Provider()

            snapshot = collect_metadata_snapshot(
                provider,
                "WIN60_QA_991827",
                root,
                "hospital_001",
            )

        self.assertIn("INPATIENT_CONSULT_APPLY", provider.requested_tables)
        self.assertNotIn(None, provider.requested_tables)
        self.assertTrue(
            any(
                item["table_name"] == "INPATIENT_CONSULT_APPLY"
                and item["column_name"] == "APPLY_CONSULT_SENT_AT"
                for item in snapshot["columns"]
            )
        )

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
