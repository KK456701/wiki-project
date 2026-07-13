import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool

import app.api.main as api_main
from app.api.main import app
from app.terminology.importer import import_term_corpus, load_term_corpus
from app.terminology.normalizer import TerminologyNormalizer
from app.terminology.release import TerminologyReleaseService
from app.terminology.repository import TerminologyRepository
from app.terminology.schema import ensure_terminology_schema


def _context():
    from app.api.terminology import TerminologyContext

    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    ensure_terminology_schema(engine)
    corpus = load_term_corpus(
        "core-rules-wiki/terminology/core_indicator_terms.yaml"
    )
    import_term_corpus(engine, corpus, "test")
    repository = TerminologyRepository(engine)
    TerminologyReleaseService(repository).publish("test")
    return TerminologyContext(
        repository=repository,
        normalizer=TerminologyNormalizer(repository),
        releases=TerminologyReleaseService(repository),
    )


class TerminologyApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.context = _context()
        self.client = TestClient(app)
        api_main._admin_tokens.add("term-admin")
        self.headers = {"Authorization": "Bearer term-admin"}
        self.patcher = patch(
            "app.api.terminology._create_terminology_context",
            return_value=self.context,
        )
        self.patcher.start()

    def tearDown(self) -> None:
        self.patcher.stop()
        api_main._admin_tokens.discard("term-admin")

    def test_read_and_recognition_test_are_public(self) -> None:
        concepts = self.client.get("/api/terminology/concepts?query=急会诊")
        preview = self.client.post(
            "/api/terminology/test",
            json={"hospital_id": "hospital_001", "text": "统计感冒患者"},
        )

        self.assertEqual(concepts.status_code, 200)
        self.assertGreaterEqual(concepts.json()["total"], 1)
        self.assertEqual(preview.status_code, 200)
        self.assertEqual(preview.json()["matches"][0]["concept_code"], "DIAG_URI")
        self.assertFalse(preview.json()["sql_eligible"])

    def test_mutation_requires_admin(self) -> None:
        payload = {
            "concept_code": "DIAG_URI",
            "alias_text": "呼吸道感染",
            "relation_type": "related",
            "retrieval_enabled": True,
            "sql_safe": False,
            "source_reference": "hospital-feedback",
            "created_by": "user_001",
        }

        response = self.client.post("/api/terminology/aliases", json=payload)

        self.assertEqual(response.status_code, 401)

    def test_alias_candidate_can_be_created_and_approved(self) -> None:
        created = self.client.post(
            "/api/terminology/aliases",
            headers=self.headers,
            json={
                "concept_code": "DIAG_URI",
                "alias_text": "呼吸道感染",
                "relation_type": "related",
                "retrieval_enabled": True,
                "sql_safe": False,
                "source_reference": "hospital-feedback",
                "created_by": "user_001",
            },
        )
        approved = self.client.post(
            f"/api/terminology/aliases/{created.json()['id']}/approve",
            headers=self.headers,
            json={"actor_id": "admin"},
        )

        self.assertEqual(created.status_code, 200)
        self.assertEqual(created.json()["approval_status"], "pending")
        self.assertEqual(approved.json()["approval_status"], "approved")

    def test_hospital_mapping_detail_is_scoped(self) -> None:
        created = self.client.post(
            "/api/terminology/hospital-mappings",
            headers=self.headers,
            json={
                "hospital_id": "hospital_001",
                "concept_code": "DIAG_URI",
                "code_system": "hospital_diagnosis",
                "local_code": "J06.9-H1",
                "local_name": "上呼吸道感染",
                "local_value": "J06.9-H1",
                "created_by": "user_001",
            },
        )
        self.client.post(
            f"/api/terminology/hospital-mappings/{created.json()['id']}/approve",
            headers=self.headers,
            json={"actor_id": "admin"},
        )

        hospital_001 = self.client.get(
            "/api/terminology/concepts/DIAG_URI?hospital_id=hospital_001"
        )
        hospital_002 = self.client.get(
            "/api/terminology/concepts/DIAG_URI?hospital_id=hospital_002"
        )

        self.assertEqual(len(hospital_001.json()["hospital_mappings"]), 1)
        self.assertEqual(hospital_002.json()["hospital_mappings"], [])

    def test_hospital_alias_detail_is_scoped(self) -> None:
        created = self.client.post(
            "/api/terminology/aliases",
            headers=self.headers,
            json={
                "hospital_id": "hospital_001",
                "concept_code": "DIAG_URI",
                "alias_text": "本院上感叫法",
                "relation_type": "colloquial",
                "retrieval_enabled": True,
                "sql_safe": False,
                "created_by": "user_001",
            },
        )
        self.client.post(
            f"/api/terminology/aliases/{created.json()['id']}/approve",
            headers=self.headers,
            json={"actor_id": "admin"},
        )

        hospital_001 = self.client.get(
            "/api/terminology/concepts/DIAG_URI?hospital_id=hospital_001"
        ).json()
        hospital_002 = self.client.get(
            "/api/terminology/concepts/DIAG_URI?hospital_id=hospital_002"
        ).json()

        aliases_001 = {item["alias_text"] for item in hospital_001["aliases"]}
        aliases_002 = {item["alias_text"] for item in hospital_002["aliases"]}
        self.assertIn("本院上感叫法", aliases_001)
        self.assertNotIn("本院上感叫法", aliases_002)

    def test_release_publish_and_restore_require_admin(self) -> None:
        published = self.client.post(
            "/api/terminology/releases/publish",
            headers=self.headers,
            json={"actor_id": "admin"},
        )
        release_id = published.json()["release_id"]
        restored = self.client.post(
            f"/api/terminology/releases/{release_id}/restore",
            headers=self.headers,
            json={"actor_id": "admin"},
        )
        releases = self.client.get("/api/terminology/releases")

        self.assertEqual(restored.status_code, 200)
        self.assertEqual(restored.json()["active_release_id"], release_id)
        self.assertTrue(releases.json()["items"])


if __name__ == "__main__":
    unittest.main()
