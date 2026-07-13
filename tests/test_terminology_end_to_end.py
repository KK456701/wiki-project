import unittest

from app.terminology.normalizer import TerminologyNormalizer
from app.terminology.sql_binding import resolve_sql_bindings
from tests.test_terminology_runtime import _repository


class TerminologyEndToEndTest(unittest.TestCase):
    def setUp(self) -> None:
        self.repository = _repository()
        self.normalizer = TerminologyNormalizer(self.repository)

    def test_indicator_alias_uniquely_recalls_urgent_consult_indicator(self) -> None:
        result = self.normalizer.normalize("急会诊响应率怎么算？", "hospital_001")

        self.assertEqual(result.matches[0].concept_code, "IND_MQSI2025_005")
        self.assertEqual(result.matches[0].linked_rule_ids, ["MQSI2025_005"])
        self.assertEqual(result.ambiguities, [])

    def test_related_diagnosis_is_retrievable_but_blocks_sql(self) -> None:
        result = self.normalizer.normalize("统计上感患者", "hospital_001")
        binding = resolve_sql_bindings(
            result, "hospital_001", "MQSI2025_005", self.repository
        )

        self.assertEqual(result.matches[0].concept_code, "DIAG_URI")
        self.assertFalse(result.sql_eligible)
        self.assertFalse(binding.ok)
        self.assertEqual(binding.problem_code, "TERM_SQL_UNSAFE")

    def test_ambiguous_rounding_term_requires_confirmation(self) -> None:
        result = self.normalizer.normalize("查房率", "hospital_001")

        self.assertEqual(result.matches, [])
        self.assertEqual(len(result.ambiguities), 1)
        self.assertGreaterEqual(len(result.ambiguities[0]["concept_codes"]), 2)
        self.assertFalse(result.sql_eligible)

    def test_rescue_success_is_never_rewritten_as_cured(self) -> None:
        success = self.normalizer.normalize("抢救成功患者", "hospital_001")
        cured = self.normalizer.normalize("治愈患者", "hospital_001")

        self.assertEqual(success.matches[0].canonical_name, "抢救成功")
        self.assertNotIn("治愈", success.normalized_text)
        self.assertEqual(cured.matches[0].relation_type, "forbidden")
        self.assertFalse(cured.sql_eligible)


if __name__ == "__main__":
    unittest.main()
