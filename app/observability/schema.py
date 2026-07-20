from __future__ import annotations

from sqlalchemy import inspect, text
from sqlalchemy.engine import Engine


_NODE_COLUMNS = {
    "parent_node_id": "VARCHAR(80) NULL",
    "subtask_id": "VARCHAR(128) NULL",
    "sequence": "INTEGER NULL",
    "started_offset_ms": "INTEGER NULL",
    "exclusive_duration_ms": "INTEGER NULL",
    "capability": "VARCHAR(80) NULL",
    "model_id": "VARCHAR(128) NULL",
    "failure_class": "VARCHAR(80) NULL",
    "input_tokens": "INTEGER NULL",
    "output_tokens": "INTEGER NULL",
    "cache_reused": "INTEGER NULL",
    "retry_count": "INTEGER NULL",
}

_TRACE_INDEXES = {
    "idx_agent_trace_hospital_started": ("med_agent_trace", "hospital_id, started_at"),
    "idx_trace_node_subtask": ("med_agent_trace_node", "trace_id, subtask_id"),
    "idx_trace_node_model": ("med_agent_trace_node", "model_id"),
    "idx_trace_node_failure_class": ("med_agent_trace_node", "failure_class"),
}


def ensure_trace_enhancement_schema(engine: Engine) -> list[str]:
    inspector = inspect(engine)
    if not inspector.has_table("med_agent_trace_node"):
        return []
    existing = {column["name"] for column in inspector.get_columns("med_agent_trace_node")}
    added: list[str] = []
    with engine.begin() as connection:
        for name, ddl in _NODE_COLUMNS.items():
            if name in existing:
                continue
            connection.execute(text(f"ALTER TABLE med_agent_trace_node ADD COLUMN {name} {ddl}"))
            added.append(name)
    inspector = inspect(engine)
    with engine.begin() as connection:
        for index_name, (table_name, columns) in _TRACE_INDEXES.items():
            if not inspector.has_table(table_name):
                continue
            existing_indexes = {
                str(item.get("name") or "")
                for item in inspector.get_indexes(table_name)
            }
            if index_name in existing_indexes:
                continue
            connection.execute(text(
                f"CREATE INDEX {index_name} ON {table_name} ({columns})"
            ))
            added.append(index_name)
    return added
