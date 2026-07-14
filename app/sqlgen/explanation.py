"""Deterministic business explanations for generated and trial-run SQL."""

from __future__ import annotations

import math
from datetime import datetime
from typing import Any, Iterable


PARAMETER_LABELS = {
    "arrive_minutes_threshold": "到位时限",
    "consult_type_value": "会诊类型",
    "transfer_minutes_threshold": "转科时限",
    "excluded_dept_id": "排除科室",
    "severity_value": "患者严重程度",
    "success_value": "抢救结果",
    "transfusion_flag_value": "术中输血标志",
    "autologous_flag_value": "自体血回输标志",
}

def format_generation_explanation(
    *,
    result: dict[str, Any],
    effective_rule: dict[str, Any],
    lineage: dict[str, Any],
    hospital_id: str,
    stat_start: str,
    stat_end: str,
) -> str:
    validation = result.get("validation") or {}
    sections = [
        "## SQL 已生成",
        _markdown_table(
            ["项目", "结果"],
            [
                ["指标", effective_rule.get("rule_name") or "未命名指标"],
                ["SQL ID", result.get("sql_id") or "未记录"],
                ["安全校验", validation.get("message") or validation.get("error") or "未返回"],
            ],
        ),
        _caliber_lines(effective_rule, lineage, hospital_id),
        _branch_section("分母如何取数", lineage, "denominator", result),
        _branch_section("分子如何从分母中筛选", lineage, "numerator", result),
        _caliber_target_section(effective_rule, lineage),
        _formula_section(lineage),
        _parameter_table(result.get("params") or {}, hospital_id, stat_start, stat_end),
        f"```sql\n{result.get('sql_text') or ''}\n```",
        "如需验证本期结果，请输入「试运行」。",
    ]
    return "\n\n".join(section for section in sections if section)


def format_trial_explanation(
    *,
    result: dict[str, Any],
    effective_rule: dict[str, Any],
    lineage: dict[str, Any],
    hospital_id: str,
    stat_start: str,
    stat_end: str,
) -> str:
    trial = result.get("trial_run") or {}
    sections = [
        "## 试运行完成",
        _caliber_lines(effective_rule, lineage, hospital_id),
        _branch_section("分母如何取数", lineage, "denominator", result),
        _branch_section("分子如何从分母中筛选", lineage, "numerator", result),
        _caliber_target_section(effective_rule, lineage),
        _trial_conclusion(trial, lineage),
        _trial_table(trial, lineage),
        _run_metadata_table(trial, hospital_id, stat_start, stat_end),
        f"```sql\n{result.get('sql_text') or ''}\n```",
    ]
    return "\n\n".join(section for section in sections if section)


def _markdown_table(headers: Iterable[Any], rows: Iterable[Iterable[Any]]) -> str:
    header_cells = [_table_cell(item) for item in headers]
    lines = [
        "| " + " | ".join(header_cells) + " |",
        "| " + " | ".join("---" for _ in header_cells) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(_table_cell(item) for item in row) + " |")
    return "\n".join(lines)


def _table_cell(value: Any) -> str:
    return str(value if value is not None else "-").replace("|", "\\|").replace("\n", "<br>")


def _caliber_lines(
    effective_rule: dict[str, Any],
    lineage: dict[str, Any],
    hospital_id: str,
) -> str:
    level = str(effective_rule.get("effective_level") or "national")
    is_hospital = level == "hospital"
    source = "本院生效口径" if is_hospital else "标准口径"
    versions = [f"标准版本 v{effective_rule.get('national_version') or '-'}"]
    if is_hospital:
        versions.append(f"本院版本 v{effective_rule.get('hospital_version') or '-'}")

    rows: list[list[Any]] = [
        ["适用医院", hospital_id],
        ["口径来源", source],
        ["口径版本", "；".join(versions)],
    ]
    custom_lines = []
    if is_hospital:
        for item in lineage.get("caliber_rows") or []:
            custom_lines.append(
                f"{_parameter_label(str(item.get('parameter') or ''))}："
                f"{item.get('current_value') or '-'}"
                f"（标准值：{item.get('standard_value') or '-'}；"
                f"{item.get('effect_scope') or '影响范围待确认'}）"
            )
    if custom_lines:
        rows.append(["本院定制", "；".join(custom_lines)])
    return "## 当前采用口径\n\n" + _markdown_table(["项目", "内容"], rows)


def _branch_section(
    title: str,
    lineage: dict[str, Any],
    branch: str,
    result: dict[str, Any],
) -> str:
    rows = lineage.get(f"{branch}_rows") or []
    if not rows:
        return (
            f"## {title}\n\n"
            "字段关系尚未结构化，系统不会根据字段名称猜测分子、分母关系。"
        )
    rendered_rows = []
    for row in rows:
        decision = str(row.get("condition_text") or "-")
        if row.get("derivation_text"):
            decision += f"；计算方式：{row['derivation_text']}"
        rendered_rows.append(
            [
                row.get("label") or "-",
                _list_text(row.get("business_fields")),
                _list_text(row.get("physical_fields")),
                decision,
                row.get("source") or "-",
                row.get("effect") or "-",
            ]
        )
    context = ""
    if branch == "denominator":
        database = lineage.get("db_name") or "未配置数据库"
        table = lineage.get("main_table") or "未配置主表"
        dialect = str(result.get("dialect") or "mysql").upper()
        context = f"数据来源：`{database}.{table}`（{dialect}）\n\n"
    effect_header = "对分母的作用" if branch == "denominator" else "对分子的作用"
    return (
        f"## {title}\n\n"
        + context
        + _markdown_table(
            ["步骤", "业务字段", "医院表字段", "判断或计算方式", "条件来源", effect_header],
            rendered_rows,
        )
    )


def _caliber_target_section(
    effective_rule: dict[str, Any], lineage: dict[str, Any]
) -> str:
    title = "## 本院口径作用在哪里"
    if str(effective_rule.get("effective_level") or "national") != "hospital":
        return title + "\n\n当前采用标准口径，无额外医院参数。"
    rows = lineage.get("caliber_rows") or []
    if not rows:
        return title + "\n\n字段关系尚未结构化，暂不能可靠说明医院参数作用位置。"
    return title + "\n\n" + _markdown_table(
        ["口径项", "本院值", "标准值", "作用条件", "对应医院字段", "影响范围"],
        [
            [
                _parameter_label(str(item.get("parameter") or "")),
                item.get("current_value") or "-",
                item.get("standard_value") or "-",
                item.get("condition_name") or "-",
                _list_text(item.get("physical_fields")),
                item.get("effect_scope") or "-",
            ]
            for item in rows
        ],
    )


def _formula_section(lineage: dict[str, Any]) -> str:
    numerator = lineage.get("numerator_name") or "分子"
    denominator = lineage.get("denominator_name") or "分母"
    return (
        "## 最终如何计算\n\n"
        f"`{numerator} / {denominator} x 100%`。"
    )


def _list_text(values: Any) -> str:
    items = [str(item) for item in values or [] if str(item)]
    return "；".join(items) if items else "-"


def _parameter_table(
    params: dict[str, Any], hospital_id: str, stat_start: str, stat_end: str
) -> str:
    rows: list[list[Any]] = [
        ["医院", hospital_id],
        ["统计区间", _format_period(stat_start, stat_end)],
    ]
    rows.extend(
        [_parameter_label(key), _display_parameter(key, value)]
        for key, value in params.items()
        if key not in {"hospital_id", "start_time", "end_time"}
    )
    return "## 本次计算参数\n\n" + _markdown_table(["参数", "本次使用值"], rows)


def _trial_conclusion(trial: dict[str, Any], lineage: dict[str, Any]) -> str:
    if str(trial.get("status") or "") == "failed":
        return f"试运行失败：{trial.get('error_message') or '数据库未返回结果'}"
    numerator = _optional_int(trial.get("numerator_count"))
    denominator = _optional_int(trial.get("denominator_count"))
    if denominator == 0:
        return "本期没有符合分母条件的数据，指标暂不可计算。"
    if numerator is None or denominator is None:
        return "旧版 SQL 未返回分子分母，暂无法展开计算过程，请重新生成 SQL。"
    if numerator > denominator:
        return "分子大于分母，结果异常，请检查本院口径或 SQL。"
    result_value = _display_value(trial.get("result_value"))
    denominator_name = str(lineage.get("denominator_name") or "业务记录")
    subject = _subject_name(denominator_name)
    unit = _count_unit(denominator_name)
    return (
        f"本期共有{denominator}{unit}{subject}进入分母，其中{numerator}{unit}进入分子，"
        f"因此 {numerator} / {denominator} x 100% = {result_value}%。"
    )


def _trial_table(trial: dict[str, Any], lineage: dict[str, Any]) -> str:
    numerator = _optional_int(trial.get("numerator_count"))
    denominator = _optional_int(trial.get("denominator_count"))
    numerator_name = str(lineage.get("numerator_name") or "符合分子条件的数量")
    denominator_name = str(lineage.get("denominator_name") or "符合分母条件的数量")
    rows: list[list[Any]] = [
        ["分母", denominator if denominator is not None else "未返回", denominator_name],
        ["分子", numerator if numerator is not None else "未返回", numerator_name],
    ]
    if numerator is not None and denominator is not None and 0 <= numerator <= denominator:
        rows.append(["未进入分子", denominator - numerator, "分母数量减去分子数量"])
    rows.append(["指标结果", f"{_display_value(trial.get('result_value'))}%", "分子 / 分母 x 100%"])
    return "## 本期聚合结果\n\n" + _markdown_table(["统计项", "数量", "说明"], rows)


def _run_metadata_table(
    trial: dict[str, Any], hospital_id: str, stat_start: str, stat_end: str
) -> str:
    rows = [
        ["运行 ID", trial.get("run_id") or "未记录"],
        ["医院", hospital_id],
        ["统计区间", _format_period(trial.get("stat_start") or stat_start, trial.get("stat_end") or stat_end)],
        ["数据源", trial.get("source") or "未返回"],
        ["运行耗时", f"{int(trial.get('duration_ms') or 0)}ms"],
    ]
    return "## 运行信息\n\n" + _markdown_table(["项目", "内容"], rows)


def _parameter_label(key: str) -> str:
    return PARAMETER_LABELS.get(key, key)


def _display_parameter(key: str, value: Any) -> str:
    if value is None:
        return "未配置"
    displayed = _display_value(value)
    if "minutes" in key:
        return f"{displayed}分钟"
    if "hours" in key:
        return f"{displayed}小时"
    return displayed


def _display_value(value: Any) -> str:
    if value is None:
        return "未返回"
    if isinstance(value, bool):
        return "是" if value else "否"
    if isinstance(value, (int, float)):
        number = float(value)
        if math.isfinite(number) and number.is_integer():
            return str(int(number))
        if math.isfinite(number):
            return f"{number:.2f}".rstrip("0").rstrip(".")
    return str(value)


def _format_period(start: Any, end: Any) -> str:
    return f"{_format_datetime(start)} 至 {_format_datetime(end)}（不含结束时刻）"


def _format_datetime(value: Any) -> str:
    text = str(value or "")
    try:
        return datetime.fromisoformat(text).strftime("%Y-%m-%d %H:%M:%S")
    except ValueError:
        return text or "未配置"


def _optional_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _count_unit(name: str) -> str:
    if "人数" in name or "患者数" in name:
        return "人"
    if "例" in name:
        return "例"
    return "次" if "次数" in name else "条"


def _subject_name(name: str) -> str:
    value = name.removeprefix("同期").removeprefix("本期")
    for suffix in ("总次数", "总人数", "总例次", "次数", "人数", "数量", "总数"):
        if value.endswith(suffix):
            value = value[: -len(suffix)]
            break
    return value or "业务记录"
