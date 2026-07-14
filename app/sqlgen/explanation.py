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

RULE_FIELD_LABELS = {
    "numerator_rule": "分子规则",
    "denominator_rule": "分母规则",
    "filter_rule": "筛选条件",
    "exclude_rule": "排除规则",
}

LOGIC_LABELS = {
    "arrive_minutes": "申请至到位耗时",
    "transfer_minutes": "入院至转科耗时",
}


def format_generation_explanation(
    *,
    result: dict[str, Any],
    effective_rule: dict[str, Any],
    spec: dict[str, Any],
    field_contract: dict[str, Any],
    mapping: dict[str, Any],
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
                ["指标", effective_rule.get("rule_name") or spec.get("rule_name") or "未命名指标"],
                ["SQL ID", result.get("sql_id") or "未记录"],
                ["安全校验", validation.get("message") or validation.get("error") or "未返回"],
            ],
        ),
        _caliber_lines(effective_rule, spec, result.get("params") or {}, hospital_id),
        _definition_table(spec, field_contract, result.get("params") or {}),
        _field_table(spec, field_contract, mapping),
        _parameter_table(result.get("params") or {}, hospital_id, stat_start, stat_end),
        f"```sql\n{result.get('sql_text') or ''}\n```",
        "如需验证本期结果，请输入「试运行」。",
    ]
    return "\n\n".join(section for section in sections if section)


def format_trial_explanation(
    *,
    result: dict[str, Any],
    effective_rule: dict[str, Any],
    spec: dict[str, Any],
    field_contract: dict[str, Any],
    mapping: dict[str, Any],
    hospital_id: str,
    stat_start: str,
    stat_end: str,
) -> str:
    trial = result.get("trial_run") or {}
    sections = [
        "## 试运行完成",
        _caliber_lines(effective_rule, spec, result.get("params") or {}, hospital_id),
        _definition_table(spec, field_contract, result.get("params") or {}),
        _trial_conclusion(trial, spec),
        _trial_table(trial, spec),
        _field_table(spec, field_contract, mapping),
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
    spec: dict[str, Any],
    params: dict[str, Any],
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
    custom_lines = _custom_caliber_lines(effective_rule, spec, params) if is_hospital else []
    if custom_lines:
        rows.append(["本院定制", "；".join(custom_lines)])
    return "## 当前采用口径\n\n" + _markdown_table(["项目", "内容"], rows)


def _custom_caliber_lines(
    effective_rule: dict[str, Any], spec: dict[str, Any], params: dict[str, Any]
) -> list[str]:
    default_params = effective_rule.get("national_params") or spec.get("default_params") or {}
    overridden = effective_rule.get("overridden_fields") or []
    lines: list[str] = []
    for key in overridden:
        if key in params:
            current = _display_parameter(key, params[key])
            standard = _display_parameter(key, default_params.get(key))
            lines.append(f"{_parameter_label(key)}：{current}（标准值：{standard}）")
        elif key in RULE_FIELD_LABELS and effective_rule.get(key):
            lines.append(f"{RULE_FIELD_LABELS[key]}：{effective_rule[key]}")
    return lines


def _definition_table(
    spec: dict[str, Any], field_contract: dict[str, Any], params: dict[str, Any]
) -> str:
    numerator = spec.get("numerator") or {}
    denominator = spec.get("denominator") or {}
    rows = [
        [
            "分母",
            denominator.get("name") or "符合统计范围的总数",
            _logic_text(denominator.get("logic") or [], field_contract, params),
        ],
        [
            "分子",
            numerator.get("name") or "符合指标条件的数量",
            _logic_text(numerator.get("logic") or [], field_contract, params),
        ],
        ["公式", "指标的计算方式", "分子 / 分母 x 100%"],
    ]
    return "## 为什么这样计算\n\n" + _markdown_table(
        ["计算项", "业务解释", "本院实际条件"], rows
    )


def _logic_text(
    logic_items: Iterable[Any], field_contract: dict[str, Any], params: dict[str, Any]
) -> str:
    rendered = [
        _humanize_logic(str(item), field_contract, params)
        for item in logic_items
        if str(item).strip()
    ]
    return "；".join(rendered) if rendered else "按当前生效口径执行"


def _humanize_logic(
    logic: str, field_contract: dict[str, Any], params: dict[str, Any]
) -> str:
    text = logic
    business_fields = field_contract.get("business_fields") or {}
    replacements: dict[str, str] = dict(LOGIC_LABELS)
    replacements.update(
        {
            key: str((item or {}).get("desc") or key)
            for key, item in business_fields.items()
        }
    )
    replacements.update(
        {key: _display_parameter(key, value) for key, value in params.items()}
    )
    for key in sorted(replacements, key=len, reverse=True):
        text = text.replace(key, replacements[key])
    if text.startswith("0 <= ") and " <= " in text[5:]:
        middle, upper = text[5:].split(" <= ", 1)
        return f"{middle}为0至{upper}"
    return text.replace(" = ", "为")


def _field_table(
    spec: dict[str, Any], field_contract: dict[str, Any], mapping: dict[str, Any]
) -> str:
    business_fields = field_contract.get("business_fields") or {}
    mapped_fields = mapping.get("fields") or {}
    required = spec.get("required_business_fields") or list(mapped_fields)
    rows = []
    for key in required:
        contract = business_fields.get(key) or {}
        rows.append([key, contract.get("desc") or key, mapped_fields.get(key) or "未映射"])
    database = mapping.get("db_name") or "未配置数据库"
    table = mapping.get("main_table") or "未配置主表"
    dialect = str(mapping.get("dialect") or "mysql").upper()
    source = f"`{database}.{table}`（{dialect}）"
    return (
        "## 从哪里取数\n\n"
        f"数据来源：{source}\n\n"
        + _markdown_table(["业务字段", "业务含义", "医院字段"], rows)
    )


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


def _trial_conclusion(trial: dict[str, Any], spec: dict[str, Any]) -> str:
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
    subject = _subject_name(str((spec.get("denominator") or {}).get("name") or "业务记录"))
    unit = _count_unit(str((spec.get("denominator") or {}).get("name") or ""))
    return (
        f"本期共有{denominator}{unit}{subject}进入分母，其中{numerator}{unit}进入分子，"
        f"因此 {numerator} / {denominator} x 100% = {result_value}%。"
    )


def _trial_table(trial: dict[str, Any], spec: dict[str, Any]) -> str:
    numerator = _optional_int(trial.get("numerator_count"))
    denominator = _optional_int(trial.get("denominator_count"))
    numerator_name = str((spec.get("numerator") or {}).get("name") or "符合分子条件的数量")
    denominator_name = str((spec.get("denominator") or {}).get("name") or "符合分母条件的数量")
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
