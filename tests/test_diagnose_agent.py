import json
import unittest
from pathlib import Path

from sqlalchemy import create_engine, event, text
from sqlalchemy.pool import StaticPool

from app.db_access.query_result import QueryResult
from app.diagnose.agent import DiagnoseAgent
from app.diagnose.data_check import _quote_ident, data_check
from tests.test_kb_tools import temp_kb_dir, write


class DiagnoseAgentProductionTest(unittest.TestCase):
    def test_layer1_stops_when_mapping_column_missing_and_returns_checks(self) -> None:
        with temp_kb_dir() as root:
            root = Path(root)
            _make_diag_kb(root, include_arrive_metadata=False)
            runtime_engine = _runtime_engine(root / "runtime.db", include_arrive_metadata=False)
            business_db = _business_db(root / "business.db")
            agent = DiagnoseAgent(root, runtime_engine, business_db)

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
            business_db = _business_db(root / "business.db")
            agent = DiagnoseAgent(root, runtime_engine, business_db)

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

    def test_layer1_can_use_realtime_metadata_provider_when_runtime_cache_is_missing(self) -> None:
        class FakeMetadataProvider:
            source_name = "dbhub"

            def list_columns(self, db_name, table_name=None):
                rows = [
                    {"table_name": "consult_record", "column_name": "request_time", "data_type": "datetime", "column_type": "datetime", "is_nullable": "NO"},
                    {"table_name": "consult_record", "column_name": "arrive_time", "data_type": "datetime", "column_type": "datetime", "is_nullable": "YES"},
                    {"table_name": "consult_record", "column_name": "dept_id", "data_type": "varchar", "column_type": "varchar(64)", "is_nullable": "YES"},
                ]
                return [row for row in rows if table_name in (None, row["table_name"])]

        with temp_kb_dir() as root:
            root = Path(root)
            _make_diag_kb(root, include_arrive_metadata=False)
            runtime_engine = _runtime_engine(root / "runtime.db", include_arrive_metadata=False)
            business_db = _business_db(root / "business.db")
            agent = DiagnoseAgent(root, runtime_engine, business_db, metadata_provider=FakeMetadataProvider())

            report = agent.run("hospital_001", "MQSI2025_005", _effective_rule())

            self.assertEqual(report["layers"][0]["metadata_source"], "dbhub")
            self.assertTrue(report["layers"][0]["ok"])

    def test_data_check_quotes_identifiers_by_dialect(self) -> None:
        self.assertEqual(_quote_ident("consult_record", "mysql"), "`consult_record`")
        self.assertEqual(_quote_ident("we`ird", "mysql"), "`we``ird`")
        self.assertEqual(_quote_ident("consult_record", "sqlite"), '"consult_record"')

    def test_data_check_uses_business_db_mcp(self) -> None:
        with temp_kb_dir() as root:
            root = Path(root)
            _make_diag_kb(root, include_arrive_metadata=True)
            business_db = _business_db(root / "business.db")

            result = data_check(root, business_db, "hospital_001", "MQSI2025_005")

            self.assertTrue(business_db.sql)
            self.assertTrue(all(statement.lower().startswith("select") for statement in business_db.sql))
            self.assertIn(result["diagnose_type"], {"数据质量风险", "数据质量正常"})

    def test_layer2_compares_national_and_hospital_results(self) -> None:
        with temp_kb_dir() as root:
            root = Path(root)
            _make_diag_kb(root, include_arrive_metadata=True)
            runtime_engine = _runtime_engine(root / "runtime.db", include_arrive_metadata=True)
            business_db = _business_db(root / "business.db")
            agent = DiagnoseAgent(root, runtime_engine, business_db)

            report = agent.run(
                "hospital_001",
                "MQSI2025_005",
                _effective_rule(),
                caliber_context=_comparison_context(),
                field_mapping=_comparison_mapping(),
                stat_period="2026-07-01~2026-07-31",
            )

            layer2 = report["layers"][1]
            comparison = layer2["caliber_comparison"]
            self.assertEqual(comparison["conclusion_code"], "caliber_result_diff")
            self.assertEqual(comparison["national"]["result_value"], 0.0)
            self.assertEqual(comparison["hospital"]["result_value"], 33.33)
            self.assertTrue(layer2["ok"])
            self.assertTrue(
                any(check["status"] == "warn" for check in layer2["checks"])
            )
            self.assertEqual(len(report["layers"]), 3)

    def test_layer2_stops_when_hospital_caliber_execution_fails(self) -> None:
        with temp_kb_dir() as root:
            root = Path(root)
            _make_diag_kb(root, include_arrive_metadata=True)
            runtime_engine = _runtime_engine(
                root / "runtime.db", include_arrive_metadata=True
            )
            business_db = _business_db(root / "business.db")
            agent = DiagnoseAgent(root, runtime_engine, business_db)

            report = agent.run(
                "hospital_001",
                "MQSI2025_005",
                _effective_rule(),
                caliber_context=_comparison_context(
                    effective_sql_template=_failing_comparison_sql()
                ),
                field_mapping=_comparison_mapping(),
                stat_period="2026-07-01~2026-07-31",
            )

            layer2 = report["layers"][1]
            self.assertFalse(layer2["ok"])
            self.assertEqual(
                layer2["conclusion_code"],
                "hospital_caliber_execution_failed",
            )
            self.assertEqual(report["stopped_at_layer"], 2)
            self.assertEqual(len(report["layers"]), 2)

    def test_pasted_sql_nullif_prevents_false_zero_guard_warning(self) -> None:
        with temp_kb_dir() as root:
            root = Path(root)
            _make_diag_kb(root, include_arrive_metadata=True)
            runtime_engine = _runtime_engine(
                root / "runtime.db", include_arrive_metadata=True
            )
            business_db = _business_db(root / "business.db")
            agent = DiagnoseAgent(root, runtime_engine, business_db)

            report = agent.run(
                "hospital_001",
                "MQSI2025_005",
                _effective_rule(),
                query_text=(
                    "SELECT COUNT(*) * 100.0 / NULLIF(COUNT(*), 0) AS index_value, "
                    "COUNT(*) AS numerator_count, COUNT(*) AS denominator_count, "
                    "COUNT(*) AS sample_count FROM consult_record;"
                ),
                caliber_context=_comparison_context(),
                field_mapping=_comparison_mapping(),
                stat_period="2026-07-01~2026-07-31",
            )

            zero_guard = [
                check
                for check in report["layers"][1]["checks"]
                if check["name"] == "zero_guard"
            ]
            self.assertEqual(zero_guard, [])
            self.assertEqual(
                report["execution_results"]["user"]["status"], "success"
            )


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

    @event.listens_for(engine, "connect")
    def register_now(dbapi_connection, _connection_record):
        dbapi_connection.create_function(
            "NOW", 0, lambda: "2026-07-10 12:00:00"
        )

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
        conn.execute(text("""
            CREATE TABLE med_sql_run_log (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              run_id TEXT NOT NULL UNIQUE,
              sql_id TEXT,
              hospital_id TEXT NOT NULL,
              rule_id TEXT NOT NULL,
              stat_start_time TEXT,
              stat_end_time TEXT,
              run_status TEXT NOT NULL,
              result_value REAL,
              error_message TEXT,
              duration_ms INTEGER,
              run_by TEXT,
              run_time TEXT NOT NULL
            )
        """))
        for column_name, data_type, nullable in columns:
            conn.execute(
                text("INSERT INTO med_metadata_column VALUES "
                     "('hospital_001', 'his', 'consult_record', :c, :dt, :dt, :n, '')"),
                {"c": column_name, "dt": data_type, "n": nullable},
            )
    return engine


def _business_db(path: Path):
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE consult_record (
              consult_id TEXT,
              hospital_id TEXT,
              consult_type TEXT,
              request_time TEXT,
              arrive_time TEXT,
              dept_id TEXT
            )
        """))
        conn.execute(text(
            "INSERT INTO consult_record VALUES "
            "('1', 'hospital_001', '急会诊', '2026-07-01 08:00:00', NULL, NULL), "
            "('2', 'hospital_001', '急会诊', '2026-07-01 09:00:00', NULL, NULL), "
            "('3', 'hospital_001', '急会诊', '2026-07-01 10:00:00', '2026-07-01 10:15:00', 'ED')"
        ))

    class SQLiteBusinessDB:
        source_id = "hospital_demo_data"
        tool_name = "execute_sql_hospital_demo_data"

        def __init__(self):
            self.sql: list[str] = []

        def execute_select(self, sql: str) -> QueryResult:
            self.sql.append(sql)
            with engine.connect() as conn:
                rows = [dict(row) for row in conn.execute(text(sql)).mappings().all()]
            return QueryResult(
                rows=rows,
                row_count=len(rows),
                source=self.source_id,
                tool_name=self.tool_name,
                duration_ms=1,
            )

    return SQLiteBusinessDB()


def _effective_rule() -> dict[str, str]:
    return {
        "rule_id": "MQSI2025_005",
        "rule_name": "急会诊及时到位率",
        "definition": "急会诊请求发出后，20分钟内到达现场的急会诊次数占同期急会诊总次数的比例。",
        "formula": "急会诊及时到位率 = (急会诊记录中20分钟内到位的急会诊次数 / 同期急会诊总次数) × 100%",
    }


def _comparison_sql() -> str:
    return """
SELECT
  CASE WHEN COUNT(*) = 0 THEN 0
       ELSE ROUND(
         SUM(CASE
               WHEN arrive_time IS NOT NULL
                AND (julianday(arrive_time) - julianday(request_time)) * 1440
                    BETWEEN 0 AND :arrive_minutes_threshold
               THEN 1 ELSE 0
             END) * 100.0 / COUNT(*),
         2
       )
  END AS index_value,
  COUNT(*) AS sample_count
FROM consult_record
WHERE hospital_id = :hospital_id
  AND consult_type = :consult_type_value
  AND request_time >= :start_time
  AND request_time < :end_time
""".strip()


def _failing_comparison_sql() -> str:
    return """
SELECT missing_column AS index_value, COUNT(*) AS sample_count
FROM consult_record
WHERE hospital_id = :hospital_id
  AND request_time >= :start_time
  AND request_time < :end_time
""".strip()


def _comparison_context(**overrides):
    payload = {
        "rule_id": "MQSI2025_005",
        "hospital_id": "hospital_001",
        "applicable": True,
        "national_sql_template": _comparison_sql(),
        "national_params": {
            "arrive_minutes_threshold": 10,
            "consult_type_value": "急会诊",
        },
        "national_version": "2025",
        "effective_sql_template": _comparison_sql(),
        "effective_params": {
            "arrive_minutes_threshold": 20,
            "consult_type_value": "急会诊",
        },
        "hospital_version": 1,
        "overridden_fields": ["arrive_minutes_threshold"],
    }
    payload.update(overrides)
    return payload


def _comparison_mapping():
    return {
        "rule_id": "MQSI2025_005",
        "hospital_id": "hospital_001",
        "db_name": "his",
        "main_table": "consult_record",
        "fields": {
            "hospital_id": "consult_record.hospital_id",
            "consult_type": "consult_record.consult_type",
            "request_time": "consult_record.request_time",
            "arrive_time": "consult_record.arrive_time",
        },
        "filters": {"consult_type_value": "急会诊"},
    }


