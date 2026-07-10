from __future__ import annotations

import hashlib
import io
import json
import uuid
import zipfile
from datetime import datetime
from typing import Any

import yaml
from sqlalchemy import Engine, text


def export_hospital_kb_zip(runtime_engine: Engine, hospital_id: str) -> bytes:
    """从医院运行库生成不含患者数据的知识交换快照。"""

    exported_at = datetime.now()
    overrides = _collect_hospital_overrides(runtime_engine, hospital_id, exported_at)
    mappings = _collect_hospital_mappings(runtime_engine, hospital_id)
    package_id = f"HKB_{exported_at.strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:8]}"
    manifest = {
        "package_id": package_id,
        "hospital_id": hospital_id,
        "exported_at": exported_at.isoformat(timespec="seconds"),
        "format_version": "kb-exchange-v2",
        "override_count": len(overrides),
        "mapping_count": len(mappings),
        "contains_patient_data": False,
    }

    files: dict[str, bytes] = {"manifest.yaml": _yaml_bytes(manifest)}
    for rule_id, payload in overrides.items():
        files[f"overrides/{rule_id}.yaml"] = _yaml_bytes(payload)
    for rule_id, payload in mappings.items():
        files[f"mappings/{rule_id}.yaml"] = _yaml_bytes(payload)

    checksums = {
        name: hashlib.sha256(content).hexdigest()
        for name, content in sorted(files.items())
    }
    files["checksums.json"] = json.dumps(
        checksums, ensure_ascii=False, indent=2, sort_keys=True
    ).encode("utf-8")

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name, content in sorted(files.items()):
            zf.writestr(name, content)
    return buffer.getvalue()


def _collect_hospital_overrides(
    runtime_engine: Engine, hospital_id: str, now: datetime
) -> dict[str, dict[str, Any]]:
    with runtime_engine.connect() as conn:
        rows = conn.execute(
            text(
                """
                SELECT c.index_code, c.custom_numerator, c.custom_denominator,
                       c.custom_filter, c.exclude_rule, c.custom_params,
                       c.custom_sql, c.version AS hospital_version,
                       c.effective_from, c.effective_to, c.oper_user,
                       c.update_time, s.index_name, s.index_type, s.index_desc,
                       s.numerator_rule, s.denominator_rule, s.filter_rule,
                       s.exclude_rule AS standard_exclude_rule,
                       s.version AS base_standard_version
                FROM med_index_hospital_custom c
                JOIN med_index_standard s ON s.index_code=c.index_code
                WHERE c.hospital_id=:hospital_id
                  AND c.status=1
                  AND c.approval_status='approved'
                  AND s.status=1
                  AND (c.effective_from IS NULL OR c.effective_from<=:now)
                  AND (c.effective_to IS NULL OR c.effective_to>=:now)
                ORDER BY c.index_code
                """
            ),
            {"hospital_id": hospital_id, "now": now},
        ).mappings().all()

    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        item = dict(row)
        numerator = str(item.get("custom_numerator") or item.get("numerator_rule") or "")
        denominator = str(
            item.get("custom_denominator") or item.get("denominator_rule") or ""
        )
        rule_id = str(item["index_code"])
        result[rule_id] = {
            "rule_id": rule_id,
            "rule_name": str(item.get("index_name") or rule_id),
            "hospital_id": hospital_id,
            "effective_level": "hospital",
            "base_standard_version": str(item.get("base_standard_version") or ""),
            "hospital_version": int(item.get("hospital_version") or 0),
            "definition": str(item.get("index_desc") or ""),
            "formula": _formula(str(item.get("index_name") or rule_id), numerator, denominator),
            "custom_numerator": item.get("custom_numerator"),
            "custom_denominator": item.get("custom_denominator"),
            "custom_filter": item.get("custom_filter"),
            "exclude_rule": item.get("exclude_rule"),
            "custom_params": _json_dict(item.get("custom_params")),
            "custom_sql": item.get("custom_sql"),
            "effective_from": _iso_value(item.get("effective_from")),
            "effective_to": _iso_value(item.get("effective_to")),
            "updated_by": str(item.get("oper_user") or ""),
            "updated_at": _iso_value(item.get("update_time")),
        }
    return result


def _collect_hospital_mappings(
    runtime_engine: Engine, hospital_id: str
) -> dict[str, dict[str, Any]]:
    with runtime_engine.connect() as conn:
        rows = conn.execute(
            text(
                """
                SELECT rule_id, business_field, db_name, table_name, column_name,
                       data_type, updated_by, updated_at
                FROM med_field_mapping
                WHERE hospital_id=:hospital_id AND status='confirmed'
                ORDER BY rule_id, business_field
                """
            ),
            {"hospital_id": hospital_id},
        ).mappings().all()

    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        rule_id = str(row["rule_id"])
        payload = result.setdefault(
            rule_id,
            {
                "hospital_id": hospital_id,
                "rule_id": rule_id,
                "status": "confirmed",
                "fields": {},
            },
        )
        payload["fields"][str(row["business_field"])] = {
            "db_name": str(row["db_name"]),
            "table_name": str(row["table_name"]),
            "column_name": str(row["column_name"]),
            "data_type": str(row.get("data_type") or ""),
            "updated_by": str(row.get("updated_by") or ""),
            "updated_at": _iso_value(row.get("updated_at")),
        }
    return result


def _formula(name: str, numerator: str, denominator: str) -> str:
    return f"{name} = ({numerator} / {denominator}) × 100%"


def _json_dict(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if not value:
        return {}
    try:
        parsed = json.loads(str(value))
    except (TypeError, ValueError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _iso_value(value: Any) -> str | None:
    if value is None:
        return None
    if hasattr(value, "isoformat"):
        return value.isoformat(timespec="seconds")
    return str(value)


def _yaml_bytes(payload: dict[str, Any]) -> bytes:
    return yaml.safe_dump(payload, allow_unicode=True, sort_keys=False).encode("utf-8")
