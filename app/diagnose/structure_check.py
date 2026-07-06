"""Layer 1: system structure and metadata checks."""

from pathlib import Path
from typing import Any

from sqlalchemy import Engine, text

from app.sqlgen.spec_loader import find_spec_dir, load_field_contract, load_hospital_mapping, load_template

LAYER_NAME = "\u7cfb\u7edf\u7ed3\u6784\u6821\u9a8c"
TYPE_FAIL = "\u7ed3\u6784\u6821\u9a8c\u5931\u8d25"
TYPE_OK = "\u7ed3\u6784\u6821\u9a8c\u901a\u8fc7"


def _check(name: str, status: str, message: str, repair_suggest: str = "") -> dict[str, str]:
    return {"name": name, "status": status, "message": message, "repair_suggest": repair_suggest}


def _normalize_col_ref(col_ref: str, fallback_table: str) -> tuple[str, str]:
    parts = [p for p in str(col_ref or "").split(".") if p]
    if len(parts) >= 2:
        return parts[-2], parts[-1]
    return fallback_table, parts[0] if parts else ""


def _metadata_row(runtime_engine: Engine, hospital_id: str, db_name: str, table_name: str, column_name: str) -> dict[str, Any] | None:
    with runtime_engine.connect() as conn:
        row = conn.execute(
            text("SELECT data_type, column_type, is_nullable FROM med_metadata_column WHERE hospital_id=:h AND table_name=:t AND column_name=:c LIMIT 1"),
            {"h": hospital_id, "t": table_name, "c": column_name},
        ).mappings().fetchone()
        if row:
            return dict(row)
        if db_name:
            row = conn.execute(
                text("SELECT data_type, column_type, is_nullable FROM med_metadata_column WHERE hospital_id=:h AND db_name=:d AND table_name=:t AND column_name=:c LIMIT 1"),
                {"h": hospital_id, "d": db_name, "t": table_name, "c": column_name},
            ).mappings().fetchone()
            return dict(row) if row else None
    return None


def structure_check(kb_root: Path, runtime_engine: Engine, hospital_id: str, rule_id: str) -> dict[str, Any]:
    kb_root = Path(kb_root)
    checks: list[dict[str, str]] = []
    spec_dir = find_spec_dir(kb_root, rule_id)
    if not spec_dir:
        checks.append(_check("sql_spec", "fail", f"SQL spec directory not found: {rule_id}", "Add the SQL spec directory first."))
        return _result(False, checks)

    checks.append(_check("sql_spec", "pass", f"SQL spec directory found: {spec_dir.name}"))
    try:
        contract = load_field_contract(kb_root, rule_id)
        business_fields = contract.get("business_fields") or {}
        if not business_fields:
            checks.append(_check("field_contract", "fail", "field_contract.yaml does not declare business_fields.", "Complete the field contract."))
        else:
            checks.append(_check("field_contract", "pass", f"Field contract declares {len(business_fields)} business fields."))
    except Exception as exc:
        checks.append(_check("field_contract", "fail", f"Failed to load field contract: {exc}", "Check field_contract.yaml."))
        return _result(False, checks)

    try:
        mapping = load_hospital_mapping(kb_root, hospital_id, rule_id)
        fields = mapping.get("fields") or {}
        checks.append(_check("hospital_mapping", "pass", f"Hospital mapping found: {hospital_id}/{rule_id}.yaml"))
    except Exception as exc:
        missing = ", ".join(business_fields.keys()) or "all fields"
        checks.append(_check("hospital_mapping", "fail", f"Failed to load hospital mapping: {exc}; missing mappings: {missing}", "Complete hospital field mapping first."))
        return _result(False, checks)

    dialect = mapping.get("dialect") or "mysql"
    try:
        load_template(kb_root, rule_id, dialect)
        checks.append(_check("sql_template", "pass", f"{dialect} SQL template found."))
    except Exception as exc:
        checks.append(_check("sql_template", "fail", f"SQL template missing: {exc}", "Add the template for this dialect."))

    db_name = str(mapping.get("db_name") or "")
    main_table = str(mapping.get("main_table") or "")
    if not main_table:
        checks.append(_check("main_table", "fail", "Hospital mapping does not configure main_table.", "Add main_table in the hospital mapping YAML."))

    for field_name, spec in business_fields.items():
        col_ref = fields.get(field_name)
        required = bool((spec or {}).get("required", False))
        if not col_ref:
            checks.append(_check(f"mapping.{field_name}", "fail" if required else "warn", f"Business field {field_name} has no hospital mapping.", "Complete field mapping and retry."))
            continue
        table_name, column_name = _normalize_col_ref(str(col_ref), main_table)
        row = _metadata_row(runtime_engine, hospital_id, db_name, table_name, column_name)
        if not row:
            checks.append(_check(f"metadata.{field_name}", "fail", f"Metadata missing column {table_name}.{column_name}.", "Sync hospital metadata again, or fix the mapping."))
            continue
        checks.append(_check(f"metadata.{field_name}", "pass", f"Metadata contains column {table_name}.{column_name}."))
        expected_type = str((spec or {}).get("type") or "").lower()
        actual_type = str(row.get("data_type") or row.get("column_type") or "").lower()
        if expected_type and actual_type and expected_type not in actual_type:
            compatible = expected_type == "string" and any(t in actual_type for t in ["char", "text", "varchar"])
            compatible = compatible or expected_type == "datetime" and any(t in actual_type for t in ["date", "time"])
            if not compatible:
                checks.append(_check(f"type.{field_name}", "warn", f"Column {column_name} type may mismatch: expected {expected_type}, actual {actual_type}.", "Manually confirm whether this field can be used."))
        if required and str(row.get("is_nullable") or "").upper() == "YES":
            checks.append(_check(f"nullable.{field_name}", "warn", f"Required business field {column_name} is nullable in metadata.", "Focus on null rate in data quality checks."))

    return _result(not any(c["status"] == "fail" for c in checks), checks)


def _result(ok: bool, checks: list[dict[str, str]]) -> dict[str, Any]:
    failed = [c for c in checks if c["status"] == "fail"]
    return {
        "ok": ok,
        "layer": 1,
        "layer_name": LAYER_NAME,
        "checks": checks,
        "diagnose_type": TYPE_FAIL if failed else TYPE_OK,
        "problem_detail": "; ".join(c["message"] for c in failed),
        "repair_suggest": "; ".join(c["repair_suggest"] for c in failed if c.get("repair_suggest")),
        "repair_sql": "",
    }
