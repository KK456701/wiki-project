"""Layer 3: raw data quality checks."""

from pathlib import Path
from typing import Any

from sqlalchemy import Engine, text
import yaml

from app.sqlgen.spec_loader import load_field_contract, load_hospital_mapping

LAYER_NAME = "\u6570\u636e\u8d28\u91cf\u6821\u9a8c"
TYPE_ACCESS_FAIL = "\u6570\u636e\u8bbf\u95ee\u5931\u8d25"
TYPE_RISK = "\u6570\u636e\u8d28\u91cf\u98ce\u9669"
TYPE_OK = "\u6570\u636e\u8d28\u91cf\u6b63\u5e38"


def load_yaml(path: Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def _check(name: str, status: str, message: str, repair_suggest: str = "") -> dict[str, str]:
    return {"name": name, "status": status, "message": message, "repair_suggest": repair_suggest}


def _quote_ident(name: str, dialect: str = "") -> str:
    raw = str(name or "")
    if str(dialect or "").lower() in {"mysql", "mariadb"}:
        safe = raw.replace("`", "``")
        return f"`{safe}`"
    safe = raw.replace('"', '""')
    return f'"{safe}"'


def _normalize_col_ref(col_ref: str, fallback_table: str) -> tuple[str, str]:
    parts = [p for p in str(col_ref or "").split(".") if p]
    if len(parts) >= 2:
        return parts[-2], parts[-1]
    return fallback_table, parts[0] if parts else ""


def data_check(kb_root: Path, business_engine: Engine, hospital_id: str, rule_id: str) -> dict[str, Any]:
    checks: list[dict[str, str]] = []
    try:
        mapping = load_hospital_mapping(Path(kb_root), hospital_id, rule_id)
        contract = load_field_contract(Path(kb_root), rule_id)
    except Exception as exc:
        checks.append(_check("data_prerequisite", "warn", f"Cannot load data-check config: {exc}", "Complete field contract and hospital mapping first."))
        return _result(True, checks)

    main_table = str(mapping.get("main_table") or "")
    dialect = str(mapping.get("dialect") or getattr(business_engine.dialect, "name", ""))
    fields = mapping.get("fields") or {}
    business_fields = contract.get("business_fields") or {}
    if not main_table:
        checks.append(_check("main_table", "fail", "Hospital mapping does not configure main_table.", "Add main_table and retry."))
        return _result(False, checks)

    try:
        with business_engine.connect() as conn:
            total = conn.execute(text(f"SELECT COUNT(*) FROM {_quote_ident(main_table, dialect)}")).scalar_one()
    except Exception as exc:
        checks.append(_check("main_table_access", "fail", f"Cannot access business main table {main_table}: {exc}", "Check business DB connection, table name, and permissions."))
        return _result(False, checks)

    if total == 0:
        checks.append(_check("table_rows", "warn", f"Business main table {main_table} has no rows.", "Check statistic period or data sync state."))
        return _result(True, checks)
    if total < 10:
        checks.append(_check("table_rows", "warn", f"Business main table sample is small: {total} rows.", "Confirm this test DB or statistic period has enough coverage."))
    else:
        checks.append(_check("table_rows", "pass", f"Business main table is accessible with {total} rows."))

    for field_name, spec in business_fields.items():
        col_ref = fields.get(field_name)
        if not col_ref:
            continue
        table_name, column_name = _normalize_col_ref(str(col_ref), main_table)
        if table_name != main_table:
            checks.append(_check(f"field.{field_name}", "warn", f"Field {field_name} maps to non-main table {table_name}.{column_name}; MVP checks main-table fields only.", "Extend joined-table quality checks later if needed."))
            continue
        required = bool((spec or {}).get("required", False))
        field_type = str((spec or {}).get("type") or "").lower()
        try:
            with business_engine.connect() as conn:
                row = conn.execute(text(f"SELECT COUNT(*) AS total, SUM(CASE WHEN {_quote_ident(column_name, dialect)} IS NULL THEN 1 ELSE 0 END) AS nulls FROM {_quote_ident(main_table, dialect)}")).mappings().one()
        except Exception as exc:
            checks.append(_check(f"field.{field_name}", "fail" if required else "warn", f"Failed to inspect column {column_name}: {exc}", "Confirm the business table column exists."))
            continue
        nulls = int(row.get("nulls") or 0)
        row_total = int(row.get("total") or total or 0)
        null_rate = nulls / max(row_total, 1)
        if required and null_rate >= 0.5:
            checks.append(_check(f"null_rate.{field_name}", "warn", f"Required column {column_name} has high null rate: {nulls}/{row_total} ({null_rate:.0%}).", "Check source data quality or whether the mapped field is correct."))
        elif null_rate > 0.3:
            checks.append(_check(f"null_rate.{field_name}", "warn", f"Column {column_name} has elevated null rate: {nulls}/{row_total} ({null_rate:.0%}).", "Confirm whether this field participates in filters or grouping."))
        else:
            checks.append(_check(f"null_rate.{field_name}", "pass", f"Column {column_name} null rate is acceptable: {nulls}/{row_total} ({null_rate:.0%})."))
        if field_type == "datetime" and nulls > 0:
            checks.append(_check(f"datetime.{field_name}", "warn", f"Datetime column {column_name} contains nulls: {nulls}/{row_total}.", "Datetime nulls may affect numerator, denominator, or statistic period."))

    return _result(not any(c["status"] == "fail" for c in checks), checks)


def _result(ok: bool, checks: list[dict[str, str]]) -> dict[str, Any]:
    failed = [c for c in checks if c["status"] == "fail"]
    warnings = [c for c in checks if c["status"] == "warn"]
    return {
        "ok": ok,
        "layer": 3,
        "layer_name": LAYER_NAME,
        "checks": checks,
        "diagnose_type": TYPE_ACCESS_FAIL if failed else (TYPE_RISK if warnings else TYPE_OK),
        "problem_detail": "; ".join(c["message"] for c in failed + warnings),
        "repair_suggest": "; ".join(c["repair_suggest"] for c in failed + warnings if c.get("repair_suggest")),
        "repair_sql": "",
    }
