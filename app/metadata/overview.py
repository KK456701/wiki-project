"""Read-only overview of the latest database metadata synchronization."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from sqlalchemy import Engine, text

from app.metadata.sync import find_affected_rules


def empty_metadata_overview(hospital_id: str, db_name: str) -> dict[str, Any]:
    return {
        "hospital_id": hospital_id,
        "db_name": db_name,
        "has_snapshot": False,
        "metadata_source": None,
        "batch_id": None,
        "synced_at": None,
        "table_count": 0,
        "column_count": 0,
        "changes": [],
        "affected_rules": [],
    }


def _snapshot_payload(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        parsed = json.loads(value)
        return parsed if isinstance(parsed, dict) else {}
    return {}


def _iso_value(value: Any) -> str | None:
    if value is None:
        return None
    return value.isoformat() if hasattr(value, "isoformat") else str(value)


def load_metadata_overview(
    runtime_engine: Engine,
    kb_root: str | Path,
    hospital_id: str,
    db_name: str,
) -> dict[str, Any]:
    """Return the latest persisted metadata snapshot and its structural impact."""

    order_column = "rowid" if runtime_engine.dialect.name == "sqlite" else "id"
    with runtime_engine.connect() as conn:
        snapshot_row = conn.execute(
            text(
                f"""
                SELECT metadata_source, sync_batch_id, snapshot_json, created_at
                FROM med_metadata_snapshot
                WHERE hospital_id=:hospital_id AND db_name=:db_name
                ORDER BY {order_column} DESC
                LIMIT 1
                """
            ),
            {"hospital_id": hospital_id, "db_name": db_name},
        ).mappings().first()
        if snapshot_row is None:
            return empty_metadata_overview(hospital_id, db_name)

        changes = [
            dict(row)
            for row in conn.execute(
                text(
                    """
                    SELECT table_name, field_name, change_type, change_desc
                    FROM med_metadata_sync_log
                    WHERE hospital_id=:hospital_id
                      AND db_name=:db_name
                      AND sync_batch_id=:batch_id
                      AND change_type <> 'full_sync'
                    ORDER BY table_name, field_name, change_type
                    """
                ),
                {
                    "hospital_id": hospital_id,
                    "db_name": db_name,
                    "batch_id": snapshot_row["sync_batch_id"],
                },
            ).mappings()
        ]

    snapshot = _snapshot_payload(snapshot_row["snapshot_json"])
    return {
        "hospital_id": hospital_id,
        "db_name": db_name,
        "has_snapshot": True,
        "metadata_source": snapshot_row["metadata_source"],
        "batch_id": snapshot_row["sync_batch_id"],
        "synced_at": _iso_value(snapshot_row["created_at"]),
        "table_count": len(snapshot.get("tables") or []),
        "column_count": len(snapshot.get("columns") or []),
        "changes": changes,
        "affected_rules": find_affected_rules(Path(kb_root), hospital_id, changes),
    }
