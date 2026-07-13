import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool


def _overview_engine():
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE med_metadata_snapshot (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              hospital_id TEXT, db_name TEXT, metadata_source TEXT,
              sync_batch_id TEXT, snapshot_json TEXT, created_at TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE med_metadata_sync_log (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              hospital_id TEXT, db_name TEXT, table_name TEXT, field_name TEXT,
              change_type TEXT, change_desc TEXT, sync_batch_id TEXT, sync_time TEXT
            )
        """))
    return engine


class MetadataOverviewTest(unittest.TestCase):
    def test_overview_returns_empty_business_state_without_snapshot(self) -> None:
        from app.metadata.overview import load_metadata_overview

        result = load_metadata_overview(
            _overview_engine(),
            Path("core-rules-wiki"),
            "hospital_001",
            "hospital_demo_data",
        )

        self.assertFalse(result["has_snapshot"])
        self.assertEqual(result["db_name"], "hospital_demo_data")
        self.assertEqual(result["table_count"], 0)
        self.assertEqual(result["column_count"], 0)
        self.assertEqual(result["changes"], [])
        self.assertEqual(result["affected_rules"], [])

    def test_overview_returns_latest_batch_changes_and_affected_rules(self) -> None:
        from app.metadata.overview import load_metadata_overview

        engine = _overview_engine()
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            mapping_dir = root / "hospital-mappings" / "hospital_001"
            mapping_dir.mkdir(parents=True)
            (mapping_dir / "MQSI2025_005.yaml").write_text(
                "main_table: consult_record\n"
                "fields:\n"
                "  arrival_time: consult_record.arrival_time\n",
                encoding="utf-8",
            )
            with engine.begin() as conn:
                for batch_id, created_at, table_count in (
                    ("old_batch", "2026-07-12 09:00:00", 0),
                    ("new_batch", "2026-07-13 09:00:00", 1),
                ):
                    snapshot = {
                        "tables": [
                            {"table_name": "consult_record"}
                            for _ in range(table_count)
                        ],
                        "columns": [],
                    }
                    conn.execute(
                        text("""
                            INSERT INTO med_metadata_snapshot
                              (hospital_id, db_name, metadata_source, sync_batch_id,
                               snapshot_json, created_at)
                            VALUES ('hospital_001', 'hospital_demo_data', 'dbhub',
                                    :batch_id, :snapshot_json, :created_at)
                        """),
                        {
                            "batch_id": batch_id,
                            "snapshot_json": json.dumps(snapshot),
                            "created_at": created_at,
                        },
                    )
                conn.execute(text("""
                    INSERT INTO med_metadata_sync_log
                      (hospital_id, db_name, table_name, field_name, change_type,
                       change_desc, sync_batch_id, sync_time)
                    VALUES
                      ('hospital_001', 'hospital_demo_data', '', '', 'full_sync',
                       '同步完成', 'new_batch', '2026-07-13 09:00:00'),
                      ('hospital_001', 'hospital_demo_data', 'consult_record',
                       'arrival_time', 'column_deleted', '删除字段',
                       'new_batch', '2026-07-13 09:00:00')
                """))

            result = load_metadata_overview(
                engine, root, "hospital_001", "hospital_demo_data"
            )

        self.assertTrue(result["has_snapshot"])
        self.assertEqual(result["batch_id"], "new_batch")
        self.assertEqual(result["table_count"], 1)
        self.assertEqual(len(result["changes"]), 1)
        self.assertEqual(result["changes"][0]["change_type"], "column_deleted")
        self.assertEqual(result["affected_rules"][0]["rule_id"], "MQSI2025_005")


if __name__ == "__main__":
    unittest.main()
