import copy
import unittest
from pathlib import Path

import yaml
from sqlalchemy import create_engine, text

from app.metadata.precheck import precheck_rule_fields


KB_ROOT = Path("core-rules-wiki")


def _urgent_calculation() -> dict:
    spec_path = next(
        (KB_ROOT / "sql-specs").glob("MQSI2025_005*/rule_sql_spec.yaml")
    )
    return yaml.safe_load(spec_path.read_text(encoding="utf-8"))["calculation"]


def _mapping() -> dict:
    fields = {
        "hospital_id": "consult_record.hospital_id",
        "consult_type": "consult_record.consult_type",
        "request_time": "consult_record.request_time",
        "arrive_time": "consult_record.arrive_time",
        "dept_id": "consult_record.dept_id",
    }
    return {
        "rule_id": "MQSI2025_005",
        "hospital_id": "hospital_001",
        "dialect": "mysql",
        "db_name": "hospital_demo_data",
        "main_table": "consult_record",
        "fields": fields,
        "status": "confirmed",
        "items": [
            {
                "business_field": business_field,
                "db_name": "hospital_demo_data",
                "table_name": physical_field.split(".", 1)[0],
                "column_name": physical_field.split(".", 1)[1],
                "status": "confirmed",
            }
            for business_field, physical_field in fields.items()
        ],
    }


class MetadataPrecheckTest(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = create_engine("sqlite://")
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    """
                    CREATE TABLE med_metadata_column (
                      hospital_id TEXT NOT NULL,
                      db_name TEXT NOT NULL,
                      table_name TEXT NOT NULL,
                      column_name TEXT NOT NULL,
                      data_type TEXT
                    )
                    """
                )
            )
            conn.execute(
                text(
                    """
                    CREATE TABLE med_table_relation (
                      hospital_id TEXT NOT NULL,
                      db_name TEXT NOT NULL,
                      left_table TEXT NOT NULL,
                      left_column TEXT NOT NULL,
                      right_table TEXT NOT NULL,
                      right_column TEXT NOT NULL,
                      status TEXT NOT NULL
                    )
                    """
                )
            )
            conn.execute(
                text(
                    """
                    INSERT INTO med_metadata_column
                      (hospital_id, db_name, table_name, column_name, data_type)
                    VALUES
                      ('hospital_001', 'hospital_demo_data', 'consult_record', 'hospital_id', 'varchar'),
                      ('hospital_001', 'hospital_demo_data', 'consult_record', 'consult_type', 'varchar'),
                      ('hospital_001', 'hospital_demo_data', 'consult_record', 'request_time', 'datetime'),
                      ('hospital_001', 'hospital_demo_data', 'consult_record', 'arrive_time', 'datetime')
                    """
                )
            )

    def test_uses_definition_dependencies_not_all_contract_fields(self) -> None:
        result = precheck_rule_fields(
            KB_ROOT,
            self.engine,
            "hospital_001",
            "MQSI2025_005",
            calculation_definition=_urgent_calculation(),
            field_mapping=_mapping(),
        )

        self.assertTrue(result["ok"])
        self.assertEqual(
            result["required_business_fields"],
            ["arrive_time", "consult_type", "hospital_id", "request_time"],
        )
        self.assertEqual(result["missing_mappings"], [])
        self.assertEqual(result["missing_columns"], [])

    def test_blocks_unconfirmed_mapping_and_missing_relation(self) -> None:
        mapping = copy.deepcopy(_mapping())
        mapping["fields"]["arrive_time"] = "staff_directory.arrive_time"
        for item in mapping["items"]:
            if item["business_field"] == "arrive_time":
                item.update(
                    table_name="staff_directory",
                    column_name="arrive_time",
                    status="pending",
                )
        mapping["status"] = "pending"
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    """
                    INSERT INTO med_metadata_column
                      (hospital_id, db_name, table_name, column_name, data_type)
                    VALUES
                      ('hospital_001', 'hospital_demo_data', 'staff_directory', 'arrive_time', 'datetime')
                    """
                )
            )

        result = precheck_rule_fields(
            KB_ROOT,
            self.engine,
            "hospital_001",
            "MQSI2025_005",
            calculation_definition=_urgent_calculation(),
            field_mapping=mapping,
        )

        self.assertFalse(result["ok"])
        self.assertEqual(result["unconfirmed_mappings"], ["arrive_time"])
        self.assertEqual(
            result["missing_relations"],
            ["consult_record -> staff_directory"],
        )

    def test_blocks_incompatible_metadata_type(self) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    """
                    UPDATE med_metadata_column
                    SET data_type='varchar'
                    WHERE table_name='consult_record' AND column_name='arrive_time'
                    """
                )
            )

        result = precheck_rule_fields(
            KB_ROOT,
            self.engine,
            "hospital_001",
            "MQSI2025_005",
            calculation_definition=_urgent_calculation(),
            field_mapping=_mapping(),
        )

        self.assertFalse(result["ok"])
        self.assertEqual(
            result["type_mismatches"],
            ["arrive_time：期望 datetime，实际 varchar（consult_record.arrive_time）"],
        )


if __name__ == "__main__":
    unittest.main()
