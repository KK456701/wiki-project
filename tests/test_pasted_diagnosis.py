from sqlalchemy import create_engine, event, text

from app.agents.contracts import PastedDiagnosisEvidence
from app.db_access.query_result import QueryResult
from app.diagnose.pasted_diagnosis import PastedDiagnosisService


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
        return QueryResult(
            rows=[outcome],
            row_count=1,
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
    assert result["stat_period"].startswith("2026-06-01 00:00:00~")


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
