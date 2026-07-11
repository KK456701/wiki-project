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

    def test_workbench_script_routes_to_registered_pages(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

        for marker in (
            "function currentWorkbenchRoute",
            "function navigateWorkbench",
            "function applyWorkbenchRoute",
            'window.addEventListener("hashchange"',
            "WORKBENCH_ROUTES",
            'var target = "#/" +',
            "window.openMonitoringWorkbench",
        ):
            self.assertIn(marker, js)
        self.assertIn('data-workbench-route="monitoring"', html)
        self.assertIn('aria-current="page"', html)

    def test_ai_is_default_route_and_monitoring_alone_requires_admin(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

        self.assertIn('id="assistantPage"', html)
        self.assertIn('data-workbench-route="assistant"', html)
        self.assertIn('data-workbench-route="monitoring"', html)
        self.assertIn('assistant: {requiresAdmin: false}', js)
        self.assertIn('monitoring: {requiresAdmin: true}', js)
        self.assertIn('return WORKBENCH_ROUTES[route] ? route : "assistant"', js)
        self.assertIn(
            'var target = "#/" + (WORKBENCH_ROUTES[route] ? route : "assistant")',
            js,
        )
        self.assertIn('if (definition.requiresAdmin && !adminToken)', js)
        self.assertIn('window.navigateWorkbench("assistant")', html)

    def test_ai_assistant_drawer_has_accessible_controls(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

        self.assertIn('id="assistantToggleButton" class="btn btn-secondary"', html)
        self.assertNotIn('aria-controls="assistantDrawer" hidden', html)
        self.assertEqual(html.count('id="messages"'), 1)
        self.assertEqual(html.count('id="chatForm"'), 1)
        for marker in (
            "function openAssistantDrawer",
            "function closeAssistantDrawer",
            "function toggleAssistantDrawer",
            'assistantToggleButton.setAttribute("aria-expanded", "true")',
            'assistantToggleButton.setAttribute("aria-expanded", "false")',
            'document.addEventListener("keydown"',
            'event.key === "Escape"',
        ):
            self.assertIn(marker, js)

    def test_single_chat_workspace_moves_between_home_and_drawer(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

        self.assertEqual(html.count('id="assistantWorkspace"'), 1)
        self.assertEqual(html.count('id="messages"'), 1)
        self.assertEqual(html.count('id="chatForm"'), 1)
        self.assertIn('id="assistantHomeMount"', html)
        self.assertIn('id="assistantDrawerMount"', html)
        self.assertIn("function mountAssistantWorkspace", js)
        self.assertIn("function ensureAssistantWelcome", js)
        self.assertIn('mountAssistantWorkspace("home")', js)
        self.assertIn('mountAssistantWorkspace("drawer")', js)


if __name__ == "__main__":
    unittest.main()
