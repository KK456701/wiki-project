import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class MonitoringUiTest(unittest.TestCase):
    def test_page_exposes_monitoring_workspace_structure(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

        for marker in (
            'id="monitoringButton"',
            'id="monitoringModal"',
            'id="monitoringPlansTab"',
            'id="monitoringResultsTab"',
            'id="monitoringAlertsTab"',
            'id="monitoringPlanList"',
            'id="monitoringPlanDetail"',
            'id="monitoringResultsList"',
            'id="monitoringAlertsList"',
            'id="monitoringPlanForm"',
            'id="monitoringResultRuleFilter"',
            'id="monitoringAlertStatusFilter"',
        ):
            self.assertIn(marker, html)
        self.assertIn('/static/monitoring.css', html)
        self.assertIn('/static/monitoring.js', html)

    def test_monitoring_styles_include_mobile_layout(self) -> None:
        css = (ROOT / "web" / "monitoring.css").read_text(encoding="utf-8")

        self.assertIn(".monitoring-workbench", css)
        self.assertIn(".monitoring-plan-layout", css)
        self.assertIn("@media (max-width: 760px)", css)

    def test_monitoring_script_manages_plans_and_manual_runs(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        js = (ROOT / "web" / "monitoring.js").read_text(encoding="utf-8")

        for marker in (
            "function openMonitoringWorkbench",
            "function loadMonitoringHealth",
            "function loadMonitoringPlans",
            "function saveMonitoringPlan",
            "function setMonitoringPlanStatus",
            "function runMonitoringPlan",
            '"/api/monitoring/plans"',
            '"enable"',
            '"disable"',
            '"/run"',
            '"/api/health/summary"',
            "showTrace(result.trace_id)",
        ):
            self.assertIn(marker, js)
        self.assertIn('requireAdminThenOpen("monitoring")', html)
        self.assertIn('area === "monitoring"', html)


if __name__ == "__main__":
    unittest.main()
