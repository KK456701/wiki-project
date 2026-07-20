from pathlib import Path

import yaml


def test_eval_dataset_has_required_categories():
    payload = yaml.safe_load(
        (Path(__file__).parent / "cases.yaml").read_text(encoding="utf-8")
    )
    cases = payload["cases"]
    categories = {case["category"] for case in cases}
    assert {
        "indicator_name",
        "indicator_typo",
        "time_expression",
        "cross_turn_reference",
        "compound_request",
        "sql_vs_trial",
        "upload",
        "diagnosis_boundary",
        "prompt_injection",
    } <= categories
    assert len({case["id"] for case in cases}) == len(cases)
