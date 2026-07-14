"""按指标真实计算依赖执行字段预校验。"""

from pathlib import Path
from typing import Any
import yaml

from sqlalchemy import Engine, inspect, text

from app.rules.calculation import (
    collect_business_dependencies,
    parse_calculation_definition,
)


TYPE_GROUPS = {
    "string": {"char", "varchar", "text", "tinytext", "mediumtext", "longtext"},
    "datetime": {"date", "datetime", "timestamp"},
    "integer": {"tinyint", "smallint", "mediumint", "int", "integer", "bigint"},
    "numeric": {"decimal", "numeric", "float", "double", "real"},
    "boolean": {"bool", "boolean", "tinyint", "bit"},
}


def load_yaml(path: Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def find_spec_dir(kb_root: Path, rule_id: str) -> Path | None:
    for d in (kb_root / "sql-specs").iterdir():
        if d.is_dir() and d.name.startswith(rule_id):
            return d
    return None


def precheck_rule_fields(
    kb_root: Path,
    runtime_engine: Engine,
    hospital_id: str,
    rule_id: str,
    *,
    calculation_definition: dict[str, Any] | None = None,
    field_mapping: dict[str, Any] | None = None,
) -> dict[str, Any]:
    spec_dir = find_spec_dir(kb_root, rule_id)
    if not spec_dir:
        return {"ok": False, "error": f"未找到 SQL 规格: {rule_id}"}

    contract = load_yaml(spec_dir / "field_contract.yaml")
    business_fields = contract.get("business_fields") or {}
    if calculation_definition:
        definition = parse_calculation_definition(calculation_definition)
        required_fields = sorted(collect_business_dependencies(definition))
    else:
        required_fields = sorted(business_fields)

    mapping = field_mapping
    if mapping is None:
        mapping_path = (
            kb_root / "hospital-mappings" / hospital_id / f"{rule_id}.yaml"
        )
        if not mapping_path.exists():
            issues = [
                f"指标计算需要“{_field_desc(business_fields, name)}”，"
                "但本院字段映射尚未配置，暂不能生成可执行 SQL。"
                for name in required_fields
            ]
            return {
                "ok": False,
                "error": "；".join(issues),
                "required_business_fields": required_fields,
                "missing_mappings": required_fields,
                "unconfirmed_mappings": [],
                "missing_columns": [],
                "type_mismatches": [],
                "missing_relations": [],
                "issues": issues,
            }
        mapping = load_yaml(mapping_path)

    mapped_fields = mapping.get("fields") or {}
    mapping_items = {
        str(item.get("business_field") or ""): item
        for item in mapping.get("items") or []
        if item.get("business_field")
    }
    missing_mappings: list[str] = []
    unconfirmed_mappings: list[str] = []
    missing_columns: list[str] = []
    type_mismatches: list[str] = []
    physical_tables: set[str] = set()

    for bf_name in required_fields:
        mapped_col = mapped_fields.get(bf_name, "")
        if not mapped_col:
            missing_mappings.append(bf_name)
            continue
        item = mapping_items.get(bf_name)
        status = str(
            (item or {}).get("status")
            or (mapping.get("status") if not mapping_items else "confirmed")
            or "confirmed"
        ).lower()
        if status != "confirmed":
            unconfirmed_mappings.append(bf_name)

        parts = str(mapped_col).split(".")
        tbl, col = (parts[-2], parts[-1]) if len(parts) >= 2 else ("", parts[-1])
        if tbl and col:
            physical_tables.add(tbl)
            with runtime_engine.connect() as conn:
                row = conn.execute(
                    text(
                        """
                        SELECT data_type
                        FROM med_metadata_column
                        WHERE hospital_id=:h AND db_name=:d
                          AND table_name=:t AND column_name=:c
                        LIMIT 1
                        """
                    ),
                    {
                        "h": hospital_id,
                        "d": str((item or {}).get("db_name") or mapping.get("db_name") or ""),
                        "t": tbl,
                        "c": col,
                    },
                ).mappings().first()
                if not row:
                    missing_columns.append(mapped_col)
                else:
                    expected_type = str(
                        (business_fields.get(bf_name) or {}).get("type") or ""
                    ).lower()
                    actual_type = str(row.get("data_type") or "").lower()
                    if not _types_compatible(expected_type, actual_type):
                        type_mismatches.append(
                            f"{bf_name}：期望 {expected_type}，实际 {actual_type}（{mapped_col}）"
                        )

    missing_relations = _missing_relations(
        runtime_engine,
        hospital_id,
        str(mapping.get("db_name") or ""),
        str(mapping.get("main_table") or ""),
        physical_tables,
    )
    issues = _business_issues(
        business_fields,
        mapped_fields,
        missing_mappings,
        unconfirmed_mappings,
        missing_columns,
        type_mismatches,
        missing_relations,
    )

    ok = not any(
        (
            missing_mappings,
            unconfirmed_mappings,
            missing_columns,
            type_mismatches,
            missing_relations,
        )
    )
    return {
        "ok": ok,
        "error": None if ok else "；".join(issues),
        "required_business_fields": required_fields,
        "missing_mappings": missing_mappings,
        "unconfirmed_mappings": unconfirmed_mappings,
        "missing_columns": missing_columns,
        "type_mismatches": type_mismatches,
        "missing_relations": missing_relations,
        "issues": issues,
        "dialect": mapping.get("dialect", "mysql"),
        "db_name": mapping.get("db_name", ""),
        "main_table": mapping.get("main_table", ""),
        "field_mapping": mapped_fields,
        "filters": mapping.get("filters", {}),
    }


def _types_compatible(expected: str, actual: str) -> bool:
    if not expected or not actual:
        return True
    return actual in TYPE_GROUPS.get(expected, {expected})


def _missing_relations(
    engine: Engine,
    hospital_id: str,
    db_name: str,
    main_table: str,
    physical_tables: set[str],
) -> list[str]:
    if len(physical_tables) <= 1:
        return []
    anchor = main_table or sorted(physical_tables)[0]
    others = sorted(physical_tables - {anchor})
    has_relation_table = inspect(engine).has_table("med_table_relation")
    missing: list[str] = []
    for other in others:
        found = None
        if has_relation_table:
            with engine.connect() as conn:
                found = conn.execute(
                    text(
                        """
                        SELECT 1 FROM med_table_relation
                        WHERE hospital_id=:hospital_id AND db_name=:db_name
                          AND status='confirmed'
                          AND ((left_table=:anchor AND right_table=:other)
                            OR (left_table=:other AND right_table=:anchor))
                        LIMIT 1
                        """
                    ),
                    {
                        "hospital_id": hospital_id,
                        "db_name": db_name,
                        "anchor": anchor,
                        "other": other,
                    },
                ).first()
        if not found:
            missing.append(f"{anchor} -> {other}")
    return missing


def _field_desc(business_fields: dict[str, Any], field_name: str) -> str:
    spec = business_fields.get(field_name) or {}
    return str(spec.get("desc") or field_name)


def _business_issues(
    business_fields: dict[str, Any],
    mapped_fields: dict[str, str],
    missing_mappings: list[str],
    unconfirmed_mappings: list[str],
    missing_columns: list[str],
    type_mismatches: list[str],
    missing_relations: list[str],
) -> list[str]:
    issues = [
        f"指标计算需要“{_field_desc(business_fields, name)}”（{name}），"
        "本院字段映射尚未配置，暂不能生成可执行 SQL。"
        for name in missing_mappings
    ]
    issues.extend(
        f"指标计算需要“{_field_desc(business_fields, name)}”（{name}），"
        f"当前映射 {mapped_fields.get(name, '-')} 尚未确认，暂不能生成可执行 SQL。"
        for name in unconfirmed_mappings
    )
    issues.extend(
        f"本院映射字段 {column} 在最新数据库元数据中不存在，请先同步元数据或修正映射。"
        for column in missing_columns
    )
    issues.extend(f"字段类型不兼容：{item}。" for item in type_mismatches)
    issues.extend(
        f"指标计算跨越 {relation}，但尚未确认两张表的关联字段。"
        for relation in missing_relations
    )
    return issues
