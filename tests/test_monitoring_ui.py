import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class MonitoringUiTest(unittest.TestCase):
    def test_page_exposes_monitoring_workspace_structure(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

        for marker in (
            'id="monitoringPage"',
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
        self.assertNotIn('id="monitoringButton"', html)
        self.assertNotIn('id="monitoringModal" class="modal"', html)
        self.assertIn('/static/monitoring.css', html)
        self.assertIn('/static/monitoring.js', html)

    def test_monitoring_styles_include_mobile_layout(self) -> None:
        css = (ROOT / "web" / "monitoring.css").read_text(encoding="utf-8")

        self.assertIn(".monitoring-workbench", css)
        self.assertIn(".monitoring-plan-layout", css)
        self.assertIn("@media (max-width: 760px)", css)
        self.assertIn(
            ".monitoring-dialog > header .ghost {\n"
            "  flex: 0 0 auto;\n"
            "  white-space: nowrap;\n"
            "}",
            css,
        )

    def test_monitoring_script_manages_plans_and_manual_runs(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
        js = (ROOT / "web" / "monitoring.js").read_text(encoding="utf-8")
        workbench_js = (ROOT / "web" / "workbench.js").read_text(encoding="utf-8")

        for marker in (
            'var monitoringPage = document.getElementById("monitoringPage")',
            "function activateMonitoringPage",
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
        self.assertNotIn('var monitoringModal = document.getElementById("monitoringModal")', js)
        self.assertNotIn("monitoringModal.hidden", js)
        self.assertIn(
            'if (window.navigateWorkbench) return window.navigateWorkbench("monitoring")',
            js,
        )
        self.assertIn('monitoring: {requiresAdmin: true}', workbench_js)
        self.assertIn("requireAdminThenOpen(route)", workbench_js)
        self.assertIn('area === "monitoring"', html)

    def test_monitoring_script_handles_results_alerts_and_readable_states(self) -> None:
        js = (ROOT / "web" / "monitoring.js").read_text(encoding="utf-8")

        for marker in (
            "function switchMonitoringTab",
            "function loadMonitoringResults",
            "function loadMonitoringAlerts",
            "function acknowledgeMonitoringAlert",
            "function closeMonitoringAlert",
            "function diagnoseMonitoringAlert",
            '"/api/monitoring/results?hospital_id="',
            '"/api/monitoring/alerts?hospital_id="',
            '"acknowledge"',
            '"close"',
            '"diagnose"',
            'success: "成功"',
            'no_sample: "无有效样本"',
            'failed: "运行失败"',
            'baseline_insufficient: "缺少历史基线"',
            'open: "未处理"',
            'acknowledged: "已确认"',
            'closed: "已关闭"',
        ):
            self.assertIn(marker, js)

    def test_monitoring_page_starts_at_top_of_workbench(self) -> None:
        css = (ROOT / "web" / "workbench.css").read_text(encoding="utf-8")

        self.assertIn(".workbench-page[hidden]", css)
        self.assertIn(".monitoring-page-surface", css)
        self.assertNotIn("min-height: 420px", css)


if __name__ == "__main__":
    unittest.main()
