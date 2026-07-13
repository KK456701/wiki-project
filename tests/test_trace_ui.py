import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB_INDEX = ROOT / "web" / "index.html"


class TraceUiTest(unittest.TestCase):
    def test_trace_summary_uses_wall_clock_duration(self) -> None:
        html = WEB_INDEX.read_text(encoding="utf-8")

        self.assertIn(
            "function renderTraceTimingSummary(nodes, totalDurationMs)", html
        )
        self.assertIn("renderTraceTimingSummary(nodes, data.duration_ms)", html)
        self.assertIn('name.textContent = "执行耗时"', html)
        self.assertIn('"已记录阶段耗时："', html)

    def test_trace_nodes_have_business_summary_and_layered_details(self) -> None:
        html = WEB_INDEX.read_text(encoding="utf-8")

        for marker in (
            "function traceStatusText",
            "function traceBusinessSummary",
            "function traceBusinessFields",
            "处理结果",
            "原始输入输出",
            "开发与排障",
            "trace-json-disclosure",
            "trace-node-summary",
            "trace-step-index",
        ):
            self.assertIn(marker, html)
        self.assertIn(
            'node.status === "success" && node.contract_status === "ok"', html
        )
        self.assertIn('status.textContent = traceStatusText(node.status)', html)
        self.assertIn("if (!nodeHealthy && node.failure_hint)", html)

    def test_trace_timeline_shows_duration_share_and_bottleneck(self) -> None:
        html = WEB_INDEX.read_text(encoding="utf-8")

        for marker in (
            "function renderTraceTimeline",
            "function traceDurationPercent",
            "trace-timeline",
            "trace-timeline-item",
            "trace-duration-bar",
            "性能提示",
            "最慢",
            "@media (max-width: 760px)",
        ):
            self.assertIn(marker, html)
        self.assertIn("renderTraceTimeline(nodes, data.duration_ms)", html)


if __name__ == "__main__":
    unittest.main()
