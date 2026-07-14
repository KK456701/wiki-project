import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class PackageExchangeUiTest(unittest.TestCase):
    def test_metadata_page_exposes_offline_package_workspace(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

        for marker in (
            'id="packageExchangeTab"',
            'id="packageExchangeWorkspace"',
            'id="metadataScopeList"',
            'id="metadataScopeSaveButton"',
            'id="metadataExportPreview"',
            'id="hospitalFeedbackExportButton"',
            'id="companyReleaseImportInput"',
            'id="companyReleaseImportButton"',
            'id="companyReleaseImportList"',
            'id="companyReleaseImportDetail"',
            "/static/package-exchange.css",
            "/static/package-exchange.js",
            "不包含患者明细",
        ):
            self.assertIn(marker, html)

    def test_script_manages_scope_export_and_quarantined_imports(self) -> None:
        js = (ROOT / "web" / "package-exchange.js").read_text(encoding="utf-8")

        for marker in (
            "function activatePackageExchangeWorkspace",
            "function activatePackageExchangeAdmin",
            "function loadMetadataExportScope",
            "function saveMetadataExportScope",
            "function downloadHospitalFeedbackPackage",
            "function importCompanyReleasePackage",
            "function loadCompanyReleaseImports",
            '"/api/kb/export/scope?hospital_id="',
            '"/api/kb/export/preview?hospital_id="',
            '"/api/kb/export?hospital_id="',
            '"/api/kb/hospital/releases/imports"',
            "ready_for_adaptation",
            "legacy_unsigned",
            "requireAdminThenOpen(\"packageExchange\")",
        ):
            self.assertIn(marker, js)

    def test_styles_prioritize_operational_readability_and_mobile(self) -> None:
        css = (ROOT / "web" / "package-exchange.css").read_text(encoding="utf-8")

        for marker in (
            ".package-exchange-workspace",
            ".package-exchange-grid",
            ".metadata-scope-table",
            ".package-import-history",
            ".package-status",
            "@media (max-width: 900px)",
        ):
            self.assertIn(marker, css)

    def test_readme_explains_signed_offline_exchange_workflow(self) -> None:
        readme = (ROOT / "README.md").read_text(encoding="utf-8")

        for marker in (
            "签名离线包交换",
            "generate_package_keys.py",
            "hospital-private.pem",
            "company-private.pem",
            "离线包交换",
            "旧版未签名包",
            "结构校验不能替代院内真实数据试运行",
            "不包含患者明细",
        ):
            self.assertIn(marker, readme)

        self.assertNotIn("医院侧从本院 MySQL 当前生效投影导出 `kb-exchange-v3`", readme)


if __name__ == "__main__":
    unittest.main()
