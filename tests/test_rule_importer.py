import json
import unittest
from pathlib import Path

from sqlalchemy import text

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
        self.assertEqual(mapping_groups, 4)


if __name__ == "__main__":
    unittest.main()
