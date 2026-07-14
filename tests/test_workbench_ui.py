import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class WorkbenchUiTest(unittest.TestCase):
    def test_workbench_script_uses_release_cache_buster(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

        self.assertIn(
            '/static/workbench.js?v=20260714-implementation-console', html
        )

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

    def test_mobile_navigation_hides_internal_scrollbars(self) -> None:
        css = (ROOT / "web" / "workbench.css").read_text(encoding="utf-8")

        self.assertIn("scrollbar-width: none", css)
        self.assertIn("::-webkit-scrollbar", css)

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

    def test_saved_login_hides_login_screen_after_reload(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

        self.assertIn("loginScreen.hidden = !!currentUser", html)

    def test_ai_assistant_drawer_has_accessible_controls(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

        self.assertIn('id="assistantToggleButton" class="btn btn-secondary"', html)
        self.assertIn('aria-controls="assistantDrawer" hidden', html)
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

    def test_ai_home_is_primary_and_loading_placeholder_collapses(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        css = (ROOT / "web" / "workbench.css").read_text(encoding="utf-8")

        self.assertIn('class="assistant-page-heading"', html)
        self.assertIn('id="systemToolsMenu"', html)
        self.assertIn(".assistant-page", css)
        self.assertIn(".assistant-home-mount", css)
        self.assertIn(".assistant-workspace.compact", css)
        hidden_rule = css[css.index(".workbench-loading[hidden]") :]
        self.assertIn("display: none", hidden_rule)

    def test_ai_home_uses_full_available_workspace(self) -> None:
        css = (ROOT / "web" / "workbench.css").read_text(encoding="utf-8")

        assistant_page = css.split(".assistant-page {", 1)[1].split("}", 1)[0]
        assistant_heading = css.split(".assistant-page-heading {", 1)[1].split("}", 1)[0]
        self.assertIn("width: 100%", assistant_page)
        self.assertIn("max-width: none", assistant_page)
        self.assertNotIn("max-width: 1040px", assistant_page)
        self.assertIn("display: flex", assistant_heading)
        self.assertIn("min-height: 58px", assistant_heading)
        self.assertIn(".assistant-workspace:not(.compact) .message-bubble", css)
        self.assertIn("max-width: min(960px, 82%)", css)
        self.assertIn("@media (max-width: 1200px)", css)

    def test_assistant_route_toggles_immersive_mode(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

        self.assertIn(
            'var workbenchShell = document.getElementById("workbenchShell")',
            js,
        )
        self.assertIn('workbenchShell.classList.add("assistant-immersive")', js)
        self.assertIn('workbenchShell.classList.remove("assistant-immersive")', js)
        self.assertIn('data-short="审"', html)
        self.assertIn('data-workbench-route="metadata"', html)
        self.assertIn('<span class="workbench-nav-index">数据</span>', html)
        self.assertIn('data-workbench-route="indicator-console"', html)
        self.assertIn('title="指标实施控制台"', html)

    def test_immersive_assistant_uses_rail_and_full_height_canvas(self) -> None:
        css = (ROOT / "web" / "workbench.css").read_text(encoding="utf-8")

        self.assertIn("@media (min-width: 761px)", css)
        self.assertIn(".assistant-immersive", css)
        self.assertIn("grid-template-columns: 64px minmax(0, 1fr)", css)
        self.assertIn(".assistant-immersive .workbench-topbar", css)
        self.assertIn("display: contents", css)
        self.assertIn(".assistant-immersive .topbar-actions", css)
        self.assertIn("position: fixed", css)
        self.assertIn(".assistant-immersive .assistant-page-heading", css)
        self.assertIn("content: attr(data-short)", css)
        self.assertIn("content: attr(title)", css)
        self.assertIn('content: "核心制度指标 Agent · "', css)


if __name__ == "__main__":
    unittest.main()
