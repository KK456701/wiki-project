
import io
import hashlib
import json
import unittest
import zipfile
from datetime import datetime, timedelta
from pathlib import Path

import yaml
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.kb.export import export_hospital_kb_zip
from app.kb.merge import MergeError, approve_merge_item, create_merge_report, read_merge_report
from tests.test_kb_tools import make_minimal_kb, temp_kb_dir


RULE_NAME = "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387"
DEF_20 = "\u672c\u9662\u6025\u4f1a\u8bca\u630920\u5206\u949f\u7edf\u8ba1\u3002"
FORMULA_20 = "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = 20\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 100%"


class KnowledgeBaseMergeTest(unittest.TestCase):
    def test_export_hospital_kb_zip_reads_current_mysql_projection(self) -> None:
        engine = _hospital_engine()
        now = datetime.now()
        with engine.begin() as conn:
            _insert_standard(conn, "R_ACTIVE", "急会诊及时到位率")
            _insert_standard(conn, "R_EXPIRED", "过期指标")
            _insert_standard(conn, "R_PENDING", "待审批指标")
            _insert_custom(conn, "R_ACTIVE", 3, "approved", None, None)
            _insert_custom(
                conn,
                "R_EXPIRED",
                2,
                "approved",
                now - timedelta(days=10),
                now - timedelta(days=1),
            )
            _insert_custom(conn, "R_PENDING", 1, "pending", None, None)
            conn.execute(
                text(
                    """
                    INSERT INTO med_field_mapping
                      (hospital_id, rule_id, business_field, db_name, table_name,
                       column_name, data_type, status, updated_by, updated_at)
                    VALUES
                      ('hospital_001', 'R_ACTIVE', 'request_time', 'hospital_demo',
                       'consult_record', 'request_time', 'datetime', 'confirmed',
                       'tester', :updated_at),
                      ('hospital_001', 'R_ACTIVE', 'arrive_time', 'hospital_demo',
                       'consult_record', 'arrive_time', 'datetime', 'pending',
                       'tester', :updated_at)
                    """
                ),
                {"updated_at": now},
            )

        data = export_hospital_kb_zip(engine, "hospital_001")

        with zipfile.ZipFile(io.BytesIO(data), "r") as zf:
            names = set(zf.namelist())
            manifest = yaml.safe_load(zf.read("manifest.yaml").decode("utf-8"))
            override = yaml.safe_load(
                zf.read("overrides/R_ACTIVE.yaml").decode("utf-8")
            )
            mapping = yaml.safe_load(
                zf.read("mappings/R_ACTIVE.yaml").decode("utf-8")
            )
            checksums = json.loads(zf.read("checksums.json").decode("utf-8"))

            for name, expected in checksums.items():
                self.assertEqual(hashlib.sha256(zf.read(name)).hexdigest(), expected)

        self.assertEqual(manifest["format_version"], "kb-exchange-v2")
        self.assertEqual(manifest["hospital_id"], "hospital_001")
        self.assertEqual(manifest["override_count"], 1)
        self.assertEqual(override["rule_id"], "R_ACTIVE")
        self.assertEqual(override["hospital_version"], 3)
        self.assertEqual(override["base_standard_version"], "2025")
        self.assertNotIn("overrides/R_EXPIRED.yaml", names)
        self.assertNotIn("overrides/R_PENDING.yaml", names)
        self.assertEqual(list(mapping["fields"]), ["request_time"])

    def test_create_merge_report_detects_caliber_conflict_without_mutating_company_standard(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            before = (root / 'wiki/standards/company/R001_company.md').read_text(encoding='utf-8')
            uploaded = _zip_bytes({
                'manifest.yaml': yaml.safe_dump({'hospital_id': 'hospital_001'}, allow_unicode=True),
                'overrides/R001.yaml': yaml.safe_dump({
                    'rule_id': 'R001',
                    'rule_name': RULE_NAME,
                    'hospital_id': 'hospital_001',
                    'definition': DEF_20,
                    'formula': FORMULA_20,
                }, allow_unicode=True),
            })

            report = create_merge_report(root, uploaded, uploaded_by='admin')
            after = (root / 'wiki/standards/company/R001_company.md').read_text(encoding='utf-8')

            self.assertEqual(before, after)
            self.assertEqual(report['status'], 'pending_review')
            self.assertEqual(report['summary']['conflicts'], 1)
            self.assertEqual(report['items'][0]['type'], 'caliber_conflict')
            self.assertEqual(report['items'][0]['status'], 'pending')
            self.assertEqual(report['items'][0]['hospital_value'], '20\u5206\u949f')
            self.assertEqual(report['items'][0]['company_value'], '10\u5206\u949f')
            self.assertTrue((root / 'merge-reports' / report['report_id'] / 'report.json').exists())

    def test_create_merge_report_rejects_zip_slip_paths(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            uploaded = _zip_bytes({
                'manifest.yaml': yaml.safe_dump({'hospital_id': 'hospital_001'}, allow_unicode=True),
                '../evil.txt': 'bad',
            })

            with self.assertRaises(MergeError):
                create_merge_report(root, uploaded, uploaded_by='admin')

    def test_approve_merge_item_records_candidate_without_changing_company_standard(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            before = (root / 'wiki/standards/company/R001_company.md').read_text(encoding='utf-8')
            uploaded = _zip_bytes({
                'manifest.yaml': yaml.safe_dump({'hospital_id': 'hospital_001'}, allow_unicode=True),
                'overrides/R001.yaml': yaml.safe_dump({
                    'rule_id': 'R001',
                    'rule_name': RULE_NAME,
                    'hospital_id': 'hospital_001',
                    'definition': DEF_20,
                    'formula': FORMULA_20,
                }, allow_unicode=True),
            })
            report = create_merge_report(root, uploaded, uploaded_by='admin')

            result = approve_merge_item(root, report['report_id'], report['items'][0]['item_id'], 'adopt_as_company_candidate', 'admin')
            updated = read_merge_report(root, report['report_id'])
            after = (root / 'wiki/standards/company/R001_company.md').read_text(encoding='utf-8')

            self.assertEqual(before, after)
            self.assertEqual(result['status'], 'approved_candidate')
            self.assertEqual(updated['items'][0]['decision'], 'adopt_as_company_candidate')
            self.assertTrue((root / 'merge-reports' / report['report_id'] / 'candidates' / (report['items'][0]['item_id'] + '.json')).exists())
            self.assertIn('adopt_as_company_candidate', (root / 'merge-reports' / report['report_id'] / 'audit.log').read_text(encoding='utf-8'))


def _zip_bytes(files: dict[str, str]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
        for name, content in files.items():
            zf.writestr(name, content)
    return buffer.getvalue()


def _hospital_engine():
    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                CREATE TABLE med_index_standard (
                  index_code TEXT PRIMARY KEY,
                  index_name TEXT NOT NULL,
                  index_type TEXT NOT NULL,
                  index_desc TEXT NOT NULL,
                  numerator_rule TEXT NOT NULL,
                  denominator_rule TEXT NOT NULL,
                  filter_rule TEXT,
                  exclude_rule TEXT,
                  version TEXT NOT NULL,
                  status INTEGER NOT NULL
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE med_index_hospital_custom (
                  hospital_id TEXT NOT NULL,
                  index_code TEXT NOT NULL,
                  custom_numerator TEXT,
                  custom_denominator TEXT,
                  custom_filter TEXT,
                  exclude_rule TEXT,
                  custom_params TEXT NOT NULL,
                  custom_sql TEXT,
                  version INTEGER NOT NULL,
                  status INTEGER NOT NULL,
                  approval_status TEXT NOT NULL,
                  effective_from DATETIME,
                  effective_to DATETIME,
                  oper_user TEXT,
                  update_time DATETIME,
                  PRIMARY KEY (hospital_id, index_code)
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE med_field_mapping (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  hospital_id TEXT NOT NULL,
                  rule_id TEXT NOT NULL,
                  business_field TEXT NOT NULL,
                  db_name TEXT NOT NULL,
                  table_name TEXT NOT NULL,
                  column_name TEXT NOT NULL,
                  data_type TEXT,
                  status TEXT NOT NULL,
                  updated_by TEXT,
                  updated_at DATETIME NOT NULL
                )
                """
            )
        )
    return engine


def _insert_standard(conn, rule_id: str, name: str) -> None:
    conn.execute(
        text(
            """
            INSERT INTO med_index_standard
              (index_code, index_name, index_type, index_desc, numerator_rule,
               denominator_rule, filter_rule, exclude_rule, version, status)
            VALUES
              (:rule_id, :name, '测试指标', :description, '分子', '分母', NULL,
               NULL, '2025', 1)
            """
        ),
        {"rule_id": rule_id, "name": name, "description": f"{name}定义"},
    )


def _insert_custom(
    conn,
    rule_id: str,
    version: int,
    approval_status: str,
    effective_from,
    effective_to,
) -> None:
    conn.execute(
        text(
            """
            INSERT INTO med_index_hospital_custom
              (hospital_id, index_code, custom_numerator, custom_denominator,
               custom_filter, exclude_rule, custom_params, custom_sql, version,
               status, approval_status, effective_from, effective_to, oper_user,
               update_time)
            VALUES
              ('hospital_001', :rule_id, '本院分子', NULL, NULL, NULL,
               :custom_params, NULL, :version, 1, :approval_status,
               :effective_from, :effective_to, 'tester', :updated_at)
            """
        ),
        {
            "rule_id": rule_id,
            "custom_params": json.dumps(
                {"arrive_minutes_threshold": 20}, ensure_ascii=False
            ),
            "version": version,
            "approval_status": approval_status,
            "effective_from": effective_from,
            "effective_to": effective_to,
            "updated_at": datetime.now(),
        },
    )


if __name__ == '__main__':
    unittest.main()
