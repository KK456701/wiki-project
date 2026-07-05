"""字段预校验服务。"""

from pathlib import Path
from typing import Any
import yaml

from sqlalchemy import Engine, text


def load_yaml(path: Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def find_spec_dir(kb_root: Path, rule_id: str) -> Path | None:
    for d in (kb_root / "sql-specs").iterdir():
        if d.is_dir() and d.name.startswith(rule_id):
            return d
    return None


def precheck_rule_fields(kb_root: Path, runtime_engine: Engine,
                          hospital_id: str, rule_id: str) -> dict[str, Any]:
    spec_dir = find_spec_dir(kb_root, rule_id)
    if not spec_dir:
        return {"ok": False, "error": f"未找到 SQL 规格: {rule_id}"}

    contract = load_yaml(spec_dir / "field_contract.yaml")
    mapping_path = kb_root / "hospital-mappings" / hospital_id / f"{rule_id}.yaml"
    if not mapping_path.exists():
        return {"ok": False, "error": f"未找到医院字段映射: {hospital_id}/{rule_id}.yaml",
                "missing_mappings": list(contract.get("business_fields", {}).keys())}
    mapping = load_yaml(mapping_path)

    missing_mappings = []
    missing_columns = []

    for bf_name, bf_spec in contract.get("business_fields", {}).items():
        mapped_col = (mapping.get("fields") or {}).get(bf_name, "")
        if not mapped_col:
            missing_mappings.append(bf_name)
            continue
        parts = mapped_col.split(".")
        tbl, col = (parts[0], parts[1]) if len(parts) == 2 else ("", mapped_col)
        if tbl and col:
            with runtime_engine.connect() as conn:
                row = conn.execute(
                    text("SELECT 1 FROM med_metadata_column WHERE hospital_id=:h AND table_name=:t AND column_name=:c LIMIT 1"),
                    {"h": hospital_id, "t": tbl, "c": col}).fetchone()
                if not row:
                    missing_columns.append(mapped_col)

    ok = len(missing_mappings) == 0 and len(missing_columns) == 0
    return {
        "ok": ok,
        "missing_mappings": missing_mappings,
        "missing_columns": missing_columns,
        "dialect": mapping.get("dialect", "mysql"),
        "db_name": mapping.get("db_name", ""),
        "main_table": mapping.get("main_table", ""),
        "field_mapping": mapping.get("fields", {}),
        "filters": mapping.get("filters", {}),
    }
