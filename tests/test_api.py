import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

import app.api.main as api_main
from app.api.main import app
from tests.test_kb_tools import make_minimal_kb, temp_kb_dir


class ApiTest(unittest.TestCase):
    def test_change_request_default_change_type_is_readable(self) -> None:
        request = api_main.ChangeRequestCreate(
            rule_id="R001",
            hospital_id="hospital_001",
            requested_formula="formula",
        )

        self.assertEqual(request.change_type, "本院口径反馈")

    def test_chat_endpoint_returns_answer(self) -> None:
        client = TestClient(app)

        response = client.post(
            "/api/chat",
            json={"query": "急会诊及时到位率怎么算？", "hospital_id": "hospital_001", "use_llm": False},
        )

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["rule_id"], "MQSI2025_005")
        self.assertIn("急会诊及时到位率", data["answer"])
        self.assertIn("当前不能生成可执行 SQL", data["answer"])

    def test_chat_endpoint_uses_session_memory_for_follow_up_feedback(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            client = TestClient(app)

            with patch.object(api_main, "DEFAULT_KB_ROOT", root):
                first = client.post(
                    "/api/chat",
                    json={
                        "query": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387\u600e\u4e48\u7b97\uff1f",
                        "hospital_id": "hospital_001",
                        "use_llm": False,
                    },
                ).json()
                second = client.post(
                    "/api/chat",
                    json={
                        "query": "\u6211\u4eec\u533b\u9662\u662f\u6309\u716730\u5206\u949f\u6765\u7684",
                        "hospital_id": "hospital_001",
                        "use_llm": False,
                        "session_id": first["session_id"],
                    },
                ).json()

            self.assertEqual(second["session_id"], first["session_id"])
            self.assertEqual(second["intent"], "feedback")
            self.assertEqual(second["rule_id"], "R001")
            self.assertEqual(second["feedback_preview"]["target_level"], "hospital")
            self.assertNotIn("change_request", second)

    def test_review_api_creates_and_approves_hospital_change_request(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            client = TestClient(app)

            with patch.object(api_main, "DEFAULT_KB_ROOT", root):
                login = client.post("/api/admin/login", json={"password": "admin123"})
                token = login.json()["token"]
                headers = {"Authorization": f"Bearer {token}"}
                created = client.post(
                    "/api/review/change-requests",
                    json={
                        "rule_id": "R001",
                        "indicator_name": "????????",
                        "hospital_id": "hospital_001",
                        "target_level": "hospital",
                        "requested_definition": "\u672c\u9662\u6025\u4f1a\u8bca\u6309\u7533\u8bf7\u53d1\u51fa\u5230\u533b\u751f\u7b7e\u5230\u4e0d\u8d85\u8fc7\u0032\u0030\u5206\u949f\u7edf\u8ba1\u3002",
                        "requested_formula": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = \u0032\u0030\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 \u0031\u0030\u0030%",
                        "hospital_feedback": "\u6211\u4eec\u533b\u9662\u6309\u0032\u0030\u5206\u949f\u8ba1\u7b97\u3002",
                        "original_user_message": "\u6211\u4eec\u533b\u9662\u6309\u0032\u0030\u5206\u949f\u8ba1\u7b97\u3002",
                    },
                )
                pending = client.get("/api/review/pending", headers=headers)
                approved = client.post(
                    f"/api/review/change-requests/{created.json()['change_id']}/approve",
                    headers=headers,
                )
                effective = client.get("/api/kb/rules/R001/effective", params={"hospital_id": "hospital_001"})

            self.assertEqual(login.status_code, 200)
            self.assertEqual(created.status_code, 200)
            self.assertEqual(pending.status_code, 200)
            self.assertEqual(len(pending.json()["items"]), 1)
            self.assertEqual(approved.json()["status"], "approved")
            self.assertEqual(effective.json()["effective_level"], "hospital")
            self.assertIn("\u0032\u0030\u5206\u949f\u5185\u7b7e\u5230", effective.json()["formula"])


    def test_review_api_lists_and_restores_hospital_override_versions(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            client = TestClient(app)

            with patch.object(api_main, "DEFAULT_KB_ROOT", root):
                login = client.post("/api/admin/login", json={"password": "admin123"})
                headers = {"Authorization": f"Bearer {login.json()['token']}"}
                first = client.post(
                    "/api/review/change-requests",
                    json={
                        "rule_id": "R001",
                        "hospital_id": "hospital_001",
                        "target_level": "hospital",
                        "requested_definition": "\u672c\u9662\u6025\u4f1a\u8bca\u6309\u0032\u0030\u5206\u949f\u7edf\u8ba1\u3002",
                        "requested_formula": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = \u0032\u0030\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 \u0031\u0030\u0030%",
                    },
                )
                approved_first = client.post(f"/api/review/change-requests/{first.json()['change_id']}/approve", headers=headers).json()
                second = client.post(
                    "/api/review/change-requests",
                    json={
                        "rule_id": "R001",
                        "hospital_id": "hospital_001",
                        "target_level": "hospital",
                        "requested_definition": "\u672c\u9662\u6025\u4f1a\u8bca\u6309\u0033\u0030\u5206\u949f\u7edf\u8ba1\u3002",
                        "requested_formula": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = \u0033\u0030\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 \u0031\u0030\u0030%",
                    },
                )
                approved_second = client.post(f"/api/review/change-requests/{second.json()['change_id']}/approve", headers=headers).json()
                versions = client.get("/api/review/hospital-overrides/hospital_001/R001/versions", headers=headers)
                restored = client.post(
                    f"/api/review/hospital-overrides/hospital_001/R001/versions/{approved_first['active_version_id']}/restore",
                    headers=headers,
                    json={"approver_id": "admin_restore"},
                )
                effective = client.get("/api/kb/rules/R001/effective", params={"hospital_id": "hospital_001"})

            self.assertEqual(versions.status_code, 200)
            self.assertEqual(versions.json()["active_version_id"], approved_second["active_version_id"])
            self.assertEqual(len(versions.json()["versions"]), 2)
            self.assertEqual(restored.status_code, 200)
            self.assertEqual(restored.json()["active_version_id"], approved_first["active_version_id"])
            self.assertIn("\u0032\u0030\u5206\u949f\u5185\u7b7e\u5230", effective.json()["formula"])

    def test_review_pending_without_admin_token_returns_401(self) -> None:
        client = TestClient(app)

        response = client.get("/api/review/pending")

        self.assertEqual(response.status_code, 401)

    def test_chat_stream_greeting_returns_chat_intent(self) -> None:
        client = TestClient(app)
        response = client.post(
            "/api/chat/stream",
            json={"query": "\u4f60\u597d", "hospital_id": "hospital_001", "use_llm": False},
        )

        self.assertEqual(response.status_code, 200)
        body = response.text
        self.assertIn('"intent": "chat"', body)
        self.assertIn('"rule_id": null', body)
        self.assertIn("\u6838\u5fc3\u5236\u5ea6\u6307\u6807 Agent", body)

    def test_chat_stream_returns_sse_events(self) -> None:
        client = TestClient(app)
        response = client.post(
            "/api/chat/stream",
            json={"query": "????????????", "hospital_id": "hospital_001", "use_llm": False},
        )

        self.assertEqual(response.status_code, 200)
        body = response.text
        self.assertIn("event: token", body)
        self.assertIn("event: done", body)


if __name__ == "__main__":
    unittest.main()
