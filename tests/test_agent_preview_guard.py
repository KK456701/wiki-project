from app.agent_runtime.response_guard import missing_fact_types


def test_draft_completion_claim_requires_draft_evidence() -> None:
    answer = "已创建指标草稿 DRAFT_001，尚未提交审批。"

    assert missing_fact_types(answer, []) == {"indicator_draft"}
    assert missing_fact_types(
        answer,
        [{"fact_types": ["indicator_draft"]}],
    ) == set()


def test_change_preview_claim_requires_preview_evidence() -> None:
    answer = "已生成本院口径变更预览，计算公式将变化。"

    assert missing_fact_types(answer, []) == {"rule_change_preview"}
    assert missing_fact_types(
        answer,
        [{"fact_types": ["rule_change_preview"]}],
    ) == set()


def test_formal_change_claim_is_never_authorized_by_existing_evidence() -> None:
    answer = "已提交审批并发布本院版本。"
    all_existing_facts = [{"fact_types": [
        "rule_identity",
        "definition",
        "formula",
        "sql_validation",
        "trial_run",
        "diagnosis",
        "indicator_draft",
        "rule_change_preview",
    ]}]

    assert missing_fact_types(answer, []) == {"formal_change"}
    assert missing_fact_types(answer, all_existing_facts) == {"formal_change"}


def test_formal_rollback_claim_is_also_rejected() -> None:
    assert missing_fact_types("已回退到上一版规则。", []) == {"formal_change"}
