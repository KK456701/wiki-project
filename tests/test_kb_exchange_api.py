from __future__ import annotations

from unittest.mock import patch

from fastapi.testclient import TestClient

from app.api import main as api_main
from app.api.main import app


class _FakeHospitalReleaseRepository:
    def import_package(self, payload: bytes, imported_by: str):
        return {
            "import_id": "IMP_001",
            "status": "ready_for_adaptation",
            "signature_status": "verified",
            "compatibility_status": "compatible",
            "items": [],
            "duplicate": False,
        }

    def list_imports(self):
        return [{"import_id": "IMP_001", "status": "ready_for_adaptation"}]

    def read_import(self, import_id: str):
        return {"import_id": import_id, "status": "ready_for_adaptation", "items": []}


def test_hospital_release_import_requires_admin_and_exposes_history() -> None:
    client = TestClient(app)
    unauthorized = client.post(
        "/api/kb/hospital/releases/imports",
        content=b"zip",
        headers={"Content-Type": "application/zip"},
    )
    login = client.post("/api/admin/login", json={"password": "admin123"})
    authorization = f"Bearer {login.json()['token']}"

    with patch.object(
        api_main,
        "_hospital_release_repository",
        return_value=_FakeHospitalReleaseRepository(),
    ):
        imported = client.post(
            "/api/kb/hospital/releases/imports",
            content=b"zip",
            headers={"Authorization": authorization, "Content-Type": "application/zip"},
        )
        listed = client.get(
            "/api/kb/hospital/releases/imports",
            headers={"Authorization": authorization},
        )
        detail = client.get(
            "/api/kb/hospital/releases/imports/IMP_001",
            headers={"Authorization": authorization},
        )

    assert unauthorized.status_code == 401
    assert imported.status_code == 200
    assert imported.json()["signature_status"] == "verified"
    assert listed.json()["items"][0]["import_id"] == "IMP_001"
    assert detail.json()["status"] == "ready_for_adaptation"
