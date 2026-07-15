from app.diagnose.sql_semantics import compare_sql_profiles, profile_sql
from app.diagnose.structure_check import _types_compatible


SYSTEM_SQL = """
WITH transfer_candidate AS (
  SELECT t.ENCOUNTER_ID, t.INPAT_TRANSFER_AT, t.ORIGIN_DEPT_ID, t.DESTINATION_DEPT_ID
  FROM WINDBA.INPAT_TRANSFER t
  WHERE t.IS_DEL = 0
),
valid_transfer AS (
  SELECT candidate.*,
         ROW_NUMBER() OVER (
           PARTITION BY candidate.ENCOUNTER_ID
           ORDER BY candidate.INPAT_TRANSFER_AT
         ) AS event_order
  FROM transfer_candidate candidate
  WHERE CHARINDEX(',' + CONVERT(varchar(30), candidate.ORIGIN_DEPT_ID) + ',',
                  ',' + :icu_org_ids_csv + ',') = 0
),
base AS (
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
  CASE WHEN COUNT_BIG(*) = 0 THEN 0 ELSE
       SUM(CASE WHEN DATEDIFF(MINUTE, base.ADMITTED_AT, base.INPAT_TRANSFER_AT)
                     BETWEEN 0 AND 2880 THEN 1 ELSE 0 END) * 100.0 / COUNT_BIG(*)
  END AS index_value
FROM base;
"""


USER_SQL = """
;WITH IcuOrg AS (
  SELECT ORG_ID FROM WINDBA.ORGANIZATION
  WHERE ORG_NO IN ('ICU01', 'ICU02')
), Base AS (
  SELECT e.ENCOUNTER_ID,
         CASE WHEN EXISTS (
           SELECT 1 FROM WINDBA.INPAT_TRANSFER t
           WHERE t.ENCOUNTER_ID = e.ENCOUNTER_ID
             AND ISNULL(t.IS_DEL, 0) = 0
             AND t.INPAT_TRANSFER_AT >= e.FIRST_ADMITTED_TO_WARD_AT
             AND t.INPAT_TRANSFER_AT < DATEADD(HOUR, 48, e.FIRST_ADMITTED_TO_WARD_AT)
             AND ISNULL(t.ORIGIN_DEPT_ID, 0) NOT IN (SELECT ORG_ID FROM IcuOrg)
             AND ISNULL(t.DESTINATION_DEPT_ID, 0) NOT IN (SELECT ORG_ID FROM IcuOrg)
         ) THEN 1 ELSE 0 END AS TRANSFER_WITHIN_48H
  FROM WINDBA.INPATIENT_ENCOUNTER e
  WHERE ISNULL(e.IS_DEL, 0) = 0
    AND ISNULL(e.INPAT_ENC_BIZ_TYPE_CODE, 0) <> 399552157
    AND e.FIRST_ADMITTED_TO_WARD_AT >= @BeginAt
    AND e.FIRST_ADMITTED_TO_WARD_AT < @EndAt
)
SELECT SUM(TRANSFER_WITHIN_48H) AS numerator_count,
       COUNT_BIG(*) AS denominator_count,
       CAST(100.0 * SUM(TRANSFER_WITHIN_48H) / NULLIF(COUNT_BIG(*), 0) AS decimal(10,2)) AS index_value
FROM Base;
"""


def test_transfer_indicator_detects_all_material_caliber_differences():
    system = profile_sql(SYSTEM_SQL, dialect="sqlserver")
    user = profile_sql(USER_SQL, dialect="sqlserver")

    findings = compare_sql_profiles(system, user)
    codes = {item.code for item in findings}

    assert "period_field_changed" in codes
    assert "elapsed_start_field_changed" in codes
    assert "upper_boundary_inclusive_changed" in codes
    assert "icu_scope_strategy_changed" in codes
    assert "event_selection_changed" in codes
    assert "null_handling_changed" in codes
    assert user.zero_denominator_guard is True
    assert system.zero_denominator_guard is True


def test_profile_lists_source_tables_and_key_columns():
    profile = profile_sql(USER_SQL, dialect="sqlserver")

    assert "WINDBA.INPATIENT_ENCOUNTER" in profile.tables
    assert "WINDBA.INPAT_TRANSFER" in profile.tables
    assert "WINDBA.ORGANIZATION" in profile.tables
    assert "FIRST_ADMITTED_TO_WARD_AT" in profile.columns
    assert "INPAT_TRANSFER_AT" in profile.columns
    assert profile.period_fields == ["FIRST_ADMITTED_TO_WARD_AT"]
    assert profile.event_selection == "any_matching_event"
    assert profile.icu_scope_strategy == "organization_code_lookup"
    assert profile.upper_boundary_mode == "exclusive"


def test_same_profile_has_no_material_findings():
    profile = profile_sql(USER_SQL, dialect="sqlserver")

    assert compare_sql_profiles(profile, profile) == []


def test_numeric_identifiers_are_compatible_with_business_string_contracts():
    assert _types_compatible("string", "numeric", "hospital_id") is True
    assert _types_compatible("string", "bigint", "consult_level_code") is True
    assert _types_compatible("string", "nvarchar", "patient_name") is True
    assert _types_compatible("datetime", "datetime2", "request_time") is True
    assert _types_compatible("datetime", "numeric", "request_time") is False
