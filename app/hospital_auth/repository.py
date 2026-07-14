from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any, Iterable

from sqlalchemy import Engine, text


def _mapping(row: Any) -> dict[str, Any] | None:
    if row is None:
        return None
    value = dict(row._mapping)
    for key in ("locked_until", "expires_at", "revoked_at", "created_at", "updated_at", "last_seen_at"):
        raw = value.get(key)
        if isinstance(raw, str):
            value[key] = datetime.fromisoformat(raw)
    return value


class HospitalAuthRepository:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def get_user_by_account(self, account_id: str) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(
                text("SELECT * FROM med_hospital_user WHERE account_id=:account_id"),
                {"account_id": account_id},
            ).first()
        return _mapping(row)

    def get_user(self, user_id: str) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(
                text("SELECT * FROM med_hospital_user WHERE user_id=:user_id"),
                {"user_id": user_id},
            ).first()
        return _mapping(row)

    def upsert_user(
        self,
        *,
        account_id: str,
        hospital_id: str,
        password_hash: str,
        password_salt: str,
        password_iterations: int,
        must_change_password: bool,
        permissions: Iterable[str],
        now: datetime,
    ) -> dict[str, Any]:
        with self.engine.begin() as conn:
            existing = conn.execute(
                text("SELECT user_id FROM med_hospital_user WHERE account_id=:account_id"),
                {"account_id": account_id},
            ).first()
            user_id = str(existing[0]) if existing else f"USER_{uuid.uuid4().hex[:12]}"
            params = {
                "user_id": user_id,
                "account_id": account_id,
                "hospital_id": hospital_id,
                "password_hash": password_hash,
                "password_salt": password_salt,
                "password_iterations": password_iterations,
                "must_change_password": bool(must_change_password),
                "now": now,
            }
            if existing:
                conn.execute(
                    text(
                        """
                        UPDATE med_hospital_user
                        SET hospital_id=:hospital_id, password_hash=:password_hash,
                            password_salt=:password_salt,
                            password_iterations=:password_iterations,
                            must_change_password=:must_change_password,
                            status='active', failed_attempts=0, locked_until=NULL,
                            updated_at=:now
                        WHERE user_id=:user_id
                        """
                    ),
                    params,
                )
                conn.execute(
                    text("UPDATE med_hospital_session SET revoked_at=:now WHERE user_id=:user_id AND revoked_at IS NULL"),
                    {"now": now, "user_id": user_id},
                )
            else:
                conn.execute(
                    text(
                        """
                        INSERT INTO med_hospital_user
                          (user_id, account_id, hospital_id, password_hash,
                           password_salt, password_iterations, must_change_password,
                           status, failed_attempts, created_at, updated_at)
                        VALUES
                          (:user_id, :account_id, :hospital_id, :password_hash,
                           :password_salt, :password_iterations, :must_change_password,
                           'active', 0, :now, :now)
                        """
                    ),
                    params,
                )
            conn.execute(
                text("DELETE FROM med_hospital_user_permission WHERE user_id=:user_id"),
                {"user_id": user_id},
            )
            for permission in sorted(set(permissions)):
                conn.execute(
                    text(
                        """
                        INSERT INTO med_hospital_user_permission
                          (user_id, permission_code, created_at)
                        VALUES (:user_id, :permission, :now)
                        """
                    ),
                    {"user_id": user_id, "permission": permission, "now": now},
                )
        user = self.get_user(user_id)
        if user is None:
            raise RuntimeError("医院账号保存失败")
        return user

    def get_permissions(self, user_id: str) -> frozenset[str]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    "SELECT permission_code FROM med_hospital_user_permission "
                    "WHERE user_id=:user_id ORDER BY permission_code"
                ),
                {"user_id": user_id},
            ).all()
        return frozenset(str(row[0]) for row in rows)

    def record_failed_login(
        self,
        user_id: str,
        *,
        failed_attempts: int,
        locked_until: datetime | None,
        now: datetime,
    ) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    """
                    UPDATE med_hospital_user
                    SET failed_attempts=:failed_attempts, locked_until=:locked_until,
                        updated_at=:now
                    WHERE user_id=:user_id
                    """
                ),
                {
                    "user_id": user_id,
                    "failed_attempts": failed_attempts,
                    "locked_until": locked_until,
                    "now": now,
                },
            )

    def record_successful_login(self, user_id: str, now: datetime) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    "UPDATE med_hospital_user SET failed_attempts=0, locked_until=NULL, "
                    "updated_at=:now WHERE user_id=:user_id"
                ),
                {"user_id": user_id, "now": now},
            )

    def update_password(
        self,
        user_id: str,
        *,
        password_hash: str,
        password_salt: str,
        password_iterations: int,
        now: datetime,
    ) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    """
                    UPDATE med_hospital_user
                    SET password_hash=:password_hash, password_salt=:password_salt,
                        password_iterations=:password_iterations,
                        must_change_password=0, failed_attempts=0, locked_until=NULL,
                        updated_at=:now
                    WHERE user_id=:user_id
                    """
                ),
                {
                    "user_id": user_id,
                    "password_hash": password_hash,
                    "password_salt": password_salt,
                    "password_iterations": password_iterations,
                    "now": now,
                },
            )

    def create_session(
        self,
        *,
        session_id: str,
        user_id: str,
        token_hash: str,
        expires_at: datetime,
        now: datetime,
    ) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    """
                    INSERT INTO med_hospital_session
                      (session_id, user_id, token_hash, expires_at, created_at, last_seen_at)
                    VALUES (:session_id, :user_id, :token_hash, :expires_at, :now, :now)
                    """
                ),
                {
                    "session_id": session_id,
                    "user_id": user_id,
                    "token_hash": token_hash,
                    "expires_at": expires_at,
                    "now": now,
                },
            )

    def get_session_by_token_hash(self, token_hash: str) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(
                text(
                    """
                    SELECT s.session_id, s.user_id, s.expires_at, s.revoked_at,
                           u.account_id, u.hospital_id, u.must_change_password,
                           u.status
                    FROM med_hospital_session s
                    JOIN med_hospital_user u ON u.user_id=s.user_id
                    WHERE s.token_hash=:token_hash
                    """
                ),
                {"token_hash": token_hash},
            ).first()
        return _mapping(row)

    def touch_session(self, session_id: str, now: datetime) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text("UPDATE med_hospital_session SET last_seen_at=:now WHERE session_id=:session_id"),
                {"session_id": session_id, "now": now},
            )

    def revoke_session(self, session_id: str, now: datetime) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    "UPDATE med_hospital_session SET revoked_at=:now "
                    "WHERE session_id=:session_id AND revoked_at IS NULL"
                ),
                {"session_id": session_id, "now": now},
            )

    def revoke_user_sessions(self, user_id: str, now: datetime) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    "UPDATE med_hospital_session SET revoked_at=:now "
                    "WHERE user_id=:user_id AND revoked_at IS NULL"
                ),
                {"user_id": user_id, "now": now},
            )

    def insert_audit(
        self,
        *,
        action: str,
        result: str,
        now: datetime,
        user_id: str | None = None,
        hospital_id: str | None = None,
        rule_id: str | None = None,
        run_id: str | None = None,
        export_id: str | None = None,
        row_count: int | None = None,
        request_id: str | None = None,
        reason: str | None = None,
    ) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    """
                    INSERT INTO med_data_access_audit
                      (audit_id, user_id, hospital_id, rule_id, run_id, export_id,
                       action, result, row_count, request_id, reason, created_at)
                    VALUES
                      (:audit_id, :user_id, :hospital_id, :rule_id, :run_id, :export_id,
                       :action, :result, :row_count, :request_id, :reason, :created_at)
                    """
                ),
                {
                    "audit_id": f"AUD_{uuid.uuid4().hex[:16]}",
                    "user_id": user_id,
                    "hospital_id": hospital_id,
                    "rule_id": rule_id,
                    "run_id": run_id,
                    "export_id": export_id,
                    "action": action,
                    "result": result,
                    "row_count": row_count,
                    "request_id": request_id,
                    "reason": reason,
                    "created_at": now,
                },
            )

    def list_audits(self, hospital_id: str) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    "SELECT * FROM med_data_access_audit "
                    "WHERE hospital_id=:hospital_id ORDER BY id"
                ),
                {"hospital_id": hospital_id},
            ).all()
        return [dict(row._mapping) for row in rows]
