import hashlib
import io
import json
import unittest
import zipfile

import yaml
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.kb.company_repository import CompanyKnowledgeError, CompanyKnowledgeRepository


class CompanyKnowledgeRepositoryTest(unittest.TestCase):
    def test_company_standard_bootstrap_is_idempotent(self) -> None:
        from pathlib import Path

        from app.kb.company_importer import import_company_standard_rules

        engine = _company_engine()

        first = import_company_standard_rules(engine, Path("core-rules-wiki"))
        second = import_company_standard_rules(engine, Path("core-rules-wiki"))

        with engine.connect() as conn:
            current_count = conn.execute(
                text("SELECT COUNT(*) FROM company_standard_rule")
            ).scalar_one()
            version_count = conn.execute(
                text("SELECT COUNT(*) FROM company_standard_rule_version")
            ).scalar_one()
        self.assertEqual(len(first["inserted"]), 4)
        self.assertEqual(len(second["skipped"]), 4)
        self.assertEqual(first["failed"], [])
        self.assertEqual(current_count, 4)
        self.assertEqual(version_count, 4)

    def test_uploaded_report_survives_repository_recreation(self) -> None:
        engine = _company_engine()
        _insert_company_standard(engine)
        package = _exchange_package()

        created = CompanyKnowledgeRepository(engine).create_merge_report(
            package, uploaded_by="admin"
        )
        loaded = CompanyKnowledgeRepository(engine).read_merge_report(
            created["report_id"]
        )

        self.assertEqual(loaded["hospital_id"], "hospital_001")
        self.assertEqual(loaded["status"], "pending_review")
        self.assertEqual(loaded["summary"]["conflicts"], 1)
        self.assertEqual(loaded["items"][0]["type"], "caliber_conflict")

    def test_upload_rejects_checksum_mismatch(self) -> None:
        engine = _company_engine()
        package = _exchange_package(tamper_override=True)

        with self.assertRaisesRegex(CompanyKnowledgeError, "CHECKSUM_MISMATCH"):
            CompanyKnowledgeRepository(engine).create_merge_report(
                package, uploaded_by="admin"
            )

    def test_approve_creates_candidate_without_mutating_company_standard(self) -> None:
        engine = _company_engine()
        _insert_company_standard(engine)
        repository = CompanyKnowledgeRepository(engine)
        report = repository.create_merge_report(_exchange_package(), "admin")
        item = next(
            value for value in report["items"] if value["type"] == "caliber_conflict"
        )

        approved = repository.approve_merge_item(
            report["report_id"],
            item["item_id"],
            "adopt_as_company_candidate",
            "admin",
        )
        candidates = CompanyKnowledgeRepository(engine).list_candidates()

        with engine.connect() as conn:
            candidate_count = conn.execute(
                text("SELECT COUNT(*) FROM company_rule_candidate")
            ).scalar_one()
            standard = conn.execute(
                text("SELECT formula, version FROM company_standard_rule WHERE rule_id='R001'")
            ).one()

        self.assertEqual(approved["status"], "approved_candidate")
        self.assertTrue(approved["candidate_id"].startswith("CAND_"))
        self.assertEqual(candidate_count, 1)
        self.assertEqual(candidates[0]["candidate_id"], approved["candidate_id"])
        self.assertEqual(candidates[0]["status"], "approved")
        self.assertIn("10分钟", standard[0])
        self.assertEqual(standard[1], 1)

    def test_publish_release_versions_standard_and_exports_fixed_package(self) -> None:
        engine = _company_engine()
        _insert_company_standard(engine)
        repository = CompanyKnowledgeRepository(engine)
        report = repository.create_merge_report(_exchange_package(), "admin")
        item = next(
            value for value in report["items"] if value["type"] == "caliber_conflict"
        )
        approved = repository.approve_merge_item(
            report["report_id"],
            item["item_id"],
            "adopt_as_company_candidate",
            "reviewer",
        )

        draft = repository.create_release(
            [approved["candidate_id"]], "publisher", "首批医院经验"
        )
        with engine.connect() as conn:
            before = conn.execute(
                text("SELECT formula, version FROM company_standard_rule WHERE rule_id='R001'")
            ).one()
        published = repository.publish_release(draft["release_id"], "approver")
        package = repository.export_release_zip(draft["release_id"])

        with engine.connect() as conn:
            after = conn.execute(
                text("SELECT formula, version FROM company_standard_rule WHERE rule_id='R001'")
            ).one()
            versions = conn.execute(
                text(
                    "SELECT version FROM company_standard_rule_version "
                    "WHERE rule_id='R001' ORDER BY version"
                )
            ).scalars().all()
        with zipfile.ZipFile(io.BytesIO(package), "r") as zf:
            manifest = yaml.safe_load(zf.read("manifest.yaml").decode("utf-8"))
            rule = yaml.safe_load(zf.read("rules/R001.yaml").decode("utf-8"))
            term_aliases = json.loads(
                zf.read("terminology/aliases.json").decode("utf-8")
            )
            term_release = json.loads(
                zf.read("terminology/release.json").decode("utf-8")
            )
            checksums = json.loads(zf.read("checksums.json").decode("utf-8"))
            for name, expected in checksums.items():
                self.assertEqual(hashlib.sha256(zf.read(name)).hexdigest(), expected)

        self.assertEqual(draft["status"], "draft")
        self.assertIn("10分钟", before[0])
        self.assertEqual(before[1], 1)
        self.assertEqual(published["status"], "published")
        self.assertIn("20分钟", after[0])
        self.assertEqual(after[1], 2)
        self.assertEqual(versions, [1, 2])
        self.assertEqual(manifest["format_version"], "company-release-v2")
        self.assertEqual(manifest["release_id"], draft["release_id"])
        self.assertEqual(rule["rule_id"], "R001")
        self.assertNotIn("hospital_id", rule)
        self.assertTrue(term_aliases)
        self.assertTrue(all("hospital_id" not in item for item in term_aliases))
        self.assertEqual(term_release["release_id"], draft["release_id"])
        self.assertFalse(term_release["contains_hospital_candidates"])
        self.assertIn("terminology/release.json", zf.namelist())
        self.assertIn("terminology/concepts.json", zf.namelist())
        self.assertIn("terminology/aliases.json", zf.namelist())

    def test_v3_term_candidate_requires_review_and_uses_separate_queue(self) -> None:
        engine = _company_engine()
        repository = CompanyKnowledgeRepository(engine)

        report = repository.create_merge_report(
            _exchange_package(format_version="kb-exchange-v3", include_terms=True),
            "admin",
        )
        item = next(value for value in report["items"] if value["type"] == "term_candidate")
        approved = repository.approve_merge_item(
            report["report_id"], item["item_id"], "adopt_as_company_candidate", "reviewer"
        )

        with engine.connect() as conn:
            term_candidate = conn.execute(
                text("SELECT * FROM company_term_candidate WHERE candidate_id=:candidate_id"),
                {"candidate_id": approved["candidate_id"]},
            ).mappings().one()
            release_count = conn.execute(text("SELECT COUNT(*) FROM company_release")).scalar_one()

        self.assertEqual(term_candidate["concept_code"], "IND_MQSI2025_005")
        self.assertEqual(term_candidate["status"], "approved")
        self.assertEqual(release_count, 0)


def _company_engine():
    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    statements = [
        """
        CREATE TABLE company_standard_rule (
          rule_id TEXT PRIMARY KEY, rule_name TEXT NOT NULL,
          definition TEXT NOT NULL, formula TEXT NOT NULL,
          payload_json TEXT NOT NULL, version INTEGER NOT NULL,
          status TEXT NOT NULL, updated_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE company_standard_rule_version (
          id INTEGER PRIMARY KEY AUTOINCREMENT, rule_id TEXT NOT NULL,
          version INTEGER NOT NULL, payload_json TEXT NOT NULL,
          source_release_id TEXT, created_at TEXT NOT NULL,
          UNIQUE(rule_id, version)
        )
        """,
        """
        CREATE TABLE company_kb_package (
          package_id TEXT PRIMARY KEY, report_id TEXT NOT NULL UNIQUE,
          hospital_id TEXT NOT NULL, format_version TEXT NOT NULL,
          exported_at TEXT, uploaded_at TEXT NOT NULL, uploaded_by TEXT NOT NULL,
          status TEXT NOT NULL, manifest_json TEXT NOT NULL,
          package_checksum TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE company_kb_package_item (
          id INTEGER PRIMARY KEY AUTOINCREMENT, package_id TEXT NOT NULL,
          item_id TEXT NOT NULL, item_type TEXT NOT NULL, rule_id TEXT,
          rule_name TEXT, field_name TEXT, hospital_value_json TEXT,
          company_value_json TEXT, source_payload_json TEXT NOT NULL,
          status TEXT NOT NULL, decision TEXT, approver_id TEXT,
          decision_reason TEXT, decided_at TEXT,
          UNIQUE(package_id, item_id)
        )
        """,
        """
        CREATE TABLE company_rule_candidate (
          candidate_id TEXT PRIMARY KEY, package_id TEXT NOT NULL,
          item_id TEXT NOT NULL, source_hospital_id TEXT NOT NULL,
          rule_id TEXT NOT NULL, payload_json TEXT NOT NULL,
          status TEXT NOT NULL, created_at TEXT NOT NULL,
          created_by TEXT NOT NULL, release_id TEXT,
          UNIQUE(package_id, item_id)
        )
        """,
        """
        CREATE TABLE company_term_candidate (
          candidate_id TEXT PRIMARY KEY, package_id TEXT NOT NULL,
          item_id TEXT NOT NULL, source_hospital_id TEXT NOT NULL,
          concept_code TEXT NOT NULL, candidate_type TEXT NOT NULL,
          payload_json TEXT NOT NULL, status TEXT NOT NULL,
          created_at TEXT NOT NULL, created_by TEXT NOT NULL,
          UNIQUE(package_id, item_id)
        )
        """,
        """
        CREATE TABLE company_release (
          release_id TEXT PRIMARY KEY, version INTEGER NOT NULL UNIQUE,
          status TEXT NOT NULL, notes TEXT, created_by TEXT NOT NULL,
          approved_by TEXT, created_at TEXT NOT NULL, published_at TEXT
        )
        """,
        """
        CREATE TABLE company_release_item (
          id INTEGER PRIMARY KEY AUTOINCREMENT, release_id TEXT NOT NULL,
          candidate_id TEXT NOT NULL, rule_id TEXT NOT NULL,
          payload_json TEXT NOT NULL, UNIQUE(release_id, candidate_id)
        )
        """,
    ]
    with engine.begin() as conn:
        for statement in statements:
            conn.execute(text(statement))
    return engine


def _insert_company_standard(engine) -> None:
    payload = {
        "rule_id": "R001",
        "rule_name": "急会诊及时到位率",
        "definition": "公司急会诊按10分钟统计。",
        "formula": "急会诊及时到位率 = 10分钟内到位次数 / 急会诊总次数 × 100%",
    }
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                INSERT INTO company_standard_rule
                  (rule_id, rule_name, definition, formula, payload_json,
                   version, status, updated_at)
                VALUES
                  ('R001', :rule_name, :definition, :formula, :payload_json,
                   1, 'published', '2026-07-10 00:00:00')
                """
            ),
            {
                "rule_name": payload["rule_name"],
                "definition": payload["definition"],
                "formula": payload["formula"],
                "payload_json": json.dumps(payload, ensure_ascii=False),
            },
        )
        conn.execute(
            text(
                """
                INSERT INTO company_standard_rule_version
                  (rule_id, version, payload_json, source_release_id, created_at)
                VALUES ('R001', 1, :payload_json, NULL, '2026-07-10 00:00:00')
                """
            ),
            {"payload_json": json.dumps(payload, ensure_ascii=False)},
        )


def _exchange_package(
    tamper_override: bool = False,
    format_version: str = "kb-exchange-v2",
    include_terms: bool = False,
) -> bytes:
    manifest = {
        "package_id": "HKB_TEST_001",
        "hospital_id": "hospital_001",
        "exported_at": "2026-07-10T10:00:00",
        "format_version": format_version,
        "override_count": 1,
        "mapping_count": 1,
        "contains_patient_data": False,
    }
    override = {
        "rule_id": "R001",
        "rule_name": "急会诊及时到位率",
        "hospital_id": "hospital_001",
        "base_standard_version": "2025",
        "hospital_version": 3,
        "definition": "本院急会诊按20分钟统计。",
        "formula": "急会诊及时到位率 = 20分钟内到位次数 / 急会诊总次数 × 100%",
        "custom_params": {"arrive_minutes_threshold": 20},
    }
    mapping = {
        "hospital_id": "hospital_001",
        "rule_id": "R001",
        "status": "confirmed",
        "fields": {
            "request_time": {
                "db_name": "hospital_demo",
                "table_name": "consult_record",
                "column_name": "request_time",
                "data_type": "datetime",
            }
        },
    }
    files = {
        "manifest.yaml": yaml.safe_dump(
            manifest, allow_unicode=True, sort_keys=False
        ).encode("utf-8"),
        "overrides/R001.yaml": yaml.safe_dump(
            override, allow_unicode=True, sort_keys=False
        ).encode("utf-8"),
        "mappings/R001.yaml": yaml.safe_dump(
            mapping, allow_unicode=True, sort_keys=False
        ).encode("utf-8"),
    }
    if include_terms:
        candidate = {
            "hospital_id": "hospital_001",
            "concept_code": "IND_MQSI2025_005",
            "alias_text": "院内急会诊响应率",
            "relation_type": "colloquial",
            "retrieval_enabled": True,
            "sql_safe": False,
            "approval_status": "pending",
            "version": 1,
        }
        files["terminology/candidates/alias_1.yaml"] = yaml.safe_dump(
            candidate, allow_unicode=True, sort_keys=False
        ).encode("utf-8")
    checksums = {
        name: hashlib.sha256(content).hexdigest() for name, content in files.items()
    }
    if tamper_override:
        files["overrides/R001.yaml"] += b"\n# tampered"
    files["checksums.json"] = json.dumps(checksums, sort_keys=True).encode("utf-8")

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name, content in files.items():
            zf.writestr(name, content)
    return buffer.getvalue()


if __name__ == "__main__":
    unittest.main()
