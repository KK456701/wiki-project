import unittest
import subprocess
import sys
from datetime import date, datetime
from pathlib import Path

from app.demo_data.generator import (
    DemoDataOptions,
    generate_demo_rows,
    summarize_demo_rows,
)
from app.demo_data.writer import validate_demo_database_name


class DemoDataGeneratorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.options = DemoDataOptions(
            start_month=date(2025, 1, 1),
            month_count=19,
            profile="realistic",
            seed=20250713,
            monthly_counts={
                "inpatient_transfer_record": 24,
                "consult_record": 30,
                "critical_rescue_record": 20,
                "intraoperative_transfusion_record": 20,
            },
        )

    def test_generation_is_deterministic_and_covers_all_tables(self) -> None:
        first = generate_demo_rows(self.options)
        second = generate_demo_rows(self.options)

        self.assertEqual(first, second)
        self.assertEqual(
            set(first),
            {
                "inpatient_transfer_record",
                "consult_record",
                "critical_rescue_record",
                "intraoperative_transfusion_record",
            },
        )
        summary = summarize_demo_rows(first)
        self.assertEqual(summary["total_rows"], 19 * (24 + 30 + 20 + 20))
        self.assertEqual(summary["month_count"], 19)
        self.assertEqual(summary["hospital_ids"], ["hospital_001"])

    def test_generated_dates_cover_previous_period_and_last_year(self) -> None:
        rows = generate_demo_rows(self.options)
        dates: list[datetime] = []
        for table_rows in rows.values():
            for row in table_rows:
                for key in ("admit_time", "request_time", "rescue_time", "surgery_time"):
                    if key in row:
                        dates.append(row[key])

        self.assertEqual(min(dates).strftime("%Y-%m"), "2025-01")
        self.assertEqual(max(dates).strftime("%Y-%m"), "2026-07")

    def test_boundary_rows_cover_indicator_thresholds(self) -> None:
        rows = generate_demo_rows(self.options)
        consult = rows["consult_record"]
        july_consult = [r for r in consult if r["request_time"].strftime("%Y-%m") == "2026-07"]
        minute_values = sorted(
            int((r["arrive_time"] - r["request_time"]).total_seconds() / 60)
            for r in july_consult
            if r["consult_type"] == "急会诊" and r["arrive_time"] is not None
        )
        for minutes in (9, 10, 11, 19, 20, 21):
            self.assertIn(minutes, minute_values)

        transfer = rows["inpatient_transfer_record"]
        july_transfer = [r for r in transfer if r["admit_time"].strftime("%Y-%m") == "2026-07"]
        transfer_minutes = {
            int((r["transfer_time"] - r["admit_time"]).total_seconds() / 60)
            for r in july_transfer
            if r["transfer_time"] is not None
        }
        self.assertTrue({2879, 2880, 2881}.issubset(transfer_minutes))

    def test_monitoring_wave_month_has_visible_consult_drop(self) -> None:
        rows = generate_demo_rows(self.options)["consult_record"]

        def timely_rate(month: str) -> float:
            urgent = [
                row for row in rows
                if row["request_time"].strftime("%Y-%m") == month
                and row["consult_type"] == "急会诊"
            ]
            timely = [
                row for row in urgent
                if row["arrive_time"] is not None
                and (row["arrive_time"] - row["request_time"]).total_seconds() <= 20 * 60
            ]
            return len(timely) / len(urgent) * 100

        self.assertGreater(timely_rate("2025-06"), 75)
        self.assertGreater(timely_rate("2026-05"), 75)
        self.assertLess(timely_rate("2026-06"), 65)

    def test_realistic_profile_contains_insertable_quality_anomalies(self) -> None:
        rows = generate_demo_rows(self.options)

        self.assertTrue(any(r["arrive_time"] is None for r in rows["consult_record"]))
        self.assertTrue(any(
            r["transfer_time"] is not None and r["transfer_time"] < r["admit_time"]
            for r in rows["inpatient_transfer_record"]
        ))
        self.assertTrue(any(r["dept_id"] == "UNKNOWN" for r in rows["critical_rescue_record"]))
        self.assertTrue(any(
            r["intraoperative_transfusion_flag"] == 0
            and r["autologous_reinfusion_flag"] == 1
            for r in rows["intraoperative_transfusion_record"]
        ))

    def test_writer_only_accepts_demo_database(self) -> None:
        self.assertEqual(validate_demo_database_name("hospital_demo_data"), "hospital_demo_data")
        with self.assertRaisesRegex(ValueError, "演示数据库"):
            validate_demo_database_name("hospital_production")

    def test_seed_script_can_preview_from_project_root(self) -> None:
        root = Path(__file__).resolve().parents[1]
        result = subprocess.run(
            [
                sys.executable,
                "scripts/seed_demo_hospital_data.py",
                "--months",
                "1",
            ],
            cwd=root,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('"total_rows"', result.stdout)


if __name__ == "__main__":
    unittest.main()
