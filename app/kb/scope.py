from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import Engine, text

from app.kb.exchange_schema import ensure_kb_exchange_schema


class MetadataExportScopeError(RuntimeError):
    pass


class MetadataExportScopeRepository:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine
        ensure_kb_exchange_schema(engine)

    def list_scope(self, hospital_id: str, db_name: str) -> dict[str, Any]:
        selected = self.selected_fields(hospital_id, db_name)
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    """
                    SELECT t.table_name, t.table_comment, t.table_type,
                           c.column_name, c.data_type, c.column_type,
                           c.is_nullable, c.column_key, c.column_comment
                    FROM med_metadata_table t
                    JOIN med_metadata_column c
                      ON c.hospital_id=t.hospital_id AND c.db_name=t.db_name
                     AND c.table_name=t.table_name
                    WHERE t.hospital_id=:hospital_id AND t.db_name=:db_name
                    ORDER BY t.table_name, c.column_name
                    """
                ),
                {"hospital_id": hospital_id, "db_name": db_name},
            ).mappings().all()
        tables: dict[str, dict[str, Any]] = {}
        for row in rows:
            table_name = str(row["table_name"])
            table = tables.setdefault(
                table_name,
                {
                    "table_name": table_name,
                    "table_comment": str(row.get("table_comment") or ""),
                    "table_type": str(row.get("table_type") or ""),
                    "columns": [],
                },
            )
            column_name = str(row["column_name"])
            table["columns"].append(
                {
                    "column_name": column_name,
                    "data_type": str(row.get("data_type") or ""),
                    "column_type": str(row.get("column_type") or ""),
                    "is_nullable": str(row.get("is_nullable") or ""),
                    "column_key": str(row.get("column_key") or ""),
                    "column_comment": str(row.get("column_comment") or ""),
                    "selected": (table_name, column_name) in selected,
                }
            )
        return {
            "hospital_id": hospital_id,
            "db_name": db_name,
            "tables": list(tables.values()),
        }

    def replace_scope(
        self,
        hospital_id: str,
        db_name: str,
        selections: list[dict[str, str]],
        actor_id: str,
    ) -> dict[str, Any]:
        normalized = {
            (str(item.get("table_name") or "").strip(), str(item.get("column_name") or "").strip())
            for item in selections
        }
        if any(not table or not column for table, column in normalized):
            raise MetadataExportScopeError("METADATA_SCOPE_FIELD_INVALID")
        with self.engine.connect() as conn:
            available = {
                (str(row[0]), str(row[1]))
                for row in conn.execute(
                    text(
                        """
                        SELECT table_name, column_name FROM med_metadata_column
                        WHERE hospital_id=:hospital_id AND db_name=:db_name
                        """
                    ),
                    {"hospital_id": hospital_id, "db_name": db_name},
                ).all()
            }
        if not normalized <= available:
            raise MetadataExportScopeError("METADATA_SCOPE_COLUMN_UNKNOWN")
        now = datetime.now()
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    """DELETE FROM med_metadata_export_scope
                    WHERE hospital_id=:hospital_id AND db_name=:db_name"""
                ),
                {"hospital_id": hospital_id, "db_name": db_name},
            )
            for table_name, column_name in sorted(normalized):
                conn.execute(
                    text(
                        """
                        INSERT INTO med_metadata_export_scope
                          (hospital_id, db_name, table_name, column_name,
                           selected_by, updated_at)
                        VALUES
                          (:hospital_id, :db_name, :table_name, :column_name,
                           :selected_by, :updated_at)
                        """
                    ),
                    {
                        "hospital_id": hospital_id,
                        "db_name": db_name,
                        "table_name": table_name,
                        "column_name": column_name,
                        "selected_by": actor_id,
                        "updated_at": now,
                    },
                )
        return self.preview_scope(hospital_id, db_name)

    def selected_fields(self, hospital_id: str, db_name: str) -> set[tuple[str, str]]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    """
                    SELECT table_name, column_name FROM med_metadata_export_scope
                    WHERE hospital_id=:hospital_id AND db_name=:db_name
                    ORDER BY table_name, column_name
                    """
                ),
                {"hospital_id": hospital_id, "db_name": db_name},
            ).all()
        return {(str(row[0]), str(row[1])) for row in rows}

    def preview_scope(self, hospital_id: str, db_name: str) -> dict[str, Any]:
        scope = self.list_scope(hospital_id, db_name)
        selected_tables: list[dict[str, Any]] = []
        selected_column_count = 0
        for table in scope["tables"]:
            columns = [item for item in table["columns"] if item["selected"]]
            if not columns:
                continue
            selected_column_count += len(columns)
            selected_tables.append({**table, "columns": columns})
        return {
            "hospital_id": hospital_id,
            "db_name": db_name,
            "selected_table_count": len(selected_tables),
            "selected_column_count": selected_column_count,
            "tables": selected_tables,
            "excluded_content": [
                "患者数据行",
                "字段样例值",
                "字段默认值",
                "数据库密码",
                "数据库连接地址",
                "未选择的表和字段",
            ],
        }
