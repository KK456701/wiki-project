"""SQL 规格和模板加载器。"""

from pathlib import Path
from typing import Any
import yaml


def load_yaml(path: Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def find_spec_dir(kb_root: Path, rule_id: str) -> Path | None:
    d = kb_root / "sql-specs"
    if not d.exists():
        return None
    for child in d.iterdir():
        if child.is_dir() and child.name.startswith(rule_id):
            return child
    return None


def load_rule_sql_spec(kb_root: Path, rule_id: str) -> dict[str, Any]:
    d = find_spec_dir(kb_root, rule_id)
    if not d:
        raise FileNotFoundError(f"SQL spec not found: {rule_id}")
    return load_yaml(d / "rule_sql_spec.yaml")


def load_field_contract(kb_root: Path, rule_id: str) -> dict[str, Any]:
    d = find_spec_dir(kb_root, rule_id)
    if not d:
        raise FileNotFoundError(f"SQL spec not found: {rule_id}")
    return load_yaml(d / "field_contract.yaml")


def load_hospital_mapping(kb_root: Path, hospital_id: str, rule_id: str) -> dict[str, Any]:
    path = kb_root / "hospital-mappings" / hospital_id / f"{rule_id}.yaml"
    if not path.exists():
        raise FileNotFoundError(f"Hospital mapping not found: {hospital_id}/{rule_id}")
    return load_yaml(path)


def load_template(kb_root: Path, rule_id: str, dialect: str) -> str:
    d = find_spec_dir(kb_root, rule_id)
    if not d:
        raise FileNotFoundError(f"SQL spec not found: {rule_id}")
    tpl_path = d / "templates" / f"{dialect}.sql.j2"
    if not tpl_path.exists():
        raise FileNotFoundError(f"Template not found: {rule_id}/{dialect}")
    return tpl_path.read_text(encoding="utf-8")
