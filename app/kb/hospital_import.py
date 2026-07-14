from __future__ import annotations

import hashlib
import io
import json
import uuid
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any

import yaml
from sqlalchemy import Engine, text

from app.kb.exchange_schema import ensure_kb_exchange_schema
from app.kb.signing import PackageSignatureError, verify_checksums


MAX_ZIP_BYTES = 20 * 1024 * 1024
MAX_EXTRACTED_BYTES = 80 * 1024 * 1024
ALLOWED_SUFFIXES = {".yaml", ".yml", ".json", ".md", ".txt", ".j2", ".sql"}


class HospitalReleaseError(RuntimeError):
    pass


class HospitalReleaseRepository:
    def __init__(
        self,
        engine: Engine,
        trusted_company_keys_dir: str | Path,
        system_version: str = "0.1.0",
    ) -> None:
        self.engine = engine
        self.trusted_company_keys_dir = Path(trusted_company_keys_dir)
        self.system_version = system_version
        ensure_kb_exchange_schema(engine)

    def import_package(self, zip_bytes: bytes, imported_by: str) -> dict[str, Any]:
        manifest, files, signature_status = _read_release_package(
            zip_bytes, self.trusted_company_keys_dir
        )
        package_id = str(manifest.get("package_id") or manifest.get("release_id") or "")
        if not package_id:
            raise HospitalReleaseError("PACKAGE_ID_MISSING")
        package_checksum = hashlib.sha256(zip_bytes).hexdigest()
        with self.engine.connect() as conn:
            existing = conn.execute(
                text(
                    """SELECT import_id, package_checksum
                    FROM med_company_package_import WHERE package_id=:package_id"""
                ),
                {"package_id": package_id},
            ).mappings().first()
        if existing is not None:
            if str(existing["package_checksum"]) != package_checksum:
                raise HospitalReleaseError(f"PACKAGE_ID_CONFLICT: {package_id}")
            result = self.read_import(str(existing["import_id"]))
            result["duplicate"] = True
            return result

        compatibility_status, compatibility = _compatibility(
            manifest, self.system_version
        )
        if signature_status != "verified":
            status = "quarantined"
        elif compatibility_status != "compatible":
            status = "incompatible"
        else:
            status = "ready_for_adaptation"
        import_id = f"IMP_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:8]}"
        now = datetime.now()
        items = _package_items(files)
        signer_key_id = str(manifest.get("signer_key_id") or "")
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    """
                    INSERT INTO med_company_package_import
                      (import_id, package_id, release_id, format_version,
                       package_checksum, signer_key_id, signature_status,
                       compatibility_status, status, manifest_json,
                       compatibility_json, imported_by, imported_at)
                    VALUES
                      (:import_id, :package_id, :release_id, :format_version,
                       :package_checksum, :signer_key_id, :signature_status,
                       :compatibility_status, :status, :manifest_json,
                       :compatibility_json, :imported_by, :imported_at)
                    """
                ),
                {
                    "import_id": import_id,
                    "package_id": package_id,
                    "release_id": str(manifest.get("release_id") or ""),
                    "format_version": str(manifest["format_version"]),
                    "package_checksum": package_checksum,
                    "signer_key_id": signer_key_id,
                    "signature_status": signature_status,
                    "compatibility_status": compatibility_status,
                    "status": status,
                    "manifest_json": json.dumps(manifest, ensure_ascii=False),
                    "compatibility_json": json.dumps(compatibility, ensure_ascii=False),
                    "imported_by": imported_by,
                    "imported_at": now,
                },
            )
            for item in items:
                conn.execute(
                    text(
                        """
                        INSERT INTO med_company_package_item
                          (import_id, item_path, item_type, rule_id, payload_json)
                        VALUES
                          (:import_id, :item_path, :item_type, :rule_id, :payload_json)
                        """
                    ),
                    {
                        "import_id": import_id,
                        "item_path": item["item_path"],
                        "item_type": item["item_type"],
                        "rule_id": item.get("rule_id"),
                        "payload_json": json.dumps(item["payload"], ensure_ascii=False),
                    },
                )
            conn.execute(
                text(
                    """
                    INSERT INTO med_package_audit
                      (direction, package_id, hospital_id, event_type, status,
                       actor_id, detail_json, created_at, message)
                    VALUES
                      ('company_to_hospital', :package_id, NULL, 'import', :status,
                       :actor_id, :detail_json, :created_at, :message)
                    """
                ),
                {
                    "package_id": package_id,
                    "status": status,
                    "actor_id": imported_by,
                    "detail_json": json.dumps(
                        {
                            "signature_status": signature_status,
                            "compatibility_status": compatibility_status,
                            "item_count": len(items),
                        },
                        ensure_ascii=False,
                    ),
                    "created_at": now,
                    "message": "公司发布包已导入隔离区",
                },
            )
        result = self.read_import(import_id)
        result["duplicate"] = False
        return result

    def list_imports(self) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    """
                    SELECT import_id, package_id, release_id, format_version,
                           signer_key_id, signature_status, compatibility_status,
                           status, imported_by, imported_at
                    FROM med_company_package_import
                    ORDER BY imported_at DESC, import_id DESC
                    """
                )
            ).mappings().all()
        return [{key: _json_value(value) for key, value in dict(row).items()} for row in rows]

    def read_import(self, import_id: str) -> dict[str, Any]:
        with self.engine.connect() as conn:
            package = conn.execute(
                text(
                    "SELECT * FROM med_company_package_import WHERE import_id=:import_id"
                ),
                {"import_id": import_id},
            ).mappings().first()
            if package is None:
                raise HospitalReleaseError(f"HOSPITAL_RELEASE_IMPORT_NOT_FOUND: {import_id}")
            rows = conn.execute(
                text(
                    """SELECT item_path, item_type, rule_id, payload_json
                    FROM med_company_package_item
                    WHERE import_id=:import_id ORDER BY item_path"""
                ),
                {"import_id": import_id},
            ).mappings().all()
        result = {key: _json_value(value) for key, value in dict(package).items()}
        result["manifest"] = _json_load(result.pop("manifest_json"))
        result["compatibility"] = _json_load(result.pop("compatibility_json"))
        result["items"] = [
            {
                "item_path": str(row["item_path"]),
                "item_type": str(row["item_type"]),
                "rule_id": str(row.get("rule_id") or ""),
                "payload": _json_load(row["payload_json"]),
            }
            for row in rows
        ]
        return result


def _read_release_package(
    zip_bytes: bytes, trusted_company_keys_dir: Path
) -> tuple[dict[str, Any], dict[str, bytes], str]:
    if len(zip_bytes) > MAX_ZIP_BYTES:
        raise HospitalReleaseError("ZIP_TOO_LARGE")
    files: dict[str, bytes] = {}
    extracted = 0
    try:
        with zipfile.ZipFile(io.BytesIO(zip_bytes), "r") as zf:
            for info in zf.infolist():
                name = info.filename.replace("\\", "/")
                if not name or name.endswith("/"):
                    continue
                if name in files:
                    raise HospitalReleaseError(f"DUPLICATE_ZIP_ENTRY: {name}")
                if name.startswith("/") or ".." in Path(name).parts:
                    raise HospitalReleaseError("UNSAFE_ZIP_PATH")
                if Path(name).suffix.lower() not in ALLOWED_SUFFIXES:
                    raise HospitalReleaseError(f"UNSUPPORTED_FILE_TYPE: {name}")
                extracted += int(info.file_size or 0)
                if extracted > MAX_EXTRACTED_BYTES:
                    raise HospitalReleaseError("ZIP_EXTRACTED_CONTENT_TOO_LARGE")
                files[name] = zf.read(info)
    except zipfile.BadZipFile as exc:
        raise HospitalReleaseError("INVALID_ZIP_FILE") from exc
    if "manifest.yaml" not in files or "checksums.json" not in files:
        raise HospitalReleaseError("REQUIRED_FILE_MISSING")
    manifest = _yaml_dict(files["manifest.yaml"], "manifest.yaml")
    format_version = str(manifest.get("format_version") or "")
    if format_version not in {"company-release-v2", "company-release-v3"}:
        raise HospitalReleaseError("UNSUPPORTED_PACKAGE_FORMAT")
    if manifest.get("contains_patient_data") is not False:
        raise HospitalReleaseError("PATIENT_DATA_FLAG_NOT_SAFE")
    try:
        checksums = json.loads(files["checksums.json"].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise HospitalReleaseError("INVALID_CHECKSUM_FILE") from exc
    expected_names = set(files) - {"checksums.json", "signature.json"}
    if not isinstance(checksums, dict) or set(checksums) != expected_names:
        raise HospitalReleaseError("CHECKSUM_FILE_SET_MISMATCH")
    for name in sorted(expected_names):
        if hashlib.sha256(files[name]).hexdigest() != str(checksums[name]):
            raise HospitalReleaseError(f"CHECKSUM_MISMATCH: {name}")
    if format_version == "company-release-v2":
        return manifest, files, "legacy_unsigned"
    if "signature.json" not in files:
        raise HospitalReleaseError("PACKAGE_SIGNATURE_FILE_MISSING")
    try:
        signature_payload = json.loads(files["signature.json"].decode("utf-8"))
        verified = verify_checksums(
            files["checksums.json"], signature_payload, trusted_company_keys_dir
        )
    except (UnicodeDecodeError, json.JSONDecodeError, PackageSignatureError) as exc:
        raise HospitalReleaseError(str(exc)) from exc
    return manifest, files, verified["status"]


def _compatibility(manifest: dict[str, Any], system_version: str) -> tuple[str, dict[str, Any]]:
    versions = manifest.get("compatible_system_versions")
    if not isinstance(versions, list):
        return "review_required", {
            "system_version": system_version,
            "supported_versions": [],
            "message": "旧版包未声明系统兼容范围，需要人工复核。",
        }
    supported = [str(value) for value in versions]
    status = "compatible" if system_version in supported else "incompatible"
    return status, {
        "system_version": system_version,
        "supported_versions": supported,
        "message": "系统版本兼容。" if status == "compatible" else "系统版本不在发布包兼容范围内。",
    }


def _package_items(files: dict[str, bytes]) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    for name in sorted(files):
        if name.startswith("rules/") and name.endswith((".yaml", ".yml")):
            payload = _yaml_dict(files[name], name)
            items.append(
                {
                    "item_path": name,
                    "item_type": "rule",
                    "rule_id": str(payload.get("rule_id") or Path(name).stem),
                    "payload": payload,
                }
            )
        elif name.startswith("terminology/") and name.endswith(".json"):
            try:
                payload = json.loads(files[name].decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise HospitalReleaseError(f"INVALID_JSON: {name}") from exc
            items.append(
                {
                    "item_path": name,
                    "item_type": "terminology",
                    "rule_id": "",
                    "payload": payload,
                }
            )
    return items


def _yaml_dict(content: bytes, name: str) -> dict[str, Any]:
    try:
        payload = yaml.safe_load(content.decode("utf-8")) or {}
    except (UnicodeDecodeError, yaml.YAMLError) as exc:
        raise HospitalReleaseError(f"INVALID_YAML: {name}") from exc
    if not isinstance(payload, dict):
        raise HospitalReleaseError(f"YAML_OBJECT_REQUIRED: {name}")
    return payload


def _json_load(value: Any) -> Any:
    if isinstance(value, (dict, list, int, float, bool)) or value is None:
        return value
    return json.loads(str(value))


def _json_value(value: Any) -> Any:
    return value.isoformat(timespec="seconds") if hasattr(value, "isoformat") else value
