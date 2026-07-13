import subprocess
import sys
import unittest
from datetime import date
from decimal import Decimal
from pathlib import Path

from app.demo_data.generator import DemoDataOptions, generate_demo_rows
from app.demo_data.monitoring_baseline import (
    DEMO_MONITORING_RULES,
    build_monitoring_periods,
    demo_plan_payload,
    json_safe,
)


class DemoMonitoringBaselineTest(unittest.TestCase):
    def test_periods_cover_month_over_month_and_year_over_year(self) -> None:
        periods = build_monitoring_periods(date(2025, 1, 1), 19)

        self.assertEqual(periods[0], "2025-01-01 00:00:00~2025-02-01 00:00:00")
        self.assertIn("2025-06-01 00:00:00~2025-07-01 00:00:00", periods)
        self.assertIn("2026-05-01 00:00:00~2026-06-01 00:00:00", periods)
        self.assertIn("2026-06-01 00:00:00~2026-07-01 00:00:00", periods)
        self.assertEqual(periods[-1], "2026-07-01 00:00:00~2026-08-01 00:00:00")

    def test_special_months_cover_no_sample_and_low_sample(self) -> None:
        rows = generate_demo_rows(DemoDataOptions())
        rescue_march = [
            row for row in rows["critical_rescue_record"]
            if row["rescue_time"].strftime("%Y-%m") == "2026-03"
        ]
        transfusion_april = [
            row for row in rows["intraoperative_transfusion_record"]
            if row["surgery_time"].strftime("%Y-%m") == "2026-04"
        ]

        self.assertEqual(
            sum(row["severity_level"] == "急危重症" for row in rescue_march), 0
        )
        self.assertEqual(
            sum(row["intraoperative_transfusion_flag"] == 1 for row in transfusion_april),
            2,
        )

    def test_plan_payload_is_deterministic_for_four_rules(self) -> None:
        payloads = [demo_plan_payload(rule_id) for rule_id in DEMO_MONITORING_RULES]

        self.assertEqual(len(payloads), 4)
        self.assertEqual(len({item["plan_id"] for item in payloads}), 4)
        self.assertTrue(all(item["frequency"] == "monthly" for item in payloads))
        self.assertTrue(all(item["mom_enabled"] and item["yoy_enabled"] for item in payloads))

    def test_script_can_preview_periods_without_running_monitoring(self) -> None:
        root = Path(__file__).resolve().parents[1]
        result = subprocess.run(
            [sys.executable, "scripts/seed_monitoring_baseline.py", "--months", "2"],
            cwd=root,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("2025-01-01 00:00:00~2025-02-01 00:00:00", result.stdout)
        self.assertIn("DEMO_MONTHLY_MQSI2025_005", result.stdout)

    def test_decimal_results_are_safe_for_cli_json_output(self) -> None:
        payload = json_safe({"result_value": Decimal("54.8600"), "items": [Decimal("1.25")]})

        self.assertEqual(payload, {"result_value": 54.86, "items": [1.25]})


if __name__ == "__main__":
    unittest.main()
