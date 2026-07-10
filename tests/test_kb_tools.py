import json
import unittest
import uuid
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

from app.kb.tools import KnowledgeBaseTools, atomic_write_text


TEST_TMP_ROOT = Path(__file__).resolve().parents[1] / "test_tmp"


@contextmanager
def temp_kb_dir():
    TEST_TMP_ROOT.mkdir(exist_ok=True)
    path = TEST_TMP_ROOT / f"kb_{uuid.uuid4().hex}"
    path.mkdir(parents=True, exist_ok=False)
    yield str(path)
def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def write_json(path: Path, data: object) -> None:
    write(path, json.dumps(data, ensure_ascii=False, indent=2))


def make_minimal_kb(root: Path, with_hospital: bool = False) -> None:
    write_json(
        root / "indexes/rule_index.json",
        {
            "rules": [
                {
                    "rule_id": "R001",
                    "rule_name": "急会诊及时到位率",
                    "aliases": ["急会诊及时到位率"],
                    "category": "会诊制度",
                    "national_path": "wiki/standards/national/R001.md",
                    "company_path": "wiki/standards/company/R001_company.md",
                    "status": "active",
                }
            ]
        },
    )
    write_json(
        root / "indexes/search_index.json",
        [
            {
                "chunk_id": "R001_definition",
                "rule_id": "R001",
                "title": "急会诊及时到位率_指标定义",
                "path": "wiki/standards/national/R001.md",
                "section": "指标定义",
                "keywords": ["R001", "急会诊及时到位率"],
                "related_rule_ids": [],
                "related_fields": [],
                "related_tables": [],
                "content": "急会诊请求发出后，10 分钟内到达现场。",
            }
        ],
    )
    write_json(
        root / "indexes/field_index.json",
        {
            "status": "pending_field_mapping",
            "field_roles": [
                {
                    "rule_id": "R001",
                    "rule_name": "急会诊及时到位率",
                    "roles": ["分子计数对象"],
                    "standard_fields": [],
                    "status": "待医院字段映射确认",
                }
            ],
        },
    )
    write_json(
        root / "indexes/relation_index.json",
        {
            "R001": {
                "rule_name": "急会诊及时到位率",
                "relations": {"same_theme": []},
            }
        },
    )
    write_json(
        root / "indexes/hospital_override_index.json",
        {
            "hospital_overrides": [
                {
                    "hospital_id": "hospital_001",
                    "rule_id": "R001",
                    "path": "wiki/hospitals/hospital_001/overrides/R001_override.md",
                    "status": "approved",
                    "version": "hospital_001_v1.0",
                }
            ]
            if with_hospital
            else []
        },
    )
    write(
        root / "wiki/standards/national/R001.md",
        """# 急会诊及时到位率

## 指标定义

急会诊请求发出后，10 分钟内到达现场的急会诊次数占同期急会诊总次数的比例。

## 计算公式

急会诊及时到位率 = 10分钟内到位急会诊次数 / 同期急会诊总次数 × 100%
""",
    )
    write(
        root / "wiki/standards/company/R001_company.md",
        """# 急会诊及时到位率_公司标准

## 公司实现口径

公司标准继承国标。

## 公司标准 SQL

原文未明确。待医院字段映射确认后，经 review/pending 提交变更申请。
""",
    )
    if with_hospital:
        write(
            root / "wiki/hospitals/hospital_001/overrides/R001_override.md",
            """# 急会诊及时到位率_本院口径

## 本院指标定义

本院急会诊以申请单创建时间到医生签到时间计算。

## 本院计算公式

本院急会诊及时到位率 = 15分钟内签到急会诊次数 / 同期急会诊总次数 × 100%

## 本院标准 SQL

待医院字段映射确认。
""",
        )


class KnowledgeBaseToolsTest(unittest.TestCase):
    def test_atomic_write_text_preserves_existing_file_when_replace_fails(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            target = root / "indexes" / "search_index.json"
            write(target, "old")

            with patch("pathlib.Path.replace", side_effect=OSError("replace failed")):
                with self.assertRaises(OSError):
                    atomic_write_text(target, "new")

            self.assertEqual(target.read_text(encoding="utf-8"), "old")
            self.assertEqual(list(target.parent.glob("*.tmp")), [])

    def test_get_effective_rule_prefers_hospital_override(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=True)

            result = KnowledgeBaseTools(root).get_effective_rule("R001", "hospital_001")

            self.assertEqual(result["effective_level"], "hospital")
            self.assertIn("15分钟内签到", result["formula"])
            self.assertEqual(result["fallback_chain"], ["hospital", "company", "national"])

    def test_get_effective_rule_falls_back_to_company_with_national_formula(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)

            result = KnowledgeBaseTools(root).get_effective_rule("R001", "hospital_001")

            self.assertEqual(result["effective_level"], "company")
            self.assertIn("10分钟内到位", result["formula"])
            self.assertIn("待医院字段映射确认", result["implementation_status"])
            self.assertIn("hospital_override_not_configured", result["warnings"])

    def test_submit_change_request_writes_pending_markdown(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root)
            tools = KnowledgeBaseTools(root)

            result = tools.submit_change_request(
                {
                    "rule_id": "R001",
                    "indicator_name": "急会诊及时到位率",
                    "hospital_id": "hospital_001",
                    "change_type": "分子修正",
                    "hospital_feedback": "本院按15分钟计算。",
                    "original_user_message": "我们医院急会诊按15分钟算。",
                }
            )

            self.assertEqual(result["status"], "pending")
            created = root / result["path"]
            self.assertTrue(created.exists())
            self.assertIn("本院按15分钟计算", created.read_text(encoding="utf-8"))

    def test_create_pending_then_approve_hospital_change_request(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            tools = KnowledgeBaseTools(root)

            pending = tools.submit_change_request(
                {
                    "rule_id": "R001",
                    "indicator_name": "\u516c\u53f8\u6807\u51c6\u7ee7\u627f\u56fd\u6807",
                    "hospital_id": "hospital_001",
                    "target_level": "hospital",
                    "change_type": "??????",
                    "requested_definition": "\u672c\u9662\u6025\u4f1a\u8bca\u6309\u7533\u8bf7\u53d1\u51fa\u5230\u533b\u751f\u7b7e\u5230\u4e0d\u8d85\u8fc7\u0032\u0030\u5206\u949f\u7edf\u8ba1\u3002",
                    "requested_formula": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = \u0032\u0030\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 \u0031\u0030\u0030%",
                    "hospital_feedback": "\u6211\u4eec\u533b\u9662\u6309\u0032\u0030\u5206\u949f\u8ba1\u7b97\u3002",
                    "original_user_message": "\u6211\u4eec\u533b\u9662\u6309\u0032\u0030\u5206\u949f\u8ba1\u7b97\u3002",
                }
            )

            self.assertEqual(pending["status"], "pending")
            self.assertEqual(len(tools.list_pending_change_requests()), 1)
            approved = tools.approve_change_request(pending["change_id"])
            effective = tools.get_effective_rule("R001", "hospital_001")
            company_page = (root / "wiki/standards/company/R001_company.md").read_text(encoding="utf-8")

            self.assertEqual(approved["status"], "approved")
            self.assertEqual(effective["effective_level"], "hospital")
            self.assertIn("\u0032\u0030\u5206\u949f\u5185\u7b7e\u5230", effective["formula"])
            self.assertIn("\u516c\u53f8\u6807\u51c6\u7ee7\u627f\u56fd\u6807", company_page)
            self.assertEqual(tools.list_pending_change_requests(), [])


    def test_approve_appends_versions_and_restore_switches_active_override(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=True)
            tools = KnowledgeBaseTools(root)

            first = tools.submit_change_request(
                {
                    "rule_id": "R001",
                    "indicator_name": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387",
                    "hospital_id": "hospital_001",
                    "target_level": "hospital",
                    "requested_definition": "\u672c\u9662\u6025\u4f1a\u8bca\u6309\u7533\u8bf7\u5230\u7b7e\u5230\u4e0d\u8d85\u8fc7\u0032\u0030\u5206\u949f\u7edf\u8ba1\u3002",
                    "requested_formula": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = \u0032\u0030\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 \u0031\u0030\u0030%",
                }
            )
            approved_first = tools.approve_change_request(first["change_id"], approver_id="admin_a")

            second = tools.submit_change_request(
                {
                    "rule_id": "R001",
                    "indicator_name": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387",
                    "hospital_id": "hospital_001",
                    "target_level": "hospital",
                    "requested_definition": "\u672c\u9662\u6025\u4f1a\u8bca\u6309\u7533\u8bf7\u5230\u7b7e\u5230\u4e0d\u8d85\u8fc7\u0033\u0030\u5206\u949f\u7edf\u8ba1\u3002",
                    "requested_formula": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = \u0033\u0030\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 \u0031\u0030\u0030%",
                }
            )
            approved_second = tools.approve_change_request(second["change_id"], approver_id="admin_b")

            history = tools.list_hospital_override_versions("R001", "hospital_001")
            effective_latest = tools.get_effective_rule("R001", "hospital_001")
            restored = tools.restore_hospital_override_version(
                "R001",
                "hospital_001",
                approved_first["active_version_id"],
                approver_id="admin_restore",
            )
            effective_restored = tools.get_effective_rule("R001", "hospital_001")

            self.assertEqual(history["active_version_id"], approved_second["active_version_id"])
            self.assertGreaterEqual(len(history["versions"]), 3)
            self.assertTrue((root / approved_first["active_version_path"]).exists())
            self.assertTrue((root / approved_second["active_version_path"]).exists())
            self.assertIn("\u0033\u0030\u5206\u949f\u5185\u7b7e\u5230", effective_latest["formula"])
            self.assertEqual(restored["active_version_id"], approved_first["active_version_id"])
            self.assertIn("\u0032\u0030\u5206\u949f\u5185\u7b7e\u5230", effective_restored["formula"])
            self.assertEqual(tools.list_hospital_override_versions("R001", "hospital_001")["active_version_id"], approved_first["active_version_id"])

    def test_rebuild_runtime_indexes_preserves_hospital_override_search_and_relations(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            tools = KnowledgeBaseTools(root)
            pending = tools.submit_change_request(
                {
                    "rule_id": "R001",
                    "indicator_name": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387",
                    "hospital_id": "hospital_001",
                    "target_level": "hospital",
                    "requested_definition": "\u672c\u9662\u6025\u4f1a\u8bca\u6309\u7533\u8bf7\u5230\u7b7e\u5230\u4e0d\u8d85\u8fc7\u0032\u0030\u5206\u949f\u7edf\u8ba1\u3002",
                    "requested_formula": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = \u0032\u0030\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 \u0031\u0030\u0030%",
                }
            )
            approved = tools.approve_change_request(pending["change_id"])

            rebuilt_once = tools.rebuild_runtime_indexes()
            rebuilt_twice = tools.rebuild_runtime_indexes()
            search = tools.search("\u0032\u0030\u5206\u949f\u5185\u7b7e\u5230", limit=5)
            relation = tools.get_relations("R001")
            override_index = json.loads((root / "indexes/hospital_override_index.json").read_text(encoding="utf-8"))
            hospital_chunks = [item for item in json.loads((root / "indexes/search_index.json").read_text(encoding="utf-8")) if item.get("type") == "hospital_override"]

            self.assertEqual(rebuilt_once["hospital_overrides"], 1)
            self.assertEqual(rebuilt_twice["hospital_overrides"], 1)
            self.assertEqual(len(hospital_chunks), 3)
            self.assertTrue(any(match.get("type") == "hospital_override" for match in search["matches"]))
            self.assertEqual(relation["relations"]["has_hospital_override"][0]["active_version_id"], approved["active_version_id"])
            self.assertEqual(override_index["hospital_overrides"][0]["active_version_id"], approved["active_version_id"])

    def test_rejects_company_level_change_request_for_hospital_mvp(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            tools = KnowledgeBaseTools(root)

            with self.assertRaises(Exception):
                tools.submit_change_request(
                    {
                        "rule_id": "R001",
                        "indicator_name": "\u516c\u53f8\u6807\u51c6\u7ee7\u627f\u56fd\u6807",
                        "hospital_id": "hospital_001",
                        "target_level": "company",
                        "requested_formula": "\u516c\u53f8\u4e5f\u6539\u6210\u0032\u0030\u5206\u949f\u3002",
                    }
                )


if __name__ == "__main__":
    unittest.main()





