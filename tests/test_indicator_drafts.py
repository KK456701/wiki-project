import json
import unittest
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.indicators.contracts import IndicatorDraftSpec
from app.indicators.repository import DraftVersionConflict, IndicatorDraftRepository


class IndicatorDraftSchemaTest(unittest.TestCase):
    def test_runtime_schema_contains_draft_and_hospital_defined_tables(self) -> None:
        ddl = Path("scripts/init_runtime_db.sql").read_text(encoding="utf-8")

        self.assertIn("CREATE TABLE IF NOT EXISTS med_indicator_draft", ddl)
        self.assertIn("CREATE TABLE IF NOT EXISTS med_indicator_draft_version", ddl)
        self.assertIn("CREATE TABLE IF NOT EXISTS med_index_hospital_defined", ddl)
        self.assertIn("CREATE TABLE IF NOT EXISTS med_index_hospital_defined_version", ddl)


class IndicatorDraftRepositoryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = _draft_engine()
        self.repository = IndicatorDraftRepository(self.engine)

    def test_create_and_save_append_immutable_versions(self) -> None:
        created = self.repository.create(_spec(), "user_001")
        saved = self.repository.save_version(
            created.draft_id,
            expected_version=1,
            changes={"index_desc": "修改后的指标定义"},
            actor_id="user_001",
        )
        versions = self.repository.list_versions(created.draft_id)

        self.assertEqual(created.status, "requirements_pending")
        self.assertEqual(created.current_version, 1)
        self.assertEqual(saved.current_version, 2)
        self.assertEqual(saved.index_desc, "修改后的指标定义")
        self.assertEqual([item["version"] for item in versions], [2, 1])
        self.assertEqual(versions[1]["snapshot"]["index_desc"], "测试指标定义")
        self.assertEqual(versions[0]["snapshot"]["index_desc"], "修改后的指标定义")

    def test_stale_version_cannot_overwrite_current_draft(self) -> None:
        created = self.repository.create(_spec(), "user_001")
        self.repository.save_version(
            created.draft_id,
            expected_version=1,
            changes={"index_desc": "版本2"},
            actor_id="user_001",
        )

        with self.assertRaises(DraftVersionConflict):
            self.repository.save_version(
                created.draft_id,
                expected_version=1,
                changes={"index_desc": "错误覆盖"},
                actor_id="user_002",
            )

        current = self.repository.get(created.draft_id)
        self.assertEqual(current.current_version, 2)
        self.assertEqual(current.index_desc, "版本2")

    def test_edit_invalidates_sql_and_trial_evidence(self) -> None:
        created = self.repository.create(_spec(), "user_001")
        ready = self.repository.transition(
            created.draft_id,
            expected_version=1,
            status="trial_passed",
            changes={
                "current_sql": "SELECT 1",
                "sql_id": "SQL_001",
                "trial_result": {"status": "success", "result_value": 50},
                "trial_draft_version": 2,
            },
            actor_id="agent",
            change_type="trial_run",
        )

        edited = self.repository.save_version(
            created.draft_id,
            expected_version=ready.current_version,
            changes={"numerator_rule": "新的分子规则"},
            actor_id="user_001",
        )

        self.assertEqual(edited.status, "requirements_pending")
        self.assertIsNone(edited.current_sql)
        self.assertIsNone(edited.sql_id)
        self.assertEqual(edited.trial_result, {})
        self.assertIsNone(edited.trial_draft_version)


def _spec() -> IndicatorDraftSpec:
    return IndicatorDraftSpec(
        hospital_id="hospital_001",
        proposed_index_code="HOSP001_20260710_001",
        index_name="夜间急会诊15分钟到位率",
        index_type="会诊制度",
        index_desc="测试指标定义",
        stat_cycle="month",
        numerator_rule="15分钟内到位次数",
        denominator_rule="夜间急会诊总次数",
        filter_rule="18点至次日8点",
        exclude_rule="",
        metric_type="ratio",
        metadata_requirements=[
            "hospital_id",
            "request_time",
            "arrive_time",
            "consult_id",
        ],
    )


def _draft_engine():
    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                CREATE TABLE med_indicator_draft (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  draft_id TEXT NOT NULL UNIQUE,
                  hospital_id TEXT NOT NULL,
                  base_index_code TEXT,
                  proposed_index_code TEXT NOT NULL,
                  index_name TEXT NOT NULL,
                  index_type TEXT NOT NULL,
                  index_desc TEXT NOT NULL,
                  stat_cycle TEXT NOT NULL,
                  numerator_rule TEXT NOT NULL,
                  denominator_rule TEXT NOT NULL,
                  filter_rule TEXT,
                  exclude_rule TEXT,
                  metric_type TEXT NOT NULL,
                  metadata_requirements TEXT NOT NULL,
                  field_mapping TEXT NOT NULL,
                  sql_plan TEXT NOT NULL,
                  current_sql TEXT,
                  sql_params TEXT NOT NULL,
                  sql_id TEXT,
                  trial_result TEXT NOT NULL,
                  trial_draft_version INTEGER,
                  status TEXT NOT NULL,
                  current_version INTEGER NOT NULL,
                  formal_index_code TEXT,
                  generated_by TEXT,
                  created_by TEXT NOT NULL,
                  updated_by TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE med_indicator_draft_version (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  draft_id TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  status TEXT NOT NULL,
                  snapshot_json TEXT NOT NULL,
                  change_type TEXT NOT NULL,
                  oper_user TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  UNIQUE(draft_id, version)
                )
                """
            )
        )
    return engine


if __name__ == "__main__":
    unittest.main()
