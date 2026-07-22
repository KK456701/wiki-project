from pathlib import Path

from app.db.engine import create_runtime_engine
from app.rules.repository import WikiRuleRepository, create_rule_repository

PROJECT_ROOT = Path(__file__).resolve().parents[1]


def test_factory_uses_wiki_as_rule_authority():
    repository = create_rule_repository(
        create_runtime_engine(), PROJECT_ROOT / "core-rules-wiki"
    )

    assert isinstance(repository, WikiRuleRepository)
    found = repository.search_for_hospital(
        "入院48小时转科比例", "hospital_001", limit=5
    )
    assert found["resolved_rule_id"] == "MQSI2025_001"


def test_wiki_rule_contains_hospital_parameters_and_sql():
    repository = create_rule_repository(
        create_runtime_engine(), PROJECT_ROOT / "core-rules-wiki"
    )

    rule = repository.get_effective_rule("MQSI2025_005", "hospital_001")
    mapping = repository.get_field_mapping("MQSI2025_005", "hospital_001")

    assert rule["rule_source"] == "wiki"
    assert rule["effective_params"]["arrive_minutes_threshold"] == 20
    assert "INPATIENT_CONSULT_APPLY" in rule["standard_sql"]
    assert mapping["rule_source"] == "wiki"
    assert mapping["parameters"]["urgent_level_code"] == 977578
    assert mapping["items"]
