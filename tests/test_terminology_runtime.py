import time
import unittest
from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool

from app.terminology.importer import import_term_corpus, load_term_corpus
from app.terminology.release import TerminologyReleaseService
from app.terminology.repository import TerminologyRepository
from app.terminology.schema import ensure_terminology_schema


ROOT = Path(__file__).resolve().parents[1]


def _repository() -> TerminologyRepository:
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    ensure_terminology_schema(engine)
    corpus = load_term_corpus(
        ROOT / "core-rules-wiki" / "terminology" / "core_indicator_terms.yaml"
    )
    import_term_corpus(engine, corpus, "test")
    repository = TerminologyRepository(engine)
    TerminologyReleaseService(repository).publish("test")
    return repository


class TerminologyRuntimeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.repository = _repository()

    def test_indicator_alias_normalizes_to_canonical_rule_name(self) -> None:
        from app.terminology.normalizer import TerminologyNormalizer

        result = TerminologyNormalizer(self.repository).normalize(
            "急会诊响应及时率怎么算", "hospital_001"
        )

        self.assertEqual(result.matches[0].concept_code, "IND_MQSI2025_005")
        self.assertIn("急会诊及时到位率", result.normalized_text)
        self.assertEqual(result.matches[0].linked_rule_ids, ["MQSI2025_005"])
        self.assertEqual(result.ambiguities, [])

    def test_related_medical_term_is_retrievable_but_not_sql_safe(self) -> None:
        from app.terminology.normalizer import TerminologyNormalizer

        result = TerminologyNormalizer(self.repository).normalize(
            "统计感冒患者", "hospital_001"
        )

        match = result.matches[0]
        self.assertEqual(match.concept_code, "DIAG_URI")
        self.assertEqual(match.relation_type, "related")
        self.assertTrue(match.retrieval_enabled)
        self.assertFalse(match.sql_safe)

    def test_forbidden_term_is_detected_even_when_not_retrieval_enabled(self) -> None:
        from app.terminology.normalizer import TerminologyNormalizer

        result = TerminologyNormalizer(self.repository).normalize(
            "把危急值当成异常值", "hospital_001"
        )

        self.assertTrue(any(item.relation_type == "forbidden" for item in result.matches))
        self.assertFalse(result.sql_eligible)

    def test_sql_binding_requires_approved_hospital_value(self) -> None:
        from app.terminology.normalizer import TerminologyNormalizer
        from app.terminology.sql_binding import resolve_sql_bindings

        normalized = TerminologyNormalizer(self.repository).normalize(
            "统计紧急会诊", "hospital_001"
        )
        missing = resolve_sql_bindings(
            normalized, "hospital_001", "MQSI2025_005", self.repository
        )
        self.assertFalse(missing.ok)
        self.assertEqual(missing.problem_code, "TERM_LOCAL_MAPPING_REQUIRED")

        pending = self.repository.create_hospital_mapping_candidate(
            {
                "hospital_id": "hospital_001",
                "concept_code": "CONSULT_URGENT",
                "code_system": "consult_type",
                "local_code": "urgent",
                "local_name": "急会诊",
                "local_value": "urgent",
                "created_by": "test",
            }
        )
        self.repository.approve_hospital_mapping(pending["id"], "admin")
        ready = resolve_sql_bindings(
            normalized, "hospital_001", "MQSI2025_005", self.repository
        )

        self.assertTrue(ready.ok)
        self.assertEqual(ready.bindings[0].business_field_key, "consult_type")
        self.assertEqual(ready.bindings[0].parameter_name, "consult_type_value")
        self.assertEqual(ready.bindings[0].values, ["urgent"])

    def test_normalization_stays_under_100ms_for_local_corpus(self) -> None:
        from app.terminology.normalizer import TerminologyNormalizer

        normalizer = TerminologyNormalizer(self.repository)
        started = time.perf_counter()
        for _ in range(100):
            normalizer.normalize("急会诊响应及时率怎么算", "hospital_001")
        per_call_ms = (time.perf_counter() - started) * 1000 / 100

        self.assertLess(per_call_ms, 100)

    def test_reuses_published_corpus_cache_across_normalizer_instances(self) -> None:
        from app.terminology.normalizer import TerminologyNormalizer

        calls = {"concepts": 0}
        original = self.repository.list_concepts

        def counted_list_concepts():
            calls["concepts"] += 1
            return original()

        self.repository.list_concepts = counted_list_concepts

        TerminologyNormalizer(self.repository).normalize(
            "急会诊及时到位率", "hospital_001"
        )
        TerminologyNormalizer(self.repository).normalize(
            "急会诊及时到位率", "hospital_001"
        )

        self.assertEqual(calls["concepts"], 1)


if __name__ == "__main__":
    unittest.main()
