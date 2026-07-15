import json

from app.diagnose.evidence import extract_pasted_evidence


RAW_DIAGNOSIS_TEXT = """
为什么我们算得不一样？

```sql
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
SELECT
    2 AS numerator_count,
    158 AS denominator_count,
    CAST(2 * 100.0 / NULLIF(158, 0) AS decimal(10, 2)) AS index_value
FROM eligible;
```

本次执行结果：分子 2，分母 158，指标结果 1.27%。
"""


class _SequenceLLM:
    def __init__(self, responses):
        self.responses = list(responses)

    def generate(self, prompt):
        return self.responses.pop(0)


def test_extracts_sqlserver_script_params_period_and_claimed_result():
    evidence = extract_pasted_evidence(
        RAW_DIAGNOSIS_TEXT,
        rule_id="MQSI2025_001",
    )

    assert evidence.rule_id == "MQSI2025_001"
    assert evidence.question == "为什么我们算得不一样？"
    assert evidence.sql_text.startswith("USE [WIN60_QA_991827]")
    assert evidence.declared_params["BeginAt"] == "2026-06-01 00:00:00"
    assert evidence.declared_params["EndAt"] == "2026-08-01 00:00:00"
    assert evidence.declared_params["ThresholdMinutes"] == 2880
    assert evidence.claimed_result["numerator_count"] == 2
    assert evidence.claimed_result["denominator_count"] == 158
    assert evidence.claimed_result["index_value"] == 1.27
    assert evidence.stat_period.start == "2026-06-01 00:00:00"
    assert evidence.stat_period.end == "2026-08-01 00:00:00"
    assert evidence.model_parse_status == "not_used"


def test_prefers_sql_code_block_over_unrelated_code_block():
    text = """
请排查下面的结果。
```text
分子=4，分母=20
```
```sql
SELECT COUNT(*) AS denominator_count FROM WINDBA.CONSULT_ORDER;
```
"""

    evidence = extract_pasted_evidence(text, rule_id="MQSI2025_005")

    assert evidence.sql_text.startswith("SELECT COUNT(*)")
    assert evidence.claimed_result["numerator_count"] == 4
    assert evidence.claimed_result["denominator_count"] == 20


def test_invalid_model_json_falls_back_to_deterministic_evidence():
    evidence = extract_pasted_evidence(
        RAW_DIAGNOSIS_TEXT,
        rule_id="MQSI2025_001",
        llm_client=_SequenceLLM(["这不是 JSON"]),
    )

    assert evidence.declared_params["BeginAt"] == "2026-06-01 00:00:00"
    assert evidence.claimed_result["denominator_count"] == 158
    assert evidence.model_parse_status == "invalid"
    assert any("模型" in item for item in evidence.parse_warnings)


def test_deterministic_sql_and_numbers_win_when_model_conflicts():
    model_result = {
        "question": "数据库是不是坏了？",
        "rule_id": "MQSI2025_999",
        "sql_text": "DELETE FROM patient",
        "declared_params": {"BeginAt": "2030-01-01"},
        "claimed_result": {"denominator_count": 999},
        "stat_period": {"start": "2030-01-01", "end": "2030-02-01"},
        "parse_warnings": [],
    }
    evidence = extract_pasted_evidence(
        RAW_DIAGNOSIS_TEXT,
        rule_id="MQSI2025_001",
        llm_client=_SequenceLLM([json.dumps(model_result, ensure_ascii=False)]),
    )

    assert evidence.rule_id == "MQSI2025_001"
    assert evidence.sql_text.startswith("USE [WIN60_QA_991827]")
    assert evidence.declared_params["BeginAt"] == "2026-06-01 00:00:00"
    assert evidence.claimed_result["denominator_count"] == 158
    assert evidence.stat_period.start == "2026-06-01 00:00:00"
    assert evidence.model_parse_status == "accepted_with_overrides"
    assert any("确定性解析" in item for item in evidence.parse_warnings)


def test_plain_question_without_sql_keeps_empty_sql():
    evidence = extract_pasted_evidence(
        "帮我诊断急会诊及时到位率为什么下降",
        rule_id="MQSI2025_005",
    )

    assert evidence.sql_text == ""
    assert evidence.question == "帮我诊断急会诊及时到位率为什么下降"
    assert evidence.declared_params == {}
