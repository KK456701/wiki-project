from __future__ import annotations

import copy

from app.sqlgen.explanation import format_generation_explanation
from tests.test_sql_explanation import (
    GENERATION_RESULT,
    HOSPITAL_RULE,
    URGENT_LINEAGE,
)


def test_sqlserver_generation_exposes_parameterized_and_navicat_tabs() -> None:
    result = copy.deepcopy(GENERATION_RESULT)
    result.update(
        {
            "dialect": "sqlserver",
            "sql_text": (
                "SELECT COUNT_BIG(*) FROM WINDBA.INPATIENT_ENCOUNTER "
                "WHERE HOSPITAL_SOID = :hospital_soid "
                "AND ADMITTED_AT >= :start_time "
                "AND ADMITTED_AT < :end_time"
            ),
            "params": {"hospital_soid": 991827},
        }
    )
    lineage = copy.deepcopy(URGENT_LINEAGE)
    lineage["db_name"] = "WIN60_QA_991827"

    answer = format_generation_explanation(
        result=result,
        effective_rule=HOSPITAL_RULE,
        lineage=lineage,
        hospital_id="hospital_001",
        stat_start="2026-06-01 00:00:00",
        stat_end="2026-08-01 00:00:00",
    )

    assert ":::sqltabs" in answer
    assert "@@tab 系统参数化 SQL" in answer
    assert "@@tab Navicat 可执行 SQL" in answer
    assert "HOSPITAL_SOID = :hospital_soid" in answer
    assert "DECLARE @hospital_soid BIGINT = 991827;" in answer
    assert "DECLARE @start_time DATETIME2 = '2026-06-01 00:00:00';" in answer
    assert "HOSPITAL_SOID = @hospital_soid" in answer
