import json
import unittest
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.diagnose.agent import DiagnoseAgent
from app.diagnose.data_check import _quote_ident
from tests.test_kb_tools import temp_kb_dir, write


class DiagnoseAgentProductionTest(unittest.TestCase):
    def test_layer1_stops_when_mapping_column_missing_and_returns_checks(self) -> None:
        with temp_kb_dir() as root:
            root = Path(root)
            _make_diag_kb(root, include_arrive_metadata=False)
            runtime_engine = _runtime_engine(root / "runtime.db", include_arrive_metadata=False)
            business_engine = _business_engine(root / "business.db")
            agent = DiagnoseAgent(root, runtime_engine, business_engine)

            report = agent.run(
                hospital_id="hospital_001",
                rule_id="MQSI2025_005",
                effective_rule=_effective_rule(),
                trigger="sql_generation_failed",
                related_sql_id="SQL_missing_col",
                stat_period="2026-07-01~2026-08-01",
            )

            self.assertFalse(report["ok"])
            self.assertEqual(report["diagnose_status"], "failed")
            self.assertEqual(report["stopped_at_layer"], 1)
            self.assertEqual(report["trigger_type"], "sql_generation_failed")
            self.assertEqual(report["related_sql_id"], "SQL_missing_col")
            failed_checks = [c for c in report["layers"][0]["checks"] if c["status"] == "fail"]
            self.assertTrue(any("arrive_time" in c["message"] for c in failed_checks))
            self.assertIn("summary_markdown", report)

    def test_full_diagnose_warns_on_data_quality_and_persists_layer_results(self) -> None:
        with temp_kb_dir() as root:
            root = Path(root)
            _make_diag_kb(root, include_arrive_metadata=True)
            runtime_engine = _runtime_engine(root / "runtime.db", include_arrive_metadata=True)
            business_engine = _business_engine(root / "business.db")
            agent = DiagnoseAgent(root, runtime_engine, business_engine)

            report = agent.run(
                hospital_id="hospital_001",
                rule_id="MQSI2025_005",
                effective_rule=_effective_rule(),
                trigger="abnormal_result",
                related_sql_id="SQL_abnormal",
                stat_period="2026-07-01~2026-08-01",
            )

            self.assertTrue(report["ok"])
            self.assertEqual(report["diagnose_status"], "warning")
            self.assertEqual(report["stopped_at_layer"], 3)
            self.assertEqual(len(report["layers"]), 3)
            layer3_checks = report["layers"][2]["checks"]
            self.assertTrue(any(c["status"] == "warn" for c in layer3_checks))

            with runtime_engine.connect() as conn:
                row = conn.execute(
                    text("SELECT trigger_type, related_sql_id, stat_period, diagnose_status, layer_results "
                         "FROM med_index_diagnose_report WHERE report_id=:rid"),
                    {"rid": report["report_id"]},
                ).mappings().one()
            self.assertEqual(row["trigger_type"], "abnormal_result")
            self.assertEqual(row["related_sql_id"], "SQL_abnormal")
            self.assertEqual(row["stat_period"], "2026-07-01~2026-08-01")
            self.assertEqual(row["diagnose_status"], "warning")
            self.assertEqual(len(json.loads(row["layer_results"])), 3)

    def test_data_check_quotes_identifiers_by_dialect(self) -> None:
        self.assertEqual(_quote_ident("consult_record", "mysql"), "`consult_record`")
        self.assertEqual(_quote_ident("we`ird", "mysql"), "`we``ird`")
        self.assertEqual(_quote_ident("consult_record", "sqlite"), '"consult_record"')

    def test_rule_check_has_no_three_caliber_compare(self) -> None:
        with temp_kb_dir() as root:
            root = Path(root)
            _make_diag_kb(root, include_arrive_metadata=True)
            runtime_engine = _runtime_engine(root / "runtime.db", include_arrive_metadata=True)
            business_engine = _business_engine(root / "business.db")
            agent = DiagnoseAgent(root, runtime_engine, business_engine)

            report = agent.run("hospital_001", "MQSI2025_005", _effective_rule())

            self.assertEqual(report["layers"][1]["layer_name"], "口径规则校验")
            payload = json.dumps(report["layers"][1], ensure_ascii=False)
            self.assertNotIn("三口径", payload)
            self.assertNotIn("caliber_compare_mode", payload)


def _make_diag_kb(root: Path, include_arrive_metadata: bool) -> None:
    root = Path(root)
    spec_dir = root / "sql-specs" / "MQSI2025_005_急会诊及时到位率"
    write(spec_dir / "field_contract.yaml", """
business_fields:
  request_time:
    required: true
    type: datetime
  arrive_time:
    required: true
    type: datetime
  dept_id:
    required: false
    type: string
""")
    write(spec_dir / "rule_sql_spec.yaml", """
rule_id: MQSI2025_005
rule_name: 急会诊及时到位率
main_table: consult_record
time_field: request_time
denominator:
  field: consult_id
numerator:
  condition: arrive_time within 20 minutes
""")
    write(spec_dir / "templates" / "mysql.sql.j2", "SELECT COUNT(*) FROM consult_record")
    write(root / "hospital-mappings" / "hospital_001" / "MQSI2025_005.yaml", """
dialect: mysql
db_name: his
main_table: consult_record
fields:
  request_time: consult_record.request_time
  arrive_time: consult_record.arrive_time
  dept_id: consult_record.dept_id
""")


def _runtime_engine(path: Path, include_arrive_metadata: bool):
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    columns = [
        ("request_time", "datetime", "NO"),
        ("dept_id", "varchar", "YES"),
    ]
    if include_arrive_metadata:
        columns.append(("arrive_time", "datetime", "YES"))
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE med_metadata_column (
              hospital_id TEXT,
              db_name TEXT,
              table_name TEXT,
              column_name TEXT,
              data_type TEXT,
              column_type TEXT,
              is_nullable TEXT,
              column_comment TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE med_index_diagnose_report (
              report_id TEXT PRIMARY KEY,
              hospital_id TEXT,
              rule_id TEXT,
              diagnose_type TEXT,
              problem_detail TEXT,
              repair_suggest TEXT,
              repair_sql TEXT,
              diagnose_time TEXT,
              status INTEGER,
              trigger_type TEXT,
              related_sql_id TEXT,
              layer_results TEXT,
              diagnose_status TEXT,
              stat_period TEXT
            )
        """))
        for column_name, data_type, nullable in columns:
            conn.execute(
                text("INSERT INTO med_metadata_column VALUES "
                     "('hospital_001', 'his', 'consult_record', :c, :dt, :dt, :n, '')"),
                {"c": column_name, "dt": data_type, "n": nullable},
            )
    return engine


def _business_engine(path: Path):
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE consult_record (
              consult_id TEXT,
              request_time TEXT,
              arrive_time TEXT,
              dept_id TEXT
            )
        """))
        conn.execute(text(
            "INSERT INTO consult_record VALUES "
            "('1', '2026-07-01 08:00:00', NULL, NULL), "
            "('2', '2026-07-01 09:00:00', NULL, NULL), "
            "('3', '2026-07-01 10:00:00', '2026-07-01 10:15:00', 'ED')"
        ))
    return engine


def _effective_rule() -> dict[str, str]:
    return {
        "rule_id": "MQSI2025_005",
        "rule_name": "急会诊及时到位率",
        "definition": "急会诊请求发出后，20分钟内到达现场的急会诊次数占同期急会诊总次数的比例。",
        "formula": "急会诊及时到位率 = (急会诊记录中20分钟内到位的急会诊次数 / 同期急会诊总次数) × 100%",
    }


