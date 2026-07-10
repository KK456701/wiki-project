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
        self.assertIn("10分钟", standard[0])
        self.assertEqual(standard[1], 1)


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


def _exchange_package(tamper_override: bool = False) -> bytes:
    manifest = {
        "package_id": "HKB_TEST_001",
        "hospital_id": "hospital_001",
        "exported_at": "2026-07-10T10:00:00",
        "format_version": "kb-exchange-v2",
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
