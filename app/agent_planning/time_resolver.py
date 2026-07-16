from __future__ import annotations

import re
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict

from .contracts import TimeExpression


class ResolvedTimeRange(BaseModel):
    model_config = ConfigDict(extra="forbid")

    start_time: datetime
    end_time: datetime
    timezone: str = "Asia/Shanghai"
    interval: str = "left_closed_right_open"
    source_text: str = ""


def _at_start(value: date, timezone: ZoneInfo) -> datetime:
    return datetime.combine(value, time.min, tzinfo=timezone)


def _month_start(year: int, month: int, timezone: ZoneInfo) -> datetime:
    return datetime(year, month, 1, tzinfo=timezone)


def _next_month(value: datetime) -> datetime:
    if value.month == 12:
        return value.replace(year=value.year + 1, month=1)
    return value.replace(month=value.month + 1)


def _parse_datetime(value: str, timezone: ZoneInfo) -> datetime:
    parsed = datetime.fromisoformat(value)
    return parsed.replace(tzinfo=timezone) if parsed.tzinfo is None else parsed


class TimeRangeResolver:
    def __init__(self, timezone_name: str = "Asia/Shanghai") -> None:
        self.timezone_name = timezone_name
        self.timezone = ZoneInfo(timezone_name)

    def resolve(
        self,
        expression: TimeExpression,
        *,
        now: datetime,
    ) -> ResolvedTimeRange | None:
        local_now = (
            now.replace(tzinfo=self.timezone)
            if now.tzinfo is None
            else now.astimezone(self.timezone)
        )
        if expression.start_time and expression.end_time:
            start = _parse_datetime(expression.start_time, self.timezone)
            end = _parse_datetime(expression.end_time, self.timezone)
            if start >= end:
                return None
            return self._result(start, end, expression.raw_text)

        raw = re.sub(r"\s+", "", expression.raw_text or "")
        raw = re.sub(r"(?:的)?(?:结果|数据|指标值)$", "", raw)
        if raw in {"本月", "这个月", "当月"}:
            return self._result(
                _month_start(local_now.year, local_now.month, self.timezone),
                local_now,
                expression.raw_text,
            )
        if raw in {"上月", "上个月"}:
            current = _month_start(local_now.year, local_now.month, self.timezone)
            previous_day = current.date() - timedelta(days=1)
            start = _month_start(previous_day.year, previous_day.month, self.timezone)
            return self._result(start, current, expression.raw_text)
        if raw in {"今年", "今年至今", "本年至今"}:
            return self._result(
                datetime(local_now.year, 1, 1, tzinfo=self.timezone),
                local_now,
                expression.raw_text,
            )

        current_year_month = re.fullmatch(
            r"(?:从)?(?:今年)?(1[0-2]|[1-9])月(?:到现在|至今|开始)",
            raw,
        )
        if current_year_month:
            start = _month_start(
                local_now.year,
                int(current_year_month.group(1)),
                self.timezone,
            )
            return self._result(start, local_now, expression.raw_text) if start < local_now else None

        chinese_date = re.fullmatch(
            r"从(\d{2}|\d{4})年(1[0-2]|[1-9])月(3[01]|[12]\d|[1-9])(?:日|号)(?:到现在|至今|开始)",
            raw,
        )
        if chinese_date:
            year = int(chinese_date.group(1))
            if year < 100:
                year += 2000
            try:
                start = _at_start(
                    date(year, int(chinese_date.group(2)), int(chinese_date.group(3))),
                    self.timezone,
                )
            except ValueError:
                return None
            return self._result(start, local_now, expression.raw_text) if start < local_now else None

        dates = re.fullmatch(
            r"(\d{4}-\d{1,2}-\d{1,2})(?:到|至|-)(\d{4}-\d{1,2}-\d{1,2})",
            raw,
        )
        if dates:
            try:
                start_date = date.fromisoformat(dates.group(1))
                end_date = date.fromisoformat(dates.group(2))
            except ValueError:
                return None
            start = _at_start(start_date, self.timezone)
            end = _at_start(end_date + timedelta(days=1), self.timezone)
            return self._result(start, end, expression.raw_text) if start < end else None
        return None

    def _result(
        self,
        start: datetime,
        end: datetime,
        source_text: str,
    ) -> ResolvedTimeRange:
        return ResolvedTimeRange(
            start_time=start,
            end_time=end,
            timezone=self.timezone_name,
            source_text=source_text,
        )
