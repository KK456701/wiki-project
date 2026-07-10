import unittest
from decimal import Decimal
from datetime import datetime


class MonitoringPeriodTest(unittest.TestCase):
    def test_monthly_plan_uses_previous_complete_month(self) -> None:
        from app.monitoring.periods import comparison_period, resolve_run_period

        period = resolve_run_period(
            "monthly", now=datetime(2026, 8, 10, 12, 0, 0)
        )

        self.assertEqual(period.start_text, "2026-07-01 00:00:00")
        self.assertEqual(period.end_text, "2026-08-01 00:00:00")
        self.assertEqual(
            comparison_period(period, "mom").start_text,
            "2026-06-01 00:00:00",
        )
        self.assertEqual(
            comparison_period(period, "yoy").start_text,
            "2025-07-01 00:00:00",
        )

    def test_daily_plan_handles_year_boundary(self) -> None:
        from app.monitoring.periods import comparison_period, resolve_run_period

        period = resolve_run_period(
            "daily", now=datetime(2026, 1, 1, 8, 0, 0)
        )

        self.assertEqual(period.start_text, "2025-12-31 00:00:00")
        self.assertEqual(period.end_text, "2026-01-01 00:00:00")
        self.assertEqual(
            comparison_period(period, "mom").start_text,
            "2025-12-30 00:00:00",
        )

    def test_explicit_date_range_includes_end_date(self) -> None:
        from app.monitoring.periods import resolve_run_period

        period = resolve_run_period(
            "monthly", stat_period="2026-07-01~2026-07-31"
        )

        self.assertEqual(period.start_text, "2026-07-01 00:00:00")
        self.assertEqual(period.end_text, "2026-08-01 00:00:00")
        self.assertEqual(
            period.label,
            "2026-07-01 00:00:00~2026-08-01 00:00:00",
        )

    def test_yoy_leap_day_falls_back_to_february_28(self) -> None:
        from app.monitoring.periods import comparison_period, resolve_run_period

        period = resolve_run_period(
            "daily", stat_period="2024-02-29~2024-02-29"
        )
        yoy = comparison_period(period, "yoy")

        self.assertEqual(yoy.start_text, "2023-02-28 00:00:00")
        self.assertEqual(yoy.end_text, "2023-03-01 00:00:00")

    def test_invalid_frequency_period_and_timezone_are_rejected(self) -> None:
        from app.monitoring.periods import MonitoringPeriodError, resolve_run_period

        cases = [
            {"frequency": "weekly"},
            {"frequency": "daily", "stat_period": "2026/07/01"},
            {
                "frequency": "daily",
                "stat_period": "2026-08-01~2026-07-01",
            },
            {"frequency": "daily", "timezone_name": "Mars/Base"},
        ]
        for kwargs in cases:
            with self.subTest(kwargs=kwargs):
                with self.assertRaises(MonitoringPeriodError):
                    resolve_run_period(**kwargs)


class MonitoringWaveTest(unittest.TestCase):
    def test_mysql_decimal_baseline_is_normalized_before_comparison(self) -> None:
        from app.monitoring.wave import detect_wave

        result = detect_wave(
            66.67,
            Decimal("50.00"),
            None,
            True,
            20,
            False,
            30,
        )

        self.assertEqual(result["mom_change_rate"], 33.34)
        self.assertEqual(result["conclusion_code"], "mom_threshold_exceeded")

    def test_threshold_is_strict_and_yoy_is_optional(self) -> None:
        from app.monitoring.wave import detect_wave

        equal = detect_wave(60, 50, None, True, 20, False, 30)
        exceeded = detect_wave(60.01, 50, None, True, 20, False, 30)

        self.assertEqual(equal["conclusion_code"], "within_threshold")
        self.assertFalse(equal["is_abnormal"])
        self.assertEqual(exceeded["conclusion_code"], "mom_threshold_exceeded")
        self.assertTrue(exceeded["is_abnormal"])

    def test_both_thresholds_can_be_exceeded(self) -> None:
        from app.monitoring.wave import detect_wave

        result = detect_wave(80, 50, 40, True, 20, True, 30)

        self.assertEqual(result["mom_change_rate"], 60.0)
        self.assertEqual(result["yoy_change_rate"], 100.0)
        self.assertEqual(
            result["conclusion_code"], "mom_yoy_threshold_exceeded"
        )

    def test_missing_yoy_does_not_hide_valid_mom_result(self) -> None:
        from app.monitoring.wave import detect_wave

        result = detect_wave(55, 50, None, True, 20, True, 30)

        self.assertEqual(result["conclusion_code"], "within_threshold")
        self.assertEqual(result["missing_comparisons"], ["yoy"])
        self.assertFalse(result["is_abnormal"])

    def test_no_baseline_or_zero_baseline_is_insufficient(self) -> None:
        from app.monitoring.wave import detect_wave

        missing = detect_wave(50, None, None, True, 20, True, 30)
        zero = detect_wave(50, 0, None, True, 20, False, 30)

        self.assertEqual(missing["conclusion_code"], "baseline_insufficient")
        self.assertEqual(zero["conclusion_code"], "baseline_insufficient")

    def test_negative_change_uses_absolute_threshold(self) -> None:
        from app.monitoring.wave import detect_wave

        result = detect_wave(30, 50, None, True, 20, False, 30)

        self.assertEqual(result["mom_change_rate"], -40.0)
        self.assertEqual(result["conclusion_code"], "mom_threshold_exceeded")

    def test_no_sample_never_triggers_alert(self) -> None:
        from app.monitoring.wave import detect_wave

        result = detect_wave(None, 50, 40, True, 20, True, 30, no_sample=True)

        self.assertEqual(result["conclusion_code"], "no_sample")
        self.assertFalse(result["is_abnormal"])


if __name__ == "__main__":
    unittest.main()
