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
from sqlalchemy import (
    JSON,
    Column,
    DateTime,
    Engine,
    MetaData,
    String,
    Table,
    UniqueConstraint,
    text,
)

from app.terminology.importer import load_term_corpus
from app.kb.signing import PackageSignatureError, PackageSigner, verify_checksums


MAX_ZIP_BYTES = 20 * 1024 * 1024
MAX_EXTRACTED_BYTES = 80 * 1024 * 1024
ALLOWED_SUFFIXES = {".yaml", ".yml", ".json", ".md", ".txt", ".j2", ".sql"}


class CompanyKnowledgeError(RuntimeError):
    pass


class CompanyKnowledgeRepository:
    def __init__(
        self,
        engine: Engine,
        trusted_hospital_keys_dir: str | Path | None = None,
        release_signer: PackageSigner | None = None,
    ) -> None:
        self.engine = engine
        self.trusted_hospital_keys_dir = (
            Path(trusted_hospital_keys_dir) if trusted_hospital_keys_dir else None
        )
        self.release_signer = release_signer
        _ensure_company_term_candidate_schema(engine)

    def create_merge_report(
        self, zip_bytes: bytes, uploaded_by: str = "admin"
    ) -> dict[str, Any]:
        manifest, files, signature_status = _read_exchange_package(
            zip_bytes, self.trusted_hospital_keys_dir
        )
        package_id = str(manifest["package_id"])
        hospital_id = str(manifest["hospital_id"])
        package_checksum = hashlib.sha256(zip_bytes).hexdigest()
        with self.engine.connect() as conn:
            existing = conn.execute(
                text(
                    """SELECT report_id, package_checksum FROM company_kb_package
                    WHERE package_id=:package_id"""
                ),
                {"package_id": package_id},
            ).mappings().first()
        if existing is not None:
            if str(existing["package_checksum"]) != package_checksum:
                raise CompanyKnowledgeError(f"PACKAGE_ID_CONFLICT: {package_id}")
            report = self.read_merge_report(str(existing["report_id"]))
            report["duplicate"] = True
            return report
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
                        "manifest_json": _json_dump(
                            {**manifest, "signature_status": signature_status}
                        ),
                        "package_checksum": package_checksum,
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
        report = self.read_merge_report(report_id)
        report["duplicate"] = False
        return report

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
        manifest = _json_load(package.get("manifest_json")) or {}
        return {
            "report_id": str(package["report_id"]),
            "package_id": str(package["package_id"]),
            "hospital_id": str(package["hospital_id"]),
            "uploaded_at": str(package["uploaded_at"]),
            "uploaded_by": str(package["uploaded_by"]),
            "status": str(package["status"]),
            "format_version": str(package["format_version"]),
            "signature_status": str(manifest.get("signature_status") or "legacy_unsigned"),
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
                item_type = str(item.get("item_type") or "")
                if item_type.startswith("term_"):
                    source_payload = _json_load(item["source_payload_json"])
                    concept_code = str((source_payload or {}).get("concept_code") or "").strip()
                    if not concept_code:
                        raise CompanyKnowledgeError("TERM_CANDIDATE_CONCEPT_MISSING")
                    candidate_id = f"TCAND_{uuid.uuid4().hex[:12]}"
                    conn.execute(
                        text(
                            """
                            INSERT INTO company_term_candidate
                              (candidate_id, package_id, item_id, source_hospital_id,
                               concept_code, candidate_type, payload_json, status,
                               created_at, created_by)
                            VALUES
                              (:candidate_id, :package_id, :item_id,
                               :source_hospital_id, :concept_code, :candidate_type,
                               :payload_json, 'approved', :created_at, :created_by)
                            """
                        ),
                        {
                            "candidate_id": candidate_id,
                            "package_id": package["package_id"],
                            "item_id": item_id,
                            "source_hospital_id": package["hospital_id"],
                            "concept_code": concept_code,
                            "candidate_type": item_type,
                            "payload_json": item["source_payload_json"],
                            "created_at": now,
                            "created_by": approver_id,
                        },
                    )
                else:
                    candidate_id = self._insert_rule_candidate(
                        conn, package, item, item_id, approver_id, now
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

    @staticmethod
    def _insert_rule_candidate(
        conn: Any,
        package: dict[str, Any],
        item: dict[str, Any],
        item_id: str,
        approver_id: str,
        now: str,
    ) -> str:
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
        return candidate_id

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

    def list_candidates(self, status: str | None = "approved") -> list[dict[str, Any]]:
        query = "SELECT * FROM company_rule_candidate"
        params: dict[str, Any] = {}
        if status:
            query += " WHERE status=:status"
            params["status"] = status
        query += " ORDER BY created_at DESC, candidate_id DESC"
        with self.engine.connect() as conn:
            rows = conn.execute(text(query), params).mappings().all()
        return [
            {
                "candidate_id": str(row["candidate_id"]),
                "package_id": str(row["package_id"]),
                "item_id": str(row["item_id"]),
                "source_hospital_id": str(row["source_hospital_id"]),
                "rule_id": str(row["rule_id"]),
                "status": str(row["status"]),
                "created_at": str(row["created_at"]),
                "created_by": str(row["created_by"]),
                "release_id": str(row.get("release_id") or ""),
                "payload": _json_load(row["payload_json"]),
            }
            for row in rows
        ]

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
        signed_release = self.release_signer is not None
        manifest = {
            "release_id": release["release_id"],
            "version": release["version"],
            "format_version": "company-release-v3" if signed_release else "company-release-v2",
            "published_at": release["published_at"],
            "rule_count": len(release["items"]),
            "contains_patient_data": False,
        }
        if signed_release and self.release_signer is not None:
            manifest.update(
                {
                    "package_id": release["release_id"],
                    "compatible_system_versions": ["0.1.0"],
                    "signature_algorithm": "Ed25519",
                    "signer_key_id": self.release_signer.key_id,
                    "permissions": ["规则与术语暂存", "字段适配", "本地只读试运行"],
                }
            )
        corpus = load_term_corpus(
            Path(__file__).resolve().parents[2]
            / "core-rules-wiki"
            / "terminology"
            / "core_indicator_terms.yaml"
        )
        concepts = [
            concept.model_dump(mode="json", exclude={"aliases"})
            for concept in corpus.concepts
            if concept.status == "active"
        ]
        aliases = [
            {
                "concept_code": concept.concept_code,
                **alias.model_dump(mode="json", exclude={"hospital_id"}),
            }
            for concept in corpus.concepts
            for alias in concept.aliases
            if alias.approval_status == "approved"
        ]
        concept_bytes = _json_bytes(concepts)
        alias_bytes = _json_bytes(aliases)
        term_release = {
            "release_id": release["release_id"],
            "published_at": release["published_at"],
            "schema_version": corpus.schema_version,
            "source": "company_reviewed_corpus",
            "concept_count": len(concepts),
            "alias_count": len(aliases),
            "checksum": hashlib.sha256(concept_bytes + alias_bytes).hexdigest(),
            "contains_hospital_candidates": False,
        }
        manifest["term_concept_count"] = len(concepts)
        manifest["term_alias_count"] = len(aliases)
        files: dict[str, bytes] = {
            "manifest.yaml": _yaml_bytes(manifest),
            "terminology/release.json": _json_bytes(term_release),
            "terminology/concepts.json": concept_bytes,
            "terminology/aliases.json": alias_bytes,
        }
        for item in release["items"]:
            files[f"rules/{item['rule_id']}.yaml"] = _yaml_bytes(item["payload"])
        checksums = {
            name: hashlib.sha256(content).hexdigest()
            for name, content in sorted(files.items())
        }
        checksum_bytes = json.dumps(
            checksums, ensure_ascii=False, indent=2, sort_keys=True
        ).encode("utf-8")
        files["checksums.json"] = checksum_bytes
        if signed_release and self.release_signer is not None:
            files["signature.json"] = _json_bytes(
                self.release_signer.sign_checksums(checksum_bytes)
            )
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
        metadata_names = sorted(
            name
            for name in files
            if name.startswith("metadata/")
            and name != "metadata/relations.yaml"
            and name.endswith((".yaml", ".yml"))
        )
        for name in metadata_names:
            payload = _yaml_dict(files[name], name)
            items.append(
                _item(
                    len(items) + 1,
                    "metadata_schema",
                    "",
                    str(payload.get("db_name") or Path(name).stem),
                    "metadata",
                    {
                        "table_count": len(payload.get("tables") or []),
                        "column_count": len(payload.get("columns") or []),
                    },
                    None,
                    "informational",
                    payload,
                )
            )
        if "metadata/relations.yaml" in files:
            payload = _yaml_dict(files["metadata/relations.yaml"], "metadata/relations.yaml")
            items.append(
                _item(
                    len(items) + 1,
                    "table_relation",
                    "",
                    "已确认表关联",
                    "relations",
                    len(payload.get("relations") or []),
                    None,
                    "informational",
                    payload,
                )
            )
        validation_names = sorted(
            name
            for name in files
            if name.startswith("validation/") and name.endswith((".yaml", ".yml"))
        )
        for name in validation_names:
            payload = _yaml_dict(files[name], name)
            rule_id = str(payload.get("rule_id") or Path(name).stem)
            items.append(
                _item(
                    len(items) + 1,
                    "validation_feedback",
                    rule_id,
                    rule_id,
                    "aggregate_result",
                    {
                        "result_value": payload.get("result_value"),
                        "numerator_count": payload.get("numerator_count"),
                        "denominator_count": payload.get("denominator_count"),
                    },
                    None,
                    "informational",
                    payload,
                )
            )
        term_candidate_names = sorted(
            name
            for name in files
            if name.startswith("terminology/candidates/")
            and name.endswith((".yaml", ".yml"))
        )
        known_aliases = _company_alias_concepts()
        for name in term_candidate_names:
            payload = _yaml_dict(files[name], name)
            concept_code = str(payload.get("concept_code") or "").strip()
            alias_text = str(payload.get("alias_text") or "").strip()
            if not concept_code or not alias_text:
                raise CompanyKnowledgeError("TERM_CANDIDATE_FIELDS_MISSING")
            item_type = "term_candidate"
            existing_concepts = known_aliases.get(_normalize(alias_text), set())
            if existing_concepts and concept_code not in existing_concepts:
                item_type = "term_conflict"
            elif payload.get("ambiguity_group") or payload.get("relation_type") == "related":
                item_type = "term_ambiguity"
            elif bool(payload.get("sql_safe")):
                item_type = "term_sql_safety_change"
            items.append(
                _item(
                    len(items) + 1,
                    item_type,
                    concept_code,
                    alias_text,
                    "alias_text",
                    alias_text,
                    sorted(existing_concepts),
                    "pending",
                    payload,
                )
            )
        term_mapping_names = sorted(
            name
            for name in files
            if name.startswith("terminology/mappings/")
            and name.endswith((".yaml", ".yml"))
        )
        for name in term_mapping_names:
            payload = _yaml_dict(files[name], name)
            concept_code = str(payload.get("concept_code") or "").strip()
            if not concept_code:
                raise CompanyKnowledgeError("TERM_MAPPING_CONCEPT_MISSING")
            items.append(
                _item(
                    len(items) + 1,
                    "term_sql_safety_change",
                    concept_code,
                    str(payload.get("local_name") or concept_code),
                    "local_value",
                    payload.get("local_value"),
                    None,
                    "pending",
                    payload,
                )
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


def _read_exchange_package(
    zip_bytes: bytes, trusted_hospital_keys_dir: Path | None = None
) -> tuple[dict[str, Any], dict[str, bytes], str]:
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
    format_version = str(manifest.get("format_version") or "")
    if format_version not in {"kb-exchange-v2", "kb-exchange-v3", "kb-exchange-v4"}:
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
    expected_names = set(files) - {"checksums.json", "signature.json"}
    if not isinstance(checksums, dict) or set(checksums) != expected_names:
        raise CompanyKnowledgeError("CHECKSUM_FILE_SET_MISMATCH")
    for name in sorted(expected_names):
        actual = hashlib.sha256(files[name]).hexdigest()
        if actual != str(checksums[name]):
            raise CompanyKnowledgeError(f"CHECKSUM_MISMATCH: {name}")
    signature_status = "legacy_unsigned"
    if format_version == "kb-exchange-v4":
        if "signature.json" not in files:
            raise CompanyKnowledgeError("PACKAGE_SIGNATURE_FILE_MISSING")
        if trusted_hospital_keys_dir is None:
            raise CompanyKnowledgeError("TRUSTED_HOSPITAL_KEYS_NOT_CONFIGURED")
        try:
            signature_payload = json.loads(files["signature.json"].decode("utf-8"))
            verified = verify_checksums(
                files["checksums.json"], signature_payload, trusted_hospital_keys_dir
            )
        except (UnicodeDecodeError, json.JSONDecodeError, PackageSignatureError) as exc:
            raise CompanyKnowledgeError(str(exc)) from exc
        signature_status = verified["status"]
    return manifest, files, signature_status


def _ensure_company_term_candidate_schema(engine: Engine) -> None:
    metadata = MetaData()
    table = Table(
        "company_term_candidate",
        metadata,
        Column(
            "candidate_id",
            String(64),
            primary_key=True,
        ),
        Column("package_id", String(64), nullable=False),
        Column("item_id", String(64), nullable=False),
        Column("source_hospital_id", String(64), nullable=False),
        Column("concept_code", String(96), nullable=False),
        Column("candidate_type", String(32), nullable=False),
        Column("payload_json", JSON, nullable=False),
        Column("status", String(32), nullable=False),
        Column("created_at", DateTime, nullable=False),
        Column("created_by", String(64), nullable=False),
        UniqueConstraint(
            "package_id", "item_id", name="uk_company_term_candidate_source"
        ),
    )
    metadata.create_all(engine, tables=[table], checkfirst=True)


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
        "term_candidates": sum(1 for item in items if item.get("type") == "term_candidate"),
        "term_conflicts": sum(1 for item in items if item.get("type") == "term_conflict"),
        "term_ambiguities": sum(1 for item in items if item.get("type") == "term_ambiguity"),
        "term_sql_safety_changes": sum(
            1 for item in items if item.get("type") == "term_sql_safety_change"
        ),
        "metadata_schemas": sum(1 for item in items if item.get("type") == "metadata_schema"),
        "table_relations": sum(1 for item in items if item.get("type") == "table_relation"),
        "validation_feedback": sum(
            1 for item in items if item.get("type") == "validation_feedback"
        ),
        "pending": sum(1 for item in items if item.get("status") == "pending"),
    }


def _company_alias_concepts() -> dict[str, set[str]]:
    corpus = load_term_corpus(
        Path(__file__).resolve().parents[2]
        / "core-rules-wiki"
        / "terminology"
        / "core_indicator_terms.yaml"
    )
    result: dict[str, set[str]] = {}
    for concept in corpus.concepts:
        for value in [concept.canonical_name, *(alias.alias_text for alias in concept.aliases)]:
            result.setdefault(_normalize(value), set()).add(concept.concept_code)
    return result


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


def _json_bytes(payload: Any) -> bytes:
    return json.dumps(
        payload, ensure_ascii=False, indent=2, sort_keys=True
    ).encode("utf-8")


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
