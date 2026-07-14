from __future__ import annotations

import base64
import hashlib
import hmac
import re
import secrets
import uuid
from datetime import datetime, timedelta, timezone
from typing import Callable, Iterable

from .models import HospitalPrincipal, LoginResult
from .repository import HospitalAuthRepository


PBKDF2_ITERATIONS = 310_000
SESSION_TTL = timedelta(hours=8)
LOCK_AFTER_FAILURES = 5
LOCK_DURATION = timedelta(minutes=15)


def _utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


class HospitalAuthError(PermissionError):
    def __init__(self, message: str, *, code: str, status_code: int) -> None:
        super().__init__(message)
        self.code = code
        self.status_code = status_code


def hash_password(
    password: str, salt: bytes, iterations: int = PBKDF2_ITERATIONS
) -> str:
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, iterations
    )
    return base64.b64encode(digest).decode("ascii")


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("ascii")).hexdigest()


class HospitalAuthService:
    def __init__(
        self,
        repository: HospitalAuthRepository,
        *,
        now_provider: Callable[[], datetime] = _utcnow,
        session_ttl: timedelta = SESSION_TTL,
    ) -> None:
        self.repository = repository
        self.now_provider = now_provider
        self.session_ttl = session_ttl

    def create_or_reset_local_user(
        self,
        *,
        account_id: str,
        hospital_id: str,
        password: str,
        permissions: Iterable[str],
        must_change_password: bool = True,
    ) -> dict:
        now = self.now_provider()
        salt = secrets.token_bytes(16)
        return self.repository.upsert_user(
            account_id=account_id,
            hospital_id=hospital_id,
            password_hash=hash_password(password, salt),
            password_salt=base64.b64encode(salt).decode("ascii"),
            password_iterations=PBKDF2_ITERATIONS,
            must_change_password=must_change_password,
            permissions=permissions,
            now=now,
        )

    def _verify_password(self, user: dict, password: str) -> bool:
        try:
            salt = base64.b64decode(str(user["password_salt"]).encode("ascii"))
            candidate = hash_password(
                password, salt, int(user["password_iterations"])
            )
        except (KeyError, TypeError, ValueError):
            return False
        return hmac.compare_digest(candidate, str(user["password_hash"]))

    def _audit(
        self,
        action: str,
        result: str,
        *,
        user: dict | None = None,
        principal: HospitalPrincipal | None = None,
        reason: str | None = None,
    ) -> None:
        self.repository.insert_audit(
            action=action,
            result=result,
            user_id=(principal.user_id if principal else None)
            or (str(user["user_id"]) if user else None),
            hospital_id=(principal.hospital_id if principal else None)
            or (str(user["hospital_id"]) if user else None),
            reason=reason,
            now=self.now_provider(),
        )

    def _issue_session(self, user: dict) -> LoginResult:
        now = self.now_provider()
        expires_at = now + self.session_ttl
        token = secrets.token_urlsafe(32)
        session_id = f"SESSION_{uuid.uuid4().hex[:16]}"
        self.repository.create_session(
            session_id=session_id,
            user_id=str(user["user_id"]),
            token_hash=hash_token(token),
            expires_at=expires_at,
            now=now,
        )
        return LoginResult(
            token=token,
            expires_at=expires_at,
            user_id=str(user["user_id"]),
            account_id=str(user["account_id"]),
            hospital_id=str(user["hospital_id"]),
            permissions=self.repository.get_permissions(str(user["user_id"])),
            must_change_password=bool(user["must_change_password"]),
        )

    def login(self, account_id: str, password: str) -> LoginResult:
        now = self.now_provider()
        user = self.repository.get_user_by_account(account_id.strip())
        if user is None:
            self._audit("AUTH_LOGIN_FAILED", "denied", reason="AUTH_BAD_CREDENTIALS")
            raise HospitalAuthError(
                "账号或密码错误", code="AUTH_BAD_CREDENTIALS", status_code=401
            )
        locked_until = user.get("locked_until")
        if locked_until is not None and locked_until > now:
            self._audit("AUTH_LOGIN_FAILED", "denied", user=user, reason="AUTH_ACCOUNT_LOCKED")
            raise HospitalAuthError(
                "账号已临时锁定，请15分钟后重试",
                code="AUTH_ACCOUNT_LOCKED",
                status_code=423,
            )
        if str(user.get("status")) != "active":
            self._audit("AUTH_LOGIN_FAILED", "denied", user=user, reason="AUTH_ACCOUNT_DISABLED")
            raise HospitalAuthError(
                "账号已停用，请联系管理员", code="AUTH_ACCOUNT_DISABLED", status_code=403
            )
        if not self._verify_password(user, password):
            failures = int(user.get("failed_attempts") or 0) + 1
            lock_until = now + LOCK_DURATION if failures >= LOCK_AFTER_FAILURES else None
            self.repository.record_failed_login(
                str(user["user_id"]),
                failed_attempts=failures,
                locked_until=lock_until,
                now=now,
            )
            self._audit("AUTH_LOGIN_FAILED", "denied", user=user, reason="AUTH_BAD_CREDENTIALS")
            raise HospitalAuthError(
                "账号或密码错误", code="AUTH_BAD_CREDENTIALS", status_code=401
            )
        self.repository.record_successful_login(str(user["user_id"]), now)
        user = self.repository.get_user(str(user["user_id"])) or user
        result = self._issue_session(user)
        self._audit("AUTH_LOGIN_SUCCESS", "success", user=user)
        return result

    def authenticate(
        self, token: str, required_permission: str | None = None
    ) -> HospitalPrincipal:
        now = self.now_provider()
        session = self.repository.get_session_by_token_hash(hash_token(token))
        if session is None or session.get("revoked_at") is not None:
            raise HospitalAuthError(
                "登录已失效，请重新登录", code="AUTH_SESSION_INVALID", status_code=401
            )
        if session["expires_at"] <= now:
            raise HospitalAuthError(
                "登录已过期，请重新登录", code="AUTH_SESSION_EXPIRED", status_code=401
            )
        if str(session.get("status")) != "active":
            raise HospitalAuthError(
                "账号已停用，请联系管理员", code="AUTH_ACCOUNT_DISABLED", status_code=403
            )
        permissions = self.repository.get_permissions(str(session["user_id"]))
        principal = HospitalPrincipal(
            user_id=str(session["user_id"]),
            account_id=str(session["account_id"]),
            hospital_id=str(session["hospital_id"]),
            permissions=permissions,
            must_change_password=bool(session["must_change_password"]),
            session_id=str(session["session_id"]),
        )
        if required_permission and principal.must_change_password:
            self._audit("ACCESS_DENIED", "denied", principal=principal, reason="AUTH_PASSWORD_CHANGE_REQUIRED")
            raise HospitalAuthError(
                "请先修改初始密码再查看指标明细",
                code="AUTH_PASSWORD_CHANGE_REQUIRED",
                status_code=403,
            )
        if required_permission and required_permission not in permissions:
            label = (
                "指标明细导出"
                if required_permission == "indicator_detail_export"
                else "指标明细查看"
            )
            self._audit("ACCESS_DENIED", "denied", principal=principal, reason="AUTH_PERMISSION_DENIED")
            raise HospitalAuthError(
                f"当前账号没有{label}权限，请联系管理员",
                code="AUTH_PERMISSION_DENIED",
                status_code=403,
            )
        self.repository.touch_session(principal.session_id, now)
        return principal

    @staticmethod
    def _validate_new_password(password: str) -> None:
        if len(password) < 8 or not re.search(r"[A-Za-z]", password) or not re.search(r"\d", password):
            raise ValueError("新密码至少8位，并且必须同时包含字母和数字")

    def change_password(
        self,
        principal: HospitalPrincipal,
        current_password: str,
        new_password: str,
    ) -> LoginResult:
        user = self.repository.get_user(principal.user_id)
        if user is None or not self._verify_password(user, current_password):
            raise HospitalAuthError(
                "当前密码不正确", code="AUTH_CURRENT_PASSWORD_INVALID", status_code=400
            )
        self._validate_new_password(new_password)
        now = self.now_provider()
        salt = secrets.token_bytes(16)
        self.repository.update_password(
            principal.user_id,
            password_hash=hash_password(new_password, salt),
            password_salt=base64.b64encode(salt).decode("ascii"),
            password_iterations=PBKDF2_ITERATIONS,
            now=now,
        )
        self.repository.revoke_user_sessions(principal.user_id, now)
        updated = self.repository.get_user(principal.user_id)
        if updated is None:
            raise RuntimeError("密码更新后账号不存在")
        result = self._issue_session(updated)
        self._audit("AUTH_PASSWORD_CHANGED", "success", user=updated)
        return result

    def logout(self, principal: HospitalPrincipal) -> None:
        self.repository.revoke_session(principal.session_id, self.now_provider())
        self._audit("AUTH_LOGOUT", "success", principal=principal)
