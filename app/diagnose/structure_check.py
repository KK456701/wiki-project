"""第一层：系统结构校验。"""

from pathlib import Path
from typing import Any

from sqlalchemy import Engine
from app.metadata.precheck import precheck_rule_fields


def structure_check(kb_root: Path, runtime_engine: Engine,
                    hospital_id: str, rule_id: str) -> dict[str, Any]:
    precheck = precheck_rule_fields(kb_root, runtime_engine, hospital_id, rule_id)
    if precheck.get("ok"):
        return {"ok": True, "layer": 1, "layer_name": "系统结构校验", "message": "字段映射和元数据结构正常"}
    return {
        "ok": False, "layer": 1, "layer_name": "系统结构校验",
        "diagnose_type": "字段问题",
        "problem_detail": f"缺失映射: {precheck.get('missing_mappings', [])}。缺失字段: {precheck.get('missing_columns', [])}",
        "repair_suggest": "请先完成医院字段映射，或重新同步元数据后重试。",
        "repair_sql": "",
    }
