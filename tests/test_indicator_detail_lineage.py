from __future__ import annotations

import pytest

from app.indicator_details.lineage import build_detail_lineage
from app.indicator_details.sql_builder import build_detail_query
from tests.test_indicator_detail_sql import make_context


def test_lineage_distinguishes_database_columns_and_derived_fields() -> None:
    context = make_context("MQSI2025_005")
    query = build_detail_query(context)

    database, tables, lineage = build_detail_lineage(context, query.columns)

    assert database == "hospital_demo_data"
    assert tables == ["consult_record"]
    assert lineage[0].field == "patient_id"
    assert lineage[0].kind == "column"
    assert lineage[0].sources == ["consult_record.patient_id"]
    assert lineage[0].explanation == "来自 consult_record.patient_id"
    arrive = next(item for item in lineage if item.field == "arrive_minutes")
    assert arrive.kind == "derived"
    assert arrive.sources == [
        "consult_record.request_time",
        "consult_record.arrive_time",
    ]
    assert arrive.explanation == "由申请时间、到位时间计算"


def test_lineage_falls_back_to_run_context_database_source() -> None:
    context = make_context("MQSI2025_005")
    context.field_mapping.pop("db_name", None)

    database, tables, _ = build_detail_lineage(
        context, build_detail_query(context).columns
    )

    assert database == "hospital_demo_data"
    assert tables == ["consult_record"]


def test_lineage_lists_each_source_table_once_in_stable_order() -> None:
    context = make_context("MQSI2025_005")
    columns = build_detail_query(context).columns
    context.field_mapping["fields"]["dept_id"] = "department.dept_id"

    _, tables, lineage = build_detail_lineage(context, columns)

    assert tables == ["consult_record", "department"]
    assert next(item for item in lineage if item.field == "dept_id").sources == [
        "department.dept_id"
    ]


def test_lineage_rejects_missing_or_unqualified_source_column() -> None:
    context = make_context("MQSI2025_005")
    columns = build_detail_query(context).columns
    del context.field_mapping["fields"]["patient_id"]

    with pytest.raises(ValueError, match="明细字段尚未完成本院映射：patient_id"):
        build_detail_lineage(context, columns)

    context.field_mapping["fields"]["patient_id"] = "patient_id"
    with pytest.raises(ValueError, match="医院字段映射格式无效：patient_id"):
        build_detail_lineage(context, columns)
