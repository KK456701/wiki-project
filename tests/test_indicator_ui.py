import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB_INDEX = ROOT / "web" / "index.html"


class IndicatorImplementationConsoleUiTest(unittest.TestCase):
    def test_workbench_exposes_formal_indicator_implementation_page(self) -> None:
        html = WEB_INDEX.read_text(encoding="utf-8")

        for marker in (
            'data-workbench-route="indicator-console"',
            'id="indicatorConsolePage"',
            'id="indicatorModeAdaptation"',
            'id="indicatorModeNew"',
            'id="indicatorDraftPrompt"',
            'id="indicatorDraftList"',
            'id="indicatorDraftDetail"',
            'id="indicatorDraftStages"',
        ):
            self.assertIn(marker, html)
        self.assertNotIn('id="indicatorDraftModal"', html)
        self.assertIn("指标实施控制台", html)
        self.assertIn("已有指标医院适配", html)
        self.assertIn("本院新增指标", html)

    def test_console_presents_five_business_steps(self) -> None:
        html = WEB_INDEX.read_text(encoding="utf-8")
        css = (ROOT / "web" / "indicator-console.css").read_text(encoding="utf-8")

        for step in (
            "定义指标",
            "确认取数要求",
            "映射医院数据",
            "生成和验证",
            "审批和发布",
        ):
            self.assertIn(step, html)
        self.assertIn('/static/indicator-console.css', html)
        self.assertIn("repeat(5, minmax(100px, 1fr))", css)

    def test_console_calls_every_closed_loop_endpoint(self) -> None:
        html = WEB_INDEX.read_text(encoding="utf-8")

        for endpoint in (
            "/api/indicator-drafts/generate",
            "/requirements-confirm",
            "/metadata-suggestions",
            "/metadata-confirm",
            "/sql-generate",
            "/trial-run",
            "/submit",
            "/approve",
        ):
            self.assertIn(endpoint, html)

    def test_console_explains_data_requirements_in_business_language(self) -> None:
        html = WEB_INDEX.read_text(encoding="utf-8")

        for marker in (
            "function indicatorBusinessFieldLabel",
            "function renderIndicatorRequirementSummary",
            "分母怎么得到",
            "分子怎么得到",
            "系统统一名称",
            "医院数据库位置",
            "本院取数位置",
            "映射确认后显示实际数据库、表和字段",
        ):
            self.assertIn(marker, html)

        self.assertIn("医院范围", html)
        self.assertIn("申请时间", html)
        self.assertIn("到位时间", html)

    def test_readme_documents_the_formal_implementation_workflow(self) -> None:
        readme = (ROOT / "README.md").read_text(encoding="utf-8")

        for marker in (
            "### 指标实施控制台",
            "定义指标 -> 确认取数要求 -> 映射医院数据 -> 生成和验证 -> 审批和发布",
            "已有指标医院适配",
            "本院新增指标",
            "/api/indicator-drafts/from-release",
            "/requirements-confirm",
        ):
            self.assertIn(marker, readme)


if __name__ == "__main__":
    unittest.main()
