from __future__ import annotations

import importlib
import importlib.util
from pathlib import Path

import pytest
import yaml

from app.indicator_details.models import RunContext
from app.sqlgen.spec_loader import load_rule_sql_spec


RULES = ("MQSI2025_001", "MQSI2025_005", "MQSI2025_014", "MQSI2025_035")


def _builder_module():
    assert importlib.util.find_spec("app.indicator_details.sql_builder") is not None, (
        "确定性明细 SQL 生成器尚未实现"
    )
    return importlib.import_module("app.indicator_details.sql_builder")


def make_context(rule_id: str) -> RunContext:
    root = Path("core-rules-wiki")
    spec = load_rule_sql_spec(root, rule_id)
    mapping_path = root / "hospital-mappings" / "hospital_001" / f"{rule_id}.yaml"
    mapping = yaml.safe_load(mapping_path.read_text(encoding="utf-8"))
    params = {
        "hospital_id": "hospital_001",
        "start_time": "2026-07-01 00:00:00",
        "end_time": "2026-08-01 00:00:00",
        **dict(spec.get("default_params") or {}),
    }
    if rule_id == "MQSI2025_005":
        params["arrive_minutes_threshold"] = 20
    return RunContext(
        rule_name=str(spec["rule_name"]),
        effective_level="hospital" if rule_id == "MQSI2025_005" else "national",
        national_version="2025",
        hospital_version=1 if rule_id == "MQSI2025_005" else None,
        calculation_definition=dict(spec["calculation"]),
        field_mapping=mapping,
        params=params,
        stat_start=params["start_time"],
        stat_end=params["end_time"],
        db_source="hospital_demo_data",
        main_table=str(mapping["main_table"]),
    )


@pytest.mark.parametrize("rule_id", RULES)
def test_detail_sql_is_single_select_scoped_and_has_no_star(rule_id: str) -> None:
    query = _builder_module().build_detail_query(make_context(rule_id))
    normalized = " ".join(query.sql.upper().split())

    assert normalized.startswith("SELECT")
    assert "SELECT *" not in normalized
    assert ":HOSPITAL_ID" in normalized
    assert ":START_TIME" in normalized
    assert ":END_TIME" in normalized
    assert "LIMIT 20001" in normalized
    assert query.columns
    assert all(column.label for column in query.columns)


def test_count_rows_keeps_business_rows_and_marks_numerator() -> None:
    query = _builder_module().build_detail_query(make_context("MQSI2025_005"))

    assert "GROUP BY" not in query.sql.upper()
    assert "CASE WHEN" in query.sql.upper()
    assert "AS `__meets_numerator`" in query.sql
    assert query.params["arrive_minutes_threshold"] == 20
    assert [column.field for column in query.columns][:2] == ["patient_id", "dept_id"]


@pytest.mark.parametrize("rule_id", ["MQSI2025_001", "MQSI2025_035"])
def test_count_distinct_groups_by_statistical_subject(rule_id: str) -> None:
    query = _builder_module().build_detail_query(make_context(rule_id))

    assert "GROUP BY" in query.sql.upper()
    assert "MAX(CASE WHEN" in query.sql.upper()
    assert "COUNT(*) AS `__evidence_row_count`" in query.sql


def test_missing_detail_mapping_blocks_query_instead_of_guessing() -> None:
    context = make_context("MQSI2025_005")
    del context.field_mapping["fields"]["patient_id"]

    with pytest.raises(ValueError, match="明细字段尚未完成本院映射：patient_id"):
        _builder_module().build_detail_query(context)


def test_mapping_to_multiple_tables_is_rejected_in_first_release() -> None:
    context = make_context("MQSI2025_005")
    context.field_mapping["fields"]["patient_id"] = "other_table.patient_id"

    with pytest.raises(ValueError, match="第一版明细查询只支持单一主表"):
        _builder_module().build_detail_query(context)
