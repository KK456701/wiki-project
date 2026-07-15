from pathlib import Path

import yaml
from sqlalchemy import create_engine, event, text

from app.agents.contracts import PastedDiagnosisEvidence
from app.db_access.query_result import QueryResult
from app.diagnose.pasted_diagnosis import PastedDiagnosisService
from app.diagnose.detail_compare import DiagnosisComparisonStore


USER_SQL = """
USE [WIN60_QA_991827];
DECLARE @BeginAt datetime2 = '2026-06-01 00:00:00';
DECLARE @EndAt datetime2 = '2026-08-01 00:00:00';
;WITH base AS (
  SELECT e.ENCOUNTER_ID
  FROM WINDBA.INPATIENT_ENCOUNTER e
  WHERE e.FIRST_ADMITTED_TO_WARD_AT >= @BeginAt
    AND e.FIRST_ADMITTED_TO_WARD_AT < @EndAt
)
SELECT 25.0 AS index_value, 1 AS numerator_count,
       4 AS denominator_count, 4 AS sample_count
FROM base;
"""

SYSTEM_TEMPLATE = """
WITH base AS (
  SELECT e.ENCOUNTER_ID
  FROM WINDBA.INPATIENT_ENCOUNTER e
  WHERE e.ADMITTED_AT >= :start_time
    AND e.ADMITTED_AT < :end_time
)
SELECT CASE WHEN COUNT_BIG(*) = 0 THEN 0 ELSE 50.0 END AS index_value,
       2 AS numerator_count, COUNT_BIG(*) AS denominator_count,
       COUNT_BIG(*) AS sample_count
FROM base
"""


class _SequencedBusinessDB:
    source_id = "win60_qa_991827"
    tool_name = "execute_sql_win60_qa_991827"

    def __init__(self, outcomes):
        self.outcomes = list(outcomes)
        self.sql = []

    def execute_select(self, sql):
        self.sql.append(sql)
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        rows = outcome if isinstance(outcome, list) else [outcome]
        return QueryResult(
            rows=rows,
            row_count=len(rows),
            source=self.source_id,
            tool_name=self.tool_name,
            duration_ms=3,
        )


def _runtime_engine():
    engine = create_engine("sqlite://")

    @event.listens_for(engine, "connect")
    def register_now(connection, _record):
        connection.create_function("NOW", 0, lambda: "2026-07-15 12:00:00")

    with engine.begin() as conn:
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
    return engine


def _context():
    return {
        "rule_id": "MQSI2025_001",
        "hospital_id": "hospital_001",
        "applicable": True,
        "national_sql_template": SYSTEM_TEMPLATE,
        "national_params": {},
        "national_version": "2025",
        "effective_sql_template": SYSTEM_TEMPLATE,
        "effective_params": {},
        "hospital_version": 1,
        "overridden_fields": ["admission_time_basis"],
    }


def _mapping():
    return {
        "rule_id": "MQSI2025_001",
        "hospital_id": "hospital_001",
        "db_name": "WIN60_QA_991827",
        "main_table": "INPATIENT_ENCOUNTER",
        "dialect": "sqlserver",
        "fields": {},
        "filters": {},
        "status": "confirmed",
    }


def _evidence(sql_text=USER_SQL):
    return PastedDiagnosisEvidence(
        raw_text=sql_text,
        question="为什么我们算得不一样？",
        rule_id="MQSI2025_001",
        sql_text=sql_text,
        declared_params={
            "BeginAt": "2026-06-01 00:00:00",
            "EndAt": "2026-08-01 00:00:00",
        },
        stat_period={
            "start": "2026-06-01 00:00:00",
            "end": "2026-08-01 00:00:00",
        },
    )


def test_executes_user_national_and_hospital_sql_and_compares_results():
    business_db = _SequencedBusinessDB([
        {"index_value": 25, "numerator_count": 1, "denominator_count": 4, "sample_count": 4},
        {"index_value": 50, "numerator_count": 2, "denominator_count": 4, "sample_count": 4},
        {"index_value": 75, "numerator_count": 3, "denominator_count": 4, "sample_count": 4},
    ])
    service = PastedDiagnosisService(
        runtime_engine=_runtime_engine(),
        business_db=business_db,
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    result = service.run(
        evidence=_evidence(),
        hospital_id="hospital_001",
        caliber_context=_context(),
        field_mapping=_mapping(),
        stat_period=None,
    )

    assert len(business_db.sql) == 3
    assert result["execution_results"]["user"]["status"] == "success"
    assert result["execution_results"]["user"]["denominator_count"] == 4
    assert result["execution_results"]["national"]["numerator_count"] == 2
    assert result["execution_results"]["hospital"]["numerator_count"] == 3
    assert result["primary_conclusion"] == "caliber_difference"
    assert "period_field_changed" in {item["code"] for item in result["findings"]}
    assert result["comparison_rows"][0] == {
        "item": "统计范围使用的时间字段不同",
        "user_sql": "FIRST_ADMITTED_TO_WARD_AT",
        "current_sql": "ADMITTED_AT",
        "impact": "两段 SQL 纳入统计的患者批次可能不同，分母会直接变化。",
        "suggestion": "请业务确认统计周期应按入院时间还是首次入区时间。",
    }
    assert result["effective_source"]["label"] == "本院生效口径 v1"
    assert result["stat_period"].startswith("2026-06-01 00:00:00~")


def test_user_sql_result_accepts_doctor_readable_chinese_aliases():
    business_db = _SequencedBusinessDB([
        {
            "分子_入区48小时内转科人次": 2,
            "分母_同期入区人次": 158,
            "入区48小时内转科比例_百分比": 1.27,
        },
        {"index_value": 1.27, "numerator_count": 2, "denominator_count": 158},
        {"index_value": 1.27, "numerator_count": 2, "denominator_count": 158},
    ])
    service = PastedDiagnosisService(
        runtime_engine=_runtime_engine(),
        business_db=business_db,
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    result = service.run(
        evidence=_evidence(),
        hospital_id="hospital_001",
        caliber_context=_context(),
        field_mapping=_mapping(),
        stat_period=None,
    )

    user_result = result["execution_results"]["user"]
    assert user_result["numerator_count"] == 2
    assert user_result["denominator_count"] == 158
    assert user_result["sample_count"] == 158
    assert user_result["result_value"] == 1.27


def test_supported_diagnosis_creates_record_comparison_snapshot(tmp_path):
    root = Path(__file__).resolve().parents[1]
    sql_text = (
        root / "tests/fixtures/diagnosis/transfer_ratio_user_sql.sql"
    ).read_text(encoding="utf-8")
    specification = yaml.safe_load((
        root
        / "core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/rule_sql_spec.yaml"
    ).read_text(encoding="utf-8"))
    mapping = yaml.safe_load((
        root / "core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_001.yaml"
    ).read_text(encoding="utf-8"))
    context = {
        **_context(),
        "effective_params": {
            "hospital_soid": 991827,
            "excluded_inpatient_business_code": 399552157,
            "transfer_department_code": 399549991,
            "transfer_ward_code": 399549990,
            "icu_org_ids_csv": "101,102",
            "transfer_minutes_threshold": 2880,
        },
    }
    business_db = _SequencedBusinessDB([
        {"index_value": 50, "numerator_count": 1, "denominator_count": 2},
        {"index_value": 0, "numerator_count": 0, "denominator_count": 2},
        {"index_value": 0, "numerator_count": 0, "denominator_count": 2},
        [
            {"record_key": "E001", "user_meets_numerator": 1},
            {"record_key": "E002", "user_meets_numerator": 0},
        ],
        [
            {"admission_id": "E001", "__meets_numerator": 0},
            {"admission_id": "E002", "__meets_numerator": 0},
        ],
    ])
    store = DiagnosisComparisonStore(tmp_path)
    service = PastedDiagnosisService(
        runtime_engine=_runtime_engine(),
        business_db=business_db,
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
        comparison_store=store,
    )

    result = service.run(
        evidence=_evidence(sql_text),
        hospital_id="hospital_001",
        caliber_context=context,
        field_mapping=mapping,
        stat_period=None,
        effective_rule={
            "rule_id": "MQSI2025_001",
            "rule_name": "患者入院48小时内转科比例",
            "effective_level": "hospital",
            "national_version": "2025",
            "hospital_version": 4,
            "calculation_definition": specification["calculation"],
        },
    )

    detail = result["detail_comparison"]
    assert detail["status"] == "ready"
    assert detail["counts"]["user_only_numerator"] == 1
    assert len(business_db.sql) == 5
    assert store.read_summary("hospital_001", detail["comparison_id"])["rule_id"] == "MQSI2025_001"


def test_unsafe_user_sql_is_not_executed_but_calibers_still_run():
    business_db = _SequencedBusinessDB([
        {"index_value": 50, "sample_count": 4},
        {"index_value": 50, "sample_count": 4},
    ])
    service = PastedDiagnosisService(
        runtime_engine=_runtime_engine(),
        business_db=business_db,
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    result = service.run(
        evidence=_evidence("UPDATE WINDBA.INPATIENT_ENCOUNTER SET IS_DEL = 1;"),
        hospital_id="hospital_001",
        caliber_context=_context(),
        field_mapping=_mapping(),
        stat_period="2026-06-01~2026-07-31",
    )

    assert len(business_db.sql) == 2
    assert result["execution_results"]["user"]["status"] == "blocked"
    assert result["execution_results"]["user"]["blocked_reasons"]
    assert result["primary_conclusion"] == "user_sql_blocked"


def test_user_execution_failure_does_not_hide_static_caliber_findings():
    business_db = _SequencedBusinessDB([
        RuntimeError("user query failed"),
        {"index_value": 50, "sample_count": 4},
        {"index_value": 50, "sample_count": 4},
    ])
    service = PastedDiagnosisService(
        runtime_engine=_runtime_engine(),
        business_db=business_db,
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    result = service.run(
        evidence=_evidence(),
        hospital_id="hospital_001",
        caliber_context=_context(),
        field_mapping=_mapping(),
        stat_period=None,
    )

    assert result["execution_results"]["user"]["status"] == "failed"
    assert "period_field_changed" in {item["code"] for item in result["findings"]}
    assert result["primary_conclusion"] == "caliber_difference"
