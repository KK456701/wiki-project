from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


class MonitoringPeriodError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class ResolvedPeriod:
    frequency: Literal["daily", "monthly"]
    start: datetime
    end: datetime
    timezone_name: str = "Asia/Shanghai"

    @property
    def start_text(self) -> str:
        return self.start.isoformat(sep=" ", timespec="seconds")

    @property
    def end_text(self) -> str:
        return self.end.isoformat(sep=" ", timespec="seconds")

    @property
    def label(self) -> str:
        return f"{self.start_text}~{self.end_text}"


def _timezone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except ZoneInfoNotFoundError as exc:
        raise MonitoringPeriodError(f"无效时区: {name}") from exc


def _parse_endpoint(value: str, *, is_end: bool) -> datetime:
    normalized = value.strip()
    try:
        if len(normalized) == 10:
            parsed = datetime.strptime(normalized, "%Y-%m-%d")
            return parsed + timedelta(days=1) if is_end else parsed
        return datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise MonitoringPeriodError(f"统计周期格式无效: {value}") from exc


def resolve_run_period(
    frequency: str,
    stat_period: str | None = None,
    now: datetime | None = None,
    timezone_name: str = "Asia/Shanghai",
) -> ResolvedPeriod:
    if frequency not in {"daily", "monthly"}:
        raise MonitoringPeriodError(f"不支持的运行频率: {frequency}")
    zone = _timezone(timezone_name)
    if stat_period and stat_period.strip():
        parts = stat_period.split("~")
        if len(parts) != 2 or not all(part.strip() for part in parts):
            raise MonitoringPeriodError("统计周期必须使用 开始时间~结束时间 格式")
        start = _parse_endpoint(parts[0], is_end=False)
        end = _parse_endpoint(parts[1], is_end=True)
    else:
        current = now or datetime.now(zone).replace(tzinfo=None)
        if current.tzinfo is not None:
            current = current.astimezone(zone).replace(tzinfo=None)
        today = current.replace(hour=0, minute=0, second=0, microsecond=0)
        if frequency == "daily":
            end = today
            start = end - timedelta(days=1)
        else:
            end = today.replace(day=1)
            previous_day = end - timedelta(days=1)
            start = previous_day.replace(day=1)
    if start.tzinfo is not None:
        start = start.astimezone(zone).replace(tzinfo=None)
    if end.tzinfo is not None:
        end = end.astimezone(zone).replace(tzinfo=None)
    if end <= start:
        raise MonitoringPeriodError("统计周期结束时间必须晚于开始时间")
    return ResolvedPeriod(frequency, start, end, timezone_name)


def _previous_month(value: datetime) -> datetime:
    if value.month == 1:
        return value.replace(year=value.year - 1, month=12)
    return value.replace(month=value.month - 1)


def _previous_year(value: datetime) -> datetime:
    try:
        return value.replace(year=value.year - 1)
    except ValueError:
        return value.replace(year=value.year - 1, day=28)


def comparison_period(
    period: ResolvedPeriod, comparison: Literal["mom", "yoy"]
) -> ResolvedPeriod:
    if comparison == "yoy":
        start = _previous_year(period.start)
        end = _previous_year(period.end)
    elif comparison == "mom":
        if (
            period.frequency == "monthly"
            and period.start.day == 1
            and period.end.day == 1
        ):
            start = _previous_month(period.start)
            end = period.start
        else:
            duration = period.end - period.start
            end = period.start
            start = end - duration
    else:
        raise MonitoringPeriodError(f"不支持的比较类型: {comparison}")
    return ResolvedPeriod(
        period.frequency, start, end, period.timezone_name
    )
