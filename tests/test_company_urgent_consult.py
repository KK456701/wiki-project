from __future__ import annotations

from app.indicator_details.models import RunContext
from app.indicator_details.lineage import build_detail_lineage
from app.indicator_details.sql_builder import build_detail_query
from app.sqlgen.spec_loader import load_template


def _context() -> RunContext:
    return RunContext(
        rule_id="MQSI2025_005",
        rule_name="急会诊及时到位率",
        effective_level="hospital",
        national_version="2025",
        hospital_version=1,
        dialect="sqlserver",
        query_profile="urgent_consult_sqlserver",
        calculation_definition={
            "schema_version": 1,
            "scope": {"conditions": []},
            "derived_fields": {
                "arrive_minutes": {
                    "name": "申请至到位耗时",
                    "operation": "timestamp_diff_minutes",
                    "source_fields": ["request_time", "arrive_time"],
                }
            },
            "denominator": {
                "name": "同期急会诊总次数",
                "inherits": "scope",
                "conditions": [],
                "aggregate": {"method": "count_distinct", "field": "consult_id"},
            },
            "numerator": {
                "name": "及时到位急会诊次数",
                "inherits": "denominator",
                "conditions": [],
                "aggregate": {"method": "count_distinct", "field": "consult_id"},
            },
            "result": {
                "operation": "ratio_percent",
                "numerator": "numerator",
                "denominator": "denominator",
            },
            "detail_fields": [
                {"field": "consult_id", "label": "会诊申请标识"},
                {"field": "patient_id", "label": "患者标识", "sensitivity": "patient_id"},
                {"field": "dept_id", "label": "申请科室"},
                {"field": "consult_type", "label": "会诊类型"},
                {"field": "request_time", "label": "申请时间"},
                {"field": "arrive_time", "label": "到位时间"},
                {"field": "arrive_minutes", "label": "到位耗时（分钟）"},
            ],
        },
        field_mapping={
            "db_name": "WIN60_QA_991827",
            "schema": "WINDBA",
            "main_table": "INPATIENT_CONSULT_APPLY",
            "fields": {
                "consult_id": "INPATIENT_CONSULT_APPLY.INP_CONSULT_APPLY_ID",
                "patient_id": "INPATIENT_CONSULT_APPLY.ADMISSION_NUMBER",
                "dept_id": "INPATIENT_CONSULT_APPLY.DEPT_ID",
                "consult_type": "INPATIENT_CONSULT_APPLY.CONSULT_LEVEL_CODE",
                "request_time": "INPATIENT_CONSULT_APPLY.APPLY_CONSULT_SENT_AT",
                "arrive_time": "INP_CONSULT_INVITATION.SIGNED_AT",
            },
        },
        params={
            "hospital_soid": 991827,
            "urgent_level_code": 977578,
            "arrive_minutes_threshold": 20,
            "start_time": "2026-06-01 00:00:00",
            "end_time": "2026-07-01 00:00:00",
        },
        stat_start="2026-06-01 00:00:00",
        stat_end="2026-07-01 00:00:00",
        db_source="win60_qa_991827",
        main_table="INPATIENT_CONSULT_APPLY",
    )


def test_sqlserver_template_counts_each_application_once() -> None:
    template = load_template(
        __import__("pathlib").Path("core-rules-wiki"),
        "MQSI2025_005",
        "sqlserver",
    )

    assert "WINDBA.INPATIENT_CONSULT_APPLY" in template
    assert "WINDBA.INP_CONSULT_INVITATION" in template
    assert "MIN(CASE" in template
    assert "CONSULT_CANCEL_AT IS NULL" in template
    assert "DATEDIFF(MINUTE" in template
    assert "TIMESTAMPDIFF" not in template


def test_sqlserver_detail_query_reuses_aggregate_scope_and_deduplication() -> None:
    query = build_detail_query(_context())

    assert query.sql.startswith("SELECT TOP 20001")
    assert "TOP (20001)" not in query.sql
    assert "WINDBA.INPATIENT_CONSULT_APPLY" in query.sql
    assert "WINDBA.INP_CONSULT_INVITATION" in query.sql
    assert "MIN(CASE" in query.sql
    assert "CONSULT_CANCEL_AT IS NULL" in query.sql
    assert "DATEDIFF(MINUTE" in query.sql
    assert "N'急会诊' AS [consult_type]" in query.sql
    assert "LIMIT" not in query.sql
    assert query.params["hospital_soid"] == 991827
    assert query.params["urgent_level_code"] == 977578
    assert query.params["arrive_minutes_threshold"] == 20


def test_company_detail_lineage_includes_database_schema() -> None:
    context = _context()
    query = build_detail_query(context)

    database, tables, fields = build_detail_lineage(context, query.columns)

    assert database == "WIN60_QA_991827"
    assert tables == [
        "WINDBA.INPATIENT_CONSULT_APPLY",
        "WINDBA.INP_CONSULT_INVITATION",
    ]
    request_time = next(item for item in fields if item.field == "request_time")
    assert request_time.sources == [
        "WINDBA.INPATIENT_CONSULT_APPLY.APPLY_CONSULT_SENT_AT"
    ]
