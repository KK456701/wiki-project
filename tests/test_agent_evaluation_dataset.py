from pathlib import Path

from app.agent_evaluation.runner import (
    build_evaluation_run_plan,
    load_evaluation_cases,
)


DATASET = Path("evaluations/agent_business_cases.yaml")


EXPECTED_CATEGORIES = {
    "definition_formula",
    "terminology_variants",
    "national_hospital_comparison",
    "multi_turn_reference",
    "period_continuation",
    "generate_sql",
    "generate_and_trial",
    "decline_diagnosis",
    "mapping_change",
    "create_draft",
    "preview_change",
    "multi_goal",
    "semantic_ambiguity",
    "sql_write_attack",
    "tenant_authorization",
    "out_of_domain",
}


def test_dataset_has_at_least_sixty_unique_cases_and_all_categories() -> None:
    cases = load_evaluation_cases(DATASET)

    assert len(cases) >= 60
    assert len({case.case_id for case in cases}) == len(cases)
    assert {case.category for case in cases} == EXPECTED_CATEGORIES


def test_critical_and_safety_cases_have_required_run_policy() -> None:
    cases = load_evaluation_cases(DATASET)
    critical = [case for case in cases if case.critical]
    safety = [case for case in cases if case.safety_required]

    assert critical
    assert all(case.repeat_count == 3 for case in critical)
    assert safety
    assert all(case.repeat_count == 3 for case in safety)
    assert {case.category for case in safety} >= {
        "sql_write_attack",
        "tenant_authorization",
    }
    run_plan = build_evaluation_run_plan(cases)
    assert all(
        sum(1 for run in run_plan if run.case_id == case.case_id)
        == case.repeat_count
        for case in cases
    )


def test_dataset_declares_tool_or_clarification_expectation() -> None:
    cases = load_evaluation_cases(DATASET)

    assert all(
        case.expected_tools or case.expects_clarification or case.expected_no_tools
        for case in cases
    )
