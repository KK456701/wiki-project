import json
import unittest

from sqlalchemy import text

from app.indicators.metadata import DraftMetadataResolver
from app.indicators.publisher import HospitalIndicatorPublisher
from app.indicators.repository import IndicatorDraftRepository
from app.indicators.service import DraftWorkflowError, IndicatorDraftWorkflowService
from app.rules.repository import MySQLRuleRepository
from tests.test_indicator_sql_plan import (
    _confirmed_mappings,
    _metadata_engine,
    _spec,
)


class IndicatorDraftWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = _workflow_engine()
        self.repository = IndicatorDraftRepository(self.engine)
        self.resolver = DraftMetadataResolver(self.engine, self.repository)
        self.inserted_sql = []
        self.service = IndicatorDraftWorkflowService(
            runtime_engine=self.engine,
            business_db=object(),
            draft_repository=self.repository,
            sql_insert_fn=lambda **kwargs: self.inserted_sql.append(kwargs),
            trial_fn=_successful_trial,
        )

    def test_generate_trial_and_submit_are_tied_to_current_version(self) -> None:
        draft = self.repository.create(_spec(), "user")
        mapped = self.resolver.confirm(
            draft.draft_id, 1, _confirmed_mappings(), "user"
        )

        sql_ready = self.service.generate_sql(
            draft.draft_id, mapped.current_version, "user"
        )
        trial = self.service.trial_run(
            draft.draft_id,
            sql_ready.current_version,
            "2026-07-01 00:00:00",
            "2026-08-01 00:00:00",
            "user",
        )
        submitted = self.service.submit(
            draft.draft_id, trial.current_version, "user"
        )

        self.assertEqual(sql_ready.status, "sql_ready")
        self.assertEqual(trial.status, "trial_passed")
        self.assertEqual(trial.trial_draft_version, trial.current_version)
        self.assertEqual(submitted.status, "pending_approval")
        self.assertEqual(
            submitted.trial_draft_version, submitted.current_version
        )
        self.assertEqual(len(self.inserted_sql), 1)

    def test_submit_before_successful_trial_is_rejected(self) -> None:
        draft = self.repository.create(_spec(), "user")

        with self.assertRaisesRegex(DraftWorkflowError, "试运行通过"):
            self.service.submit(draft.draft_id, 1, "user")


class HospitalIndicatorPublisherTest(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = _workflow_engine()
        self.repository = IndicatorDraftRepository(self.engine)
        self.resolver = DraftMetadataResolver(self.engine, self.repository)
        self.service = IndicatorDraftWorkflowService(
            runtime_engine=self.engine,
            business_db=object(),
            draft_repository=self.repository,
            sql_insert_fn=lambda **kwargs: None,
            trial_fn=_successful_trial,
        )
        self.publisher = HospitalIndicatorPublisher(self.engine, self.repository)

    def test_new_indicator_is_published_searchable_and_restorable(self) -> None:
        pending = self._pending_draft()

        published = self.publisher.approve(
            pending.draft_id, pending.current_version, "admin"
        )
        effective = MySQLRuleRepository(self.engine).get_effective_rule(
            published["formal_index_code"], "hospital_001"
        )
        matches = MySQLRuleRepository(self.engine).search_for_hospital(
            "夜间急会诊", "hospital_001"
        )
        restored = self.publisher.restore_version(
            published["formal_index_code"], "hospital_001", 1, "admin"
        )
        versions = self.publisher.list_versions(
            published["formal_index_code"], "hospital_001"
        )

        self.assertEqual(published["publication_type"], "hospital_defined")
        self.assertEqual(effective["effective_level"], "hospital_defined")
        self.assertEqual(effective["rule_source"], "mysql")
        self.assertEqual(matches["resolved_rule_id"], published["formal_index_code"])
        self.assertEqual(restored["active_version"], 2)
        self.assertEqual([item["version"] for item in versions["versions"]], [2, 1])

    def test_existing_standard_is_published_as_hospital_caliber_difference(self) -> None:
        _insert_standard(self.engine)
        spec = _spec().model_copy(update={"base_index_code": "MQSI2025_005"})
        pending = self._pending_draft(spec)

        published = self.publisher.approve(
            pending.draft_id, pending.current_version, "admin"
        )

        with self.engine.connect() as conn:
            current = conn.execute(
                text(
                    "SELECT version, approval_status FROM med_index_hospital_custom "
                    "WHERE hospital_id='hospital_001' AND index_code='MQSI2025_005'"
                )
            ).one()
            version_count = conn.execute(
                text(
                    "SELECT COUNT(*) FROM med_index_hospital_custom_version "
                    "WHERE hospital_id='hospital_001' AND index_code='MQSI2025_005'"
                )
            ).scalar_one()
        self.assertEqual(published["publication_type"], "hospital_caliber")
        self.assertEqual(current[0], 1)
        self.assertEqual(current[1], "approved")
        self.assertEqual(version_count, 1)

    def _pending_draft(self, spec=None):
        draft = self.repository.create(spec or _spec(), "user")
        mapped = self.resolver.confirm(
            draft.draft_id, 1, _confirmed_mappings(), "user"
        )
        sql_ready = self.service.generate_sql(
            draft.draft_id, mapped.current_version, "user"
        )
        trial = self.service.trial_run(
            draft.draft_id,
            sql_ready.current_version,
            "2026-07-01 00:00:00",
            "2026-08-01 00:00:00",
            "user",
        )
        return self.service.submit(draft.draft_id, trial.current_version, "user")


def _successful_trial(**kwargs):
    return {
        "run_id": "RUN_DRAFT_001",
        "status": "success",
        "result_value": 50.0,
        "sample_count": 4,
        "no_sample": False,
        "duration_ms": 12,
    }


def _workflow_engine():
    engine = _metadata_engine()
    statements = [
        """
        CREATE TABLE med_index_standard (
          index_code TEXT PRIMARY KEY, index_name TEXT, index_type TEXT,
          index_desc TEXT, stat_cycle TEXT, numerator_rule TEXT,
          denominator_rule TEXT, filter_rule TEXT, exclude_rule TEXT,
          rely_table_field TEXT, standard_sql TEXT, rule_params TEXT,
          source_path TEXT, version TEXT, status INTEGER,
          create_time TEXT, update_time TEXT
        )
        """,
        """
        CREATE TABLE med_index_hospital_custom (
          hospital_id TEXT, index_code TEXT, custom_numerator TEXT,
          custom_denominator TEXT, custom_filter TEXT, exclude_rule TEXT,
          custom_params TEXT, custom_sql TEXT, version INTEGER, status INTEGER,
          approval_status TEXT, effective_from TEXT, effective_to TEXT,
          oper_user TEXT, create_time TEXT, update_time TEXT,
          UNIQUE(hospital_id, index_code)
        )
        """,
        """
        CREATE TABLE med_index_hospital_custom_version (
          change_id TEXT UNIQUE, hospital_id TEXT, index_code TEXT,
          version INTEGER, approval_status TEXT, snapshot_json TEXT,
          source_version INTEGER, change_type TEXT, oper_user TEXT,
          approver_id TEXT, created_at TEXT, approved_at TEXT,
          UNIQUE(hospital_id, index_code, version)
        )
        """,
        """
        CREATE TABLE med_index_hospital_defined (
          hospital_id TEXT, index_code TEXT, index_name TEXT, index_type TEXT,
          index_desc TEXT, stat_cycle TEXT, numerator_rule TEXT,
          denominator_rule TEXT, filter_rule TEXT, exclude_rule TEXT,
          field_contract TEXT, sql_template TEXT, rule_params TEXT,
          version INTEGER, status INTEGER, approval_status TEXT,
          effective_from TEXT, effective_to TEXT, source_draft_id TEXT,
          oper_user TEXT, create_time TEXT, update_time TEXT,
          UNIQUE(hospital_id, index_code)
        )
        """,
        """
        CREATE TABLE med_index_hospital_defined_version (
          hospital_id TEXT, index_code TEXT, version INTEGER,
          snapshot_json TEXT, source_version INTEGER, source_draft_id TEXT,
          change_type TEXT, oper_user TEXT, approver_id TEXT,
          created_at TEXT, approved_at TEXT,
          UNIQUE(hospital_id, index_code, version)
        )
        """,
        """
        CREATE TABLE med_field_mapping (
          id INTEGER PRIMARY KEY AUTOINCREMENT, hospital_id TEXT,
          rule_id TEXT, business_field TEXT, db_name TEXT, table_name TEXT,
          column_name TEXT, data_type TEXT, status TEXT, updated_by TEXT,
          updated_at TEXT, UNIQUE(hospital_id, rule_id, business_field)
        )
        """,
    ]
    with engine.begin() as conn:
        for statement in statements:
            conn.execute(text(statement))
    return engine


def _insert_standard(engine) -> None:
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                INSERT INTO med_index_standard
                  (index_code, index_name, index_type, index_desc, stat_cycle,
                   numerator_rule, denominator_rule, filter_rule, exclude_rule,
                   rely_table_field, standard_sql, rule_params, source_path,
                   version, status, create_time, update_time)
                VALUES
                  ('MQSI2025_005', '急会诊及时到位率', '会诊制度', '国标定义',
                   'month', '10分钟内到位次数', '急会诊总次数', '', '', '{}',
                   'SELECT 1', '{}', '', '2025', 1,
                   '2026-07-10 00:00:00', '2026-07-10 00:00:00')
                """
            )
        )


if __name__ == "__main__":
    unittest.main()
