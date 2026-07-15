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
    def test_formal_sqlserver_import_replaces_demo_mapping_safely(self) -> None:
        from app.rules.importer import import_four_indicator_rules
        from app.rules.repository import MySQLRuleRepository

        engine = _rule_engine()
        import_four_indicator_rules(engine, Path("core-rules-wiki"))
        with engine.begin() as conn:
            conn.execute(
                text(
                    "CREATE TABLE med_indicator_run_plan ("
                    "plan_id TEXT PRIMARY KEY, hospital_id TEXT, status TEXT)"
                )
            )
            conn.execute(
                text(
                    "INSERT INTO med_indicator_run_plan VALUES "
                    "('DEMO_MONTHLY_MQSI2025_005','hospital_001','enabled'),"
                    "('PLAN_USER_001','hospital_001','enabled')"
                )
            )

        result = import_four_indicator_rules(
            engine,
            Path("core-rules-wiki"),
            business_source_id="win60_qa_991827",
            business_dialect="sqlserver",
            hospital_scope_value=991827,
            urgent_level_code=977578,
            transfer_department_code=399549991,
            transfer_ward_code=399549990,
            icu_org_ids_csv="360896232048246943,360915701134999568",
        )

        self.assertEqual(result["failed"], [])
        repository = MySQLRuleRepository(engine)
        mapping = repository.get_field_mapping("MQSI2025_005", "hospital_001")
        self.assertEqual(mapping["db_name"], "WIN60_QA_991827")
        self.assertEqual(mapping["main_table"], "INPATIENT_CONSULT_APPLY")
        self.assertEqual(
            mapping["fields"]["arrive_time"],
            "INP_CONSULT_INVITATION.SIGNED_AT",
        )
        transfer_mapping = repository.get_field_mapping(
            "MQSI2025_001", "hospital_001"
        )
        self.assertEqual(transfer_mapping["db_name"], "WIN60_QA_991827")
        self.assertEqual(transfer_mapping["main_table"], "INPATIENT_ENCOUNTER")
        self.assertEqual(
            transfer_mapping["fields"]["admit_time"],
            "INPATIENT_ENCOUNTER.ADMITTED_AT",
        )
        self.assertEqual(
            transfer_mapping["fields"]["ward_entry_time"],
            "INPATIENT_ENCOUNTER.FIRST_ADMITTED_TO_WARD_AT",
        )
        self.assertEqual(
            transfer_mapping["fields"]["transfer_time"],
            "INPAT_TRANSFER.INPAT_TRANSFER_AT",
        )
        self.assertEqual(
            transfer_mapping["query_profile"],
            "inpatient_transfer_48h_sqlserver",
        )
        with engine.connect() as conn:
            custom = conn.execute(
                text(
                    "SELECT custom_params, custom_sql "
                    "FROM med_index_hospital_custom "
                    "WHERE hospital_id='hospital_001' "
                    "AND index_code='MQSI2025_005'"
                )
            ).mappings().one()
            transfer_custom = conn.execute(
                text(
                    "SELECT custom_params, custom_sql "
                    "FROM med_index_hospital_custom "
                    "WHERE hospital_id='hospital_001' "
                    "AND index_code='MQSI2025_001'"
                )
            ).mappings().one()
            remaining = conn.execute(
                text(
                    "SELECT DISTINCT rule_id FROM med_field_mapping "
                    "WHERE hospital_id='hospital_001' ORDER BY rule_id"
                )
            ).scalars().all()
            relation_count = conn.execute(
                text("SELECT COUNT(*) FROM med_table_relation")
            ).scalar_one()
            plans = dict(
                conn.execute(
                    text(
                        "SELECT plan_id, status FROM med_indicator_run_plan "
                        "ORDER BY plan_id"
                    )
                ).all()
            )
        params = json.loads(custom["custom_params"])
        self.assertEqual(params["hospital_soid"], 991827)
        self.assertEqual(params["urgent_level_code"], 977578)
        self.assertIn("WINDBA.INPATIENT_CONSULT_APPLY", custom["custom_sql"])
        transfer_params = json.loads(transfer_custom["custom_params"])
        self.assertEqual(transfer_params["hospital_soid"], 991827)
        self.assertEqual(
            transfer_params["transfer_department_code"], 399549991
        )
        self.assertEqual(transfer_params["transfer_ward_code"], 399549990)
        self.assertEqual(
            transfer_params["icu_org_ids_csv"],
            "360896232048246943,360915701134999568",
        )
        self.assertIn("WINDBA.INPATIENT_ENCOUNTER", transfer_custom["custom_sql"])
        self.assertIn("WINDBA.INPAT_TRANSFER", transfer_custom["custom_sql"])
        self.assertEqual(remaining, ["MQSI2025_001", "MQSI2025_005"])
        self.assertEqual(relation_count, 2)
        self.assertEqual(plans["DEMO_MONTHLY_MQSI2025_005"], "disabled")
        self.assertEqual(plans["PLAN_USER_001"], "enabled")

        updated = import_four_indicator_rules(
            engine,
            Path("core-rules-wiki"),
            business_source_id="win60_qa_991827",
            business_dialect="sqlserver",
            hospital_scope_value=991827,
            urgent_level_code=977578,
            transfer_department_code=399549991,
            transfer_ward_code=399549990,
            icu_org_ids_csv=(
                "360896232048246943,360915701134999568,999999"
            ),
        )
        self.assertEqual(updated["failed"], [])
        with engine.connect() as conn:
            current_version = conn.execute(
                text(
                    "SELECT version FROM med_index_hospital_custom "
                    "WHERE hospital_id='hospital_001' "
                    "AND index_code='MQSI2025_001'"
                )
            ).scalar_one()
            version_count = conn.execute(
                text(
                    "SELECT COUNT(*) FROM med_index_hospital_custom_version "
                    "WHERE hospital_id='hospital_001' "
                    "AND index_code='MQSI2025_001'"
                )
            ).scalar_one()
        self.assertEqual(current_version, 2)
        self.assertEqual(version_count, 2)

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
