import unittest
from datetime import datetime
from pathlib import Path

import yaml
from sqlalchemy import create_engine, event, text

from app.sqlgen.template_renderer import render_sql
from app.sqlgen.validator import validate_select_sql


KB_ROOT = Path("core-rules-wiki")
CODES = ("MQSI2025_001", "MQSI2025_005", "MQSI2025_014", "MQSI2025_035")


def _spec_dir(code: str) -> Path:
    matches = list((KB_ROOT / "sql-specs").glob(f"{code}_*"))
    if len(matches) != 1:
        raise AssertionError(f"expected one SQL spec directory for {code}, got {matches}")
    return matches[0]


def _load_contract(code: str):
    spec_dir = _spec_dir(code)
    spec = yaml.safe_load((spec_dir / "rule_sql_spec.yaml").read_text(encoding="utf-8"))
    mapping = yaml.safe_load(
        (KB_ROOT / "hospital-mappings" / "hospital_001" / f"{code}.yaml").read_text(
            encoding="utf-8"
        )
    )
    template = (spec_dir / "templates" / "mysql.sql.j2").read_text(encoding="utf-8")
    sql = render_sql(template, mapping["fields"], mapping["main_table"], mapping.get("custom_rules"))
    return spec, mapping, sql


def _sqlite_engine():
    engine = create_engine("sqlite://")

    @event.listens_for(engine, "connect")
    def register_timestampdiff(dbapi_connection, _connection_record):
        def timestampdiff(unit, start, end):
            if start is None or end is None:
                return None
            start_dt = datetime.fromisoformat(str(start))
            end_dt = datetime.fromisoformat(str(end))
            seconds = (end_dt - start_dt).total_seconds()
            return int(seconds / 60) if str(unit).upper() == "MINUTE" else int(seconds / 3600)

        dbapi_connection.create_function("TIMESTAMPDIFF", 3, timestampdiff)

    with engine.begin() as conn:
        conn.execute(text("""CREATE TABLE inpatient_transfer_record (
            hospital_id TEXT, admission_id TEXT, admit_time TEXT, transfer_time TEXT,
            from_dept_id TEXT, to_dept_id TEXT)"""))
        conn.execute(text("""INSERT INTO inpatient_transfer_record VALUES
            ('hospital_001','A001','2026-07-01 08:00:00','2026-07-02 07:00:00','D001','D002'),
            ('hospital_001','A002','2026-07-01 08:00:00','2026-07-04 09:00:00','D001','D003'),
            ('hospital_001','A003','2026-07-01 08:00:00','2026-07-01 12:00:00','ICU','D002'),
            ('hospital_001','A004','2026-07-01 08:00:00',NULL,'D001',NULL)"""))

        conn.execute(text("""CREATE TABLE consult_record (
            hospital_id TEXT, patient_id TEXT, consult_type TEXT, request_time TEXT,
            arrive_time TEXT, status TEXT, dept_id TEXT)"""))
        conn.execute(text("""INSERT INTO consult_record VALUES
            ('hospital_001','P001','急会诊','2026-07-01 10:00:00','2026-07-01 10:08:00','完成','D001'),
            ('hospital_001','P002','急会诊','2026-07-01 11:00:00','2026-07-01 11:15:00','完成','D001'),
            ('hospital_001','P003','急会诊','2026-07-01 12:00:00','2026-07-01 12:30:00','完成','D002'),
            ('hospital_001','P004','普通会诊','2026-07-01 13:00:00','2026-07-01 13:10:00','完成','D002')"""))

        conn.execute(text("""CREATE TABLE critical_rescue_record (
            hospital_id TEXT, patient_id TEXT, rescue_id TEXT, rescue_time TEXT,
            severity_level TEXT, rescue_result TEXT, dept_id TEXT)"""))
        conn.execute(text("""INSERT INTO critical_rescue_record VALUES
            ('hospital_001','P101','R001','2026-07-02 08:00:00','急危重症','成功','D001'),
            ('hospital_001','P102','R002','2026-07-02 09:00:00','急危重症','成功','D001'),
            ('hospital_001','P103','R003','2026-07-02 10:00:00','急危重症','成功','D002'),
            ('hospital_001','P104','R004','2026-07-02 11:00:00','急危重症','失败','D002')"""))

        conn.execute(text("""CREATE TABLE intraoperative_transfusion_record (
            hospital_id TEXT, patient_id TEXT, surgery_id TEXT, surgery_time TEXT,
            intraoperative_transfusion_flag INTEGER, autologous_reinfusion_flag INTEGER,
            dept_id TEXT)"""))
        conn.execute(text("""INSERT INTO intraoperative_transfusion_record VALUES
            ('hospital_001','P201','S001','2026-07-03 08:00:00',1,1,'D001'),
            ('hospital_001','P201','S001','2026-07-03 08:00:00',1,1,'D001'),
            ('hospital_001','P202','S002','2026-07-03 09:00:00',1,1,'D001'),
            ('hospital_001','P203','S003','2026-07-03 10:00:00',1,0,'D002'),
            ('hospital_001','P204','S004','2026-07-03 11:00:00',1,0,'D002')"""))
    return engine


PARAMS = {
    "MQSI2025_001": {"transfer_minutes_threshold": 2880, "excluded_dept_id": "ICU"},
    "MQSI2025_005": {"arrive_minutes_threshold": 20, "consult_type_value": "急会诊"},
    "MQSI2025_014": {"severity_value": "急危重症", "success_value": "成功"},
    "MQSI2025_035": {"transfusion_flag_value": 1, "autologous_flag_value": 1},
}


class FourIndicatorSQLTest(unittest.TestCase):
    def test_all_four_specs_render_and_pass_safety_validation(self) -> None:
        for code in CODES:
            with self.subTest(code=code):
                spec, mapping, sql = _load_contract(code)
                self.assertTrue(validate_select_sql(sql, "hospital_001", mapping["main_table"])["ok"])
                self.assertNotIn("hospital_001", sql)
                self.assertIn(":hospital_id", sql)
                self.assertIn(":start_time", sql)
                self.assertIn(":end_time", sql)
                for alias in (
                    "numerator_count",
                    "denominator_count",
                    "sample_count",
                ):
                    self.assertIn(alias, sql)
                self.assertEqual(spec["rule_id"], code)

    def test_confirmed_caliber_contracts_are_encoded(self) -> None:
        spec_001, mapping_001, sql_001 = _load_contract("MQSI2025_001")
        spec_005, _, _ = _load_contract("MQSI2025_005")
        _, _, sql_035 = _load_contract("MQSI2025_035")

        self.assertIn("from_dept_id", mapping_001["fields"])
        self.assertNotIn("count_multiple_transfers", mapping_001.get("custom_rules", {}))
        self.assertIn("COUNT(DISTINCT", sql_001)
        self.assertEqual(spec_001["default_params"]["transfer_minutes_threshold"], 2880)
        self.assertEqual(spec_005["default_params"]["arrive_minutes_threshold"], 10)
        self.assertGreaterEqual(sql_035.count("COUNT(DISTINCT"), 2)

    def test_demo_rows_produce_expected_hospital_results(self) -> None:
        engine = _sqlite_engine()
        expected = {
            "MQSI2025_001": 25.0,
            "MQSI2025_005": 66.67,
            "MQSI2025_014": 75.0,
            "MQSI2025_035": 50.0,
        }
        expected_counts = {
            "MQSI2025_001": (1, 4),
            "MQSI2025_005": (2, 3),
            "MQSI2025_014": (3, 4),
            "MQSI2025_035": (2, 4),
        }
        common = {
            "hospital_id": "hospital_001",
            "start_time": "2026-07-01 00:00:00",
            "end_time": "2026-08-01 00:00:00",
        }
        for code, expected_value in expected.items():
            with self.subTest(code=code):
                _, _, sql = _load_contract(code)
                sqlite_sql = sql.replace("TIMESTAMPDIFF(MINUTE,", "TIMESTAMPDIFF('MINUTE',")
                with engine.connect() as conn:
                    row = conn.execute(text(sqlite_sql), {**common, **PARAMS[code]}).mappings().one()
                self.assertEqual(float(row["index_value"]), expected_value)
                self.assertEqual(
                    (int(row["numerator_count"]), int(row["denominator_count"])),
                    expected_counts[code],
                )
                self.assertEqual(int(row["sample_count"]), int(row["denominator_count"]))


if __name__ == "__main__":
    unittest.main()
