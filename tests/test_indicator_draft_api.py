import unittest
from types import SimpleNamespace
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.api.main import app
import app.api.indicator_drafts as draft_api


class IndicatorDraftApiTest(unittest.TestCase):
    @staticmethod
    def _services():
        class FakeRepository:
            def __init__(self) -> None:
                self.calls = []

            def list(self, hospital_id, status=None):
                self.calls.append(("list", hospital_id, status))
                return [{"draft_id": "DRAFT_001", "status": status or "metadata_pending"}]

            def get(self, draft_id):
                self.calls.append(("get", draft_id))
                return {"draft_id": draft_id, "status": "metadata_pending", "current_version": 1}

            def save_version(self, draft_id, expected_version, changes, actor_id):
                self.calls.append(("save", draft_id, expected_version, changes, actor_id))
                return {"draft_id": draft_id, "status": "metadata_pending", "current_version": 2}

        class FakeMetadata:
            def __init__(self) -> None:
                self.calls = []

            def suggest_draft_fields(self, draft_id):
                self.calls.append(("suggest", draft_id))
                return {"draft_id": draft_id, "ready_for_confirmation": True}

            def confirm_draft_fields(self, draft_id, expected_version, mappings, actor_id):
                self.calls.append(("confirm", draft_id, expected_version, mappings, actor_id))
                return {"draft_id": draft_id, "status": "metadata_ready", "current_version": 2}

        class FakeWorkflow:
            def __init__(self) -> None:
                self.calls = []

            def confirm_requirements(self, draft_id, expected_version, actor_id):
                self.calls.append(("requirements", draft_id, expected_version, actor_id))
                return {"draft_id": draft_id, "status": "metadata_pending", "current_version": 2}

            def generate_sql(self, draft_id, expected_version, actor_id):
                self.calls.append(("sql", draft_id, expected_version, actor_id))
                return {"draft_id": draft_id, "status": "sql_ready", "current_version": 3}

            def trial_run(self, draft_id, expected_version, stat_start_time, stat_end_time, actor_id):
                self.calls.append(("trial", draft_id, expected_version, stat_start_time, stat_end_time, actor_id))
                return {"draft_id": draft_id, "status": "trial_passed", "current_version": 4}

            def submit(self, draft_id, expected_version, actor_id):
                self.calls.append(("submit", draft_id, expected_version, actor_id))
                return {"draft_id": draft_id, "status": "pending_approval", "current_version": 5}

        class FakePublisher:
            def __init__(self) -> None:
                self.calls = []

            def approve(self, draft_id, expected_version, approver_id):
                self.calls.append(("approve", draft_id, expected_version, approver_id))
                return {"draft_id": draft_id, "status": "published", "formal_index_code": "HOSP001_001"}

            def reject(self, draft_id, expected_version, approver_id, reason):
                self.calls.append(("reject", draft_id, expected_version, approver_id, reason))
                return {"draft_id": draft_id, "status": "rejected"}

            def list_versions(self, index_code, hospital_id):
                self.calls.append(("versions", index_code, hospital_id))
                return {"index_code": index_code, "hospital_id": hospital_id, "versions": []}

            def restore_version(self, index_code, hospital_id, version, approver_id):
                self.calls.append(("restore", index_code, hospital_id, version, approver_id))
                return {"index_code": index_code, "active_version": 3, "restored_from_version": version}

        return SimpleNamespace(
            indicator_generation=SimpleNamespace(),
            repository=FakeRepository(),
            metadata=FakeMetadata(),
            workflow=FakeWorkflow(),
            publisher=FakePublisher(),
            release_adaptation=SimpleNamespace(),
        )

    def test_indicator_draft_generate_route_is_registered(self) -> None:
        response = TestClient(app).post("/api/indicator-drafts/generate")

        self.assertNotEqual(response.status_code, 404)

    def test_generate_delegates_to_indicator_generation_agent(self) -> None:
        class FakeIndicatorAgent:
            def __init__(self) -> None:
                self.calls = []

            def create_draft(self, query, hospital_id, actor_id):
                self.calls.append((query, hospital_id, actor_id))
                return {
                    "draft_id": "DRAFT_001",
                    "hospital_id": hospital_id,
                    "index_name": "急会诊及时到位率",
                    "status": "metadata_pending",
                    "current_version": 1,
                }

        indicator = FakeIndicatorAgent()
        services = SimpleNamespace(indicator_generation=indicator)
        with patch.object(
            draft_api, "_create_indicator_draft_services", return_value=services, create=True
        ):
            response = TestClient(app).post(
                "/api/indicator-drafts/generate",
                json={
                    "query": "创建急会诊及时到位率指标",
                    "hospital_id": "hospital_001",
                    "actor_id": "user_001",
                },
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["draft_id"], "DRAFT_001")
        self.assertEqual(
            indicator.calls,
            [("创建急会诊及时到位率指标", "hospital_001", "user_001")],
        )

    def test_draft_edit_and_metadata_routes_delegate_with_version(self) -> None:
        services = self._services()
        client = TestClient(app)
        with patch.object(draft_api, "_create_indicator_draft_services", return_value=services):
            listed = client.get(
                "/api/indicator-drafts",
                params={"hospital_id": "hospital_001", "status": "metadata_pending"},
            )
            fetched = client.get("/api/indicator-drafts/DRAFT_001")
            saved = client.put(
                "/api/indicator-drafts/DRAFT_001",
                json={"expected_version": 1, "changes": {"index_name": "新名称"}, "actor_id": "user_001"},
            )
            suggestions = client.get("/api/indicator-drafts/DRAFT_001/metadata-suggestions")
            confirmed = client.post(
                "/api/indicator-drafts/DRAFT_001/metadata-confirm",
                json={
                    "expected_version": 1,
                    "mappings": {"patient_id": {"table_name": "consult_record"}},
                    "actor_id": "user_001",
                },
            )

        self.assertEqual([listed.status_code, fetched.status_code, saved.status_code], [200, 200, 200])
        self.assertEqual([suggestions.status_code, confirmed.status_code], [200, 200])
        self.assertIn(("save", "DRAFT_001", 1, {"index_name": "新名称"}, "user_001"), services.repository.calls)
        self.assertEqual(services.metadata.calls[-1][0:3], ("confirm", "DRAFT_001", 1))

    def test_sql_trial_and_submit_routes_delegate_in_order(self) -> None:
        services = self._services()
        client = TestClient(app)
        with patch.object(draft_api, "_create_indicator_draft_services", return_value=services):
            generated = client.post(
                "/api/indicator-drafts/DRAFT_001/sql-generate",
                json={"expected_version": 2, "actor_id": "user_001"},
            )
            trial = client.post(
                "/api/indicator-drafts/DRAFT_001/trial-run",
                json={
                    "expected_version": 3,
                    "stat_start_time": "2026-07-01 00:00:00",
                    "stat_end_time": "2026-08-01 00:00:00",
                    "actor_id": "user_001",
                },
            )
            submitted = client.post(
                "/api/indicator-drafts/DRAFT_001/submit",
                json={"expected_version": 4, "actor_id": "user_001"},
            )

        self.assertEqual([generated.status_code, trial.status_code, submitted.status_code], [200, 200, 200])
        self.assertEqual([call[0] for call in services.workflow.calls], ["sql", "trial", "submit"])

    def test_requirements_confirm_route_starts_metadata_mapping(self) -> None:
        services = self._services()
        client = TestClient(app)
        with patch.object(draft_api, "_create_indicator_draft_services", return_value=services):
            response = client.post(
                "/api/indicator-drafts/DRAFT_001/requirements-confirm",
                json={"expected_version": 1, "actor_id": "user_001"},
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "metadata_pending")
        self.assertEqual(
            services.workflow.calls,
            [("requirements", "DRAFT_001", 1, "user_001")],
        )

    def test_publish_and_restore_routes_require_admin(self) -> None:
        services = self._services()
        client = TestClient(app)
        unauthorized = client.post(
            "/api/indicator-drafts/DRAFT_001/approve",
            json={"expected_version": 5, "approver_id": "admin"},
        )
        login = client.post("/api/admin/login", json={"password": "admin123"})
        headers = {"Authorization": f"Bearer {login.json()['token']}"}
        with patch.object(draft_api, "_create_indicator_draft_services", return_value=services):
            approved = client.post(
                "/api/indicator-drafts/DRAFT_001/approve",
                json={"expected_version": 5, "approver_id": "admin"},
                headers=headers,
            )
            rejected = client.post(
                "/api/indicator-drafts/DRAFT_002/reject",
                json={
                    "expected_version": 5,
                    "approver_id": "admin",
                    "reason": "口径说明不完整",
                },
                headers=headers,
            )
            versions = client.get(
                "/api/hospital-defined/hospital_001/HOSP001_001/versions",
                headers=headers,
            )
            restored = client.post(
                "/api/hospital-defined/hospital_001/HOSP001_001/versions/1/restore",
                json={"approver_id": "admin"},
                headers=headers,
            )

        self.assertEqual(unauthorized.status_code, 401)
        self.assertEqual(
            [approved.status_code, rejected.status_code, versions.status_code, restored.status_code],
            [200, 200, 200, 200],
        )
        self.assertEqual(
            [call[0] for call in services.publisher.calls],
            ["approve", "reject", "versions", "restore"],
        )

    def test_company_release_rule_requires_admin_and_creates_adaptation(self) -> None:
        class FakeReleaseAdaptation:
            def __init__(self) -> None:
                self.calls = []

            def create(self, import_id, rule_id, hospital_id, actor_id):
                self.calls.append((import_id, rule_id, hospital_id, actor_id))
                return {
                    "draft_id": "DRAFT_FROM_RELEASE",
                    "status": "metadata_pending",
                    "duplicate": False,
                }

        services = self._services()
        services.release_adaptation = FakeReleaseAdaptation()
        client = TestClient(app)
        payload = {
            "import_id": "IMP_001",
            "rule_id": "MQSI2025_005",
            "hospital_id": "hospital_001",
            "actor_id": "admin",
        }

        unauthorized = client.post("/api/indicator-drafts/from-release", json=payload)
        login = client.post("/api/admin/login", json={"password": "admin123"})
        headers = {"Authorization": f"Bearer {login.json()['token']}"}
        with patch.object(
            draft_api, "_create_indicator_draft_services", return_value=services
        ):
            created = client.post(
                "/api/indicator-drafts/from-release", json=payload, headers=headers
            )

        self.assertEqual(unauthorized.status_code, 401)
        self.assertEqual(created.status_code, 200)
        self.assertEqual(created.json()["draft_id"], "DRAFT_FROM_RELEASE")
        self.assertEqual(
            services.release_adaptation.calls,
            [("IMP_001", "MQSI2025_005", "hospital_001", "admin")],
        )


if __name__ == "__main__":
    unittest.main()
