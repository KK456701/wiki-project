import unittest

from pydantic import ValidationError
from sqlalchemy import text

from app.indicators.contracts import IndicatorDraftSpec, IndicatorSQLPlan
from app.indicators.metadata import DraftMetadataResolver, MetadataResolutionError
from app.indicators.repository import IndicatorDraftRepository
from app.indicators.sql_plan import SQLPlanError, render_indicator_sql
from app.sqlgen.validator import validate_select_sql
from tests.test_indicator_drafts import _draft_engine


class DraftMetadataResolverTest(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = _metadata_engine()
        self.repository = IndicatorDraftRepository(self.engine)

    def test_suggests_exact_fields_and_reports_missing_fields(self) -> None:
        draft = self.repository.create(_spec(extra_requirement="missing_field"), "user")

        result = DraftMetadataResolver(self.engine, self.repository).suggest(
            draft.draft_id
        )

        self.assertEqual(
            result["suggestions"]["request_time"][0]["column_name"],
            "request_time",
        )
        self.assertIn("missing_field", result["missing_fields"])
        self.assertEqual(result["main_table"], "consult_record")

    def test_reports_ambiguous_fields_from_multiple_databases(self) -> None:
        with self.engine.begin() as conn:
            _insert_column(conn, "archive_db", "consult_record", "request_time")
        draft = self.repository.create(_spec(), "user")

        result = DraftMetadataResolver(self.engine, self.repository).suggest(
            draft.draft_id
        )

        self.assertIn("request_time", result["ambiguous_fields"])

    def test_confirmed_mapping_creates_metadata_ready_version(self) -> None:
        draft = self.repository.create(_spec(), "user")
        resolver = DraftMetadataResolver(self.engine, self.repository)

        confirmed = resolver.confirm(
            draft.draft_id,
            expected_version=1,
            mappings=_confirmed_mappings(),
            actor_id="user",
        )

        self.assertEqual(confirmed.status, "metadata_ready")
        self.assertEqual(confirmed.current_version, 2)
        self.assertEqual(
            confirmed.field_mapping["consult_id"]["column_name"], "consult_id"
        )

    def test_confirm_rejects_missing_or_cross_table_mapping(self) -> None:
        draft = self.repository.create(_spec(), "user")
        mappings = _confirmed_mappings()
        mappings.pop("arrive_time")

        with self.assertRaisesRegex(MetadataResolutionError, "映射不完整"):
            DraftMetadataResolver(self.engine, self.repository).confirm(
                draft.draft_id, 1, mappings, "user"
            )

        mappings = _confirmed_mappings()
        mappings["arrive_time"]["table_name"] = "other_table"
        with self.assertRaisesRegex(MetadataResolutionError, "单一主表"):
            DraftMetadataResolver(self.engine, self.repository).confirm(
                draft.draft_id, 1, mappings, "user"
            )


class IndicatorSQLPlanRendererTest(unittest.TestCase):
    def test_ratio_sql_returns_explainable_aggregate_columns(self) -> None:
        rendered = render_indicator_sql(_plan(), _confirmed_mappings())

        self.assertIn("AS numerator_count", rendered["sql_text"])
        self.assertIn("AS denominator_count", rendered["sql_text"])
        self.assertIn("AS sample_count", rendered["sql_text"])

    def test_ratio_sql_is_parameterized_and_has_mandatory_scope(self) -> None:
        rendered = render_indicator_sql(_plan(), _confirmed_mappings())

        self.assertIn("`hospital_id` = :hospital_id", rendered["sql_text"])
        self.assertIn("`request_time` >= :start_time", rendered["sql_text"])
        self.assertIn("`request_time` < :end_time", rendered["sql_text"])
        self.assertIn("COUNT(DISTINCT CASE WHEN", rendered["sql_text"])
        self.assertNotIn("急会诊", rendered["sql_text"])
        self.assertIn("急会诊", rendered["params"].values())
        self.assertTrue(
            validate_select_sql(
                rendered["sql_text"], "hospital_001", "consult_record"
            )["ok"]
        )

    def test_count_sql_uses_distinct_subject(self) -> None:
        plan = _plan().model_copy(update={"metric_type": "count"})

        rendered = render_indicator_sql(plan, _confirmed_mappings())

        self.assertIn("AS index_value", rendered["sql_text"])
        self.assertNotIn("* 100", rendered["sql_text"])

    def test_duration_condition_uses_parameterized_timestampdiff(self) -> None:
        payload = _plan().model_dump()
        payload["numerator_conditions"] = [
            {
                "field": "arrive_time",
                "operator": "minutes_between_lte",
                "compare_field": "request_time",
                "value": 10,
            }
        ]

        rendered = render_indicator_sql(payload, _confirmed_mappings())

        self.assertIn(
            "TIMESTAMPDIFF(MINUTE, `request_time`, `arrive_time`) BETWEEN 0 AND :num_0",
            rendered["sql_text"],
        )
        self.assertEqual(rendered["params"]["num_0"], 10)
        self.assertTrue(
            validate_select_sql(
                rendered["sql_text"], "hospital_001", "consult_record"
            )["ok"]
        )

    def test_duration_condition_excludes_negative_dirty_intervals(self) -> None:
        payload = _plan().model_dump()
        payload["numerator_conditions"] = [
            {
                "field": "arrive_time",
                "operator": "minutes_between_lte",
                "compare_field": "request_time",
                "value": 10,
            }
        ]

        rendered = render_indicator_sql(payload, _confirmed_mappings())

        self.assertIn(
            "TIMESTAMPDIFF(MINUTE, `request_time`, `arrive_time`) BETWEEN 0 AND :num_0",
            rendered["sql_text"],
        )

    def test_rejects_unknown_operator_unconfirmed_field_and_identifier(self) -> None:
        payload = _plan().model_dump()
        payload["numerator_conditions"][0]["operator"] = "contains_sql"
        with self.assertRaises(ValidationError):
            IndicatorSQLPlan.model_validate(payload)

        payload = _plan().model_dump()
        payload["numerator_conditions"] = [
            {
                "field": "arrive_time",
                "operator": "minutes_between_lte",
                "compare_field": "request_time",
                "value": "1-than",
            }
        ]
        with self.assertRaises(ValidationError):
            IndicatorSQLPlan.model_validate(payload)

        mappings = _confirmed_mappings()
        mappings.pop("consult_type")
        with self.assertRaisesRegex(SQLPlanError, "字段尚未确认"):
            render_indicator_sql(_plan(), mappings)

        mappings = _confirmed_mappings()
        mappings["consult_id"]["column_name"] = "consult_id;DROP"
        with self.assertRaisesRegex(SQLPlanError, "非法标识符"):
            render_indicator_sql(_plan(), mappings)


def _spec(extra_requirement: str | None = None) -> IndicatorDraftSpec:
    requirements = [
        "hospital_id",
        "consult_id",
        "request_time",
        "arrive_time",
        "consult_type",
    ]
    if extra_requirement:
        requirements.append(extra_requirement)
    return IndicatorDraftSpec(
        hospital_id="hospital_001",
        proposed_index_code="HOSP001_METADATA_TEST",
        index_name="夜间急会诊15分钟到位率",
        index_type="会诊制度",
        index_desc="测试定义",
        numerator_rule="15分钟内到位次数",
        denominator_rule="急会诊总次数",
        metric_type="ratio",
        metadata_requirements=requirements,
        sql_plan=_plan(),
    )


def _plan() -> IndicatorSQLPlan:
    return IndicatorSQLPlan.model_validate(
        {
            "main_table": "consult_record",
            "metric_type": "ratio",
            "subject_field": "consult_id",
            "time_field": "request_time",
            "hospital_field": "hospital_id",
            "numerator_conditions": [
                {"field": "consult_type", "operator": "eq", "value": "急会诊"},
                {"field": "arrive_time", "operator": "not_null"},
            ],
            "denominator_conditions": [
                {"field": "consult_type", "operator": "eq", "value": "急会诊"}
            ],
        }
    )


def _confirmed_mappings() -> dict:
    return {
        field: {
            "db_name": "hospital_demo_data",
            "table_name": "consult_record",
            "column_name": field,
            "data_type": "datetime" if field.endswith("time") else "varchar",
            "status": "confirmed",
        }
        for field in (
            "hospital_id",
            "consult_id",
            "request_time",
            "arrive_time",
            "consult_type",
        )
    }


def _metadata_engine():
    engine = _draft_engine()
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                CREATE TABLE med_metadata_column (
                  hospital_id TEXT NOT NULL,
                  db_name TEXT NOT NULL,
                  table_name TEXT NOT NULL,
                  column_name TEXT NOT NULL,
                  data_type TEXT,
                  column_comment TEXT,
                  sync_time TEXT
                )
                """
            )
        )
        for field in (
            "hospital_id",
            "consult_id",
            "request_time",
            "arrive_time",
            "consult_type",
        ):
            _insert_column(conn, "hospital_demo_data", "consult_record", field)
    return engine


def _insert_column(conn, db_name: str, table_name: str, column_name: str) -> None:
    conn.execute(
        text(
            """
            INSERT INTO med_metadata_column
              (hospital_id, db_name, table_name, column_name, data_type,
               column_comment, sync_time)
            VALUES
              ('hospital_001', :db_name, :table_name, :column_name, 'varchar',
               '', '2026-07-10 00:00:00')
            """
        ),
        {
            "db_name": db_name,
            "table_name": table_name,
            "column_name": column_name,
        },
    )


if __name__ == "__main__":
    unittest.main()
