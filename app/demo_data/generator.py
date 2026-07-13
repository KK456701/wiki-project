"""生成四个核心指标使用的确定性虚构业务数据。"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from random import Random
from typing import Any


TABLE_TIME_FIELDS = {
    "inpatient_transfer_record": "admit_time",
    "consult_record": "request_time",
    "critical_rescue_record": "rescue_time",
    "intraoperative_transfusion_record": "surgery_time",
}

DEFAULT_MONTHLY_COUNTS = {
    "inpatient_transfer_record": 800,
    "consult_record": 640,
    "critical_rescue_record": 210,
    "intraoperative_transfusion_record": 260,
}


@dataclass(frozen=True)
class DemoDataOptions:
    start_month: date = date(2025, 1, 1)
    month_count: int = 19
    hospital_id: str = "hospital_001"
    profile: str = "realistic"
    seed: int = 20250713
    monthly_counts: dict[str, int] = field(
        default_factory=lambda: dict(DEFAULT_MONTHLY_COUNTS)
    )

    def __post_init__(self) -> None:
        if self.start_month.day != 1:
            raise ValueError("start_month 必须是月份第一天")
        if self.month_count < 1:
            raise ValueError("month_count 必须大于 0")
        if self.profile not in {"baseline", "realistic"}:
            raise ValueError("profile 只支持 baseline 或 realistic")
        missing = set(TABLE_TIME_FIELDS) - set(self.monthly_counts)
        if missing:
            raise ValueError(f"缺少数据量配置：{', '.join(sorted(missing))}")
        if any(int(value) < 8 for value in self.monthly_counts.values()):
            raise ValueError("每张表每月至少生成 8 条，才能覆盖边界场景")


def _add_months(value: date, months: int) -> date:
    month_index = value.year * 12 + value.month - 1 + months
    return date(month_index // 12, month_index % 12 + 1, 1)


def _event_time(month: date, index: int) -> datetime:
    return datetime(month.year, month.month, index % 27 + 1, 7 + index % 12, index % 60)


def _prefix(month: date, index: int) -> str:
    return f"{month:%Y%m}-{index:05d}"


def _transfer_rows(
    options: DemoDataOptions, month: date, count: int, random: Random
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    boundary_minutes = {0: 2879, 1: 2880, 2: 2881}
    for index in range(count):
        code = _prefix(month, index)
        admit_time = _event_time(month, index)
        from_dept = "ICU" if index == 3 else f"D{index % 6 + 1:03d}"
        to_dept = f"D{(index + 1) % 6 + 1:03d}"
        if index in boundary_minutes:
            transfer_time = admit_time + timedelta(minutes=boundary_minutes[index])
        elif index % 10 == 0:
            transfer_time = None
            to_dept = None
        elif index / count < 0.74:
            transfer_time = admit_time + timedelta(minutes=random.randint(120, 2800))
        else:
            transfer_time = admit_time + timedelta(minutes=random.randint(2881, 7200))
        if options.profile == "realistic" and index == count - 1:
            transfer_time = admit_time - timedelta(minutes=30)
        if options.profile == "realistic" and index == count - 2:
            to_dept = "UNKNOWN"
        if options.profile == "realistic" and index == count - 3:
            from_dept = None
        rows.append(
            {
                "hospital_id": options.hospital_id,
                "patient_id": f"PT-{code}",
                "admission_id": f"ADM-{code if index != 5 else _prefix(month, 4)}",
                "admit_time": admit_time,
                "transfer_time": transfer_time,
                "from_dept_id": from_dept,
                "to_dept_id": to_dept,
                "transfer_status": "完成" if transfer_time else "未转科",
            }
        )
    return rows


def _consult_rows(
    options: DemoDataOptions, month: date, count: int, random: Random
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    urgent_count = max(6, int(count * 0.9))
    timely_rate = 0.55 if (month.year, month.month) == (2026, 6) else 0.85
    timely_count = int(urgent_count * timely_rate)
    national_timely_count = int(urgent_count * (0.35 if timely_rate < 0.7 else 0.65))
    boundary_minutes = [9, 10, 11, 19, 20, 21]
    for index in range(count):
        code = _prefix(month, index)
        request_time = _event_time(month, index)
        is_urgent = index < urgent_count
        if not is_urgent:
            minutes = random.randint(5, 90)
        elif index < len(boundary_minutes):
            minutes = boundary_minutes[index]
        elif index < national_timely_count:
            minutes = random.randint(3, 10)
        elif index < timely_count:
            minutes = random.randint(11, 20)
        else:
            minutes = random.randint(21, 90)
        arrive_time = request_time + timedelta(minutes=minutes)
        status = "完成"
        if options.profile == "realistic" and index == urgent_count - 1:
            arrive_time = None
            status = "待到位"
        if options.profile == "realistic" and index == urgent_count - 2:
            arrive_time = request_time - timedelta(minutes=5)
        rows.append(
            {
                "hospital_id": options.hospital_id,
                "patient_id": f"PC-{code}",
                "consult_type": "急会诊" if is_urgent else "普通会诊",
                "request_time": request_time,
                "arrive_time": arrive_time,
                "status": status,
                "dept_id": "UNKNOWN" if options.profile == "realistic" and index == count - 1 else f"D{index % 6 + 1:03d}",
            }
        )
    return rows


def _rescue_rows(
    options: DemoDataOptions, month: date, count: int, random: Random
) -> list[dict[str, Any]]:
    del random
    rows: list[dict[str, Any]] = []
    critical_count = 0 if (month.year, month.month) == (2026, 3) else int(count * 0.9)
    success_rate = 0.6 if (month.year, month.month) == (2026, 6) else 0.8
    success_count = int(critical_count * success_rate)
    for index in range(count):
        code = _prefix(month, index)
        is_critical = index < critical_count
        rows.append(
            {
                "hospital_id": options.hospital_id,
                "patient_id": f"PR-{code}",
                "rescue_id": f"RES-{code}",
                "rescue_time": _event_time(month, index),
                "severity_level": "急危重症" if is_critical else "一般急症",
                "rescue_result": "成功" if is_critical and index < success_count else "失败",
                "dept_id": "UNKNOWN" if options.profile == "realistic" and index == count - 1 else f"D{index % 6 + 1:03d}",
            }
        )
    return rows


def _transfusion_rows(
    options: DemoDataOptions, month: date, count: int, random: Random
) -> list[dict[str, Any]]:
    del random
    rows: list[dict[str, Any]] = []
    transfusion_count = 2 if (month.year, month.month) == (2026, 4) else int(count * 0.8)
    autologous_rate = 0.2 if (month.year, month.month) == (2026, 6) else 0.4
    autologous_count = int(transfusion_count * autologous_rate)
    for index in range(count):
        code = _prefix(month, index)
        transfusion_flag = 1 if index < transfusion_count else 0
        autologous_flag = 1 if index < autologous_count else 0
        surgery_id = f"SUR-{code if index != 5 else _prefix(month, 4)}"
        if options.profile == "realistic" and index == count - 1:
            transfusion_flag, autologous_flag = 0, 1
        rows.append(
            {
                "hospital_id": options.hospital_id,
                "patient_id": f"PS-{code}",
                "surgery_id": surgery_id,
                "surgery_time": _event_time(month, index),
                "intraoperative_transfusion_flag": transfusion_flag,
                "autologous_reinfusion_flag": autologous_flag,
                "dept_id": f"D{index % 6 + 1:03d}",
            }
        )
    return rows


def generate_demo_rows(options: DemoDataOptions) -> dict[str, list[dict[str, Any]]]:
    random = Random(options.seed)
    rows = {table: [] for table in TABLE_TIME_FIELDS}
    for offset in range(options.month_count):
        month = _add_months(options.start_month, offset)
        rows["inpatient_transfer_record"].extend(
            _transfer_rows(options, month, options.monthly_counts["inpatient_transfer_record"], random)
        )
        rows["consult_record"].extend(
            _consult_rows(options, month, options.monthly_counts["consult_record"], random)
        )
        rows["critical_rescue_record"].extend(
            _rescue_rows(options, month, options.monthly_counts["critical_rescue_record"], random)
        )
        rows["intraoperative_transfusion_record"].extend(
            _transfusion_rows(options, month, options.monthly_counts["intraoperative_transfusion_record"], random)
        )
    return rows


def summarize_demo_rows(rows: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    months: set[str] = set()
    hospitals: set[str] = set()
    table_counts: dict[str, int] = {}
    for table, table_rows in rows.items():
        table_counts[table] = len(table_rows)
        time_field = TABLE_TIME_FIELDS[table]
        for row in table_rows:
            hospitals.add(str(row["hospital_id"]))
            months.add(row[time_field].strftime("%Y-%m"))
    return {
        "table_counts": table_counts,
        "total_rows": sum(table_counts.values()),
        "month_count": len(months),
        "month_range": [min(months), max(months)] if months else [],
        "hospital_ids": sorted(hospitals),
    }
