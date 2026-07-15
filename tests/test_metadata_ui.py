import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class MetadataUiTest(unittest.TestCase):
    def test_page_exposes_metadata_workspace_instead_of_test_modal(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

        for marker in (
            'data-workbench-route="metadata"',
            'id="metadataPage"',
            'id="metadataSyncButton"',
            'id="metadataOverview"',
            'id="metadataChanges"',
            'id="metadataAffectedRules"',
            'id="metadataConnectionDetails"',
            "重新检查连接",
            '/static/metadata.css',
            '/static/metadata.js',
        ):
            self.assertIn(marker, html)
        self.assertNotIn("DBHub MCP 测试", html)
        self.assertNotIn('id="mcpModal"', html)
        self.assertNotIn("请先在 MCP 中同步", html)

    def test_metadata_script_loads_overview_sources_and_syncs_structure(self) -> None:
        js = (ROOT / "web" / "metadata.js").read_text(encoding="utf-8")

        for marker in (
            "function activateMetadataPage",
            "function loadMetadataOverview",
            "function loadMetadataSources",
            "function syncMetadataStructure",
            "function readMetadataResponse",
            "function renderMetadataOverview",
            "function renderMetadataChanges",
            "function renderAffectedRules",
            '"/api/metadata/overview?hospital_id="',
            '"/api/metadata/sync"',
            "同步数据库结构",
            'source.name !== "wiki_agent_runtime"',
        ):
            self.assertIn(marker, js)
        self.assertNotIn("metadataSourceList.innerHTML = '<div", js)

    def test_metadata_route_is_registered_without_admin_gate(self) -> None:
        js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

        self.assertIn('metadata: {requiresAdmin: false}', js)
        self.assertIn('route === "metadata"', js)
        self.assertIn('window.activateMetadataPage()', js)

    def test_metadata_styles_support_business_layout_and_mobile(self) -> None:
        css = (ROOT / "web" / "metadata.css").read_text(encoding="utf-8")

        for marker in (
            ".metadata-page-surface",
            ".metadata-status-strip",
            ".metadata-impact-grid",
            ".metadata-connection-details",
            "@media (max-width: 760px)",
        ):
            self.assertIn(marker, css)

    def test_readme_explains_metadata_workspace(self) -> None:
        readme = (ROOT / "README.md").read_text(encoding="utf-8")

        for marker in (
            "数据库与元数据工作台",
            "同步数据库结构",
            "不读取患者业务数据",
            "连接详情",
        ):
            self.assertIn(marker, readme)
        self.assertNotIn("缺少字段时先在 MCP 页面同步元数据", readme)


if __name__ == "__main__":
    unittest.main()
