import json
import unittest
from pathlib import Path

from sqlalchemy import text

from app.rules.calculation import (
    collect_business_dependencies,
    parse_calculation_definition,
    validate_calculation_definition,
)
from tests.test_rule_repository import _rule_engine


def _count(engine, table_name: str) -> int:
    with engine.connect() as conn:
        return int(conn.execute(text(f"SELECT COUNT(*) FROM {table_name}")).scalar_one())


class FourIndicatorRuleImporterTest(unittest.TestCase):
    def test_import_is_idempotent_and_seeds_four_rules(self) -> None:
        from app.rules.importer import FOUR_INDICATOR_CODES, import_four_indicator_rules

        engine = _rule_engine()

        first = import_four_indicator_rules(engine, Path("core-rules-wiki"))
        second = import_four_indicator_rules(engine, Path("core-rules-wiki"))

        self.assertEqual(first["failed"], [])
        self.assertEqual(second["failed"], [])
        self.assertEqual(
            FOUR_INDICATOR_CODES,
            (
                "MQSI2025_001",
                "MQSI2025_005",
                "MQSI2025_014",
                "MQSI2025_035",
            ),
        )
        self.assertEqual(_count(engine, "med_index_standard"), 4)
        self.assertEqual(_count(engine, "med_index_hospital_custom"), 1)
        self.assertEqual(_count(engine, "med_index_hospital_custom_version"), 1)

        with engine.connect() as conn:
            standard_params = conn.execute(
                text(
                    "SELECT rule_params FROM med_index_standard "
                    "WHERE index_code='MQSI2025_005'"
                )
            ).scalar_one()
            standard_sql = conn.execute(
                text(
                    "SELECT standard_sql FROM med_index_standard "
                    "WHERE index_code='MQSI2025_005'"
                )
            ).scalar_one()
            custom_params = conn.execute(
                text(
                    "SELECT custom_params FROM med_index_hospital_custom "
                    "WHERE hospital_id='hospital_001' AND index_code='MQSI2025_005'"
                )
            ).scalar_one()
            mapping_groups = conn.execute(
                text("SELECT COUNT(DISTINCT rule_id) FROM med_field_mapping")
            ).scalar_one()

        self.assertEqual(json.loads(standard_params)["arrive_minutes_threshold"], 10)
        self.assertEqual(json.loads(custom_params)["arrive_minutes_threshold"], 20)
        self.assertNotIn("30", standard_params)
        self.assertNotIn("30", custom_params)
        self.assertIn("exclude_dept_filters", standard_sql)
        self.assertEqual(mapping_groups, 4)

    def test_import_persists_valid_calculation_definitions(self) -> None:
        from app.rules.importer import import_four_indicator_rules

        engine = _rule_engine()
        result = import_four_indicator_rules(engine, Path("core-rules-wiki"))

        self.assertEqual(result["failed"], [])
        with engine.connect() as conn:
            rows = conn.execute(
                text(
                    "SELECT index_code, calculation_definition, "
                    "rely_table_field, rule_params "
                    "FROM med_index_standard ORDER BY index_code"
                )
            ).mappings().all()
        self.assertEqual(len(rows), 4)
        for row in rows:
            with self.subTest(index_code=row["index_code"]):
                definition = parse_calculation_definition(
                    row["calculation_definition"]
                )
                field_contract = json.loads(row["rely_table_field"])
                params = {
                    "hospital_id": "hospital_001",
                    "start_time": "2026-07-01 00:00:00",
                    "end_time": "2026-08-01 00:00:00",
                    **json.loads(row["rule_params"]),
                }
                self.assertEqual(
                    validate_calculation_definition(
                        definition, field_contract["business_fields"], params
                    ),
                    [],
                )

        urgent = parse_calculation_definition(
            next(
                row["calculation_definition"]
                for row in rows
                if row["index_code"] == "MQSI2025_005"
            )
        )
        self.assertEqual(
            urgent.derived_fields["arrive_minutes"].source_fields,
            ["request_time", "arrive_time"],
        )
        self.assertEqual(
            collect_business_dependencies(urgent),
            {"hospital_id", "consult_type", "request_time", "arrive_time"},
        )
        self.assertEqual(
            [item.field for item in urgent.detail_fields],
            [
                "patient_id",
                "dept_id",
                "consult_type",
                "request_time",
                "arrive_time",
                "arrive_minutes",
            ],
        )
        self.assertEqual(urgent.detail_fields[0].label, "患者标识")

        with engine.connect() as conn:
            patient_mapping = conn.execute(
                text(
                    "SELECT table_name, column_name FROM med_field_mapping "
                    "WHERE hospital_id='hospital_001' "
                    "AND rule_id='MQSI2025_005' AND business_field='patient_id'"
                )
            ).mappings().one()
        self.assertEqual(dict(patient_mapping), {"table_name": "consult_record", "column_name": "patient_id"})


if __name__ == "__main__":
    unittest.main()
