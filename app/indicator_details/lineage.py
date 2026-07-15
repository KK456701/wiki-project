from __future__ import annotations

import re

from app.sqlgen.context_overrides import apply_execution_field_roles

from .models import DetailColumn, DetailFieldLineage, RunContext


_QUALIFIED_COLUMN = re.compile(
    r"^(?P<table>[A-Za-z_][A-Za-z0-9_]*)\.(?P<column>[A-Za-z_][A-Za-z0-9_]*)$"
)


def _mapped_column(
    mappings: dict[str, object], field: str, schema: str = ""
) -> str:
    value = str(mappings.get(field) or "").strip()
    if not value:
        raise ValueError(f"明细字段尚未完成本院映射：{field}")
    if _QUALIFIED_COLUMN.fullmatch(value) is None:
        raise ValueError(f"医院字段映射格式无效：{field}")
    return f"{schema}.{value}" if schema else value


def _append_source_tables(tables: list[str], sources: list[str]) -> None:
    for source in sources:
        parts = source.split(".")
        table = ".".join(parts[:-1]) if len(parts) >= 2 else ""
        if table and table not in tables:
            tables.append(table)


def build_detail_lineage(
    context: RunContext,
    columns: list[DetailColumn],
) -> tuple[str, list[str], list[DetailFieldLineage]]:
    effective_mapping = apply_execution_field_roles(
        context.field_mapping, context.execution_context
    )
    mappings = dict(effective_mapping.get("fields") or {})
    derived = dict(context.calculation_definition.get("derived_fields") or {})
    labels = {column.field: column.label for column in columns}
    database = str(
        effective_mapping.get("db_name") or context.db_source or ""
    ).strip()
    schema = str(effective_mapping.get("schema") or "").strip()
    main_table = f"{schema}.{context.main_table}" if schema else context.main_table
    tables = [main_table] if main_table else []
    result: list[DetailFieldLineage] = []

    for column in columns:
        definition = derived.get(column.field)
        if isinstance(definition, dict):
            source_fields = list(definition.get("source_fields") or [])
            if not source_fields:
                raise ValueError(f"派生字段缺少来源字段：{column.field}")
            sources = [
                _mapped_column(mappings, str(field), schema)
                for field in source_fields
            ]
            source_labels = [labels.get(str(field), str(field)) for field in source_fields]
            lineage = DetailFieldLineage(
                field=column.field,
                label=column.label,
                kind="derived",
                sources=sources,
                explanation=f"由{'、'.join(source_labels)}计算",
            )
        else:
            source = _mapped_column(mappings, column.field, schema)
            lineage = DetailFieldLineage(
                field=column.field,
                label=column.label,
                kind="column",
                sources=[source],
                explanation=f"来自 {source}",
            )
        _append_source_tables(tables, lineage.sources)
        result.append(lineage)
    return database, tables, result
