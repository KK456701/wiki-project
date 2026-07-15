from datetime import datetime, timedelta
from pathlib import Path

import yaml

from app.diagnose.detail_compare import (
    DiagnosisComparisonStore,
    build_current_detail_query,
    build_user_detail_query,
    compare_detail_rows,
    create_detail_comparison,
)
from app.db_access.query_result import QueryResult
from app.indicator_details.models import DetailColumn, DetailQuery
from app.diagnose.user_sql import prepare_pasted_sql


USER_SQL = """
USE [WIN60_QA_991827];
DECLARE @BeginAt datetime2 = '2026-06-01 00:00:00';
DECLARE @EndAt datetime2 = '2026-08-01 00:00:00';
;WITH PatientResult AS (
    SELECT e.ENCOUNTER_ID,
           CASE WHEN e.FIRST_ADMITTED_TO_WARD_AT >= @BeginAt
                AND e.FIRST_ADMITTED_TO_WARD_AT < @EndAt
                THEN 1 ELSE 0 END AS TRANSFER_WITHIN_48H
    FROM WINDBA.INPATIENT_ENCOUNTER e
)
SELECT
    SUM(TRANSFER_WITHIN_48H) AS [分子_入区48小时内转科人次],
    COUNT_BIG(*) AS [分母_同期入区人次],
    1.27 AS [入区48小时内转科比例_百分比]
FROM PatientResult;
"""


def test_builds_readonly_detail_query_from_supported_cte_aggregate():
    prepared = prepare_pasted_sql(
        USER_SQL,
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    detail_sql = build_user_detail_query(prepared.query_sql, "MQSI2025_001")

    assert "PatientResult.ENCOUNTER_ID AS [record_key]" in detail_sql
    assert "PatientResult.TRANSFER_WITHIN_48H AS [user_meets_numerator]" in detail_sql
    assert "SUM(TRANSFER_WITHIN_48H)" not in detail_sql
    assert "FROM PatientResult" in detail_sql


def test_builds_detail_query_when_numerator_flag_uses_chinese_alias():
    sql = USER_SQL.replace(
        "TRANSFER_WITHIN_48H",
        "是否48小时内转科",
    ).replace(
        "SUM(是否48小时内转科)",
        "SUM(CAST(是否48小时内转科 AS bigint))",
    )
    prepared = prepare_pasted_sql(
        sql,
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    detail_sql = build_user_detail_query(prepared.query_sql, "MQSI2025_001")

    assert "PatientResult.[是否48小时内转科] AS [user_meets_numerator]" in detail_sql
    assert "SUM(CAST(是否48小时内转科 AS bigint))" not in detail_sql
    assert "FROM PatientResult" in detail_sql


def test_rejects_aggregate_without_supported_record_contract():
    sql = "SELECT COUNT_BIG(*) AS denominator_count FROM WINDBA.INPATIENT_ENCOUNTER"

    try:
        build_user_detail_query(sql, "MQSI2025_001")
    except ValueError as exc:
        assert "业务主键" in str(exc)
    else:
        raise AssertionError("缺少业务主键的聚合 SQL 不应生成明细查询")


def test_compares_scope_and_numerator_by_business_record_key():
    user_rows = [
        {"record_key": "E001", "user_meets_numerator": 1},
        {"record_key": "E002", "user_meets_numerator": 1},
        {"record_key": "E003", "user_meets_numerator": 0},
    ]
    current_rows = [
        {"admission_id": "E002", "__meets_numerator": 0, "admit_time": "2026-06-01"},
        {"admission_id": "E003", "__meets_numerator": 1, "admit_time": "2026-06-02"},
        {"admission_id": "E004", "__meets_numerator": 0, "admit_time": "2026-06-03"},
    ]

    comparison = compare_detail_rows(user_rows, current_rows)

    assert comparison["counts"] == {
        "all_differences": 4,
        "only_user_scope": 1,
        "only_current_scope": 1,
        "user_only_numerator": 1,
        "current_only_numerator": 1,
    }
    rows = {item["record_key"]: item for item in comparison["rows"]}
    assert rows["E001"]["difference_group"] == "only_user_scope"
    assert rows["E002"]["difference_group"] == "user_only_numerator"
    assert rows["E003"]["difference_group"] == "current_only_numerator"
    assert rows["E004"]["difference_group"] == "only_current_scope"
    assert rows["E003"]["current_details"]["admit_time"] == "2026-06-02"


def test_comparison_snapshot_is_scoped_paginated_and_expires(tmp_path):
    now = datetime(2026, 7, 15, 12, 0, 0)
    store = DiagnosisComparisonStore(
        tmp_path,
        now_provider=lambda: now,
        ttl=timedelta(hours=24),
    )
    saved = store.save(
        hospital_id="hospital_001",
        rule_id="MQSI2025_001",
        source_database="WIN60_QA_991827",
        user_result={"numerator_count": 1, "denominator_count": 2, "result_value": 50},
        current_result={"numerator_count": 0, "denominator_count": 2, "result_value": 0},
        comparison={
            "counts": {
                "all_differences": 1,
                "only_user_scope": 0,
                "only_current_scope": 0,
                "user_only_numerator": 1,
                "current_only_numerator": 0,
            },
            "rows": [{
                "record_key": "ENCOUNTER-001",
                "difference_group": "user_only_numerator",
                "difference_reason": "用户 SQL 计入分子，当前生效 SQL 未计入分子。",
                "user_in_scope": True,
                "current_in_scope": True,
                "user_meets_numerator": True,
                "current_meets_numerator": False,
                "current_details": {},
            }],
        },
    )

    summary = store.read_summary("hospital_001", saved["comparison_id"])
    page = store.read_page(
        "hospital_001",
        saved["comparison_id"],
        "user_only_numerator",
        page=1,
        page_size=20,
    )

    assert summary["counts"]["all_differences"] == 1
    assert page["total"] == 1
    assert page["items"][0]["record_key"] != "ENCOUNTER-001"
    assert "*" in page["items"][0]["record_key"]

    expired = DiagnosisComparisonStore(
        tmp_path,
        now_provider=lambda: now + timedelta(hours=25),
        ttl=timedelta(hours=24),
    )
    try:
        expired.read_summary("hospital_001", saved["comparison_id"])
    except ValueError as exc:
        assert "过期" in str(exc)
    else:
        raise AssertionError("过期对账快照不应继续读取")
    assert expired.cleanup_expired() == 1
    assert list(tmp_path.rglob("*.json.gz")) == []


class _DetailDB:
    source_id = "win60_qa_991827"

    def __init__(self, outcomes):
        self.outcomes = list(outcomes)
        self.sql = []

    def execute_select(self, sql):
        self.sql.append(sql)
        rows = self.outcomes.pop(0)
        return QueryResult(
            rows=rows,
            row_count=len(rows),
            source=self.source_id,
            tool_name="execute_sql_win60_qa_991827",
            duration_ms=2,
        )


def test_executes_both_detail_queries_and_saves_verified_comparison(tmp_path):
    db = _DetailDB([
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
    current_query = DetailQuery(
        sql="SELECT admission_id, __meets_numerator FROM current_detail",
        params={},
        columns=[
            DetailColumn(
                field="admission_id",
                label="入院流水号",
                sensitivity="patient_id",
            )
        ],
    )

    result = create_detail_comparison(
        business_db=db,
        store=store,
        hospital_id="hospital_001",
        rule_id="MQSI2025_001",
        source_database="WIN60_QA_991827",
        user_detail_sql="SELECT record_key, user_meets_numerator FROM user_detail",
        current_detail_query=current_query,
        user_result={"numerator_count": 1, "denominator_count": 2, "result_value": 50},
        current_result={"numerator_count": 0, "denominator_count": 2, "result_value": 0},
    )

    assert result["status"] == "ready"
    assert result["counts"]["user_only_numerator"] == 1
    assert len(db.sql) == 2
    assert store.read_summary("hospital_001", result["comparison_id"])["rule_id"] == "MQSI2025_001"


def test_builds_current_detail_query_from_effective_rule_and_mapping():
    root = Path(__file__).resolve().parents[1]
    specification = yaml.safe_load((
        root
        / "core-rules-wiki/sql-specs/MQSI2025_001_患者入院48小时内转科比例/rule_sql_spec.yaml"
    ).read_text(encoding="utf-8"))
    mapping = yaml.safe_load((
        root / "core-rules-wiki/hospital-mappings/hospital_001/MQSI2025_001.yaml"
    ).read_text(encoding="utf-8"))

    query = build_current_detail_query(
        effective_rule={
            "rule_name": "患者入院48小时内转科比例",
            "effective_level": "hospital",
            "national_version": "2025",
            "hospital_version": 4,
            "calculation_definition": specification["calculation"],
        },
        caliber_context={
            "effective_params": {
                "hospital_soid": 991827,
                "excluded_inpatient_business_code": 399552157,
                "transfer_department_code": 399549991,
                "transfer_ward_code": 399549990,
                "icu_org_ids_csv": "101,102",
                "transfer_minutes_threshold": 2880,
            },
        },
        field_mapping=mapping,
        stat_start="2026-06-01 00:00:00",
        stat_end="2026-08-01 00:00:00",
    )

    assert "encounter.ADMITTED_AT >= :start_time" in query.sql
    assert "AS [admission_id]" in query.sql
    assert query.params["start_time"] == "2026-06-01 00:00:00"
    assert query.params["end_time"] == "2026-08-01 00:00:00"


def test_does_not_save_detail_when_rows_do_not_match_aggregate(tmp_path):
    db = _DetailDB([
        [{"record_key": "E001", "user_meets_numerator": 1}],
        [{"admission_id": "E001", "__meets_numerator": 0}],
    ])

    result = create_detail_comparison(
        business_db=db,
        store=DiagnosisComparisonStore(tmp_path),
        hospital_id="hospital_001",
        rule_id="MQSI2025_001",
        source_database="WIN60_QA_991827",
        user_detail_sql="SELECT record_key, user_meets_numerator FROM user_detail",
        current_detail_query=DetailQuery(
            sql="SELECT admission_id, __meets_numerator FROM current_detail",
            params={},
            columns=[],
        ),
        user_result={"numerator_count": 2, "denominator_count": 2, "result_value": 100},
        current_result={"numerator_count": 0, "denominator_count": 1, "result_value": 0},
    )

    assert result["status"] == "unavailable"
    assert "汇总结果不一致" in result["reason"]
    assert list(tmp_path.rglob("*.json.gz")) == []
