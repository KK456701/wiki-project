import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class WorkbenchUiTest(unittest.TestCase):
    def test_page_exposes_real_workbench_shell(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

        for marker in (
            'id="workbenchShell"',
            'id="workbenchNav"',
            'id="workbenchContent"',
            'id="monitoringPage"',
            'id="assistantToggleButton"',
            'id="assistantDrawer"',
            'id="assistantCloseButton"',
        ):
            self.assertIn(marker, html)
        self.assertIn('/static/workbench.css', html)
        self.assertIn('/static/workbench.js', html)
        self.assertNotIn('id="monitoringModal" class="modal"', html)

    def test_workbench_css_has_desktop_and_mobile_layouts(self) -> None:
        path = ROOT / "web" / "workbench.css"
        self.assertTrue(path.exists(), "缺少 web/workbench.css")
        css = path.read_text(encoding="utf-8")

        self.assertIn(".workbench-shell", css)
        self.assertIn("grid-template-columns: 224px minmax(0, 1fr)", css)
        self.assertIn(".assistant-drawer", css)
        self.assertIn("@media (max-width: 760px)", css)

    def test_workbench_script_routes_to_monitoring(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

        for marker in (
            "function currentWorkbenchRoute",
            "function navigateWorkbench",
            "function applyWorkbenchRoute",
            'window.addEventListener("hashchange"',
            '"#/monitoring"',
            "window.openMonitoringWorkbench",
        ):
            self.assertIn(marker, js)
        self.assertIn('data-workbench-route="monitoring"', html)
        self.assertIn('aria-current="page"', html)


if __name__ == "__main__":
    unittest.main()
