"""元数据同步服务：通过 Provider 抽象保存快照、比对结构变更并关联受影响指标。"""

from __future__ import annotations

import json
import uuid
from pathlib import Path
from typing import Any

import yaml
from sqlalchemy import Engine, text

from app.db.repositories import log_sync_change, log_sync_column, log_sync_table
from app.db_access.metadata_provider import MetadataProvider, SQLAlchemyMetadataProvider


def _norm(value: Any) -> str:
    return str(value or "")


def _table_key(item: dict[str, Any]) -> str:
    return _norm(item.get("table_name") or item.get("TABLE_NAME"))


def _column_key(item: dict[str, Any]) -> tuple[str, str]:
    table = _norm(item.get("table_name") or item.get("TABLE_NAME"))
    column = _norm(item.get("column_name") or item.get("COLUMN_NAME"))
    return table, column


def load_runtime_snapshot(runtime_engine: Engine, hospital_id: str, db_name: str) -> dict[str, list[dict[str, Any]]]:
    with runtime_engine.connect() as conn:
        tables = conn.execute(
            text(
                """
                SELECT table_name, table_comment, table_type
                FROM med_metadata_table
                WHERE hospital_id=:h AND db_name=:d
                """
            ),
            {"h": hospital_id, "d": db_name},
        ).mappings().fetchall()
        columns = conn.execute(
            text(
                """
                SELECT table_name, column_name, data_type, column_type,
                       is_nullable, column_key, column_default, column_comment
                FROM med_metadata_column
                WHERE hospital_id=:h AND db_name=:d
                """
            ),
            {"h": hospital_id, "d": db_name},
        ).mappings().fetchall()
    return {"tables": [dict(row) for row in tables], "columns": [dict(row) for row in columns]}


def diff_metadata_snapshots(previous: dict[str, Any], current: dict[str, Any]) -> list[dict[str, Any]]:
    changes: list[dict[str, Any]] = []
    prev_tables = {_table_key(item): item for item in previous.get("tables", []) if _table_key(item)}
    curr_tables = {_table_key(item): item for item in current.get("tables", []) if _table_key(item)}

    for table in sorted(set(curr_tables) - set(prev_tables)):
        changes.append({"change_type": "table_added", "table_name": table, "field_name": "", "change_desc": f"新增表: {table}"})
    for table in sorted(set(prev_tables) - set(curr_tables)):
        changes.append({"change_type": "table_deleted", "table_name": table, "field_name": "", "change_desc": f"删除表: {table}"})

    prev_cols = {_column_key(item): item for item in previous.get("columns", []) if all(_column_key(item))}
    curr_cols = {_column_key(item): item for item in current.get("columns", []) if all(_column_key(item))}

    for table, column in sorted(set(curr_cols) - set(prev_cols)):
        changes.append({"change_type": "column_added", "table_name": table, "field_name": column, "change_desc": f"新增字段: {table}.{column}"})
    for table, column in sorted(set(prev_cols) - set(curr_cols)):
        changes.append({"change_type": "column_deleted", "table_name": table, "field_name": column, "change_desc": f"删除字段: {table}.{column}"})

    for key in sorted(set(prev_cols) & set(curr_cols)):
        prev = prev_cols[key]
        curr = curr_cols[key]
        table, column = key
        prev_type = _norm(prev.get("column_type") or prev.get("data_type"))
        curr_type = _norm(curr.get("column_type") or curr.get("data_type"))
        if _norm(prev.get("data_type")).lower() != _norm(curr.get("data_type")).lower() or prev_type.lower() != curr_type.lower():
            changes.append(
                {
                    "change_type": "column_type_changed",
                    "table_name": table,
                    "field_name": column,
                    "change_desc": f"字段类型变化: {table}.{column} {prev_type} -> {curr_type}",
                }
            )
        prev_nullable = _norm(prev.get("is_nullable")).upper()
        curr_nullable = _norm(curr.get("is_nullable")).upper()
        if prev_nullable != curr_nullable:
            changes.append(
                {
                    "change_type": "column_nullable_changed",
                    "table_name": table,
                    "field_name": column,
                    "change_desc": f"字段可空性变化: {table}.{column} {prev_nullable} -> {curr_nullable}",
                }
            )
    return changes


def _read_yaml(path: Path) -> dict[str, Any]:
    try:
        return yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except Exception:
        return {}


def _mapped_table_names(
    kb_root: Path, hospital_id: str, db_name: str
) -> list[str]:
    mapping_dir = Path(kb_root) / "hospital-mappings" / hospital_id
    names: list[str] = []
    if not mapping_dir.exists():
        return names
    for path in sorted(mapping_dir.glob("*.yaml")):
        mapping = _read_yaml(path)
        if str(mapping.get("db_name") or "") != db_name:
            continue
        candidates = [str(mapping.get("main_table") or "")]
        for value in (mapping.get("fields") or {}).values():
            parts = [part for part in str(value or "").split(".") if part]
            if len(parts) >= 2:
                candidates.append(parts[-2])
        for name in candidates:
            if name and name not in names:
                names.append(name)
    return names


def collect_metadata_snapshot(
    provider: MetadataProvider,
    db_name: str,
    kb_root: str | Path | None = None,
    hospital_id: str = "",
) -> dict[str, list[dict[str, Any]]]:
    """Collect the bulk catalog and deterministically refill mapped tables."""

    tables = list(provider.list_tables(db_name))
    columns = list(provider.list_columns(db_name))
    if kb_root and hospital_id:
        table_names = _mapped_table_names(Path(kb_root), hospital_id, db_name)
        table_index = {_table_key(item): dict(item) for item in tables if _table_key(item)}
        column_index = {
            _column_key(item): dict(item)
            for item in columns
            if all(_column_key(item))
        }
        for table_name in table_names:
            table_index.setdefault(
                table_name,
                {
                    "table_name": table_name,
                    "table_comment": "指标映射依赖表",
                    "table_type": "MAPPED_OBJECT",
                },
            )
            for item in provider.list_columns(db_name, table_name):
                key = _column_key(item)
                if all(key):
                    column_index[key] = dict(item)
        tables = list(table_index.values())
        columns = list(column_index.values())
    return {"tables": tables, "columns": columns}


def find_affected_rules(kb_root: Path, hospital_id: str, changes: list[dict[str, Any]]) -> list[dict[str, Any]]:
    kb_root = Path(kb_root)
    mapping_dir = kb_root / "hospital-mappings" / hospital_id
    if not mapping_dir.exists():
        return []

    changed = {(str(c.get("table_name") or ""), str(c.get("field_name") or "")) for c in changes}
    affected: dict[str, dict[str, Any]] = {}
    for mapping_file in mapping_dir.glob("*.yaml"):
        rule_id = mapping_file.stem
        mapping = _read_yaml(mapping_file)
        main_table = str(mapping.get("main_table") or "")
        fields = mapping.get("fields") or {}
        for business_field, col_ref in fields.items():
            ref = str(col_ref or "")
            parts = [p for p in ref.split(".") if p]
            table = parts[-2] if len(parts) >= 2 else main_table
            column = parts[-1] if parts else ""
            if (table, column) in changed or (table, "") in changed:
                item = affected.setdefault(rule_id, {"rule_id": rule_id, "matched_columns": [], "business_fields": []})
                item["matched_columns"].append(column)
                item["business_fields"].append(str(business_field))
    return list(affected.values())


def _save_snapshot(runtime_engine: Engine, hospital_id: str, db_name: str, source: str, batch_id: str, snapshot: dict[str, Any]) -> None:
    with runtime_engine.connect() as conn:
        try:
            conn.execute(
                text(
                    """
                    INSERT INTO med_metadata_snapshot
                      (hospital_id, db_name, metadata_source, sync_batch_id, snapshot_json, created_at)
                    VALUES (:h, :d, :s, :b, :j, CURRENT_TIMESTAMP)
                    """
                ),
                {
                    "h": hospital_id,
                    "d": db_name,
                    "s": source,
                    "b": batch_id,
                    "j": json.dumps(snapshot, ensure_ascii=False, default=str),
                },
            )
            conn.commit()
        except Exception:
            conn.rollback()


def sync_metadata_from_provider(
    runtime_engine: Engine,
    provider: MetadataProvider,
    hospital_id: str,
    db_name: str,
    kb_root: str | Path | None = None,
) -> dict[str, Any]:
    batch_id = uuid.uuid4().hex[:12]
    previous = load_runtime_snapshot(runtime_engine, hospital_id, db_name)
    current = collect_metadata_snapshot(
        provider, db_name, kb_root=kb_root, hospital_id=hospital_id
    )
    tables = current["tables"]
    columns = current["columns"]
    changes = diff_metadata_snapshots(previous, current)
    _save_snapshot(runtime_engine, hospital_id, db_name, provider.source_name, batch_id, current)

    for table in tables:
        log_sync_table(
            runtime_engine,
            hospital_id,
            db_name,
            _norm(table.get("table_name")),
            _norm(table.get("table_comment")),
            _norm(table.get("table_type")),
            batch_id,
        )
    for col in columns:
        log_sync_column(
            runtime_engine,
            hospital_id,
            db_name,
            _norm(col.get("table_name")),
            _norm(col.get("column_name")),
            _norm(col.get("data_type")),
            _norm(col.get("column_type")),
            _norm(col.get("is_nullable")),
            _norm(col.get("column_key")),
            _norm(col.get("column_default")),
            _norm(col.get("column_comment")),
            batch_id,
        )
    log_sync_change(runtime_engine, hospital_id, db_name, "", "", "full_sync", f"元数据同步完成: {len(tables)} 张表, {len(columns)} 个字段", batch_id)
    for change in changes:
        log_sync_change(
            runtime_engine,
            hospital_id,
            db_name,
            _norm(change.get("table_name")),
            _norm(change.get("field_name")),
            _norm(change.get("change_type")),
            _norm(change.get("change_desc")),
            batch_id,
        )

    affected_rules = find_affected_rules(Path(kb_root), hospital_id, changes) if kb_root else []
    return {
        "hospital_id": hospital_id,
        "db_name": db_name,
        "metadata_source": provider.source_name,
        "table_count": len(tables),
        "column_count": len(columns),
        "batch_id": batch_id,
        "changes": changes,
        "affected_rules": affected_rules,
    }


def sync_mysql_metadata(
    runtime_engine: Engine,
    business_engine: Engine | None = None,
    hospital_id: str = "",
    db_name: str = "",
    kb_root: str | Path | None = None,
    metadata_provider: MetadataProvider | None = None,
) -> dict[str, Any]:
    """兼容旧测试入口；生产链路应传入 DBHubMetadataProvider。"""

    provider = metadata_provider or (SQLAlchemyMetadataProvider(business_engine) if business_engine is not None else None)
    if provider is None:
        raise ValueError("必须提供 metadata_provider，或仅在测试兼容场景提供 business_engine")
    return sync_metadata_from_provider(runtime_engine, provider, hospital_id, db_name, kb_root=kb_root)
