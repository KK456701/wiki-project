from __future__ import annotations

import hashlib
import io
import json
import re
import uuid
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any

import yaml
from sqlalchemy import Engine, text


MAX_ZIP_BYTES = 20 * 1024 * 1024
MAX_EXTRACTED_BYTES = 80 * 1024 * 1024
ALLOWED_SUFFIXES = {".yaml", ".yml", ".json", ".md", ".txt", ".j2", ".sql"}


class CompanyKnowledgeError(RuntimeError):
    pass


class CompanyKnowledgeRepository:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def create_merge_report(
        self, zip_bytes: bytes, uploaded_by: str = "admin"
    ) -> dict[str, Any]:
        manifest, files = _read_exchange_package(zip_bytes)
        package_id = str(manifest["package_id"])
        hospital_id = str(manifest["hospital_id"])
        now = _now()
        report_id = f"MR_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}"
        items = self._build_items(files)

        try:
            with self.engine.begin() as conn:
                conn.execute(
                    text(
                        """
                        INSERT INTO company_kb_package
                          (package_id, report_id, hospital_id, format_version,
                           exported_at, uploaded_at, uploaded_by, status,
                           manifest_json, package_checksum)
                        VALUES
                          (:package_id, :report_id, :hospital_id, :format_version,
                           :exported_at, :uploaded_at, :uploaded_by,
                           'pending_review', :manifest_json, :package_checksum)
                        """
                    ),
                    {
                        "package_id": package_id,
                        "report_id": report_id,
                        "hospital_id": hospital_id,
                        "format_version": str(manifest["format_version"]),
                        "exported_at": manifest.get("exported_at"),
                        "uploaded_at": now,
                        "uploaded_by": uploaded_by,
                        "manifest_json": _json_dump(manifest),
                        "package_checksum": hashlib.sha256(zip_bytes).hexdigest(),
                    },
                )
                for item in items:
                    conn.execute(
                        text(
                            """
                            INSERT INTO company_kb_package_item
                              (package_id, item_id, item_type, rule_id, rule_name,
                               field_name, hospital_value_json, company_value_json,
                               source_payload_json, status)
                            VALUES
                              (:package_id, :item_id, :item_type, :rule_id,
                               :rule_name, :field_name, :hospital_value_json,
                               :company_value_json, :source_payload_json, :status)
                            """
                        ),
                        {
                            "package_id": package_id,
                            "item_id": item["item_id"],
                            "item_type": item["type"],
                            "rule_id": item.get("rule_id"),
                            "rule_name": item.get("rule_name"),
                            "field_name": item.get("field"),
                            "hospital_value_json": _json_dump(item.get("hospital_value")),
                            "company_value_json": _json_dump(item.get("company_value")),
                            "source_payload_json": _json_dump(item.get("source_payload") or {}),
                            "status": item["status"],
                        },
                    )
        except Exception as exc:
            if "UNIQUE" in str(exc).upper() or "DUPLICATE" in str(exc).upper():
                raise CompanyKnowledgeError(f"PACKAGE_ALREADY_IMPORTED: {package_id}") from exc
            raise
        return self.read_merge_report(report_id)

    def list_merge_reports(self) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    """
                    SELECT report_id FROM company_kb_package
                    ORDER BY uploaded_at DESC, report_id DESC
                    """
                )
            ).all()
        return [self._report_summary(self.read_merge_report(str(row[0]))) for row in rows]

    def read_merge_report(self, report_id: str) -> dict[str, Any]:
        with self.engine.connect() as conn:
            package = conn.execute(
                text(
                    """
                    SELECT * FROM company_kb_package WHERE report_id=:report_id
                    """
                ),
                {"report_id": report_id},
            ).mappings().first()
            if package is None:
                raise CompanyKnowledgeError(f"MERGE_REPORT_NOT_FOUND: {report_id}")
            rows = conn.execute(
                text(
                    """
                    SELECT * FROM company_kb_package_item
                    WHERE package_id=:package_id ORDER BY item_id
                    """
                ),
                {"package_id": package["package_id"]},
            ).mappings().all()

        items = [_item_from_row(dict(row)) for row in rows]
        return {
            "report_id": str(package["report_id"]),
            "package_id": str(package["package_id"]),
            "hospital_id": str(package["hospital_id"]),
            "uploaded_at": str(package["uploaded_at"]),
            "uploaded_by": str(package["uploaded_by"]),
            "status": str(package["status"]),
            "summary": _summarize(items),
            "items": items,
        }

    def approve_merge_item(
        self,
        report_id: str,
        item_id: str,
        decision: str,
        approver_id: str = "admin",
    ) -> dict[str, Any]:
        if decision not in {"adopt_as_company_candidate", "keep_as_hospital_local"}:
            raise CompanyKnowledgeError("INVALID_MERGE_DECISION")
        now = _now()
        candidate_id: str | None = None
        with self.engine.begin() as conn:
            package, item = _find_item(conn, report_id, item_id)
            if item["status"] != "pending":
                raise CompanyKnowledgeError("MERGE_ITEM_ALREADY_REVIEWED")
            status = (
                "approved_candidate"
                if decision == "adopt_as_company_candidate"
                else "kept_hospital_local"
            )
            if decision == "adopt_as_company_candidate":
                rule_id = str(item.get("rule_id") or "").strip()
                if not rule_id:
                    raise CompanyKnowledgeError("CANDIDATE_RULE_ID_MISSING")
                candidate_id = f"CAND_{uuid.uuid4().hex[:12]}"
                conn.execute(
                    text(
                        """
                        INSERT INTO company_rule_candidate
                          (candidate_id, package_id, item_id, source_hospital_id,
                           rule_id, payload_json, status, created_at, created_by)
                        VALUES
                          (:candidate_id, :package_id, :item_id,
                           :source_hospital_id, :rule_id, :payload_json,
                           'approved', :created_at, :created_by)
                        """
                    ),
                    {
                        "candidate_id": candidate_id,
                        "package_id": package["package_id"],
                        "item_id": item_id,
                        "source_hospital_id": package["hospital_id"],
                        "rule_id": rule_id,
                        "payload_json": item["source_payload_json"],
                        "created_at": now,
                        "created_by": approver_id,
                    },
                )
            conn.execute(
                text(
                    """
                    UPDATE company_kb_package_item
                    SET status=:status, decision=:decision,
                        approver_id=:approver_id, decided_at=:decided_at
                    WHERE package_id=:package_id AND item_id=:item_id
                    """
                ),
                {
                    "status": status,
                    "decision": decision,
                    "approver_id": approver_id,
                    "decided_at": now,
                    "package_id": package["package_id"],
                    "item_id": item_id,
                },
            )
            _refresh_package_status(conn, str(package["package_id"]))
        return {
            "report_id": report_id,
            "item_id": item_id,
            "status": status,
            "decision": decision,
            "candidate_id": candidate_id,
            "approved_at": now,
            "approver_id": approver_id,
        }

    def reject_merge_item(
        self,
        report_id: str,
        item_id: str,
        reason: str = "",
        approver_id: str = "admin",
    ) -> dict[str, Any]:
        now = _now()
        with self.engine.begin() as conn:
            package, item = _find_item(conn, report_id, item_id)
            if item["status"] != "pending":
                raise CompanyKnowledgeError("MERGE_ITEM_ALREADY_REVIEWED")
            conn.execute(
                text(
                    """
                    UPDATE company_kb_package_item
                    SET status='rejected', decision='reject',
                        approver_id=:approver_id, decision_reason=:reason,
                        decided_at=:decided_at
                    WHERE package_id=:package_id AND item_id=:item_id
                    """
                ),
                {
                    "approver_id": approver_id,
                    "reason": reason,
                    "decided_at": now,
                    "package_id": package["package_id"],
                    "item_id": item_id,
                },
            )
            _refresh_package_status(conn, str(package["package_id"]))
        return {
            "report_id": report_id,
            "item_id": item_id,
            "status": "rejected",
            "rejected_at": now,
            "approver_id": approver_id,
        }

    def create_release(
        self,
        candidate_ids: list[str],
        created_by: str,
        notes: str = "",
    ) -> dict[str, Any]:
        unique_ids = list(dict.fromkeys(str(value).strip() for value in candidate_ids if str(value).strip()))
        if not unique_ids:
            raise CompanyKnowledgeError("RELEASE_CANDIDATES_REQUIRED")
        release_id = f"REL_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}"
        now = _now()
        with self.engine.begin() as conn:
            release_version = int(
                conn.execute(text("SELECT MAX(version) FROM company_release")).scalar_one()
                or 0
            ) + 1
            candidates: list[dict[str, Any]] = []
            rule_ids: set[str] = set()
            for candidate_id in unique_ids:
                row = conn.execute(
                    text(
                        """
                        SELECT * FROM company_rule_candidate
                        WHERE candidate_id=:candidate_id
                        """
                    ),
                    {"candidate_id": candidate_id},
                ).mappings().first()
                if row is None:
                    raise CompanyKnowledgeError(f"CANDIDATE_NOT_FOUND: {candidate_id}")
                candidate = dict(row)
                if candidate["status"] != "approved" or candidate.get("release_id"):
                    raise CompanyKnowledgeError(f"CANDIDATE_NOT_AVAILABLE: {candidate_id}")
                rule_id = str(candidate["rule_id"])
                if rule_id in rule_ids:
                    raise CompanyKnowledgeError(f"DUPLICATE_RELEASE_RULE: {rule_id}")
                rule_ids.add(rule_id)
                candidates.append(candidate)

            conn.execute(
                text(
                    """
                    INSERT INTO company_release
                      (release_id, version, status, notes, created_by, created_at)
                    VALUES
                      (:release_id, :version, 'draft', :notes, :created_by, :created_at)
                    """
                ),
                {
                    "release_id": release_id,
                    "version": release_version,
                    "notes": notes,
                    "created_by": created_by,
                    "created_at": now,
                },
            )
            for candidate in candidates:
                candidate_id = str(candidate["candidate_id"])
                payload = _standard_payload(candidate)
                conn.execute(
                    text(
                        """
                        INSERT INTO company_release_item
                          (release_id, candidate_id, rule_id, payload_json)
                        VALUES
                          (:release_id, :candidate_id, :rule_id, :payload_json)
                        """
                    ),
                    {
                        "release_id": release_id,
                        "candidate_id": candidate_id,
                        "rule_id": candidate["rule_id"],
                        "payload_json": _json_dump(payload),
                    },
                )
                conn.execute(
                    text(
                        """
                        UPDATE company_rule_candidate
                        SET status='in_release', release_id=:release_id
                        WHERE candidate_id=:candidate_id
                        """
                    ),
                    {"release_id": release_id, "candidate_id": candidate_id},
                )
        return self.read_release(release_id)

    def publish_release(self, release_id: str, approver_id: str) -> dict[str, Any]:
        now = _now()
        with self.engine.begin() as conn:
            release = conn.execute(
                text("SELECT * FROM company_release WHERE release_id=:release_id"),
                {"release_id": release_id},
            ).mappings().first()
            if release is None:
                raise CompanyKnowledgeError(f"RELEASE_NOT_FOUND: {release_id}")
            if release["status"] != "draft":
                raise CompanyKnowledgeError("RELEASE_ALREADY_PROCESSED")
            items = conn.execute(
                text(
                    """
                    SELECT * FROM company_release_item
                    WHERE release_id=:release_id ORDER BY rule_id
                    """
                ),
                {"release_id": release_id},
            ).mappings().all()
            if not items:
                raise CompanyKnowledgeError("RELEASE_ITEMS_REQUIRED")

            for row in items:
                payload = _json_load(row["payload_json"])
                if not isinstance(payload, dict):
                    raise CompanyKnowledgeError("INVALID_RELEASE_RULE_PAYLOAD")
                rule_id = str(row["rule_id"])
                current = conn.execute(
                    text(
                        """
                        SELECT version FROM company_standard_rule
                        WHERE rule_id=:rule_id
                        """
                    ),
                    {"rule_id": rule_id},
                ).first()
                rule_version = int(current[0]) + 1 if current is not None else 1
                version_payload = dict(payload)
                version_payload["version"] = rule_version
                conn.execute(
                    text(
                        """
                        INSERT INTO company_standard_rule_version
                          (rule_id, version, payload_json, source_release_id, created_at)
                        VALUES
                          (:rule_id, :version, :payload_json, :source_release_id,
                           :created_at)
                        """
                    ),
                    {
                        "rule_id": rule_id,
                        "version": rule_version,
                        "payload_json": _json_dump(version_payload),
                        "source_release_id": release_id,
                        "created_at": now,
                    },
                )
                standard_params = {
                    "rule_id": rule_id,
                    "rule_name": str(payload.get("rule_name") or rule_id),
                    "definition": str(payload.get("definition") or ""),
                    "formula": str(payload.get("formula") or ""),
                    "payload_json": _json_dump(version_payload),
                    "version": rule_version,
                    "updated_at": now,
                }
                if current is None:
                    conn.execute(
                        text(
                            """
                            INSERT INTO company_standard_rule
                              (rule_id, rule_name, definition, formula,
                               payload_json, version, status, updated_at)
                            VALUES
                              (:rule_id, :rule_name, :definition, :formula,
                               :payload_json, :version, 'published', :updated_at)
                            """
                        ),
                        standard_params,
                    )
                else:
                    conn.execute(
                        text(
                            """
                            UPDATE company_standard_rule
                            SET rule_name=:rule_name, definition=:definition,
                                formula=:formula, payload_json=:payload_json,
                                version=:version, status='published',
                                updated_at=:updated_at
                            WHERE rule_id=:rule_id
                            """
                        ),
                        standard_params,
                    )
                conn.execute(
                    text(
                        """
                        UPDATE company_rule_candidate SET status='released'
                        WHERE candidate_id=:candidate_id AND release_id=:release_id
                        """
                    ),
                    {
                        "candidate_id": row["candidate_id"],
                        "release_id": release_id,
                    },
                )
            conn.execute(
                text(
                    """
                    UPDATE company_release
                    SET status='published', approved_by=:approved_by,
                        published_at=:published_at
                    WHERE release_id=:release_id
                    """
                ),
                {
                    "approved_by": approver_id,
                    "published_at": now,
                    "release_id": release_id,
                },
            )
        return self.read_release(release_id)

    def list_releases(self) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    """
                    SELECT release_id FROM company_release
                    ORDER BY version DESC
                    """
                )
            ).all()
        return [self.read_release(str(row[0])) for row in rows]

    def read_release(self, release_id: str) -> dict[str, Any]:
        with self.engine.connect() as conn:
            release = conn.execute(
                text("SELECT * FROM company_release WHERE release_id=:release_id"),
                {"release_id": release_id},
            ).mappings().first()
            if release is None:
                raise CompanyKnowledgeError(f"RELEASE_NOT_FOUND: {release_id}")
            rows = conn.execute(
                text(
                    """
                    SELECT candidate_id, rule_id, payload_json
                    FROM company_release_item
                    WHERE release_id=:release_id ORDER BY rule_id
                    """
                ),
                {"release_id": release_id},
            ).mappings().all()
        return {
            "release_id": str(release["release_id"]),
            "version": int(release["version"]),
            "status": str(release["status"]),
            "notes": str(release.get("notes") or ""),
            "created_by": str(release["created_by"]),
            "approved_by": str(release.get("approved_by") or ""),
            "created_at": str(release["created_at"]),
            "published_at": str(release.get("published_at") or ""),
            "items": [
                {
                    "candidate_id": str(row["candidate_id"]),
                    "rule_id": str(row["rule_id"]),
                    "payload": _json_load(row["payload_json"]),
                }
                for row in rows
            ],
        }

    def export_release_zip(self, release_id: str) -> bytes:
        release = self.read_release(release_id)
        if release["status"] != "published":
            raise CompanyKnowledgeError("RELEASE_NOT_PUBLISHED")
        manifest = {
            "release_id": release["release_id"],
            "version": release["version"],
            "format_version": "company-release-v1",
            "published_at": release["published_at"],
            "rule_count": len(release["items"]),
            "contains_patient_data": False,
        }
        files: dict[str, bytes] = {"manifest.yaml": _yaml_bytes(manifest)}
        for item in release["items"]:
            files[f"rules/{item['rule_id']}.yaml"] = _yaml_bytes(item["payload"])
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

    def _build_items(self, files: dict[str, bytes]) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        override_names = sorted(
            name for name in files if name.startswith("overrides/") and name.endswith((".yaml", ".yml"))
        )
        for name in override_names:
            payload = _yaml_dict(files[name], name)
            items.append(self._override_item(len(items) + 1, payload))
        mapping_names = sorted(
            name for name in files if name.startswith("mappings/") and name.endswith((".yaml", ".yml"))
        )
        for name in mapping_names:
            payload = _yaml_dict(files[name], name)
            rule_id = str(payload.get("rule_id") or Path(name).stem)
            items.append(
                {
                    "item_id": f"ITEM_{len(items) + 1:03d}",
                    "type": "field_mapping",
                    "rule_id": rule_id,
                    "rule_name": rule_id,
                    "field": "fields",
                    "hospital_value": payload.get("fields") or {},
                    "company_value": {},
                    "status": "pending",
                    "source_payload": payload,
                }
            )
        return items

    def _override_item(self, index: int, payload: dict[str, Any]) -> dict[str, Any]:
        rule_id = str(payload.get("rule_id") or "").strip()
        if not rule_id:
            raise CompanyKnowledgeError("RULE_ID_MISSING")
        rule_name = str(payload.get("rule_name") or rule_id)
        with self.engine.connect() as conn:
            standard = conn.execute(
                text(
                    """
                    SELECT definition, formula FROM company_standard_rule
                    WHERE rule_id=:rule_id AND status='published'
                    """
                ),
                {"rule_id": rule_id},
            ).mappings().first()
        if standard is None:
            return _item(index, "new_indicator", rule_id, rule_name, "rule", payload, None, "pending", payload)

        hospital_text = f"{payload.get('definition') or ''}\n{payload.get('formula') or ''}"
        company_text = f"{standard.get('definition') or ''}\n{standard.get('formula') or ''}"
        hospital_minutes = _extract_minutes(hospital_text)
        company_minutes = _extract_minutes(company_text)
        if hospital_minutes is not None and company_minutes is not None and hospital_minutes != company_minutes:
            return _item(
                index,
                "caliber_conflict",
                rule_id,
                rule_name,
                "minutes_threshold",
                f"{hospital_minutes}分钟",
                f"{company_minutes}分钟",
                "pending",
                payload,
            )
        if _normalize(hospital_text) != _normalize(company_text):
            return _item(
                index,
                "caliber_conflict",
                rule_id,
                rule_name,
                "formula_or_definition",
                {"definition": payload.get("definition"), "formula": payload.get("formula")},
                {"definition": standard.get("definition"), "formula": standard.get("formula")},
                "pending",
                payload,
            )
        return _item(index, "unchanged", rule_id, rule_name, "formula_or_definition", None, None, "skipped", payload)

    @staticmethod
    def _report_summary(report: dict[str, Any]) -> dict[str, Any]:
        return {
            "report_id": report["report_id"],
            "package_id": report["package_id"],
            "hospital_id": report["hospital_id"],
            "uploaded_at": report["uploaded_at"],
            "uploaded_by": report["uploaded_by"],
            "status": report["status"],
            "summary": report["summary"],
        }


def _read_exchange_package(zip_bytes: bytes) -> tuple[dict[str, Any], dict[str, bytes]]:
    if len(zip_bytes) > MAX_ZIP_BYTES:
        raise CompanyKnowledgeError("ZIP_TOO_LARGE")
    files: dict[str, bytes] = {}
    total_size = 0
    try:
        with zipfile.ZipFile(io.BytesIO(zip_bytes), "r") as zf:
            for info in zf.infolist():
                name = info.filename.replace("\\", "/")
                if not name or name.endswith("/"):
                    continue
                if name in files:
                    raise CompanyKnowledgeError(f"DUPLICATE_ZIP_ENTRY: {name}")
                if name.startswith("/") or ".." in Path(name).parts:
                    raise CompanyKnowledgeError("UNSAFE_ZIP_PATH")
                suffix = Path(name).suffix.lower()
                if suffix and suffix not in ALLOWED_SUFFIXES:
                    raise CompanyKnowledgeError(f"UNSUPPORTED_FILE_TYPE: {name}")
                total_size += int(info.file_size or 0)
                if total_size > MAX_EXTRACTED_BYTES:
                    raise CompanyKnowledgeError("ZIP_EXTRACTED_CONTENT_TOO_LARGE")
                files[name] = zf.read(info)
    except zipfile.BadZipFile as exc:
        raise CompanyKnowledgeError("INVALID_ZIP_FILE") from exc

    if "manifest.yaml" not in files or "checksums.json" not in files:
        raise CompanyKnowledgeError("REQUIRED_FILE_MISSING")
    manifest = _yaml_dict(files["manifest.yaml"], "manifest.yaml")
    if manifest.get("format_version") != "kb-exchange-v2":
        raise CompanyKnowledgeError("UNSUPPORTED_PACKAGE_FORMAT")
    if not str(manifest.get("package_id") or "").strip():
        raise CompanyKnowledgeError("PACKAGE_ID_MISSING")
    if not str(manifest.get("hospital_id") or "").strip():
        raise CompanyKnowledgeError("HOSPITAL_ID_MISSING_IN_MANIFEST")
    if manifest.get("contains_patient_data") is not False:
        raise CompanyKnowledgeError("PATIENT_DATA_FLAG_NOT_SAFE")

    try:
        checksums = json.loads(files["checksums.json"].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CompanyKnowledgeError("INVALID_CHECKSUM_FILE") from exc
    expected_names = set(files) - {"checksums.json"}
    if not isinstance(checksums, dict) or set(checksums) != expected_names:
        raise CompanyKnowledgeError("CHECKSUM_FILE_SET_MISMATCH")
    for name in sorted(expected_names):
        actual = hashlib.sha256(files[name]).hexdigest()
        if actual != str(checksums[name]):
            raise CompanyKnowledgeError(f"CHECKSUM_MISMATCH: {name}")
    return manifest, files


def _standard_payload(candidate: dict[str, Any]) -> dict[str, Any]:
    source = _json_load(candidate.get("payload_json"))
    if not isinstance(source, dict):
        raise CompanyKnowledgeError("INVALID_CANDIDATE_PAYLOAD")
    rule_id = str(candidate["rule_id"])
    return {
        "rule_id": rule_id,
        "rule_name": str(source.get("rule_name") or rule_id),
        "definition": str(source.get("definition") or ""),
        "formula": str(source.get("formula") or ""),
        "base_standard_version": str(source.get("base_standard_version") or ""),
        "recommended_params": source.get("custom_params") or {},
        "source_candidate_id": str(candidate["candidate_id"]),
        "source_hospital_id": str(candidate["source_hospital_id"]),
    }


def _find_item(conn: Any, report_id: str, item_id: str) -> tuple[dict[str, Any], dict[str, Any]]:
    package = conn.execute(
        text("SELECT * FROM company_kb_package WHERE report_id=:report_id"),
        {"report_id": report_id},
    ).mappings().first()
    if package is None:
        raise CompanyKnowledgeError(f"MERGE_REPORT_NOT_FOUND: {report_id}")
    item = conn.execute(
        text(
            """
            SELECT * FROM company_kb_package_item
            WHERE package_id=:package_id AND item_id=:item_id
            """
        ),
        {"package_id": package["package_id"], "item_id": item_id},
    ).mappings().first()
    if item is None:
        raise CompanyKnowledgeError(f"MERGE_ITEM_NOT_FOUND: {item_id}")
    return dict(package), dict(item)


def _refresh_package_status(conn: Any, package_id: str) -> None:
    pending = conn.execute(
        text(
            """
            SELECT COUNT(*) FROM company_kb_package_item
            WHERE package_id=:package_id AND status='pending'
            """
        ),
        {"package_id": package_id},
    ).scalar_one()
    conn.execute(
        text("UPDATE company_kb_package SET status=:status WHERE package_id=:package_id"),
        {"status": "reviewed" if int(pending or 0) == 0 else "pending_review", "package_id": package_id},
    )


def _item_from_row(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "item_id": str(row["item_id"]),
        "type": str(row["item_type"]),
        "rule_id": str(row.get("rule_id") or ""),
        "rule_name": str(row.get("rule_name") or row.get("rule_id") or ""),
        "field": str(row.get("field_name") or ""),
        "hospital_value": _json_load(row.get("hospital_value_json")),
        "company_value": _json_load(row.get("company_value_json")),
        "source_payload": _json_load(row.get("source_payload_json")) or {},
        "status": str(row["status"]),
        "decision": row.get("decision"),
        "approver_id": row.get("approver_id"),
        "reason": row.get("decision_reason"),
        "decided_at": row.get("decided_at"),
    }


def _item(
    index: int,
    item_type: str,
    rule_id: str,
    rule_name: str,
    field: str,
    hospital_value: Any,
    company_value: Any,
    status: str,
    source_payload: dict[str, Any],
) -> dict[str, Any]:
    return {
        "item_id": f"ITEM_{index:03d}",
        "type": item_type,
        "rule_id": rule_id,
        "rule_name": rule_name,
        "field": field,
        "hospital_value": hospital_value,
        "company_value": company_value,
        "status": status,
        "source_payload": source_payload,
    }


def _summarize(items: list[dict[str, Any]]) -> dict[str, int]:
    return {
        "total_items": len(items),
        "conflicts": sum(1 for item in items if item.get("type") == "caliber_conflict"),
        "new_indicators": sum(1 for item in items if item.get("type") == "new_indicator"),
        "new_rules": sum(1 for item in items if item.get("type") == "field_mapping"),
        "unchanged": sum(1 for item in items if item.get("type") == "unchanged"),
        "pending": sum(1 for item in items if item.get("status") == "pending"),
    }


def _yaml_dict(content: bytes, name: str) -> dict[str, Any]:
    try:
        value = yaml.safe_load(content.decode("utf-8")) or {}
    except (UnicodeDecodeError, yaml.YAMLError) as exc:
        raise CompanyKnowledgeError(f"INVALID_YAML: {name}") from exc
    if not isinstance(value, dict):
        raise CompanyKnowledgeError(f"YAML_OBJECT_REQUIRED: {name}")
    return value


def _yaml_bytes(payload: dict[str, Any]) -> bytes:
    return yaml.safe_dump(payload, allow_unicode=True, sort_keys=False).encode("utf-8")


def _extract_minutes(value: str) -> int | None:
    match = re.search(r"(\d+)\s*分钟", value or "")
    return int(match.group(1)) if match else None


def _normalize(value: Any) -> str:
    return re.sub(r"\s+", "", str(value or "").lower())


def _json_dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def _json_load(value: Any) -> Any:
    if value is None or value == "":
        return None
    if isinstance(value, (dict, list, int, float, bool)):
        return value
    return json.loads(str(value))


def _now() -> str:
    return datetime.now().isoformat(sep=" ", timespec="seconds")
