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


if __name__ == "__main__":
    unittest.main()
