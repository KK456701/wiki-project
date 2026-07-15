import pytest

from app.diagnose.user_sql import prepare_pasted_sql


SAFE_SCRIPT = """
USE [WIN60_QA_991827];
DECLARE @BeginAt datetime2 = '2026-06-01 00:00:00';
DECLARE @EndAt datetime2 = '2026-08-01 00:00:00';
DECLARE @ThresholdMinutes int = 2880;

;WITH eligible AS (
  SELECT e.ENCOUNTER_ID
  FROM WINDBA.INPATIENT_ENCOUNTER e
  WHERE e.FIRST_ADMITTED_TO_WARD_AT >= @BeginAt
    AND e.FIRST_ADMITTED_TO_WARD_AT < @EndAt
)
SELECT COUNT_BIG(*) AS denominator_count,
       @ThresholdMinutes AS threshold_minutes
FROM eligible;
"""


def test_prepares_current_database_declare_and_cte_for_readonly_execution():
    prepared = prepare_pasted_sql(
        SAFE_SCRIPT,
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    assert prepared.safe_to_execute is True
    assert prepared.query_sql.lstrip().upper().startswith("WITH")
    assert "DECLARE" not in prepared.query_sql.upper()
    assert "'2026-06-01 00:00:00'" in prepared.query_sql
    assert "'2026-08-01 00:00:00'" in prepared.query_sql
    assert "2880 AS threshold_minutes" in prepared.query_sql
    assert prepared.declared_params["BeginAt"] == "2026-06-01 00:00:00"
    assert prepared.referenced_schemas == ["WINDBA"]


@pytest.mark.parametrize(
    "unsafe_sql",
    [
        "UPDATE WINDBA.T SET A = 1;",
        "DELETE FROM WINDBA.T;",
        "MERGE WINDBA.T AS target USING WINDBA.S AS source ON 1=1 WHEN MATCHED THEN UPDATE SET A=1;",
        "EXEC WINDBA.DO_SOMETHING;",
        "CREATE TABLE #TMP (ID int); SELECT * FROM #TMP;",
        "SELECT * INTO #TMP FROM WINDBA.T;",
        "DECLARE @sql nvarchar(max) = N'SELECT 1'; EXEC(@sql);",
    ],
)
def test_rejects_unsafe_scripts(unsafe_sql):
    prepared = prepare_pasted_sql(
        unsafe_sql,
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    assert prepared.safe_to_execute is False
    assert prepared.query_sql == ""
    assert prepared.blocked_reasons


def test_rejects_use_of_another_database():
    prepared = prepare_pasted_sql(
        "USE [OTHER_DB]; SELECT * FROM WINDBA.T;",
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    assert prepared.safe_to_execute is False
    assert any("数据库" in reason for reason in prepared.blocked_reasons)


def test_rejects_three_part_reference_to_another_database():
    prepared = prepare_pasted_sql(
        "SELECT * FROM OTHER_DB.WINDBA.T;",
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    assert prepared.safe_to_execute is False
    assert prepared.referenced_databases == ["OTHER_DB"]


def test_rejects_schema_outside_current_business_source():
    prepared = prepare_pasted_sql(
        "SELECT * FROM dbo.PATIENT;",
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    assert prepared.safe_to_execute is False
    assert prepared.referenced_schemas == ["dbo"]


def test_rejects_unresolved_parameters():
    prepared = prepare_pasted_sql(
        "SELECT * FROM WINDBA.T WHERE CREATED_AT >= @BeginAt;",
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    assert prepared.safe_to_execute is False
    assert any("参数" in reason for reason in prepared.blocked_reasons)


def test_does_not_replace_at_sign_inside_string_literal():
    prepared = prepare_pasted_sql(
        "DECLARE @Value int = 1; SELECT '@Value' AS raw_value, @Value AS bound_value;",
        allowed_database="WIN60_QA_991827",
        allowed_schema="WINDBA",
    )

    assert prepared.safe_to_execute is True
    assert "'@Value' AS raw_value" in prepared.query_sql
    assert "1 AS bound_value" in prepared.query_sql
