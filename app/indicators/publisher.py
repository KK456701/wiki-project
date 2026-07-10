from __future__ import annotations

import json
import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import Engine, text

from .repository import IndicatorDraftRepository, _row_to_draft


class IndicatorPublishError(RuntimeError):
    pass


class HospitalIndicatorPublisher:
    def __init__(self, engine: Engine, draft_repository: IndicatorDraftRepository):
        self.engine = engine
        self.draft_repository = draft_repository

    def approve(
        self, draft_id: str, expected_version: int, approver_id: str
    ) -> dict[str, Any]:
        now = _now()
        with self.engine.begin() as conn:
            row = self.draft_repository._get_row(conn, draft_id)
            draft = _row_to_draft(row)
            self._require_pending(draft, expected_version)
            if draft.base_index_code:
                formal_code, active_version = self._publish_caliber(
                    conn, draft, approver_id, now
                )
                publication_type = "hospital_caliber"
            else:
                formal_code, active_version = self._publish_defined(
                    conn, draft, approver_id, now
                )
                publication_type = "hospital_defined"
            self._replace_mappings(conn, draft, formal_code, approver_id, now)
            next_draft_version = expected_version + 1
            updated = conn.execute(
                text(
                    """
                    UPDATE med_indicator_draft
                    SET status='published', current_version=:next_version,
                        formal_index_code=:formal_index_code,
                        updated_by=:updated_by, updated_at=:updated_at
                    WHERE draft_id=:draft_id AND current_version=:expected_version
                    """
                ),
                {
                    "next_version": next_draft_version,
                    "formal_index_code": formal_code,
                    "updated_by": approver_id,
                    "updated_at": now,
                    "draft_id": draft_id,
                    "expected_version": expected_version,
                },
            )
            if updated.rowcount != 1:
                raise IndicatorPublishError("DRAFT_VERSION_CONFLICT")
            published_row = self.draft_repository._get_row(conn, draft_id)
            self.draft_repository._insert_snapshot(
                conn, published_row, "published", approver_id, now
            )
        return {
            "draft_id": draft_id,
            "status": "published",
            "publication_type": publication_type,
            "formal_index_code": formal_code,
            "active_version": active_version,
            "approver_id": approver_id,
        }

    def reject(
        self, draft_id: str, expected_version: int, approver_id: str, reason: str
    ) -> dict[str, Any]:
        draft = self.draft_repository.get(draft_id)
        self._require_pending(draft, expected_version)
        rejected = self.draft_repository.transition(
            draft_id,
            expected_version,
            "rejected",
            {},
            approver_id,
            f"rejected:{reason}",
        )
        return rejected.model_dump(exclude_none=True)

    def list_versions(self, index_code: str, hospital_id: str) -> dict[str, Any]:
        with self.engine.connect() as conn:
            current = conn.execute(
                text(
                    """
                    SELECT version FROM med_index_hospital_defined
                    WHERE hospital_id=:hospital_id AND index_code=:index_code
                    """
                ),
                {"hospital_id": hospital_id, "index_code": index_code},
            ).first()
            rows = conn.execute(
                text(
                    """
                    SELECT version, snapshot_json, source_version, change_type,
                           oper_user, approver_id, approved_at
                    FROM med_index_hospital_defined_version
                    WHERE hospital_id=:hospital_id AND index_code=:index_code
                    ORDER BY version DESC
                    """
                ),
                {"hospital_id": hospital_id, "index_code": index_code},
            ).mappings().all()
        if current is None:
            raise IndicatorPublishError("HOSPITAL_DEFINED_RULE_NOT_FOUND")
        active = int(current[0])
        return {
            "hospital_id": hospital_id,
            "index_code": index_code,
            "active_version": active,
            "versions": [
                {
                    "version": int(row["version"]),
                    "snapshot": _load(row["snapshot_json"]),
                    "source_version": row.get("source_version"),
                    "change_type": row["change_type"],
                    "oper_user": row["oper_user"],
                    "approver_id": row.get("approver_id"),
                    "approved_at": row.get("approved_at"),
                    "active": int(row["version"]) == active,
                }
                for row in rows
            ],
        }

    def restore_version(
        self,
        index_code: str,
        hospital_id: str,
        version: int,
        approver_id: str,
    ) -> dict[str, Any]:
        now = _now()
        with self.engine.begin() as conn:
            row = conn.execute(
                text(
                    """
                    SELECT snapshot_json FROM med_index_hospital_defined_version
                    WHERE hospital_id=:hospital_id AND index_code=:index_code
                      AND version=:version
                    """
                ),
                {
                    "hospital_id": hospital_id,
                    "index_code": index_code,
                    "version": int(version),
                },
            ).first()
            if row is None:
                raise IndicatorPublishError("HOSPITAL_DEFINED_VERSION_NOT_FOUND")
            snapshot = _load(row[0])
            new_version = int(
                conn.execute(
                    text(
                        """
                        SELECT MAX(version) FROM med_index_hospital_defined_version
                        WHERE hospital_id=:hospital_id AND index_code=:index_code
                        """
                    ),
                    {"hospital_id": hospital_id, "index_code": index_code},
                ).scalar_one()
                or 0
            ) + 1
            snapshot["version"] = new_version
            self._write_defined_current(conn, snapshot, approver_id, now)
            self._insert_defined_version(
                conn,
                snapshot,
                source_version=int(version),
                source_draft_id=None,
                change_type="restore",
                oper_user=approver_id,
                approver_id=approver_id,
                now=now,
            )
            self._replace_mapping_payload(
                conn,
                hospital_id,
                index_code,
                snapshot.get("field_mapping") or {},
                approver_id,
                now,
            )
        return {
            "hospital_id": hospital_id,
            "index_code": index_code,
            "active_version": new_version,
            "restored_from_version": int(version),
            "approver_id": approver_id,
        }

    @staticmethod
    def _require_pending(draft: Any, expected_version: int) -> None:
        if draft.current_version != expected_version:
            raise IndicatorPublishError("DRAFT_VERSION_CONFLICT")
        if draft.status != "pending_approval":
            raise IndicatorPublishError("DRAFT_NOT_PENDING_APPROVAL")
        if (
            draft.trial_result.get("status") != "success"
            or draft.trial_draft_version != draft.current_version
        ):
            raise IndicatorPublishError("DRAFT_TRIAL_EVIDENCE_STALE")

    def _publish_defined(
        self, conn: Any, draft: Any, approver_id: str, now: str
    ) -> tuple[str, int]:
        index_code = draft.proposed_index_code
        exists = conn.execute(
            text(
                """
                SELECT version FROM med_index_hospital_defined
                WHERE hospital_id=:hospital_id AND index_code=:index_code
                """
            ),
            {"hospital_id": draft.hospital_id, "index_code": index_code},
        ).first()
        version = int(exists[0]) + 1 if exists is not None else 1
        snapshot = self._defined_snapshot(draft, index_code, version)
        self._write_defined_current(conn, snapshot, approver_id, now)
        self._insert_defined_version(
            conn,
            snapshot,
            source_version=None,
            source_draft_id=draft.draft_id,
            change_type="draft_publish",
            oper_user=draft.updated_by,
            approver_id=approver_id,
            now=now,
        )
        return index_code, version

    @staticmethod
    def _defined_snapshot(draft: Any, index_code: str, version: int) -> dict[str, Any]:
        return {
            "hospital_id": draft.hospital_id,
            "index_code": index_code,
            "index_name": draft.index_name,
            "index_type": draft.index_type,
            "index_desc": draft.index_desc,
            "stat_cycle": draft.stat_cycle,
            "numerator_rule": draft.numerator_rule,
            "denominator_rule": draft.denominator_rule,
            "filter_rule": draft.filter_rule,
            "exclude_rule": draft.exclude_rule,
            "field_contract": draft.metadata_requirements,
            "field_mapping": draft.field_mapping,
            "sql_template": draft.current_sql,
            "rule_params": draft.sql_params,
            "version": version,
            "status": 1,
            "approval_status": "approved",
            "effective_from": None,
            "effective_to": None,
            "source_draft_id": draft.draft_id,
        }

    @staticmethod
    def _write_defined_current(
        conn: Any, snapshot: dict[str, Any], approver_id: str, now: str
    ) -> None:
        params = {
            **snapshot,
            "field_contract": _dump(snapshot.get("field_contract") or []),
            "sql_template": str(snapshot.get("sql_template") or ""),
            "rule_params": _dump(snapshot.get("rule_params") or {}),
            "oper_user": approver_id,
            "now": now,
        }
        exists = conn.execute(
            text(
                """
                SELECT 1 FROM med_index_hospital_defined
                WHERE hospital_id=:hospital_id AND index_code=:index_code
                """
            ),
            params,
        ).first()
        if exists is None:
            conn.execute(
                text(
                    """
                    INSERT INTO med_index_hospital_defined
                      (hospital_id, index_code, index_name, index_type, index_desc,
                       stat_cycle, numerator_rule, denominator_rule, filter_rule,
                       exclude_rule, field_contract, sql_template, rule_params,
                       version, status, approval_status, effective_from,
                       effective_to, source_draft_id, oper_user, create_time,
                       update_time)
                    VALUES
                      (:hospital_id, :index_code, :index_name, :index_type,
                       :index_desc, :stat_cycle, :numerator_rule,
                       :denominator_rule, :filter_rule, :exclude_rule,
                       :field_contract, :sql_template, :rule_params, :version,
                       :status, :approval_status, :effective_from, :effective_to,
                       :source_draft_id, :oper_user, :now, :now)
                    """
                ),
                params,
            )
        else:
            conn.execute(
                text(
                    """
                    UPDATE med_index_hospital_defined
                    SET index_name=:index_name, index_type=:index_type,
                        index_desc=:index_desc, stat_cycle=:stat_cycle,
                        numerator_rule=:numerator_rule,
                        denominator_rule=:denominator_rule,
                        filter_rule=:filter_rule, exclude_rule=:exclude_rule,
                        field_contract=:field_contract, sql_template=:sql_template,
                        rule_params=:rule_params, version=:version, status=:status,
                        approval_status=:approval_status,
                        effective_from=:effective_from, effective_to=:effective_to,
                        source_draft_id=:source_draft_id, oper_user=:oper_user,
                        update_time=:now
                    WHERE hospital_id=:hospital_id AND index_code=:index_code
                    """
                ),
                params,
            )

    @staticmethod
    def _insert_defined_version(
        conn: Any,
        snapshot: dict[str, Any],
        *,
        source_version: int | None,
        source_draft_id: str | None,
        change_type: str,
        oper_user: str,
        approver_id: str,
        now: str,
    ) -> None:
        conn.execute(
            text(
                """
                INSERT INTO med_index_hospital_defined_version
                  (hospital_id, index_code, version, snapshot_json,
                   source_version, source_draft_id, change_type, oper_user,
                   approver_id, created_at, approved_at)
                VALUES
                  (:hospital_id, :index_code, :version, :snapshot_json,
                   :source_version, :source_draft_id, :change_type, :oper_user,
                   :approver_id, :created_at, :approved_at)
                """
            ),
            {
                "hospital_id": snapshot["hospital_id"],
                "index_code": snapshot["index_code"],
                "version": snapshot["version"],
                "snapshot_json": _dump(snapshot),
                "source_version": source_version,
                "source_draft_id": source_draft_id,
                "change_type": change_type,
                "oper_user": oper_user,
                "approver_id": approver_id,
                "created_at": now,
                "approved_at": now,
            },
        )

    def _publish_caliber(
        self, conn: Any, draft: Any, approver_id: str, now: str
    ) -> tuple[str, int]:
        index_code = str(draft.base_index_code)
        standard = conn.execute(
            text(
                "SELECT 1 FROM med_index_standard WHERE index_code=:index_code AND status=1"
            ),
            {"index_code": index_code},
        ).first()
        if standard is None:
            raise IndicatorPublishError("BASE_STANDARD_RULE_NOT_FOUND")
        version = int(
            conn.execute(
                text(
                    """
                    SELECT MAX(version) FROM med_index_hospital_custom_version
                    WHERE hospital_id=:hospital_id AND index_code=:index_code
                    """
                ),
                {"hospital_id": draft.hospital_id, "index_code": index_code},
            ).scalar_one()
            or 0
        ) + 1
        snapshot = {
            "custom_numerator": draft.numerator_rule,
            "custom_denominator": draft.denominator_rule,
            "custom_filter": draft.filter_rule,
            "exclude_rule": draft.exclude_rule,
            "custom_params": draft.sql_params,
            "custom_sql": draft.current_sql,
            "status": 1,
            "effective_from": None,
            "effective_to": None,
            "field_mapping": draft.field_mapping,
        }
        change_id = f"DRAFTPUB_{uuid.uuid4().hex[:12]}"
        conn.execute(
            text(
                """
                INSERT INTO med_index_hospital_custom_version
                  (change_id, hospital_id, index_code, version, approval_status,
                   snapshot_json, source_version, change_type, oper_user,
                   approver_id, created_at, approved_at)
                VALUES
                  (:change_id, :hospital_id, :index_code, :version, 'approved',
                   :snapshot_json, NULL, 'draft_publish', :oper_user,
                   :approver_id, :created_at, :approved_at)
                """
            ),
            {
                "change_id": change_id,
                "hospital_id": draft.hospital_id,
                "index_code": index_code,
                "version": version,
                "snapshot_json": _dump(snapshot),
                "oper_user": draft.updated_by,
                "approver_id": approver_id,
                "created_at": now,
                "approved_at": now,
            },
        )
        params = {
            "hospital_id": draft.hospital_id,
            "index_code": index_code,
            "custom_numerator": draft.numerator_rule,
            "custom_denominator": draft.denominator_rule,
            "custom_filter": draft.filter_rule,
            "exclude_rule": draft.exclude_rule,
            "custom_params": _dump(draft.sql_params),
            "custom_sql": draft.current_sql,
            "version": version,
            "oper_user": approver_id,
            "now": now,
        }
        exists = conn.execute(
            text(
                """
                SELECT 1 FROM med_index_hospital_custom
                WHERE hospital_id=:hospital_id AND index_code=:index_code
                """
            ),
            params,
        ).first()
        if exists is None:
            conn.execute(
                text(
                    """
                    INSERT INTO med_index_hospital_custom
                      (hospital_id, index_code, custom_numerator,
                       custom_denominator, custom_filter, exclude_rule,
                       custom_params, custom_sql, version, status,
                       approval_status, effective_from, effective_to, oper_user,
                       create_time, update_time)
                    VALUES
                      (:hospital_id, :index_code, :custom_numerator,
                       :custom_denominator, :custom_filter, :exclude_rule,
                       :custom_params, :custom_sql, :version, 1, 'approved',
                       NULL, NULL, :oper_user, :now, :now)
                    """
                ),
                params,
            )
        else:
            conn.execute(
                text(
                    """
                    UPDATE med_index_hospital_custom
                    SET custom_numerator=:custom_numerator,
                        custom_denominator=:custom_denominator,
                        custom_filter=:custom_filter, exclude_rule=:exclude_rule,
                        custom_params=:custom_params, custom_sql=:custom_sql,
                        version=:version, status=1, approval_status='approved',
                        oper_user=:oper_user, update_time=:now
                    WHERE hospital_id=:hospital_id AND index_code=:index_code
                    """
                ),
                params,
            )
        return index_code, version

    def _replace_mappings(
        self, conn: Any, draft: Any, formal_code: str, actor_id: str, now: str
    ) -> None:
        self._replace_mapping_payload(
            conn,
            draft.hospital_id,
            formal_code,
            draft.field_mapping,
            actor_id,
            now,
        )

    @staticmethod
    def _replace_mapping_payload(
        conn: Any,
        hospital_id: str,
        rule_id: str,
        mappings: dict[str, dict[str, Any]],
        actor_id: str,
        now: str,
    ) -> None:
        conn.execute(
            text(
                "DELETE FROM med_field_mapping WHERE hospital_id=:hospital_id AND rule_id=:rule_id"
            ),
            {"hospital_id": hospital_id, "rule_id": rule_id},
        )
        for business_field, item in mappings.items():
            conn.execute(
                text(
                    """
                    INSERT INTO med_field_mapping
                      (hospital_id, rule_id, business_field, db_name, table_name,
                       column_name, data_type, status, updated_by, updated_at)
                    VALUES
                      (:hospital_id, :rule_id, :business_field, :db_name,
                       :table_name, :column_name, :data_type, 'confirmed',
                       :updated_by, :updated_at)
                    """
                ),
                {
                    "hospital_id": hospital_id,
                    "rule_id": rule_id,
                    "business_field": business_field,
                    "db_name": item.get("db_name") or "",
                    "table_name": item.get("table_name") or "",
                    "column_name": item.get("column_name") or "",
                    "data_type": item.get("data_type") or "",
                    "updated_by": actor_id,
                    "updated_at": now,
                },
            )


def _dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def _load(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    return json.loads(str(value or "{}"))


def _now() -> str:
    return datetime.now().isoformat(sep=" ", timespec="seconds")
