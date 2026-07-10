from __future__ import annotations

import json
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
        raise NotImplementedError("MySQL rule writes are implemented in the versioning task")

    def approve_change_request(self, change_id: str, approver_id: str) -> dict[str, Any]:
        raise NotImplementedError("MySQL rule writes are implemented in the versioning task")

    def list_versions(self, index_code: str, hospital_id: str) -> dict[str, Any]:
        raise NotImplementedError("MySQL rule writes are implemented in the versioning task")

    def restore_version(
        self, index_code: str, hospital_id: str, version: int, approver_id: str
    ) -> dict[str, Any]:
        raise NotImplementedError("MySQL rule writes are implemented in the versioning task")


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

    def submit_change_request(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self.primary.submit_change_request(payload)

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
