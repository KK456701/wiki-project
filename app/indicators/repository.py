from __future__ import annotations

import json
import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import Engine, text

from .contracts import IndicatorDraft, IndicatorDraftSpec


class DraftNotFoundError(LookupError):
    pass


class DraftVersionConflict(RuntimeError):
    pass


_JSON_FIELDS = {
    "metadata_requirements",
    "field_mapping",
    "sql_plan",
    "sql_params",
    "trial_result",
}
_EDITABLE_FIELDS = {
    "base_index_code",
    "proposed_index_code",
    "index_name",
    "index_type",
    "index_desc",
    "stat_cycle",
    "numerator_rule",
    "denominator_rule",
    "filter_rule",
    "exclude_rule",
    "metric_type",
    "metadata_requirements",
    "field_mapping",
    "sql_plan",
    "current_sql",
    "sql_params",
    "sql_id",
    "trial_result",
    "trial_draft_version",
    "formal_index_code",
}
_STATUSES = {
    "drafting",
    "metadata_pending",
    "metadata_ready",
    "sql_ready",
    "trial_passed",
    "pending_approval",
    "published",
    "rejected",
}


class IndicatorDraftRepository:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def create(self, spec: IndicatorDraftSpec, actor_id: str) -> IndicatorDraft:
        now = _now()
        draft_id = f"DRAFT_{uuid.uuid4().hex[:12]}"
        payload = spec.model_dump(exclude_none=True)
        payload.setdefault("base_index_code", None)
        sql_plan = payload.pop("sql_plan", None)
        params = {
            **payload,
            "draft_id": draft_id,
            "field_mapping": _dump({}),
            "sql_plan": _dump(sql_plan or {}),
            "sql_params": _dump({}),
            "trial_result": _dump({}),
            "status": "metadata_pending",
            "current_version": 1,
            "created_by": actor_id,
            "updated_by": actor_id,
            "created_at": now,
            "updated_at": now,
            "metadata_requirements": _dump(payload["metadata_requirements"]),
        }
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    """
                    INSERT INTO med_indicator_draft
                      (draft_id, hospital_id, base_index_code, proposed_index_code,
                       index_name, index_type, index_desc, stat_cycle,
                       numerator_rule, denominator_rule, filter_rule, exclude_rule,
                       metric_type, metadata_requirements, field_mapping, sql_plan,
                       current_sql, sql_params, sql_id, trial_result,
                       trial_draft_version, status, current_version,
                       formal_index_code, generated_by, created_by, updated_by,
                       created_at, updated_at)
                    VALUES
                      (:draft_id, :hospital_id, :base_index_code,
                       :proposed_index_code, :index_name, :index_type, :index_desc,
                       :stat_cycle, :numerator_rule, :denominator_rule,
                       :filter_rule, :exclude_rule, :metric_type,
                       :metadata_requirements, :field_mapping, :sql_plan,
                       NULL, :sql_params, NULL, :trial_result, NULL,
                       :status, :current_version, NULL, :generated_by,
                       :created_by, :updated_by, :created_at, :updated_at)
                    """
                ),
                params,
            )
            row = self._get_row(conn, draft_id)
            self._insert_snapshot(conn, row, "created", actor_id, now)
        return _row_to_draft(row)

    def get(self, draft_id: str) -> IndicatorDraft:
        with self.engine.connect() as conn:
            return _row_to_draft(self._get_row(conn, draft_id))

    def list(self, hospital_id: str, status: str | None = None) -> list[IndicatorDraft]:
        query = "SELECT * FROM med_indicator_draft WHERE hospital_id=:hospital_id"
        params: dict[str, Any] = {"hospital_id": hospital_id}
        if status:
            query += " AND status=:status"
            params["status"] = status
        query += " ORDER BY updated_at DESC, draft_id DESC"
        with self.engine.connect() as conn:
            rows = conn.execute(text(query), params).mappings().all()
        return [_row_to_draft(dict(row)) for row in rows]

    def save_version(
        self,
        draft_id: str,
        expected_version: int,
        changes: dict[str, Any],
        actor_id: str,
    ) -> IndicatorDraft:
        invalidated = {
            **changes,
            "current_sql": None,
            "sql_params": {},
            "sql_id": None,
            "trial_result": {},
            "trial_draft_version": None,
        }
        return self._update(
            draft_id,
            expected_version,
            "metadata_pending",
            invalidated,
            actor_id,
            "edited",
        )

    def transition(
        self,
        draft_id: str,
        expected_version: int,
        status: str,
        changes: dict[str, Any],
        actor_id: str,
        change_type: str,
    ) -> IndicatorDraft:
        if status not in _STATUSES:
            raise ValueError(f"UNKNOWN_DRAFT_STATUS: {status}")
        return self._update(
            draft_id,
            expected_version,
            status,
            changes,
            actor_id,
            change_type,
        )

    def list_versions(self, draft_id: str) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    """
                    SELECT version, status, snapshot_json, change_type,
                           oper_user, created_at
                    FROM med_indicator_draft_version
                    WHERE draft_id=:draft_id ORDER BY version DESC
                    """
                ),
                {"draft_id": draft_id},
            ).mappings().all()
        return [
            {
                "version": int(row["version"]),
                "status": str(row["status"]),
                "snapshot": _load(row["snapshot_json"], {}),
                "change_type": str(row["change_type"]),
                "oper_user": str(row["oper_user"]),
                "created_at": str(row["created_at"]),
            }
            for row in rows
        ]

    def _update(
        self,
        draft_id: str,
        expected_version: int,
        status: str,
        changes: dict[str, Any],
        actor_id: str,
        change_type: str,
    ) -> IndicatorDraft:
        unknown = set(changes) - _EDITABLE_FIELDS
        if unknown:
            raise ValueError(f"UNKNOWN_DRAFT_FIELDS: {sorted(unknown)}")
        now = _now()
        with self.engine.begin() as conn:
            current = self._get_row(conn, draft_id)
            if int(current["current_version"]) != int(expected_version):
                raise DraftVersionConflict(
                    f"DRAFT_VERSION_CONFLICT: expected={expected_version}, "
                    f"actual={current['current_version']}"
                )
            next_version = int(expected_version) + 1
            values: dict[str, Any] = {
                "draft_id": draft_id,
                "expected_version": int(expected_version),
                "status": status,
                "current_version": next_version,
                "updated_by": actor_id,
                "updated_at": now,
            }
            assignments = [
                "status=:status",
                "current_version=:current_version",
                "updated_by=:updated_by",
                "updated_at=:updated_at",
            ]
            for field, value in changes.items():
                values[field] = _dump(value) if field in _JSON_FIELDS else value
                assignments.append(f"{field}=:{field}")
            result = conn.execute(
                text(
                    f"UPDATE med_indicator_draft SET {', '.join(assignments)} "
                    "WHERE draft_id=:draft_id AND current_version=:expected_version"
                ),
                values,
            )
            if result.rowcount != 1:
                raise DraftVersionConflict("DRAFT_VERSION_CONFLICT")
            row = self._get_row(conn, draft_id)
            self._insert_snapshot(conn, row, change_type, actor_id, now)
        return _row_to_draft(row)

    @staticmethod
    def _get_row(conn: Any, draft_id: str) -> dict[str, Any]:
        row = conn.execute(
            text("SELECT * FROM med_indicator_draft WHERE draft_id=:draft_id"),
            {"draft_id": draft_id},
        ).mappings().first()
        if row is None:
            raise DraftNotFoundError(f"DRAFT_NOT_FOUND: {draft_id}")
        return dict(row)

    @staticmethod
    def _insert_snapshot(
        conn: Any,
        row: dict[str, Any],
        change_type: str,
        actor_id: str,
        now: str,
    ) -> None:
        snapshot = _row_to_draft(row).model_dump(exclude_none=True)
        conn.execute(
            text(
                """
                INSERT INTO med_indicator_draft_version
                  (draft_id, version, status, snapshot_json, change_type,
                   oper_user, created_at)
                VALUES
                  (:draft_id, :version, :status, :snapshot_json, :change_type,
                   :oper_user, :created_at)
                """
            ),
            {
                "draft_id": row["draft_id"],
                "version": int(row["current_version"]),
                "status": row["status"],
                "snapshot_json": _dump(snapshot),
                "change_type": change_type,
                "oper_user": actor_id,
                "created_at": now,
            },
        )


def _row_to_draft(row: dict[str, Any]) -> IndicatorDraft:
    payload = dict(row)
    for field, default in (
        ("metadata_requirements", []),
        ("field_mapping", {}),
        ("sql_plan", {}),
        ("sql_params", {}),
        ("trial_result", {}),
    ):
        payload[field] = _load(payload.get(field), default)
    for field in ("created_at", "updated_at"):
        value = payload.get(field)
        payload[field] = value.isoformat(sep=" ", timespec="seconds") if hasattr(value, "isoformat") else str(value)
    return IndicatorDraft.model_validate(payload)


def _dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def _load(value: Any, default: Any) -> Any:
    if value is None or value == "":
        return default
    if isinstance(value, (dict, list)):
        return value
    try:
        return json.loads(str(value))
    except (TypeError, ValueError):
        return default


def _now() -> str:
    return datetime.now().isoformat(sep=" ", timespec="seconds")
