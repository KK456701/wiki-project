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


if __name__ == "__main__":
    unittest.main()
