import unittest
from pathlib import Path


WEB_INDEX = Path(__file__).resolve().parents[1] / "web" / "index.html"


class IndicatorDraftUiTest(unittest.TestCase):
    def test_chat_page_exposes_indicator_draft_workspace(self) -> None:
        html = WEB_INDEX.read_text(encoding="utf-8")

        for marker in (
            'id="indicatorDraftButton"',
            'id="indicatorDraftModal"',
            'id="indicatorDraftPrompt"',
            'id="indicatorDraftList"',
            'id="indicatorDraftDetail"',
            'id="indicatorDraftStages"',
        ):
            self.assertIn(marker, html)
        self.assertIn("指标设计稿", html)
        self.assertIn("本院新增指标", html)
        self.assertIn("本院口径差异", html)

    def test_workspace_calls_every_closed_loop_endpoint(self) -> None:
        html = WEB_INDEX.read_text(encoding="utf-8")

        for endpoint in (
            "/api/indicator-drafts/generate",
            "/metadata-suggestions",
            "/metadata-confirm",
            "/sql-generate",
            "/trial-run",
            "/submit",
            "/approve",
        ):
            self.assertIn(endpoint, html)
        for status in (
            "待确认字段",
            "字段已确认",
            "SQL 已生成",
            "试运行通过",
            "待审批",
            "已发布",
        ):
            self.assertIn(status, html)


if __name__ == "__main__":
    unittest.main()
