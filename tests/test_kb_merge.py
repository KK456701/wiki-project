
import io
import json
import unittest
import zipfile
from pathlib import Path

import yaml

from app.kb.export import export_hospital_kb_zip
from app.kb.merge import MergeError, approve_merge_item, create_merge_report, read_merge_report
from tests.test_kb_tools import make_minimal_kb, temp_kb_dir, write


RULE_NAME = "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387"
DEF_20 = "\u672c\u9662\u6025\u4f1a\u8bca\u630920\u5206\u949f\u7edf\u8ba1\u3002"
FORMULA_20 = "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = 20\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 100%"


class KnowledgeBaseMergeTest(unittest.TestCase):
    def test_export_hospital_kb_zip_contains_manifest_override_and_mapping(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=True)
            write(
                root / 'hospital-mappings/hospital_001/R001.yaml',
                'hospital_id: hospital_001\nrule_id: R001\nstatus: confirmed\nfields:\n  hospital_id: table.hospital_id\n',
            )

            data = export_hospital_kb_zip(root, 'hospital_001')

            with zipfile.ZipFile(io.BytesIO(data), 'r') as zf:
                names = set(zf.namelist())
                manifest = yaml.safe_load(zf.read('manifest.yaml').decode('utf-8'))
                override = yaml.safe_load(zf.read('overrides/R001.yaml').decode('utf-8'))

            self.assertEqual(manifest['hospital_id'], 'hospital_001')
            self.assertIn('overrides/R001.yaml', names)
            self.assertIn('mappings/R001.yaml', names)
            self.assertEqual(override['rule_id'], 'R001')
            self.assertIn('15\u5206\u949f\u5185\u7b7e\u5230', override['formula'])

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


if __name__ == '__main__':
    unittest.main()
