"""第三层：原始数据校验。"""

from pathlib import Path
from typing import Any

from sqlalchemy import Engine, text
import yaml


def load_yaml(path: Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def data_check(kb_root: Path, business_engine: Engine,
               hospital_id: str, rule_id: str) -> dict[str, Any]:
    mapping_path = kb_root / "hospital-mappings" / hospital_id / f"{rule_id}.yaml"
    if not mapping_path.exists():
        return {"ok": True, "layer": 3, "layer_name": "原始数据校验", "message": "无映射文件，跳过数据检查"}

    mapping = load_yaml(mapping_path)
    main_table = mapping.get("main_table", "")
    fields = mapping.get("fields", {})
    issues = []

    for bf_name, col_ref in fields.items():
        parts = col_ref.split(".")
        col = parts[-1] if parts else col_ref
        try:
            with business_engine.connect() as conn:
                row = conn.execute(
                    text(f"SELECT COUNT(*) as total, SUM(CASE WHEN `{col}` IS NULL THEN 1 ELSE 0 END) as nulls FROM `{main_table}`")
                ).fetchone()
                if row:
                    total, nulls = row[0], row[1]
                    if total > 0 and nulls and nulls / total >= 0.5:
                        issues.append(f"{col}: {nulls}/{total} 为空 ({round(nulls/total*100)}%)")
        except Exception:
            pass

    if issues:
        return {
            "ok": False, "layer": 3, "layer_name": "原始数据校验",
            "diagnose_type": "数据问题",
            "problem_detail": "以下字段空值率超过50%: " + ", ".join(issues),
            "repair_suggest": "请检查医院业务系统数据质量，补充缺失数据后重试。",
            "repair_sql": "",
        }
    return {"ok": True, "layer": 3, "layer_name": "原始数据校验", "message": "数据质量正常"}
