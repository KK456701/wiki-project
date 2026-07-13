import tempfile
import unittest
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.terminology.importer import import_term_corpus, load_term_corpus
from app.terminology.schema import ensure_terminology_schema


ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "core-rules-wiki" / "terminology" / "core_indicator_terms.yaml"


def _engine():
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    ensure_terminology_schema(engine)
    return engine


class TerminologyRepositoryTest(unittest.TestCase):
    def test_import_is_idempotent_and_persists_rule_links(self) -> None:
        engine = _engine()
        corpus = load_term_corpus(CORPUS)

        first = import_term_corpus(engine, corpus, "seed")
        second = import_term_corpus(engine, corpus, "seed")

        self.assertEqual(first["concept_count"], len(corpus.concepts))
        self.assertGreater(first["alias_count"], 70)
        self.assertEqual(second["changed_count"], 0)
        with engine.connect() as conn:
            link_count = conn.execute(
                text("SELECT COUNT(*) FROM med_term_rule_link")
            ).scalar_one()
        self.assertGreaterEqual(link_count, 35)

    def test_hospital_mapping_requires_approval_and_is_isolated(self) -> None:
        from app.terminology.repository import TerminologyRepository

        repository = TerminologyRepository(_engine())
        pending = repository.create_hospital_mapping_candidate(
            {
                "hospital_id": "hospital_001",
                "concept_code": "CONSULT_URGENT",
                "code_system": "consult_type",
                "local_code": "urgent",
                "local_name": "急会诊",
                "local_value": "urgent",
                "created_by": "user_001",
            }
        )

        self.assertEqual(repository.active_hospital_mappings("hospital_001"), [])
        repository.approve_hospital_mapping(pending["id"], "admin")
        self.assertEqual(len(repository.active_hospital_mappings("hospital_001")), 1)
        self.assertEqual(repository.active_hospital_mappings("hospital_002"), [])

    def test_hospital_alias_is_scoped_and_does_not_enter_company_release(self) -> None:
        from app.terminology.repository import TerminologyRepository

        engine = _engine()
        repository = TerminologyRepository(engine)
        pending = repository.create_alias_candidate(
            {
                "hospital_id": "hospital_001",
                "concept_code": "DIAG_URI",
                "alias_text": "本院上感",
                "relation_type": "colloquial",
                "retrieval_enabled": True,
                "sql_safe": False,
                "source_reference": "hospital-feedback",
                "created_by": "user_001",
            }
        )
        repository.approve_alias(pending["id"], "admin")

        hospital_001 = repository.list_hospital_aliases("hospital_001")
        hospital_002 = repository.list_hospital_aliases("hospital_002")

        self.assertEqual(hospital_001[0]["alias_text"], "本院上感")
        self.assertEqual(hospital_002, [])
        self.assertNotIn(
            "本院上感",
            [item["alias_text"] for item in repository.snapshot()["aliases"]],
        )

    def test_release_restore_switches_active_snapshot(self) -> None:
        from app.terminology.release import TerminologyReleaseService
        from app.terminology.repository import TerminologyRepository

        engine = _engine()
        corpus = load_term_corpus(CORPUS)
        import_term_corpus(engine, corpus, "seed")
        repository = TerminologyRepository(engine)
        service = TerminologyReleaseService(repository)

        first = service.publish("admin")
        repository.create_alias_candidate(
            {
                "concept_code": "IND_MQSI2025_005",
                "alias_text": "急会诊快速响应率",
                "relation_type": "abbreviation",
                "retrieval_enabled": True,
                "sql_safe": False,
                "source_reference": "hospital-feedback",
                "created_by": "admin",
            }
        )
        alias = repository.list_aliases(approval_status="pending")[0]
        repository.approve_alias(alias["id"], "admin")
        second = service.publish("admin")
        restored = service.restore(first["release_id"], "admin")

        self.assertNotEqual(first["release_id"], second["release_id"])
        self.assertEqual(restored["active_release_id"], first["release_id"])
        self.assertEqual(repository.active_release()["release_id"], first["release_id"])

    def test_wiki_sync_exposes_terms_without_hospital_values(self) -> None:
        from app.terminology.wiki_sync import write_terminology_wiki

        corpus = load_term_corpus(CORPUS)
        with tempfile.TemporaryDirectory() as tmp:
            result = write_terminology_wiki(corpus, Path(tmp))
            index_text = (Path(tmp) / "indexes" / "term_index.json").read_text(
                encoding="utf-8"
            )

        self.assertEqual(result["concept_count"], len(corpus.concepts))
        self.assertIn("感冒", index_text)
        self.assertNotIn("local_value", index_text)


if __name__ == "__main__":
    unittest.main()
