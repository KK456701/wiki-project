from __future__ import annotations

import importlib
import importlib.util

from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool


def _client():
    assert importlib.util.find_spec("app.api.hospital_auth") is not None, (
        "医院账号认证 API 尚未实现"
    )
    api_module = importlib.import_module("app.api.hospital_auth")
    dependencies = importlib.import_module("app.hospital_auth.dependencies")
    repository_module = importlib.import_module("app.hospital_auth.repository")
    schema_module = importlib.import_module("app.hospital_auth.schema")
    service_module = importlib.import_module("app.hospital_auth.service")

    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    schema_module.ensure_hospital_auth_schema(engine)
    service = service_module.HospitalAuthService(
        repository_module.HospitalAuthRepository(engine)
    )
    service.create_or_reset_local_user(
        account_id="user_001",
        hospital_id="hospital_001",
        password="123456",
        permissions={"indicator_detail_view", "indicator_detail_export"},
        must_change_password=True,
    )
    app = FastAPI()
    app.include_router(api_module.router)
    app.dependency_overrides[dependencies.get_hospital_auth_service] = lambda: service
    return TestClient(app), service


def test_login_change_password_and_logout_flow() -> None:
    client, service = _client()

    login = client.post(
        "/api/auth/hospital/login",
        json={"account_id": "user_001", "password": "123456"},
    )

    assert login.status_code == 200
    assert login.json()["must_change_password"] is True
    first_token = login.json()["token"]
    changed = client.post(
        "/api/auth/hospital/change-password",
        headers={"Authorization": f"Bearer {first_token}"},
        json={"current_password": "123456", "new_password": "hospital2026"},
    )
    assert changed.status_code == 200
    assert changed.json()["must_change_password"] is False
    second_token = changed.json()["token"]
    assert service.authenticate(second_token, "indicator_detail_view").hospital_id == "hospital_001"

    logout = client.post(
        "/api/auth/hospital/logout",
        headers={"Authorization": f"Bearer {second_token}"},
    )
    assert logout.status_code == 204
    unauthorized = client.post(
        "/api/auth/hospital/logout",
        headers={"Authorization": f"Bearer {second_token}"},
    )
    assert unauthorized.status_code == 401


def test_login_uses_generic_error_for_unknown_account_and_bad_password() -> None:
    client, _ = _client()

    unknown = client.post(
        "/api/auth/hospital/login",
        json={"account_id": "not_exists", "password": "wrong-password"},
    )
    bad_password = client.post(
        "/api/auth/hospital/login",
        json={"account_id": "user_001", "password": "wrong-password"},
    )

    assert unknown.status_code == 401
    assert bad_password.status_code == 401
    assert unknown.json()["detail"] == "账号或密码错误"
    assert bad_password.json()["detail"] == "账号或密码错误"


def test_change_password_rejects_weak_new_password() -> None:
    client, _ = _client()
    login = client.post(
        "/api/auth/hospital/login",
        json={"account_id": "user_001", "password": "123456"},
    ).json()

    response = client.post(
        "/api/auth/hospital/change-password",
        headers={"Authorization": f"Bearer {login['token']}"},
        json={"current_password": "123456", "new_password": "12345678"},
    )

    assert response.status_code == 400
    assert "字母和数字" in response.json()["detail"]
