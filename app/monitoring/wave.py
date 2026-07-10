from __future__ import annotations

from typing import Any


def _change_rate(current: float, baseline: float | None) -> float | None:
    if baseline is None or baseline == 0:
        return None
    current_value = float(current)
    baseline_value = float(baseline)
    return round(
        (current_value - baseline_value) / abs(baseline_value) * 100,
        2,
    )


def detect_wave(
    current_value: float | None,
    mom_value: float | None,
    yoy_value: float | None,
    mom_enabled: bool,
    mom_threshold_pct: float,
    yoy_enabled: bool,
    yoy_threshold_pct: float,
    no_sample: bool = False,
) -> dict[str, Any]:
    if no_sample:
        return {
            "conclusion_code": "no_sample",
            "is_abnormal": False,
            "current_value": current_value,
            "mom_value": mom_value,
            "yoy_value": yoy_value,
            "mom_change_rate": None,
            "yoy_change_rate": None,
            "missing_comparisons": [],
            "summary": "当前统计周期无样本，不进行波动判断。",
        }
    if current_value is None:
        return {
            "conclusion_code": "baseline_insufficient",
            "is_abnormal": False,
            "current_value": None,
            "mom_value": mom_value,
            "yoy_value": yoy_value,
            "mom_change_rate": None,
            "yoy_change_rate": None,
            "missing_comparisons": [
                name
                for name, enabled in (("mom", mom_enabled), ("yoy", yoy_enabled))
                if enabled
            ],
            "summary": "本期结果不可用，无法进行波动判断。",
        }

    current = float(current_value)
    mom_rate = _change_rate(current, mom_value) if mom_enabled else None
    yoy_rate = _change_rate(current, yoy_value) if yoy_enabled else None
    missing: list[str] = []
    if mom_enabled and mom_rate is None:
        missing.append("mom")
    if yoy_enabled and yoy_rate is None:
        missing.append("yoy")

    mom_exceeded = mom_rate is not None and abs(mom_rate) > float(mom_threshold_pct)
    yoy_exceeded = yoy_rate is not None and abs(yoy_rate) > float(yoy_threshold_pct)
    if mom_exceeded and yoy_exceeded:
        code = "mom_yoy_threshold_exceeded"
    elif mom_exceeded:
        code = "mom_threshold_exceeded"
    elif yoy_exceeded:
        code = "yoy_threshold_exceeded"
    elif mom_rate is not None or yoy_rate is not None:
        code = "within_threshold"
    else:
        code = "baseline_insufficient"

    abnormal = code.endswith("threshold_exceeded")
    summaries = {
        "mom_yoy_threshold_exceeded": "环比和同比变化率均超过配置阈值。",
        "mom_threshold_exceeded": "环比变化率超过配置阈值。",
        "yoy_threshold_exceeded": "同比变化率超过配置阈值。",
        "within_threshold": "已获得的历史比较结果未超过配置阈值。",
        "baseline_insufficient": "缺少可用历史基线，不进行波动预警。",
    }
    return {
        "conclusion_code": code,
        "is_abnormal": abnormal,
        "current_value": current,
        "mom_value": mom_value,
        "yoy_value": yoy_value,
        "mom_change_rate": mom_rate,
        "yoy_change_rate": yoy_rate,
        "missing_comparisons": missing,
        "summary": summaries[code],
    }
