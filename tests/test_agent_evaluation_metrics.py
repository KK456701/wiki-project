from app.agent_evaluation.contracts import EvaluationCase, EvaluationObservation
from app.agent_evaluation.runner import evaluate_observations


def _case(case_id, *, tools=(), clarification=False, safety=False):
    return EvaluationCase(
        case_id=case_id,
        category="fixture",
        query="测试问题",
        expected_tools=list(tools),
        expects_clarification=clarification,
        expected_no_tools=not tools and not clarification,
        safety_required=safety,
        repeat_count=1,
    )


def test_report_calculates_denominators_thresholds_and_safety_gate() -> None:
    cases = [
        _case("single", tools=("search_indicator_rules",)),
        _case("multi", tools=("search_indicator_rules", "get_effective_rule")),
        _case("clarify", clarification=True),
        _case("safety", safety=True),
    ]
    observations = [
        EvaluationObservation(
            case_id="single",
            called_tools=["search_indicator_rules"],
            task_completed=True,
            schema_valid=True,
            chinese_answer=True,
            evidence_valid=True,
        ),
        EvaluationObservation(
            case_id="multi",
            called_tools=["search_indicator_rules", "get_effective_rule"],
            task_completed=True,
            schema_valid=True,
            chinese_answer=True,
            evidence_valid=True,
        ),
        EvaluationObservation(
            case_id="clarify",
            clarification_requested=True,
            schema_valid=True,
            chinese_answer=True,
            evidence_valid=True,
        ),
        EvaluationObservation(
            case_id="safety",
            schema_valid=True,
            chinese_answer=True,
            evidence_valid=True,
            safety_violations=[],
        ),
    ]

    report = evaluate_observations(cases, observations)

    assert report.metrics["single_tool_accuracy"].model_dump() == {
        "numerator": 1,
        "denominator": 1,
        "value": 1.0,
        "threshold": 0.9,
        "passed": True,
        "lower_is_better": False,
    }
    assert report.metrics["multi_tool_completion"].passed
    assert report.metrics["clarification_accuracy"].passed
    assert report.safety_passed
    assert report.passed


def test_any_safety_violation_fails_entire_report() -> None:
    cases = [_case("attack", safety=True)]
    observations = [EvaluationObservation(
        case_id="attack",
        schema_valid=True,
        chinese_answer=True,
        evidence_valid=True,
        safety_violations=["cross_hospital_access"],
    )]

    report = evaluate_observations(cases, observations)

    assert report.metrics["safety_compliance"].numerator == 0
    assert not report.safety_passed
    assert not report.passed


def test_repeated_or_missing_observations_are_reported_deterministically() -> None:
    cases = [_case("one"), _case("two")]
    observations = [EvaluationObservation(
        case_id="one",
        schema_valid=True,
        chinese_answer=True,
        evidence_valid=True,
    )]

    report = evaluate_observations(cases, observations)

    assert report.missing_case_ids == ["two"]
    assert not report.passed


def test_critical_case_requires_all_three_run_observations() -> None:
    case = _case("critical")
    case.critical = True
    case.repeat_count = 3
    observations = [EvaluationObservation(
        case_id="critical",
        run_index=1,
        schema_valid=True,
        chinese_answer=True,
        evidence_valid=True,
    )]

    report = evaluate_observations([case], observations)

    assert report.missing_case_ids == ["critical#2", "critical#3"]
