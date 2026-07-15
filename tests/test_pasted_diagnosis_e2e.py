import json
from pathlib import Path

from sqlalchemy import create_engine, event, text

from app.db_access.query_result import QueryResult
from app.diagnose.evidence import extract_pasted_evidence
from app.diagnose.narrator import DiagnosisNarrator
from app.diagnose.pasted_diagnosis import PastedDiagnosisService
from app.diagnose.structure_check import _types_compatible


FIXTURE_ROOT = Path(__file__).parent / "fixtures" / "diagnosis"

SYSTEM_TEMPLATE = """
WITH transfer_candidate AS (
  SELECT t.ENCOUNTER_ID, t.INPAT_TRANSFER_ID, t.INPAT_TRANSFER_AT,
         t.ORIGIN_DEPT_ID, t.DESTINATION_DEPT_ID
  FROM WINDBA.INPAT_TRANSFER t
  WHERE t.IS_DEL = 0
), valid_transfer AS (
  SELECT candidate.*,
         ROW_NUMBER() OVER (
           PARTITION BY candidate.ENCOUNTER_ID
           ORDER BY candidate.INPAT_TRANSFER_AT, candidate.INPAT_TRANSFER_ID
         ) AS event_order
  FROM transfer_candidate candidate
  WHERE CHARINDEX(',' + CONVERT(varchar(30), candidate.ORIGIN_DEPT_ID) + ',',
                  ',' + :icu_org_ids_csv + ',') = 0
), base AS (
  SELECT e.ENCOUNTER_ID, e.ADMITTED_AT, t.INPAT_TRANSFER_AT
  FROM WINDBA.INPATIENT_ENCOUNTER e
  LEFT JOIN valid_transfer t
    ON t.ENCOUNTER_ID = e.ENCOUNTER_ID AND t.event_order = 1
  WHERE e.ADMITTED_AT >= :start_time
    AND e.ADMITTED_AT < :end_time
    AND e.INPAT_ENC_BIZ_TYPE_CODE <> :excluded_code
)
SELECT
  SUM(CASE WHEN DATEDIFF(MINUTE, base.ADMITTED_AT, base.INPAT_TRANSFER_AT)
                BETWEEN 0 AND 2880 THEN 1 ELSE 0 END) AS numerator_count,
  COUNT_BIG(*) AS denominator_count,
  COUNT_BIG(*) AS sample_count,
  CASE WHEN COUNT_BIG(*) = 0 THEN 0 ELSE
       SUM(CASE WHEN DATEDIFF(MINUTE, base.ADMITTED_AT, base.INPAT_TRANSFER_AT)
                     BETWEEN 0 AND 2880 THEN 1 ELSE 0 END) * 100.0 / COUNT_BIG(*)
  END AS index_value
FROM base
""".strip()


class _SequencedBusinessDB:
    source_id = "win60_qa_991827"
    tool_name = "execute_sql_win60_qa_991827"

    def __init__(self):
        self.outcomes = [
            {"index_value": 1.27, "numerator_count": 2, "denominator_count": 158, "sample_count": 158},
            {"index_value": 2.50, "numerator_count": 4, "denominator_count": 160, "sample_count": 160},
            {"index_value": 3.01, "numerator_count": 5, "denominator_count": 166, "sample_count": 166},
        ]
        self.executed = []

    def execute_select(self, sql):
        self.executed.append(sql)
        return QueryResult(
            rows=[self.outcomes.pop(0)],
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
        "national_params": {
            "icu_org_ids_csv": "101,102",
            "excluded_code": 399552157,
        },
        "national_version": "2025",
        "effective_sql_template": SYSTEM_TEMPLATE,
        "effective_params": {
            "icu_org_ids_csv": "101,102,103",
            "excluded_code": 399552157,
        },
        "hospital_version": 2,
        "overridden_fields": ["icu_org_ids_csv"],
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


def test_transfer_ratio_pasted_sql_is_diagnosed_end_to_end_without_model():
    sql_text = (FIXTURE_ROOT / "transfer_ratio_user_sql.sql").read_text(encoding="utf-8")
    claimed = json.loads(
        (FIXTURE_ROOT / "transfer_ratio_claimed_result.json").read_text(encoding="utf-8")
    )
    raw_text = (
        "为什么我们算得不一样？\n\n```sql\n"
        + sql_text
        + "\n```\n\n本次执行结果：分子 "
        + str(claimed["numerator_count"])
        + "，分母 "
        + str(claimed["denominator_count"])
        + "，指标结果 "
        + str(claimed["index_value"])
        + "% 。"
    )
    evidence = extract_pasted_evidence(
        raw_text,
        rule_id="MQSI2025_001",
    )
    business_db = _SequencedBusinessDB()
    result = PastedDiagnosisService(
        runtime_engine=_runtime_engine(),
        business_db=business_db,
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    ).run(
        evidence=evidence,
        hospital_id="hospital_001",
        caliber_context=_context(),
        field_mapping=_mapping(),
        stat_period=None,
    )

    codes = {item["code"] for item in result["findings"]}
    assert {
        "period_field_changed",
        "elapsed_start_field_changed",
        "upper_boundary_inclusive_changed",
        "icu_scope_strategy_changed",
        "event_selection_changed",
        "null_handling_changed",
    }.issubset(codes)
    assert result["user_zero_denominator_guard"] is True
    assert result["primary_conclusion"] == "caliber_difference"
    assert len(business_db.executed) == 3
    assert result["execution_results"]["user"]["denominator_count"] == 158
    assert result["execution_results"]["hospital"]["denominator_count"] == 166
    assert _types_compatible("string", "numeric", "hospital_id") is True

    diagnosis = {
        **result,
        "evidence": {
            **result["evidence_summary"],
            "stat_period": {
                "start": evidence.stat_period.start,
                "end": evidence.stat_period.end,
            },
        },
    }
    answer = DiagnosisNarrator(None).compose(diagnosis)
    assert answer.startswith("## 结论")
    assert "口径" in answer
    assert "数据库连接问题" in answer
    assert "第一层" not in answer
    assert "2 / 158" in answer
    assert "5 / 166" in answer
    assert "SELECT " not in answer
