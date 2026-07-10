from __future__ import annotations

from typing import Any

from sqlalchemy import Engine, text

from .contracts import IndicatorDraft


class MetadataResolutionError(ValueError):
    pass


class DraftMetadataResolver:
    def __init__(self, engine: Engine, draft_repository: Any):
        self.engine = engine
        self.draft_repository = draft_repository

    def suggest(self, draft_id: str) -> dict[str, Any]:
        draft = self.draft_repository.get(draft_id)
        main_table = str(draft.sql_plan.get("main_table") or "").strip()
        if not main_table:
            raise MetadataResolutionError("设计稿缺少统计主表")
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    """
                    SELECT db_name, table_name, column_name, data_type,
                           column_comment
                    FROM med_metadata_column
                    WHERE hospital_id=:hospital_id AND table_name=:table_name
                    ORDER BY db_name, column_name
                    """
                ),
                {"hospital_id": draft.hospital_id, "table_name": main_table},
            ).mappings().all()

        suggestions: dict[str, list[dict[str, Any]]] = {}
        missing: list[str] = []
        ambiguous: list[str] = []
        for business_field in draft.metadata_requirements:
            matches = [
                _candidate(dict(row), business_field)
                for row in rows
                if _matches_field(dict(row), business_field)
            ]
            suggestions[business_field] = matches
            if not matches:
                missing.append(business_field)
            elif len(matches) > 1:
                ambiguous.append(business_field)
        return {
            "draft_id": draft_id,
            "hospital_id": draft.hospital_id,
            "main_table": main_table,
            "suggestions": suggestions,
            "missing_fields": missing,
            "ambiguous_fields": ambiguous,
            "ready_for_confirmation": not missing,
        }

    def confirm(
        self,
        draft_id: str,
        expected_version: int,
        mappings: dict[str, dict[str, Any]],
        actor_id: str,
    ) -> IndicatorDraft:
        draft = self.draft_repository.get(draft_id)
        missing = [
            field for field in draft.metadata_requirements if field not in mappings
        ]
        if missing:
            raise MetadataResolutionError(f"字段映射不完整：{missing}")
        main_table = str(draft.sql_plan.get("main_table") or "").strip()
        tables = {str(item.get("table_name") or "") for item in mappings.values()}
        if len(tables) != 1 or tables != {main_table}:
            raise MetadataResolutionError("第一版字段映射必须来自单一主表")

        normalized: dict[str, dict[str, Any]] = {}
        with self.engine.connect() as conn:
            for business_field in draft.metadata_requirements:
                item = mappings[business_field]
                params = {
                    "hospital_id": draft.hospital_id,
                    "db_name": str(item.get("db_name") or ""),
                    "table_name": str(item.get("table_name") or ""),
                    "column_name": str(item.get("column_name") or ""),
                }
                row = conn.execute(
                    text(
                        """
                        SELECT data_type FROM med_metadata_column
                        WHERE hospital_id=:hospital_id AND db_name=:db_name
                          AND table_name=:table_name AND column_name=:column_name
                        LIMIT 1
                        """
                    ),
                    params,
                ).first()
                if row is None:
                    raise MetadataResolutionError(
                        f"字段不在最近元数据快照中：{business_field}"
                    )
                normalized[business_field] = {
                    **params,
                    "data_type": str(row[0] or item.get("data_type") or ""),
                    "status": "confirmed",
                }
        return self.draft_repository.transition(
            draft_id,
            expected_version,
            "metadata_ready",
            {"field_mapping": normalized},
            actor_id,
            "metadata_confirmed",
        )


def _matches_field(row: dict[str, Any], business_field: str) -> bool:
    expected = _normalize(business_field)
    column = _normalize(row.get("column_name"))
    comment = _normalize(row.get("column_comment"))
    return column == expected or bool(comment and (comment == expected or expected in comment))


def _candidate(row: dict[str, Any], business_field: str) -> dict[str, Any]:
    exact = _normalize(row.get("column_name")) == _normalize(business_field)
    return {
        "db_name": str(row.get("db_name") or ""),
        "table_name": str(row.get("table_name") or ""),
        "column_name": str(row.get("column_name") or ""),
        "data_type": str(row.get("data_type") or ""),
        "confidence": 1.0 if exact else 0.8,
        "reason": "字段名完全匹配" if exact else "字段注释匹配",
    }


def _normalize(value: Any) -> str:
    return str(value or "").strip().lower().replace("_", "")
