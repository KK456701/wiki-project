import unittest
from pathlib import Path

from pydantic import ValidationError
from sqlalchemy import create_engine, inspect
from sqlalchemy.pool import StaticPool


ROOT = Path(__file__).resolve().parents[1]


def _engine():
    return create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )


class TerminologyFoundationTest(unittest.TestCase):
    def test_schema_is_idempotent(self) -> None:
        from app.terminology.schema import ensure_terminology_schema

        engine = _engine()
        first = ensure_terminology_schema(engine)
        second = ensure_terminology_schema(engine)
        expected = {
            "med_term_concept",
            "med_term_alias",
            "med_term_rule_link",
            "med_hospital_term_mapping",
            "med_hospital_term_mapping_version",
            "med_term_release",
            "med_term_audit_log",
        }

        self.assertTrue(expected.issubset(set(inspect(engine).get_table_names())))
        self.assertEqual(set(first["created_tables"]), expected)
        self.assertEqual(second, {"created_tables": [], "added_columns": []})

    def test_related_and_forbidden_aliases_cannot_be_sql_safe(self) -> None:
        from app.terminology.contracts import TermAlias

        for relation_type in ("related", "forbidden"):
            with self.assertRaises(ValidationError):
                TermAlias(
                    concept_code="DIAG_URI",
                    alias_text="感冒",
                    relation_type=relation_type,
                    retrieval_enabled=True,
                    sql_safe=True,
                    source_reference="safety-test",
                    approval_status="approved",
                    version=1,
                )

    def test_corpus_covers_35_rules_with_non_official_aliases(self) -> None:
        from app.terminology.importer import load_term_corpus, validate_term_corpus

        corpus = load_term_corpus(
            ROOT / "core-rules-wiki" / "terminology" / "core_indicator_terms.yaml"
        )
        report = validate_term_corpus(corpus)

        self.assertTrue(report.ok, report.errors)
        self.assertEqual(report.rule_count, 35)
        self.assertEqual(report.pending_count, 0)
        for rule_id, coverage in corpus.rule_coverage.items():
            indicator = next(
                item
                for item in corpus.concepts
                if item.concept_code == f"IND_{rule_id}"
            )
            self.assertGreaterEqual(
                len({alias.alias_text for alias in indicator.aliases}),
                2,
                rule_id,
            )
            self.assertEqual(coverage.review_status, "approved")


if __name__ == "__main__":
    unittest.main()
