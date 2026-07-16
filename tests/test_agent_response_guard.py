import pytest

from app.agent_runtime.response_guard import (
    evidence_correction_prompt,
    missing_fact_types,
)


@pytest.mark.parametrize(
    ("answer", "required"),
    [
        ("该 SQL 已校验通过，可以执行。", {"sql_validation"}),
        ("本次试运行分子 37、分母 40，指标值 92.5%。", {"trial_run"}),
        ("本次运行结果为 92.5%。", {"trial_run"}),
        ("分子：37，分母：40。", {"trial_run"}),
        ("诊断发现根因是字段映射缺失。", {"diagnosis"}),
        ("排查结论为到位时间字段异常。", {"diagnosis"}),
        (
            "本院采用 20 分钟会导致国标考核结果显著偏低。",
            {"rule_change_preview"},
        ),
    ],
)
def test_claims_require_matching_fact_types(answer, required) -> None:
    assert missing_fact_types(answer, []) == required


def test_matching_evidence_authorizes_claim() -> None:
    evidence = [{"fact_types": ["trial_run", "aggregate_result"]}]

    assert missing_fact_types(
        "本次试运行分子 37、分母 40，指标值 92.5%。",
        evidence,
    ) == set()


def test_rule_formula_does_not_require_trial_evidence() -> None:
    evidence = [{"fact_types": ["definition", "formula"]}]

    assert missing_fact_types(
        "公式为及时到位例数除以急会诊总例数乘以 100%。",
        evidence,
    ) == set()


def test_empty_or_general_answer_has_no_special_fact_requirement() -> None:
    assert missing_fact_types("", []) == set()
    assert missing_fact_types("请先选择需要查询的指标。", []) == set()


def test_correction_prompt_names_missing_business_evidence() -> None:
    prompt = evidence_correction_prompt({"trial_run", "sql_validation"})

    assert "SQL 安全校验" in prompt
    assert "试运行聚合结果" in prompt
    assert "不得编造" in prompt
