from __future__ import annotations

import hashlib
import io
import json
import uuid
import zipfile
from datetime import datetime
from typing import Any

import yaml
from sqlalchemy import Engine, inspect, text

from app.kb.scope import MetadataExportScopeError, MetadataExportScopeRepository
from app.kb.signing import PackageSigner


def export_hospital_kb_zip(
    runtime_engine: Engine,
    hospital_id: str,
    db_name: str | None = None,
    signer: PackageSigner | None = None,
    actor_id: str = "system",
) -> bytes:
    """从医院运行库生成不含患者数据的知识交换快照。"""

    exported_at = datetime.now()
    signed_export = db_name is not None or signer is not None
    if signed_export and (not db_name or signer is None):
        raise MetadataExportScopeError("PACKAGE_SIGNING_CONFIGURATION_INCOMPLETE")
    selected_fields: set[tuple[str, str]] | None = None
    metadata_payload: dict[str, Any] | None = None
    relations: list[dict[str, Any]] = []
    validations: dict[str, dict[str, Any]] = {}
    if signed_export:
        scope_repository = MetadataExportScopeRepository(runtime_engine)
        selected_fields = scope_repository.selected_fields(hospital_id, str(db_name))
        if not selected_fields:
            raise MetadataExportScopeError("METADATA_EXPORT_SCOPE_EMPTY")
        metadata_payload = _collect_export_metadata(
            runtime_engine, hospital_id, str(db_name), selected_fields
        )
        relations = _collect_export_relations(
            runtime_engine, hospital_id, str(db_name), selected_fields
        )
        validations = _collect_validation_feedback(runtime_engine, hospital_id)
    overrides = _collect_hospital_overrides(
        runtime_engine, hospital_id, exported_at, include_sql=not signed_export
    )
    mappings = _collect_hospital_mappings(
        runtime_engine, hospital_id, selected_fields=selected_fields
    )
    term_mappings = _collect_hospital_term_mappings(runtime_engine, hospital_id)
    term_candidates = _collect_hospital_term_candidates(runtime_engine, hospital_id)
    package_id = f"HKB_{exported_at.strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:8]}"
    manifest = {
        "package_id": package_id,
        "hospital_id": hospital_id,
        "exported_at": exported_at.isoformat(timespec="seconds"),
        "format_version": "kb-exchange-v4" if signed_export else "kb-exchange-v3",
        "override_count": len(overrides),
        "mapping_count": len(mappings),
        "term_mapping_count": len(term_mappings),
        "term_candidate_count": len(term_candidates),
        "contains_patient_data": False,
    }
    if signed_export and metadata_payload is not None and signer is not None:
        manifest.update(
            {
                "db_name": db_name,
                "metadata_table_count": len(metadata_payload["tables"]),
                "metadata_column_count": len(metadata_payload["columns"]),
                "relation_count": len(relations),
                "validation_count": len(validations),
                "signature_algorithm": "Ed25519",
                "signer_key_id": signer.key_id,
                "contains_full_sql": False,
            }
        )

    files: dict[str, bytes] = {"manifest.yaml": _yaml_bytes(manifest)}
    for rule_id, payload in overrides.items():
        files[f"overrides/{rule_id}.yaml"] = _yaml_bytes(payload)
    for rule_id, payload in mappings.items():
        files[f"mappings/{rule_id}.yaml"] = _yaml_bytes(payload)
    for mapping_id, payload in term_mappings.items():
        files[f"terminology/mappings/mapping_{mapping_id}.yaml"] = _yaml_bytes(payload)
    for alias_id, payload in term_candidates.items():
        files[f"terminology/candidates/alias_{alias_id}.yaml"] = _yaml_bytes(payload)
    if signed_export and metadata_payload is not None:
        files[f"metadata/{db_name}.yaml"] = _yaml_bytes(metadata_payload)
        files["metadata/relations.yaml"] = _yaml_bytes({"relations": relations})
        for rule_id, payload in validations.items():
            files[f"validation/{rule_id}.yaml"] = _yaml_bytes(payload)

    checksums = {
        name: hashlib.sha256(content).hexdigest()
        for name, content in sorted(files.items())
    }
    checksum_bytes = json.dumps(
        checksums, ensure_ascii=False, indent=2, sort_keys=True
    ).encode("utf-8")
    files["checksums.json"] = checksum_bytes
    if signed_export and signer is not None:
        files["signature.json"] = json.dumps(
            signer.sign_checksums(checksum_bytes),
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        ).encode("utf-8")

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name, content in sorted(files.items()):
            zf.writestr(name, content)
    result = buffer.getvalue()
    if signed_export:
        _record_export_audit(
            runtime_engine,
            package_id,
            hospital_id,
            actor_id,
            str(db_name),
            len(selected_fields or set()),
        )
    return result


def _collect_hospital_overrides(
    runtime_engine: Engine,
    hospital_id: str,
    now: datetime,
    include_sql: bool = True,
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
        payload = {
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
            "effective_from": _iso_value(item.get("effective_from")),
            "effective_to": _iso_value(item.get("effective_to")),
            "updated_by": str(item.get("oper_user") or ""),
            "updated_at": _iso_value(item.get("update_time")),
        }
        if include_sql:
            payload["custom_sql"] = item.get("custom_sql")
        result[rule_id] = payload
    return result


def _collect_hospital_mappings(
    runtime_engine: Engine,
    hospital_id: str,
    selected_fields: set[tuple[str, str]] | None = None,
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
        table_column = (str(row["table_name"]), str(row["column_name"]))
        if selected_fields is not None and table_column not in selected_fields:
            continue
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


def _collect_export_metadata(
    runtime_engine: Engine,
    hospital_id: str,
    db_name: str,
    selected_fields: set[tuple[str, str]],
) -> dict[str, Any]:
    with runtime_engine.connect() as conn:
        table_rows = conn.execute(
            text(
                """
                SELECT table_name, table_comment, table_type
                FROM med_metadata_table
                WHERE hospital_id=:hospital_id AND db_name=:db_name
                ORDER BY table_name
                """
            ),
            {"hospital_id": hospital_id, "db_name": db_name},
        ).mappings().all()
        column_rows = conn.execute(
            text(
                """
                SELECT table_name, column_name, data_type, column_type,
                       is_nullable, column_key, column_comment
                FROM med_metadata_column
                WHERE hospital_id=:hospital_id AND db_name=:db_name
                ORDER BY table_name, column_name
                """
            ),
            {"hospital_id": hospital_id, "db_name": db_name},
        ).mappings().all()
    selected_tables = {table for table, _ in selected_fields}
    tables = [
        {key: value for key, value in dict(row).items()}
        for row in table_rows
        if str(row["table_name"]) in selected_tables
    ]
    columns = [
        {key: value for key, value in dict(row).items()}
        for row in column_rows
        if (str(row["table_name"]), str(row["column_name"])) in selected_fields
    ]
    return {
        "hospital_id": hospital_id,
        "db_name": db_name,
        "tables": tables,
        "columns": columns,
        "contains_data_rows": False,
        "contains_sample_values": False,
        "contains_column_defaults": False,
    }


def _collect_export_relations(
    runtime_engine: Engine,
    hospital_id: str,
    db_name: str,
    selected_fields: set[tuple[str, str]],
) -> list[dict[str, Any]]:
    if not inspect(runtime_engine).has_table("med_table_relation"):
        return []
    with runtime_engine.connect() as conn:
        rows = conn.execute(
            text(
                """
                SELECT left_table, left_column, right_table, right_column,
                       join_type, relation_source
                FROM med_table_relation
                WHERE hospital_id=:hospital_id AND db_name=:db_name
                  AND status='confirmed'
                ORDER BY left_table, left_column, right_table, right_column
                """
            ),
            {"hospital_id": hospital_id, "db_name": db_name},
        ).mappings().all()
    return [
        dict(row)
        for row in rows
        if (str(row["left_table"]), str(row["left_column"])) in selected_fields
        and (str(row["right_table"]), str(row["right_column"])) in selected_fields
    ]


def _collect_validation_feedback(
    runtime_engine: Engine, hospital_id: str
) -> dict[str, dict[str, Any]]:
    if not inspect(runtime_engine).has_table("med_sql_run_log"):
        return {}
    with runtime_engine.connect() as conn:
        rows = conn.execute(
            text(
                """
                SELECT run_id, rule_id, stat_start_time, stat_end_time,
                       run_status, result_value, duration_ms,
                       numerator_count, denominator_count, run_time
                FROM med_sql_run_log
                WHERE hospital_id=:hospital_id AND run_status='success'
                ORDER BY run_time DESC, run_id DESC
                """
            ),
            {"hospital_id": hospital_id},
        ).mappings().all()
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        rule_id = str(row["rule_id"])
        if rule_id in result:
            continue
        result[rule_id] = {
            "hospital_id": hospital_id,
            "rule_id": rule_id,
            "run_id": str(row["run_id"]),
            "status": "success",
            "stat_start_time": _iso_value(row.get("stat_start_time")),
            "stat_end_time": _iso_value(row.get("stat_end_time")),
            "result_value": float(row["result_value"]) if row.get("result_value") is not None else None,
            "numerator_count": int(row["numerator_count"]) if row.get("numerator_count") is not None else None,
            "denominator_count": int(row["denominator_count"]) if row.get("denominator_count") is not None else None,
            "duration_ms": int(row["duration_ms"]) if row.get("duration_ms") is not None else None,
            "run_time": _iso_value(row.get("run_time")),
            "contains_patient_data": False,
        }
    return result


def _record_export_audit(
    runtime_engine: Engine,
    package_id: str,
    hospital_id: str,
    actor_id: str,
    db_name: str,
    selected_column_count: int,
) -> None:
    with runtime_engine.begin() as conn:
        conn.execute(
            text(
                """
                INSERT INTO med_package_audit
                  (direction, package_id, hospital_id, event_type, status,
                   actor_id, detail_json, created_at, message)
                VALUES
                  ('hospital_to_company', :package_id, :hospital_id, 'export',
                   'success', :actor_id, :detail_json, :created_at, :message)
                """
            ),
            {
                "package_id": package_id,
                "hospital_id": hospital_id,
                "actor_id": actor_id,
                "detail_json": json.dumps(
                    {"db_name": db_name, "selected_column_count": selected_column_count},
                    ensure_ascii=False,
                ),
                "created_at": datetime.now(),
                "message": "医院反馈包导出成功",
            },
        )


def _collect_hospital_term_mappings(
    runtime_engine: Engine, hospital_id: str
) -> dict[str, dict[str, Any]]:
    if not inspect(runtime_engine).has_table("med_hospital_term_mapping"):
        return {}
    with runtime_engine.connect() as conn:
        rows = conn.execute(
            text(
                """
                SELECT id, hospital_id, concept_code, code_system, local_code,
                       local_name, local_value, approval_status, effective_from,
                       effective_to, version, created_by, approved_by,
                       created_at, approved_at
                FROM med_hospital_term_mapping
                WHERE hospital_id=:hospital_id AND approval_status='approved'
                ORDER BY concept_code, id
                """
            ),
            {"hospital_id": hospital_id},
        ).mappings().all()
    return {
        str(row["id"]): {
            key: _iso_value(value) if key.endswith("_at") or key.startswith("effective_") else value
            for key, value in dict(row).items()
        }
        for row in rows
    }


def _collect_hospital_term_candidates(
    runtime_engine: Engine, hospital_id: str
) -> dict[str, dict[str, Any]]:
    if not inspect(runtime_engine).has_table("med_term_alias"):
        return {}
    with runtime_engine.connect() as conn:
        rows = conn.execute(
            text(
                """
                SELECT id, hospital_id, concept_code, alias_text, relation_type,
                       retrieval_enabled, sql_safe, ambiguity_group,
                       source_reference, approval_status, version, created_by,
                       created_at
                FROM med_term_alias
                WHERE hospital_id=:hospital_id AND approval_status='pending'
                ORDER BY concept_code, id
                """
            ),
            {"hospital_id": hospital_id},
        ).mappings().all()
    return {
        str(row["id"]): {
            **dict(row),
            "retrieval_enabled": bool(row["retrieval_enabled"]),
            "sql_safe": bool(row["sql_safe"]),
            "created_at": _iso_value(row["created_at"]),
        }
        for row in rows
    }


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
