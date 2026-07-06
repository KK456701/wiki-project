
from __future__ import annotations

import io
import json
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any

import yaml

from app.kb.tools import DEFAULT_KB_ROOT, KnowledgeBaseTools


def export_hospital_kb_zip(kb_root: str | Path = DEFAULT_KB_ROOT, hospital_id: str = "hospital_001") -> bytes:
    root = Path(kb_root)
    tools = KnowledgeBaseTools(root)
    overrides = _collect_hospital_overrides(root, tools, hospital_id)
    mappings = _collect_hospital_mappings(root, hospital_id)
    manifest = {
        "hospital_id": hospital_id,
        "exported_at": datetime.now().isoformat(timespec="seconds"),
        "format_version": "kb-export-v1",
        "override_count": len(overrides),
        "mapping_count": len(mappings),
    }

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("manifest.yaml", yaml.safe_dump(manifest, allow_unicode=True, sort_keys=False))
        for rule_id, payload in overrides.items():
            zf.writestr(f"overrides/{rule_id}.yaml", yaml.safe_dump(payload, allow_unicode=True, sort_keys=False))
        for rule_id, text in mappings.items():
            zf.writestr(f"mappings/{rule_id}.yaml", text)
    return buffer.getvalue()


def _collect_hospital_overrides(root: Path, tools: KnowledgeBaseTools, hospital_id: str) -> dict[str, dict[str, Any]]:
    index_path = root / "indexes" / "hospital_override_index.json"
    if not index_path.exists():
        return {}
    index = json.loads(index_path.read_text(encoding="utf-8"))
    result: dict[str, dict[str, Any]] = {}
    for item in index.get("hospital_overrides", []):
        if item.get("hospital_id") != hospital_id or item.get("status") != "approved":
            continue
        rule_id = str(item.get("rule_id") or "")
        if not rule_id:
            continue
        effective = tools.get_effective_rule(rule_id, hospital_id)
        result[rule_id] = {
            "rule_id": rule_id,
            "rule_name": effective.get("rule_name", ""),
            "hospital_id": hospital_id,
            "effective_level": effective.get("effective_level", ""),
            "definition": effective.get("definition", ""),
            "formula": effective.get("formula", ""),
            "implementation_status": effective.get("implementation_status", ""),
            "active_version_id": item.get("active_version_id") or item.get("version") or "",
            "source_path": item.get("path", ""),
        }
    return result


def _collect_hospital_mappings(root: Path, hospital_id: str) -> dict[str, str]:
    mapping_dir = root / "hospital-mappings" / hospital_id
    if not mapping_dir.exists():
        return {}
    result: dict[str, str] = {}
    for path in sorted(mapping_dir.glob("*.yaml")):
        result[path.stem] = path.read_text(encoding="utf-8")
    return result
