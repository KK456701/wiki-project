"""术语当前投影、医院映射和审核仓储。"""

from __future__ import annotations

import json
import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import Engine, text

from app.terminology.contracts import TermAlias


class TerminologyRepository:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def list_concepts(self) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            return [
                dict(row)
                for row in conn.execute(
                    text("SELECT * FROM med_term_concept WHERE status='active' ORDER BY concept_code")
                ).mappings()
            ]

    def list_aliases(
        self, approval_status: str | None = "approved"
    ) -> list[dict[str, Any]]:
        sql = "SELECT * FROM med_term_alias"
        params: dict[str, Any] = {}
        if approval_status is not None:
            sql += " WHERE approval_status=:approval_status"
            params["approval_status"] = approval_status
        sql += " ORDER BY concept_code, alias_text"
        with self.engine.connect() as conn:
            return [dict(row) for row in conn.execute(text(sql), params).mappings()]

    def list_rule_links(self) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            return [
                dict(row)
                for row in conn.execute(
                    text("SELECT * FROM med_term_rule_link ORDER BY index_code, concept_code")
                ).mappings()
            ]

    def create_alias_candidate(self, payload: dict[str, Any]) -> dict[str, Any]:
        now = datetime.now()
        alias = TermAlias.model_validate(
            {**payload, "approval_status": "pending", "version": int(payload.get("version") or 1)}
        )
        with self.engine.begin() as conn:
            result = conn.execute(
                text(
                    """
                    INSERT INTO med_term_alias
                      (concept_code, alias_text, relation_type, retrieval_enabled,
                       sql_safe, ambiguity_group, source_reference, approval_status,
                       version, created_by, approved_by, created_at, approved_at)
                    VALUES
                      (:concept_code, :alias_text, :relation_type, :retrieval_enabled,
                       :sql_safe, :ambiguity_group, :source_reference, 'pending',
                       :version, :created_by, NULL, :created_at, NULL)
                    """
                ),
                {
                    **alias.model_dump(),
                    "retrieval_enabled": int(alias.retrieval_enabled),
                    "sql_safe": int(alias.sql_safe),
                    "created_by": payload.get("created_by") or "unknown",
                    "created_at": now,
                },
            )
            alias_id = result.lastrowid
        return {"id": alias_id, **alias.model_dump(), "approval_status": "pending"}

    def approve_alias(self, alias_id: int, approver_id: str) -> dict[str, Any]:
        with self.engine.begin() as conn:
            row = conn.execute(
                text("SELECT * FROM med_term_alias WHERE id=:id"), {"id": alias_id}
            ).mappings().first()
            if row is None:
                raise LookupError("TERM_ALIAS_NOT_FOUND")
            item = dict(row)
            if item["relation_type"] in {"related", "forbidden"} and int(item["sql_safe"]):
                raise ValueError("TERM_ALIAS_SQL_UNSAFE")
            conflicts = conn.execute(
                text(
                    """SELECT concept_code, ambiguity_group FROM med_term_alias
                       WHERE alias_text=:alias_text AND approval_status='approved'
                         AND concept_code<>:concept_code"""
                ),
                item,
            ).mappings().all()
            if conflicts and not item.get("ambiguity_group"):
                raise ValueError("TERM_ALIAS_CONFLICT")
            now = datetime.now()
            conn.execute(
                text(
                    """UPDATE med_term_alias SET approval_status='approved',
                       approved_by=:approved_by, approved_at=:approved_at WHERE id=:id"""
                ),
                {"id": alias_id, "approved_by": approver_id, "approved_at": now},
            )
            self._audit(conn, "approve", "term_alias", str(alias_id), approver_id, {})
        return {**item, "approval_status": "approved", "approved_by": approver_id}

    def create_hospital_mapping_candidate(self, payload: dict[str, Any]) -> dict[str, Any]:
        now = datetime.now()
        with self.engine.begin() as conn:
            version = int(
                conn.execute(
                    text(
                        """SELECT COALESCE(MAX(version), 0) FROM med_hospital_term_mapping
                           WHERE hospital_id=:hospital_id AND concept_code=:concept_code"""
                    ),
                    payload,
                ).scalar_one()
            ) + 1
            values = {
                **payload,
                "version": version,
                "created_by": payload.get("created_by") or "unknown",
                "created_at": now,
            }
            result = conn.execute(
                text(
                    """
                    INSERT INTO med_hospital_term_mapping
                      (hospital_id, concept_code, code_system, local_code, local_name,
                       local_value, approval_status, effective_from, effective_to,
                       version, created_by, approved_by, created_at, approved_at)
                    VALUES
                      (:hospital_id, :concept_code, :code_system, :local_code, :local_name,
                       :local_value, 'pending', :effective_from, :effective_to,
                       :version, :created_by, NULL, :created_at, NULL)
                    """
                ),
                {
                    **values,
                    "effective_from": payload.get("effective_from"),
                    "effective_to": payload.get("effective_to"),
                },
            )
            mapping_id = result.lastrowid
        return {"id": mapping_id, **values, "approval_status": "pending"}

    def approve_hospital_mapping(self, mapping_id: int, approver_id: str) -> dict[str, Any]:
        now = datetime.now()
        with self.engine.begin() as conn:
            row = conn.execute(
                text("SELECT * FROM med_hospital_term_mapping WHERE id=:id"),
                {"id": mapping_id},
            ).mappings().first()
            if row is None:
                raise LookupError("TERM_MAPPING_NOT_FOUND")
            item = dict(row)
            conn.execute(
                text(
                    """UPDATE med_hospital_term_mapping SET approval_status='approved',
                       approved_by=:approved_by, approved_at=:approved_at WHERE id=:id"""
                ),
                {"id": mapping_id, "approved_by": approver_id, "approved_at": now},
            )
            version_id = f"TMV_{uuid.uuid4().hex[:12]}"
            conn.execute(
                text(
                    """
                    INSERT INTO med_hospital_term_mapping_version
                      (version_id, hospital_id, concept_code, version, snapshot_json,
                       change_type, oper_user, approver_id, created_at, approved_at)
                    VALUES (:version_id, :hospital_id, :concept_code, :version,
                            :snapshot_json, 'approve', :oper_user, :approver_id,
                            :created_at, :approved_at)
                    """
                ),
                {
                    "version_id": version_id,
                    "hospital_id": item["hospital_id"],
                    "concept_code": item["concept_code"],
                    "version": item["version"],
                    "snapshot_json": json.dumps(_json_safe(item), ensure_ascii=False),
                    "oper_user": item.get("created_by"),
                    "approver_id": approver_id,
                    "created_at": item["created_at"],
                    "approved_at": now,
                },
            )
            self._audit(
                conn, "approve", "hospital_term_mapping", str(mapping_id), approver_id,
                {"hospital_id": item["hospital_id"], "version_id": version_id},
                hospital_id=item["hospital_id"],
            )
        return {**item, "approval_status": "approved", "version_id": version_id}

    def active_hospital_mappings(
        self, hospital_id: str, concept_codes: list[str] | None = None
    ) -> list[dict[str, Any]]:
        sql = """
            SELECT * FROM med_hospital_term_mapping
            WHERE hospital_id=:hospital_id AND approval_status='approved'
              AND (effective_from IS NULL OR effective_from<=:now)
              AND (effective_to IS NULL OR effective_to>:now)
        """
        params: dict[str, Any] = {"hospital_id": hospital_id, "now": datetime.now()}
        if concept_codes:
            names = []
            for index, code in enumerate(concept_codes):
                key = f"code_{index}"
                names.append(f":{key}")
                params[key] = code
            sql += f" AND concept_code IN ({','.join(names)})"
        sql += " ORDER BY concept_code, version DESC"
        with self.engine.connect() as conn:
            return [dict(row) for row in conn.execute(text(sql), params).mappings()]

    def snapshot(self) -> dict[str, Any]:
        return {
            "concepts": [_json_safe(item) for item in self.list_concepts()],
            "aliases": [_json_safe(item) for item in self.list_aliases("approved")],
            "rule_links": [_json_safe(item) for item in self.list_rule_links()],
        }

    def replace_projection(self, snapshot: dict[str, Any]) -> None:
        with self.engine.begin() as conn:
            conn.execute(text("DELETE FROM med_term_rule_link"))
            conn.execute(text("DELETE FROM med_term_alias"))
            conn.execute(text("DELETE FROM med_term_concept"))
            for table_name, key in (
                ("med_term_concept", "concepts"),
                ("med_term_alias", "aliases"),
                ("med_term_rule_link", "rule_links"),
            ):
                for item in snapshot.get(key, []):
                    values = dict(item)
                    values.pop("id", None)
                    columns = list(values)
                    conn.execute(
                        text(
                            f"INSERT INTO {table_name} ({','.join(columns)}) "
                            f"VALUES ({','.join(':' + column for column in columns)})"
                        ),
                        values,
                    )

    def active_release(self) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(
                text("SELECT * FROM med_term_release WHERE status='active' ORDER BY version DESC LIMIT 1")
            ).mappings().first()
        return _parse_release(row)

    def get_release(self, release_id: str) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(
                text("SELECT * FROM med_term_release WHERE release_id=:release_id"),
                {"release_id": release_id},
            ).mappings().first()
        return _parse_release(row)

    def list_releases(self) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            return [
                _parse_release(row) or {}
                for row in conn.execute(
                    text("SELECT * FROM med_term_release ORDER BY version DESC")
                ).mappings()
            ]

    @staticmethod
    def _audit(
        conn: Any, action: str, object_type: str, object_id: str,
        actor_id: str, detail: dict[str, Any], hospital_id: str | None = None,
    ) -> None:
        conn.execute(
            text(
                """
                INSERT INTO med_term_audit_log
                  (action, object_type, object_id, hospital_id, version,
                   actor_id, detail_json, created_at)
                VALUES (:action, :object_type, :object_id, :hospital_id, NULL,
                        :actor_id, :detail_json, :created_at)
                """
            ),
            {
                "action": action,
                "object_type": object_type,
                "object_id": object_id,
                "hospital_id": hospital_id,
                "actor_id": actor_id,
                "detail_json": json.dumps(detail, ensure_ascii=False),
                "created_at": datetime.now(),
            },
        )


def _json_safe(value: Any) -> Any:
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    if isinstance(value, dict):
        return {key: _json_safe(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_json_safe(item) for item in value]
    return value


def _parse_release(row: Any) -> dict[str, Any] | None:
    if row is None:
        return None
    item = dict(row)
    snapshot = item.get("snapshot_json")
    if isinstance(snapshot, str):
        item["snapshot_json"] = json.loads(snapshot)
    return item
