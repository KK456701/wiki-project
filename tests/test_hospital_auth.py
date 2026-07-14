from __future__ import annotations

import importlib
import importlib.util
from datetime import datetime, timedelta

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool


class MutableClock:
    def __init__(self) -> None:
        self.value = datetime(2026, 7, 14, 8, 0, 0)

    def __call__(self) -> datetime:
        return self.value

    def advance(self, **kwargs: int) -> None:
        self.value += timedelta(**kwargs)


def _auth_modules():
    assert importlib.util.find_spec("app.hospital_auth.service") is not None, (
        "医院账号认证模块尚未实现"
    )
    service_module = importlib.import_module("app.hospital_auth.service")
    repository_module = importlib.import_module("app.hospital_auth.repository")
    schema_module = importlib.import_module("app.hospital_auth.schema")
    return service_module, repository_module, schema_module


def _make_service(*, must_change_password: bool = True, permissions=None):
    service_module, repository_module, schema_module = _auth_modules()
    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    schema_module.ensure_hospital_auth_schema(engine)
    clock = MutableClock()
    repository = repository_module.HospitalAuthRepository(engine)
    service = service_module.HospitalAuthService(repository, now_provider=clock)
    service.create_or_reset_local_user(
        account_id="user_001",
        hospital_id="hospital_001",
        password="123456",
        permissions=permissions
        or {"indicator_detail_view", "indicator_detail_export"},
        must_change_password=must_change_password,
    )
    return service, repository, engine, clock


def test_demo_user_must_change_password_before_detail_access() -> None:
    service, _, _, _ = _make_service()

    login = service.login("user_001", "123456")

    assert login.must_change_password is True
    with pytest.raises(PermissionError, match="请先修改初始密码"):
        service.authenticate(login.token, "indicator_detail_view")


def test_password_change_rotates_session_and_enables_permissions() -> None:
    service, _, engine, _ = _make_service()
    first = service.login("user_001", "123456")

    principal = service.authenticate(first.token)
    changed = service.change_password(principal, "123456", "hospital2026")

    assert changed.must_change_password is False
    with pytest.raises(PermissionError, match="登录已失效"):
        service.authenticate(first.token)
    active = service.authenticate(changed.token, "indicator_detail_export")
    assert active.account_id == "user_001"
    assert active.hospital_id == "hospital_001"
    with engine.connect() as conn:
        stored = conn.execute(
            text("SELECT token_hash FROM med_hospital_session WHERE session_id=:sid"),
            {"sid": active.session_id},
        ).scalar_one()
    assert stored != changed.token
    assert changed.token not in stored


def test_five_failed_logins_lock_account_for_fifteen_minutes() -> None:
    service, _, _, clock = _make_service()

    for _ in range(5):
        with pytest.raises(PermissionError, match="账号或密码错误"):
            service.login("user_001", "wrong-password")
    with pytest.raises(PermissionError, match="账号已临时锁定"):
        service.login("user_001", "123456")

    clock.advance(minutes=15)
    assert service.login("user_001", "123456").account_id == "user_001"


def test_session_expires_after_eight_hours() -> None:
    service, _, _, clock = _make_service(must_change_password=False)
    login = service.login("user_001", "123456")

    clock.advance(hours=8, seconds=1)

    with pytest.raises(PermissionError, match="登录已过期"):
        service.authenticate(login.token, "indicator_detail_view")


def test_principal_is_scoped_to_hospital_and_permission() -> None:
    service, _, _, _ = _make_service(
        must_change_password=False,
        permissions={"indicator_detail_view"},
    )
    login = service.login("user_001", "123456")

    principal = service.authenticate(login.token, "indicator_detail_view")

    assert principal.can_access_hospital("hospital_001") is True
    assert principal.can_access_hospital("hospital_002") is False
    with pytest.raises(PermissionError, match="没有指标明细导出权限"):
        service.authenticate(login.token, "indicator_detail_export")


def test_auth_audit_never_stores_password_or_token() -> None:
    service, repository, _, _ = _make_service(must_change_password=False)
    with pytest.raises(PermissionError):
        service.login("user_001", "secret-not-stored")
    login = service.login("user_001", "123456")
    service.logout(service.authenticate(login.token))

    audits = repository.list_audits("hospital_001")
    serialized = repr(audits)
    assert "secret-not-stored" not in serialized
    assert login.token not in serialized
    assert {item["action"] for item in audits} >= {
        "AUTH_LOGIN_FAILED",
        "AUTH_LOGIN_SUCCESS",
        "AUTH_LOGOUT",
    }
