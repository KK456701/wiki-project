from __future__ import annotations

from pathlib import Path

from app.indicator_details.lineage import build_detail_lineage
from app.indicator_details.models import RunContext
from app.indicator_details.sql_builder import build_detail_query
from app.sqlgen.spec_loader import load_template


def _context() -> RunContext:
    return RunContext(
        rule_id="MQSI2025_001",
        rule_name="患者入院48小时内转科的比例",
        effective_level="hospital",
        national_version="2025",
        hospital_version=1,
        dialect="sqlserver",
        query_profile="inpatient_transfer_48h_sqlserver",
        calculation_definition={
            "schema_version": 1,
            "scope": {"conditions": []},
            "derived_fields": {
                "transfer_minutes": {
                    "name": "入院至转科耗时",
                    "operation": "timestamp_diff_minutes",
                    "source_fields": ["admit_time", "transfer_time"],
                }
            },
            "denominator": {
                "name": "同期入院患者总人次数",
                "inherits": "scope",
                "conditions": [],
                "aggregate": {"method": "count_distinct", "field": "admission_id"},
            },
            "numerator": {
                "name": "入院48小时内非ICU转科患者人次数",
                "inherits": "denominator",
                "conditions": [],
                "aggregate": {"method": "count_distinct", "field": "admission_id"},
            },
            "result": {
                "operation": "ratio_percent",
                "numerator": "numerator",
                "denominator": "denominator",
            },
            "detail_fields": [
                {"field": "admission_id", "label": "住院流水号", "sensitivity": "patient_id"},
                {"field": "admit_time", "label": "办理住院时间"},
                {"field": "transfer_time", "label": "实际转科时间"},
                {"field": "from_dept_id", "label": "转出科室"},
                {"field": "to_dept_id", "label": "转入科室"},
                {"field": "transfer_minutes", "label": "入院至转科耗时（分钟）"},
            ],
        },
        field_mapping={
            "db_name": "WIN60_QA_991827",
            "schema": "WINDBA",
            "main_table": "INPATIENT_ENCOUNTER",
            "fields": {
                "hospital_id": "INPATIENT_ENCOUNTER.HOSPITAL_SOID",
                "admission_id": "INPATIENT_ENCOUNTER.ENCOUNTER_ID",
                "admit_time": "INPATIENT_ENCOUNTER.ADMITTED_AT",
                "transfer_id": "INPAT_TRANSFER.INPAT_TRANSFER_ID",
                "transfer_time": "INPAT_TRANSFER.INPAT_TRANSFER_AT",
                "transfer_type": "INPAT_TRANSFER.INPAT_TRANSFER_TYPE_CODE",
                "from_dept_id": "INPAT_TRANSFER.ORIGIN_DEPT_ID",
                "from_ward_id": "INPAT_TRANSFER.ORIGIN_WARD_ID",
                "to_dept_id": "INPAT_TRANSFER.DESTINATION_DEPT_ID",
                "to_ward_id": "INPAT_TRANSFER.DESTINATION_WARD_ID",
            },
        },
        params={
            "hospital_soid": 991827,
            "excluded_inpatient_business_code": 399552157,
            "transfer_department_code": 399549991,
            "transfer_ward_code": 399549990,
            "icu_org_ids_csv": "360896232048246943,360915701134999568",
            "transfer_minutes_threshold": 2880,
            "start_time": "2026-06-01 00:00:00",
            "end_time": "2026-08-01 00:00:00",
        },
        stat_start="2026-06-01 00:00:00",
        stat_end="2026-08-01 00:00:00",
        db_source="win60_qa_991827",
        main_table="INPATIENT_ENCOUNTER",
    )


def test_sqlserver_template_encodes_the_approved_caliber() -> None:
    sql = load_template(
        Path("core-rules-wiki"), "MQSI2025_001", "sqlserver"
    )

    assert "WINDBA.INPATIENT_ENCOUNTER" in sql
    assert "WINDBA.INPAT_TRANSFER" in sql
    assert "WINDBA.ORGANIZATION" not in sql
    assert "encounter.ADMITTED_AT >= :start_time" in sql
    assert "encounter.ADMITTED_AT < :end_time" in sql
    assert "ORDER BY candidate.transfer_time, candidate.transfer_id" in sql
    assert "DATEDIFF(MINUTE, base.admit_time, base.transfer_time)" in sql
    assert "BETWEEN 0 AND :transfer_minutes_threshold" in sql
    assert "icu_org_ids_csv" in sql
    assert "DATEDIFF(HOUR" not in sql
    for alias in (
        "index_value",
        "numerator_count",
        "denominator_count",
        "sample_count",
    ):
        assert alias in sql


def test_sqlserver_detail_reuses_the_aggregate_scope_and_event_selection() -> None:
    query = build_detail_query(_context())

    assert query.sql.startswith("WITH eligible_encounter AS")
    assert "SELECT TOP 20001" in query.sql
    assert "WINDBA.INPATIENT_ENCOUNTER" in query.sql
    assert "WINDBA.INPAT_TRANSFER" in query.sql
    assert "WINDBA.ORGANIZATION" not in query.sql
    assert "encounter.ADMITTED_AT >= :start_time" in query.sql
    assert "encounter.ADMITTED_AT < :end_time" in query.sql
    assert "ORDER BY candidate.transfer_time, candidate.transfer_id" in query.sql
    assert "BETWEEN 0 AND :transfer_minutes_threshold" in query.sql
    assert "AS [__meets_numerator]" in query.sql
    assert query.params["hospital_soid"] == 991827
    assert query.params["transfer_minutes_threshold"] == 2880


def test_sqlserver_detail_lineage_lists_both_source_tables() -> None:
    context = _context()
    query = build_detail_query(context)

    database, tables, fields = build_detail_lineage(context, query.columns)

    assert database == "WIN60_QA_991827"
    assert tables == [
        "WINDBA.INPATIENT_ENCOUNTER",
        "WINDBA.INPAT_TRANSFER",
    ]
    transfer_minutes = next(item for item in fields if item.field == "transfer_minutes")
    assert transfer_minutes.sources == [
        "WINDBA.INPATIENT_ENCOUNTER.ADMITTED_AT",
        "WINDBA.INPAT_TRANSFER.INPAT_TRANSFER_AT",
    ]
