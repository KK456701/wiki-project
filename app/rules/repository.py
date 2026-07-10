from __future__ import annotations

import json
import re
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any, Protocol

from sqlalchemy import Engine, text


class RuleNotFoundError(LookupError):
    """Raised when a structured indicator rule is not available."""


class RuleRepository(Protocol):
    def search(self, query: str, limit: int = 5) -> dict[str, Any]: ...

    def get_effective_rule(
        self, index_code_or_name: str, hospital_id: str | None
    ) -> dict[str, Any]: ...

    def get_field_mapping(self, index_code: str, hospital_id: str) -> dict[str, Any]: ...

    def submit_change_request(self, payload: dict[str, Any]) -> dict[str, Any]: ...

    def list_pending_changes(self) -> list[dict[str, Any]]: ...

    def reject_change_request(self, change_id: str, approver_id: str) -> dict[str, Any]: ...

    def approve_change_request(self, change_id: str, approver_id: str) -> dict[str, Any]: ...

    def list_versions(self, index_code: str, hospital_id: str) -> dict[str, Any]: ...

    def restore_version(
        self, index_code: str, hospital_id: str, version: int, approver_id: str
    ) -> dict[str, Any]: ...


def _json_dict(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return dict(value)
    if not value:
        return {}
    try:
        parsed = json.loads(str(value))
    except (TypeError, json.JSONDecodeError):
        return {}
    return dict(parsed) if isinstance(parsed, dict) else {}


def _datetime(value: Any) -> datetime | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value
    try:
        return datetime.fromisoformat(str(value))
    except ValueError:
        return None


def _active_now(row: dict[str, Any], now: datetime) -> bool:
    if int(row.get("status") or 0) != 1:
        return False
    if str(row.get("approval_status") or "") != "approved":
        return False
    effective_from = _datetime(row.get("effective_from"))
    effective_to = _datetime(row.get("effective_to"))
    if effective_from is not None and now < effective_from:
        return False
    if effective_to is not None and now >= effective_to:
        return False
    return True


def _formula(name: str, numerator: str, denominator: str) -> str:
    return f"{name} = ({numerator} / {denominator}) × 100%"


class MySQLRuleRepository:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def search(self, query: str, limit: int = 5) -> dict[str, Any]:
        pattern = f"%{str(query or '').strip()}%"
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    """
                    SELECT index_code, index_name, index_type, index_desc
                    FROM med_index_standard
                    WHERE status=1
                      AND (index_code=:query OR index_name=:query
                           OR index_name LIKE :pattern OR index_desc LIKE :pattern)
                    ORDER BY CASE WHEN index_code=:query OR index_name=:query THEN 0 ELSE 1 END,
                             index_code
                    LIMIT :limit
                    """
                ),
                {"query": query, "pattern": pattern, "limit": int(limit)},
            ).mappings().all()
        matches = [
            {
                "rule_id": row["index_code"],
                "rule_name": row["index_name"],
                "category": row["index_type"],
                "content": row["index_desc"],
                "type": "mysql_standard",
            }
            for row in rows
        ]
        return {
            "query": query,
            "resolved_rule_id": matches[0]["rule_id"] if matches else None,
            "matches": matches,
        }

    def _find_standard(self, index_code_or_name: str) -> dict[str, Any] | None:
        query = str(index_code_or_name or "").strip()
        with self.engine.connect() as conn:
            row = conn.execute(
                text(
                    """
                    SELECT * FROM med_index_standard
                    WHERE status=1 AND (index_code=:query OR index_name=:query)
                    ORDER BY CASE WHEN index_code=:query THEN 0 ELSE 1 END
                    LIMIT 1
                    """
                ),
                {"query": query},
            ).mappings().first()
            if row is None:
                row = conn.execute(
                    text(
                        """
                        SELECT * FROM med_index_standard
                        WHERE status=1 AND index_name LIKE :pattern
                        ORDER BY index_code
                        LIMIT 1
                        """
                    ),
                    {"pattern": f"%{query}%"},
                ).mappings().first()
        return dict(row) if row is not None else None

    def _find_custom(self, hospital_id: str, index_code: str) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(
                text(
                    """
                    SELECT * FROM med_index_hospital_custom
                    WHERE hospital_id=:hospital_id AND index_code=:index_code
                    LIMIT 1
                    """
                ),
                {"hospital_id": hospital_id, "index_code": index_code},
            ).mappings().first()
        item = dict(row) if row is not None else None
        return item if item is not None and _active_now(item, datetime.now()) else None

    def get_effective_rule(
        self, index_code_or_name: str, hospital_id: str | None = None
    ) -> dict[str, Any]:
        standard = self._find_standard(index_code_or_name)
        if standard is None:
            raise RuleNotFoundError(f"RULE_NOT_MIGRATED: {index_code_or_name}")

        index_code = str(standard["index_code"])
        custom = self._find_custom(hospital_id, index_code) if hospital_id else None
        national_params = _json_dict(standard.get("rule_params"))
        effective_params = dict(national_params)
        overridden_fields: list[str] = []
        if custom:
            custom_params = _json_dict(custom.get("custom_params"))
            for key, value in custom_params.items():
                if national_params.get(key) != value:
                    overridden_fields.append(key)
                effective_params[key] = value

        numerator = str(standard.get("numerator_rule") or "")
        denominator = str(standard.get("denominator_rule") or "")
        filter_rule = str(standard.get("filter_rule") or "")
        exclude_rule = str(standard.get("exclude_rule") or "")
        sql_template = str(standard.get("standard_sql") or "")
        for field, custom_key in (
            ("numerator_rule", "custom_numerator"),
            ("denominator_rule", "custom_denominator"),
            ("filter_rule", "custom_filter"),
            ("exclude_rule", "exclude_rule"),
            ("standard_sql", "custom_sql"),
        ):
            custom_value = str((custom or {}).get(custom_key) or "").strip()
            if not custom_value:
                continue
            overridden_fields.append(field)
            if field == "numerator_rule":
                numerator = custom_value
            elif field == "denominator_rule":
                denominator = custom_value
            elif field == "filter_rule":
                filter_rule = custom_value
            elif field == "exclude_rule":
                exclude_rule = custom_value
            else:
                sql_template = custom_value

        name = str(standard["index_name"])
        national_rule = {
            "definition": str(standard.get("index_desc") or ""),
            "formula": _formula(
                name,
                str(standard.get("numerator_rule") or ""),
                str(standard.get("denominator_rule") or ""),
            ),
            "version": str(standard.get("version") or ""),
            "source_path": str(standard.get("source_path") or ""),
        }
        return {
            "rule_id": index_code,
            "index_code": index_code,
            "rule_name": name,
            "category": str(standard.get("index_type") or ""),
            "hospital_id": hospital_id,
            "effective_level": "hospital" if custom else "national",
            "definition": str(standard.get("index_desc") or ""),
            "formula": _formula(name, numerator, denominator),
            "numerator_rule": numerator,
            "denominator_rule": denominator,
            "filter_rule": filter_rule,
            "exclude_rule": exclude_rule,
            "implementation_status": sql_template,
            "standard_sql": sql_template,
            "field_contract": _json_dict(standard.get("rely_table_field")),
            "field_status": "configured",
            "sql_status": "available" if sql_template else "unavailable",
            "hospital_override": custom,
            "national_rule": national_rule,
            "national_params": national_params,
            "effective_params": effective_params,
            "national_version": str(standard.get("version") or ""),
            "hospital_version": int(custom.get("version") or 0) if custom else None,
            "overridden_fields": list(dict.fromkeys(overridden_fields)),
            "fallback_chain": ["hospital", "national"],
            "rule_source": "mysql",
            "warnings": [],
            "relations": {},
        }

    def get_field_mapping(self, index_code: str, hospital_id: str) -> dict[str, Any]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    """
                    SELECT business_field, db_name, table_name, column_name, data_type, status
                    FROM med_field_mapping
                    WHERE hospital_id=:hospital_id AND rule_id=:index_code
                    ORDER BY id
                    """
                ),
                {"hospital_id": hospital_id, "index_code": index_code},
            ).mappings().all()
        fields = {
            str(row["business_field"]): f"{row['table_name']}.{row['column_name']}"
            for row in rows
        }
        first = rows[0] if rows else {}
        return {
            "rule_id": index_code,
            "hospital_id": hospital_id,
            "dialect": "mysql",
            "db_name": str(first.get("db_name") or ""),
            "main_table": str(first.get("table_name") or ""),
            "fields": fields,
            "status": "confirmed" if rows else "missing",
            "items": [dict(row) for row in rows],
        }

    def submit_change_request(self, payload: dict[str, Any]) -> dict[str, Any]:
        index_code = str(payload.get("rule_id") or payload.get("index_code") or "").strip()
        hospital_id = str(payload.get("hospital_id") or "").strip()
        if not index_code or not hospital_id:
            raise ValueError("rule_id 和 hospital_id 不能为空")
        if str(payload.get("target_level") or "hospital") != "hospital":
            raise ValueError("仅支持医院定制口径变更")

        requested_formula = str(payload.get("requested_formula") or "").strip()
        requested_definition = str(payload.get("requested_definition") or "").strip()
        now = datetime.now().isoformat(sep=" ", timespec="seconds")
        change_id = f"CR_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:8]}"

        with self.engine.begin() as conn:
            standard = conn.execute(
                text("SELECT 1 FROM med_index_standard WHERE index_code=:code AND status=1"),
                {"code": index_code},
            ).first()
            if standard is None:
                raise RuleNotFoundError(f"RULE_NOT_MIGRATED: {index_code}")
            current = conn.execute(
                text(
                    "SELECT * FROM med_index_hospital_custom "
                    "WHERE hospital_id=:hospital_id AND index_code=:index_code"
                ),
                {"hospital_id": hospital_id, "index_code": index_code},
            ).mappings().first()
            snapshot = self._snapshot_from_current(dict(current) if current else {})
            snapshot["requested_definition"] = requested_definition
            snapshot["requested_formula"] = requested_formula
            if index_code == "MQSI2025_005":
                minute_match = re.search(r"(\d+)\s*分钟", requested_formula)
                if minute_match is None:
                    raise ValueError("急会诊口径必须明确分钟阈值")
                threshold = int(minute_match.group(1))
                if threshold <= 0:
                    raise ValueError("分钟阈值必须大于0")
                params = _json_dict(snapshot.get("custom_params"))
                params["arrive_minutes_threshold"] = threshold
                snapshot["custom_params"] = params
                snapshot["custom_numerator"] = (
                    f"急会诊请求发出后0至{threshold}分钟内到位的急会诊次数"
                )
            version = self._next_version(conn, hospital_id, index_code)
            conn.execute(
                text(
                    """
                    INSERT INTO med_index_hospital_custom_version
                      (change_id, hospital_id, index_code, version, approval_status,
                       snapshot_json, source_version, change_type, oper_user,
                       approver_id, created_at, approved_at)
                    VALUES
                      (:change_id, :hospital_id, :index_code, :version, 'pending',
                       :snapshot_json, :source_version, :change_type, :oper_user,
                       NULL, :created_at, NULL)
                    """
                ),
                {
                    "change_id": change_id,
                    "hospital_id": hospital_id,
                    "index_code": index_code,
                    "version": version,
                    "snapshot_json": json.dumps(snapshot, ensure_ascii=False),
                    "source_version": int((current or {}).get("version") or 0) or None,
                    "change_type": str(payload.get("change_type") or "本院口径反馈"),
                    "oper_user": str(payload.get("submitter_id") or "unknown"),
                    "created_at": now,
                },
            )
        return {
            "change_id": change_id,
            "rule_id": index_code,
            "hospital_id": hospital_id,
            "version": version,
            "status": "pending",
            "approval_status": "pending",
        }

    def approve_change_request(self, change_id: str, approver_id: str) -> dict[str, Any]:
        now = datetime.now().isoformat(sep=" ", timespec="seconds")
        with self.engine.begin() as conn:
            row = conn.execute(
                text(
                    "SELECT * FROM med_index_hospital_custom_version "
                    "WHERE change_id=:change_id"
                ),
                {"change_id": change_id},
            ).mappings().first()
            if row is None:
                raise RuleNotFoundError(f"CHANGE_NOT_FOUND: {change_id}")
            item = dict(row)
            if item["approval_status"] != "pending":
                raise ValueError("该变更已处理，不能重复审批")
            snapshot = _json_dict(item["snapshot_json"])
            self._write_current(
                conn,
                str(item["hospital_id"]),
                str(item["index_code"]),
                int(item["version"]),
                snapshot,
                approver_id,
                now,
            )
            conn.execute(
                text(
                    """
                    UPDATE med_index_hospital_custom_version
                    SET approval_status='approved', approver_id=:approver_id,
                        approved_at=:approved_at
                    WHERE change_id=:change_id
                    """
                ),
                {"change_id": change_id, "approver_id": approver_id, "approved_at": now},
            )
        return {
            "change_id": change_id,
            "rule_id": item["index_code"],
            "hospital_id": item["hospital_id"],
            "status": "approved",
            "approval_status": "approved",
            "active_version": int(item["version"]),
            "active_version_id": str(item["version"]),
            "override_path": f"mysql://{item['hospital_id']}/{item['index_code']}",
            "approver_id": approver_id,
            "approved_at": now,
        }

    def list_pending_changes(self) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    """
                    SELECT * FROM med_index_hospital_custom_version
                    WHERE approval_status='pending'
                    ORDER BY id
                    """
                )
            ).mappings().all()
        result = []
        for row in rows:
            item = dict(row)
            snapshot = _json_dict(item.get("snapshot_json"))
            result.append(
                {
                    "change_id": item["change_id"],
                    "rule_id": item["index_code"],
                    "indicator_name": item["index_code"],
                    "hospital_id": item["hospital_id"],
                    "target_level": "hospital",
                    "change_type": item["change_type"],
                    "requested_definition": snapshot.get("requested_definition") or "",
                    "requested_formula": snapshot.get("requested_formula") or "",
                    "submitter_id": item.get("oper_user") or "",
                    "status": "pending",
                    "approval_status": "pending",
                    "created_at": item.get("created_at"),
                    "version": int(item["version"]),
                }
            )
        return result

    def reject_change_request(self, change_id: str, approver_id: str) -> dict[str, Any]:
        now = datetime.now().isoformat(sep=" ", timespec="seconds")
        with self.engine.begin() as conn:
            row = conn.execute(
                text(
                    "SELECT hospital_id, index_code, approval_status "
                    "FROM med_index_hospital_custom_version WHERE change_id=:change_id"
                ),
                {"change_id": change_id},
            ).mappings().first()
            if row is None:
                raise RuleNotFoundError(f"CHANGE_NOT_FOUND: {change_id}")
            if row["approval_status"] != "pending":
                raise ValueError("该变更已处理，不能重复拒绝")
            conn.execute(
                text(
                    """
                    UPDATE med_index_hospital_custom_version
                    SET approval_status='rejected', approver_id=:approver_id,
                        approved_at=:processed_at
                    WHERE change_id=:change_id
                    """
                ),
                {
                    "change_id": change_id,
                    "approver_id": approver_id,
                    "processed_at": now,
                },
            )
        return {
            "change_id": change_id,
            "rule_id": row["index_code"],
            "hospital_id": row["hospital_id"],
            "status": "rejected",
            "approval_status": "rejected",
            "approver_id": approver_id,
            "rejected_at": now,
        }

    def list_versions(self, index_code: str, hospital_id: str) -> dict[str, Any]:
        with self.engine.connect() as conn:
            current = conn.execute(
                text(
                    "SELECT version FROM med_index_hospital_custom "
                    "WHERE hospital_id=:hospital_id AND index_code=:index_code"
                ),
                {"hospital_id": hospital_id, "index_code": index_code},
            ).first()
            rows = conn.execute(
                text(
                    """
                    SELECT * FROM med_index_hospital_custom_version
                    WHERE hospital_id=:hospital_id AND index_code=:index_code
                    ORDER BY version DESC
                    """
                ),
                {"hospital_id": hospital_id, "index_code": index_code},
            ).mappings().all()
        active_version = int(current[0]) if current is not None else None
        versions = []
        for row in rows:
            item = dict(row)
            snapshot = _json_dict(item.get("snapshot_json"))
            versions.append(
                {
                    "version_id": str(item["version"]),
                    "version": int(item["version"]),
                    "status": item["approval_status"],
                    "source": item["change_type"],
                    "definition": snapshot.get("requested_definition") or "",
                    "formula": snapshot.get("requested_formula") or "",
                    "custom_params": _json_dict(snapshot.get("custom_params")),
                    "approver_id": item.get("approver_id") or "",
                    "approved_at": item.get("approved_at"),
                    "created_at": item.get("created_at"),
                    "active": int(item["version"]) == active_version,
                }
            )
        return {
            "hospital_id": hospital_id,
            "rule_id": index_code,
            "active_version_id": str(active_version) if active_version is not None else None,
            "versions": versions,
        }

    def restore_version(
        self, index_code: str, hospital_id: str, version: int, approver_id: str
    ) -> dict[str, Any]:
        now = datetime.now().isoformat(sep=" ", timespec="seconds")
        change_id = f"RESTORE_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:8]}"
        with self.engine.begin() as conn:
            row = conn.execute(
                text(
                    """
                    SELECT snapshot_json FROM med_index_hospital_custom_version
                    WHERE hospital_id=:hospital_id AND index_code=:index_code
                      AND version=:version AND approval_status='approved'
                    """
                ),
                {
                    "hospital_id": hospital_id,
                    "index_code": index_code,
                    "version": int(version),
                },
            ).mappings().first()
            if row is None:
                raise RuleNotFoundError(f"VERSION_NOT_FOUND: {hospital_id}/{index_code}/{version}")
            snapshot = _json_dict(row["snapshot_json"])
            new_version = self._next_version(conn, hospital_id, index_code)
            conn.execute(
                text(
                    """
                    INSERT INTO med_index_hospital_custom_version
                      (change_id, hospital_id, index_code, version, approval_status,
                       snapshot_json, source_version, change_type, oper_user,
                       approver_id, created_at, approved_at)
                    VALUES
                      (:change_id, :hospital_id, :index_code, :version, 'approved',
                       :snapshot_json, :source_version, 'restore', :approver_id,
                       :approver_id, :created_at, :approved_at)
                    """
                ),
                {
                    "change_id": change_id,
                    "hospital_id": hospital_id,
                    "index_code": index_code,
                    "version": new_version,
                    "snapshot_json": json.dumps(snapshot, ensure_ascii=False),
                    "source_version": int(version),
                    "approver_id": approver_id,
                    "created_at": now,
                    "approved_at": now,
                },
            )
            self._write_current(
                conn, hospital_id, index_code, new_version, snapshot, approver_id, now
            )
        return {
            "change_id": change_id,
            "rule_id": index_code,
            "hospital_id": hospital_id,
            "status": "approved",
            "active_version": new_version,
            "active_version_id": str(new_version),
            "restored_from_version": int(version),
            "approver_id": approver_id,
        }

    @staticmethod
    def _snapshot_from_current(current: dict[str, Any]) -> dict[str, Any]:
        return {
            "custom_numerator": current.get("custom_numerator"),
            "custom_denominator": current.get("custom_denominator"),
            "custom_filter": current.get("custom_filter"),
            "exclude_rule": current.get("exclude_rule"),
            "custom_params": _json_dict(current.get("custom_params")),
            "custom_sql": current.get("custom_sql"),
            "status": int(current.get("status") or 1),
            "effective_from": current.get("effective_from"),
            "effective_to": current.get("effective_to"),
        }

    @staticmethod
    def _next_version(conn: Any, hospital_id: str, index_code: str) -> int:
        value = conn.execute(
            text(
                """
                SELECT MAX(version) FROM med_index_hospital_custom_version
                WHERE hospital_id=:hospital_id AND index_code=:index_code
                """
            ),
            {"hospital_id": hospital_id, "index_code": index_code},
        ).scalar_one()
        return int(value or 0) + 1

    @staticmethod
    def _write_current(
        conn: Any,
        hospital_id: str,
        index_code: str,
        version: int,
        snapshot: dict[str, Any],
        oper_user: str,
        now: str,
    ) -> None:
        params = {
            "hospital_id": hospital_id,
            "index_code": index_code,
            "custom_numerator": snapshot.get("custom_numerator"),
            "custom_denominator": snapshot.get("custom_denominator"),
            "custom_filter": snapshot.get("custom_filter"),
            "exclude_rule": snapshot.get("exclude_rule"),
            "custom_params": json.dumps(
                _json_dict(snapshot.get("custom_params")), ensure_ascii=False
            ),
            "custom_sql": snapshot.get("custom_sql"),
            "version": int(version),
            "status": int(snapshot.get("status") or 1),
            "effective_from": snapshot.get("effective_from"),
            "effective_to": snapshot.get("effective_to"),
            "oper_user": oper_user,
            "now": now,
        }
        exists = conn.execute(
            text(
                "SELECT 1 FROM med_index_hospital_custom "
                "WHERE hospital_id=:hospital_id AND index_code=:index_code"
            ),
            params,
        ).first()
        if exists is None:
            conn.execute(
                text(
                    """
                    INSERT INTO med_index_hospital_custom
                      (hospital_id, index_code, custom_numerator, custom_denominator,
                       custom_filter, exclude_rule, custom_params, custom_sql,
                       version, status, approval_status, effective_from, effective_to,
                       oper_user, create_time, update_time)
                    VALUES
                      (:hospital_id, :index_code, :custom_numerator, :custom_denominator,
                       :custom_filter, :exclude_rule, :custom_params, :custom_sql,
                       :version, :status, 'approved', :effective_from, :effective_to,
                       :oper_user, :now, :now)
                    """
                ),
                params,
            )
            return
        conn.execute(
            text(
                """
                UPDATE med_index_hospital_custom
                SET custom_numerator=:custom_numerator,
                    custom_denominator=:custom_denominator,
                    custom_filter=:custom_filter, exclude_rule=:exclude_rule,
                    custom_params=:custom_params, custom_sql=:custom_sql,
                    version=:version, status=:status, approval_status='approved',
                    effective_from=:effective_from, effective_to=:effective_to,
                    oper_user=:oper_user, update_time=:now
                WHERE hospital_id=:hospital_id AND index_code=:index_code
                """
            ),
            params,
        )


class WikiRuleSource:
    """Read-only adapter around the existing file-based knowledge base."""

    def __init__(self, tools: Any) -> None:
        self.tools = tools

    def search(self, query: str, limit: int = 5) -> dict[str, Any]:
        return self.tools.search(query, limit=limit)

    def get_effective_rule(
        self, index_code_or_name: str, hospital_id: str | None
    ) -> dict[str, Any]:
        return self.tools.get_effective_rule(index_code_or_name, hospital_id)

    def get_field_mapping(self, index_code: str, hospital_id: str) -> dict[str, Any]:
        result = self.tools.get_field_mapping(index_code)
        return {**result, "hospital_id": hospital_id}


class FallbackRuleRepository:
    def __init__(self, primary: RuleRepository, fallback: WikiRuleSource) -> None:
        self.primary = primary
        self.fallback = fallback

    @staticmethod
    def _annotate_fallback(result: dict[str, Any], warning: str) -> dict[str, Any]:
        annotated = dict(result)
        annotated["rule_source"] = "wiki_fallback"
        annotated["warnings"] = [*annotated.get("warnings", []), warning]
        annotated["fallback_chain"] = ["hospital", "national", "wiki_fallback"]
        return annotated

    def search(self, query: str, limit: int = 5) -> dict[str, Any]:
        try:
            result = self.primary.search(query, limit=limit)
            if result.get("resolved_rule_id") or result.get("matches"):
                return {**result, "rule_source": "mysql", "warnings": []}
            warning = "rule_not_migrated"
        except Exception:
            warning = "rule_store_unavailable"
        return self._annotate_fallback(self.fallback.search(query, limit=limit), warning)

    def get_effective_rule(
        self, index_code_or_name: str, hospital_id: str | None
    ) -> dict[str, Any]:
        try:
            return self.primary.get_effective_rule(index_code_or_name, hospital_id)
        except RuleNotFoundError:
            warning = "rule_not_migrated"
        except Exception:
            warning = "rule_store_unavailable"
        result = self.fallback.get_effective_rule(index_code_or_name, hospital_id)
        return self._annotate_fallback(result, warning)

    def get_field_mapping(self, index_code: str, hospital_id: str) -> dict[str, Any]:
        try:
            result = self.primary.get_field_mapping(index_code, hospital_id)
            if result.get("fields") or result.get("items"):
                return {**result, "rule_source": "mysql", "warnings": []}
            warning = "mapping_not_migrated"
        except Exception:
            warning = "rule_store_unavailable"
        result = self.fallback.get_field_mapping(index_code, hospital_id)
        return self._annotate_fallback(result, warning)

    def build_feedback_preview(
        self, index_code: str, hospital_id: str | None, user_feedback: str
    ) -> dict[str, Any]:
        preview = self.fallback.tools.build_feedback_preview(
            index_code, hospital_id, user_feedback
        )
        effective = self.get_effective_rule(index_code, hospital_id)
        preview["current_effective_level"] = effective.get("effective_level")
        preview["current_effective"] = {
            "level": effective.get("effective_level"),
            "status": "effective",
            "definition": effective.get("definition", ""),
            "formula": effective.get("formula", ""),
            "implementation_status": effective.get("implementation_status", ""),
        }
        preview["rule_source"] = effective.get("rule_source")
        return preview

    def submit_change_request(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self.primary.submit_change_request(payload)

    def list_pending_changes(self) -> list[dict[str, Any]]:
        return self.primary.list_pending_changes()

    def reject_change_request(self, change_id: str, approver_id: str) -> dict[str, Any]:
        return self.primary.reject_change_request(change_id, approver_id)

    def approve_change_request(self, change_id: str, approver_id: str) -> dict[str, Any]:
        return self.primary.approve_change_request(change_id, approver_id)

    def list_versions(self, index_code: str, hospital_id: str) -> dict[str, Any]:
        return self.primary.list_versions(index_code, hospital_id)

    def restore_version(
        self, index_code: str, hospital_id: str, version: int, approver_id: str
    ) -> dict[str, Any]:
        return self.primary.restore_version(index_code, hospital_id, version, approver_id)


def create_rule_repository(engine: Engine, kb_root: str | Path) -> RuleRepository:
    from app.kb.tools import KnowledgeBaseTools

    primary = MySQLRuleRepository(engine)
    fallback = WikiRuleSource(KnowledgeBaseTools(kb_root))
    return FallbackRuleRepository(primary, fallback)
