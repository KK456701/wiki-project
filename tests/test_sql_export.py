from __future__ import annotations

from app.sqlgen.sql_export import render_sqlserver_navicat_script


def test_render_sqlserver_navicat_script_declares_and_rewrites_parameters() -> None:
    sql = (
        "SELECT COUNT_BIG(*) FROM WINDBA.INPATIENT_ENCOUNTER "
        "WHERE HOSPITAL_SOID = :hospital_soid "
        "AND ADMITTED_AT >= :start_time "
        "AND ADMITTED_AT < :end_time "
        "AND INPAT_TRANSFER_TYPE_CODE = :transfer_code "
        "AND NAME = :display_name"
    )

    script = render_sqlserver_navicat_script(
        sql,
        {
            "hospital_soid": 991827,
            "start_time": "2026-06-01 00:00:00",
            "end_time": "2026-08-01 00:00:00",
            "transfer_code": 399549991,
            "display_name": "儿童'病区",
        },
        database="WIN60_QA_991827",
    )

    assert "USE [WIN60_QA_991827];" in script
    assert "DECLARE @hospital_soid BIGINT = 991827;" in script
    assert "DECLARE @start_time DATETIME2 = '2026-06-01 00:00:00';" in script
    assert "DECLARE @end_time DATETIME2 = '2026-08-01 00:00:00';" in script
    assert "DECLARE @display_name NVARCHAR(MAX) = N'儿童''病区';" in script
    assert "HOSPITAL_SOID = @hospital_soid" in script
    assert "ADMITTED_AT >= @start_time" in script
    assert ":hospital_soid" not in script


def test_render_sqlserver_navicat_script_rejects_missing_parameter() -> None:
    try:
        render_sqlserver_navicat_script("SELECT :missing", {})
    except ValueError as exc:
        assert "missing" in str(exc)
    else:
        raise AssertionError("missing SQL parameter should fail")
