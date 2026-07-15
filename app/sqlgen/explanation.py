"""Deterministic business explanations for generated and trial-run SQL."""

from __future__ import annotations

import math
import re
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

DETAILS_START = ":::details 查看技术详情（供信息科和实施人员）"
DETAILS_END = ":::"


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
        "## 指标计算方法已准备好",
        _doctor_caliber_section(
            effective_rule, lineage, hospital_id, stat_start, stat_end
        ),
        _execution_context_notice(result),
        _business_calculation_section(lineage, result),
        "如需验证本期结果，请输入「试运行」。",
        _details_section(
            [
                "## SQL 已生成",
                _markdown_table(
                    ["项目", "结果"],
                    [
                        ["指标", effective_rule.get("rule_name") or "未命名指标"],
                        ["SQL ID", result.get("sql_id") or "未记录"],
                        [
                            "安全校验",
                            validation.get("message")
                            or validation.get("error")
                            or "未返回",
                        ],
                    ],
                ),
                _caliber_lines(effective_rule, lineage, hospital_id),
                _branch_section("分母如何取数", lineage, "denominator", result),
                _branch_section(
                    "分子如何从分母中筛选", lineage, "numerator", result
                ),
                _caliber_target_section(effective_rule, lineage),
                _parameter_table(
                    result.get("params") or {}, hospital_id, stat_start, stat_end
                ),
                f"```sql\n{result.get('sql_text') or ''}\n```",
            ]
        ),
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
        "## 本次计算结果",
        _trial_conclusion(trial, lineage, effective_rule),
        _trial_table(trial, lineage),
        _doctor_caliber_section(
            effective_rule, lineage, hospital_id, stat_start, stat_end
        ),
        _execution_context_notice(result),
        _business_calculation_section(lineage, result),
        _details_section(
            [
                "## 试运行技术信息",
                _caliber_lines(effective_rule, lineage, hospital_id),
                _branch_section("分母如何取数", lineage, "denominator", result),
                _branch_section(
                    "分子如何从分母中筛选", lineage, "numerator", result
                ),
                _caliber_target_section(effective_rule, lineage),
                _run_metadata_table(trial, hospital_id, stat_start, stat_end),
                f"```sql\n{result.get('sql_text') or ''}\n```",
            ]
        ),
    ]
    return "\n\n".join(section for section in sections if section)


def _details_section(sections: Iterable[str]) -> str:
    body = "\n\n".join(section for section in sections if section)
    return f"{DETAILS_START}\n{body}\n{DETAILS_END}"


def _execution_context_notice(result: dict[str, Any]) -> str:
    context = result.get("execution_context") or {}
    overrides = context.get("overrides") or {}
    resolved = context.get("resolved_fields") or {}
    ward_roles = [
        key
        for key in ("period_time_field", "elapsed_time_start")
        if overrides.get(key) == "ward_entry_time"
    ]
    if not ward_roles:
        return ""
    field = next(
        (str(resolved.get(key) or "") for key in ward_roles if resolved.get(key)),
        "",
    )
    role_text = (
        "统计范围和48小时计算起点"
        if len(ward_roles) == 2
        else (
            "统计范围"
            if ward_roles[0] == "period_time_field"
            else "48小时计算起点"
        )
    )
    location = f"（医院字段：`{field}`）" if field else ""
    trial = result.get("trial_run") or {}
    source_count = _optional_int(trial.get("ward_entry_source_count"))
    missing_count = _optional_int(trial.get("ward_entry_missing_count"))
    completeness = trial.get("ward_entry_completeness_percent")
    quality_text = ""
    if source_count is not None and missing_count is not None:
        quality_text = (
            f"本次按办理住院日期核对同一时间范围内的{source_count}条住院记录，"
            f"首次入区时间缺失{missing_count}条，完整率为"
            f"{_display_value(completeness)}%。"
        )
    return (
        "## 本次临时口径\n\n"
        f"{role_text}采用**首次入区时间**{location}。"
        f"{quality_text}"
        "缺失该时间的记录不会自动改用办理住院时间，"
        "以免在同一次统计中混用两种口径；请同时核对数据完整性。"
    )


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


def _doctor_caliber_section(
    effective_rule: dict[str, Any],
    lineage: dict[str, Any],
    hospital_id: str,
    stat_start: str,
    stat_end: str,
) -> str:
    is_hospital = str(effective_rule.get("effective_level") or "national") == "hospital"
    source = "采用本院生效口径" if is_hospital else "采用标准口径"
    rows: list[list[Any]] = [
        ["指标", effective_rule.get("rule_name") or "未命名指标"],
        ["统计医院", "当前医院" if hospital_id else "未指定医院"],
        ["统计时间", _format_period(stat_start, stat_end)],
        ["采用规则", source],
    ]
    adjustments = []
    if is_hospital:
        for item in lineage.get("caliber_rows") or []:
            effect = str(item.get("effect_scope") or "影响范围待确认").replace(
                "只改变分子", "只影响分子"
            )
            adjustments.append(
                f"{_parameter_label(str(item.get('parameter') or ''))}采用"
                f"{item.get('current_value') or '-'}（标准值："
                f"{item.get('standard_value') or '-'}），{effect}"
            )
    if adjustments:
        rows.append(["本院口径", "；".join(adjustments)])
    return "## 当前采用什么规则\n\n" + _markdown_table(["项目", "通俗说明"], rows)


def _business_calculation_section(
    lineage: dict[str, Any], result: dict[str, Any]
) -> str:
    if not lineage.get("denominator_rows") or not lineage.get("numerator_rows"):
        return "## 分子与分母怎么计算\n\n" + _missing_lineage_message()
    return "\n\n".join(
        [
            _data_source_section(lineage, result),
            _plain_calculation_summary(lineage),
            _calculation_breakdown_table(lineage),
            _execution_steps_section(lineage, result),
        ]
    )


def _data_source_section(
    lineage: dict[str, Any], result: dict[str, Any]
) -> str:
    database = str(lineage.get("db_name") or "尚未配置")
    dialect = str(result.get("dialect") or "mysql").upper()
    tables = list(lineage.get("physical_tables") or [])
    main_table = str(lineage.get("main_table") or "")
    if not tables and main_table:
        tables.append(main_table)

    field_items = _lineage_field_items(lineage)
    rows: list[list[str]] = []
    for table in tables:
        labels = _unique_text(
            str(item.get("label") or item.get("business_field") or "")
            for item in field_items
            if str(item.get("physical_field") or "").startswith(f"{table}.")
        )
        purpose = "、".join(labels) if labels else "字段用途尚未映射"
        rows.append([f"`{table}`", f"提供{purpose}"])
    if not rows:
        rows.append(["尚未配置", "数据表映射尚未配置完整"])

    return (
        "## 数据从哪里来\n\n"
        f"数据库：`{database}`（{dialect}）\n\n"
        + _markdown_table(["医院数据表", "本指标使用的数据"], rows)
    )


def _plain_calculation_summary(lineage: dict[str, Any]) -> str:
    denominator_conditions = _condition_texts(
        lineage.get("denominator_rows") or [], exclude_inheritance=True
    )
    numerator_rows = _condition_rows(lineage.get("numerator_rows") or [])
    numerator_conditions = []
    for row in numerator_rows:
        text = str(row.get("condition_text") or "")
        derivation = str(row.get("derivation_text") or "")
        if derivation:
            text = f"{derivation}，再判断{text}"
        if text:
            numerator_conditions.append(text)

    source = _source_location_text(lineage)
    denominator_text = "；".join(denominator_conditions)
    numerator_text = "；".join(numerator_conditions) or "满足分子追加条件"
    caliber = next(
        (
            item
            for item in lineage.get("caliber_rows") or []
            if "分子" in str(item.get("effect_scope") or "")
        ),
        None,
    )
    caliber_text = ""
    if caliber:
        caliber_text = f"，本次按本院规定的{caliber.get('current_value') or '-'}判断"
    return (
        "## 一句话说明\n\n"
        f"先从{source}筛出“{denominator_text}”的记录，逐条计数得到分母；"
        f"再从这些记录中按“{numerator_text}”继续筛选{caliber_text}，"
        "符合条件的记录累计得到分子；最后用分子除以分母并乘以 100%。"
    )


def _calculation_breakdown_table(lineage: dict[str, Any]) -> str:
    denominator_rows = lineage.get("denominator_rows") or []
    numerator_rows = lineage.get("numerator_rows") or []
    denominator_fields = _format_field_items(
        _unique_field_items(denominator_rows)
    )
    numerator_fields = _format_field_items(_unique_field_items(numerator_rows))
    denominator_conditions = "；".join(
        _condition_texts(denominator_rows, exclude_inheritance=True)
    )
    numerator_conditions = _numerator_rule_text(numerator_rows)
    denominator_aggregate = _aggregate_row_text(denominator_rows)
    numerator_aggregate = _aggregate_row_text(numerator_rows)
    denominator_formula = _aggregate_formula("分母", denominator_rows)
    numerator_formula = _aggregate_formula("分子", numerator_rows)

    return "## 分子与分母怎么计算\n\n" + _markdown_table(
        ["统计项", "涉及的医院数据", "系统怎样计算", "运算关系"],
        [
            [
                "分母（统计范围）",
                denominator_fields,
                f"同时满足：{denominator_conditions}。然后{denominator_aggregate}。",
                denominator_formula,
            ],
            [
                "分子（达到要求）",
                f"继承分母全部字段；另外使用：{numerator_fields}",
                f"先进入分母，再判断：{numerator_conditions}。然后{numerator_aggregate}。",
                numerator_formula,
            ],
            [
                "指标结果",
                "分子、分母",
                "分母不为 0 时，用分子除以分母，再乘以 100，结果保留两位小数。",
                "指标值 = 分子 / 分母 x 100%",
            ],
            [
                "样本数",
                "与分母相同",
                "用于说明本次统计实际覆盖了多少条业务记录。",
                "样本数 = 分母",
            ],
        ],
    )


def _execution_steps_section(
    lineage: dict[str, Any], result: dict[str, Any]
) -> str:
    denominator_rows = lineage.get("denominator_rows") or []
    numerator_rows = lineage.get("numerator_rows") or []
    source = _source_location_text(lineage)
    denominator_conditions = "；".join(
        _condition_texts(denominator_rows, exclude_inheritance=True)
    )
    steps = [
        f"**筛选统计范围**：从{source}取出同时满足“{denominator_conditions}”的记录。"
    ]

    derived_rows = [
        row for row in _condition_rows(numerator_rows) if row.get("derivation_text")
    ]
    if derived_rows:
        details = "；".join(
            _derived_operation_text(row, result) for row in derived_rows
        )
        steps.append(f"**计算时间差**：{details}。")

    steps.append(
        f"**统计分母**：{_aggregate_row_text(denominator_rows)}，得到“"
        f"{lineage.get('denominator_name') or '分母'}”。"
    )
    numerator_name = lineage.get("numerator_name") or "分子"
    if _is_distinct_aggregate(numerator_rows):
        numerator_count = (
            f"满足条件后，{_aggregate_row_text(numerator_rows)}，"
            f"得到“{numerator_name}”"
        )
    else:
        numerator_count = (
            "满足条件记为 1，不满足记为 0，"
            f"最后累计得到“{numerator_name}”"
        )
    steps.append(
        "**统计分子**：只在分母记录中继续判断“"
        f"{_numerator_rule_text(numerator_rows)}”；{numerator_count}。"
    )
    steps.append(
        "**计算指标**：分子 / 分母 x 100%，四舍五入保留两位小数；"
        "如果分母为 0，则显示本期无可计算数据。"
    )
    rendered = "\n".join(
        f"{index}. {step}" for index, step in enumerate(steps, start=1)
    )
    return f"## 系统实际执行的步骤\n\n{rendered}"


def _condition_rows(rows: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        row
        for row in rows
        if str(row.get("condition_id") or "") != "inherits_denominator"
        and not str(row.get("condition_id") or "").endswith("_aggregate")
    ]


def _condition_texts(
    rows: Iterable[dict[str, Any]], *, exclude_inheritance: bool
) -> list[str]:
    result = []
    for row in rows:
        condition_id = str(row.get("condition_id") or "")
        if condition_id.endswith("_aggregate"):
            continue
        if exclude_inheritance and condition_id == "inherits_denominator":
            continue
        text = str(row.get("condition_text") or "")
        if text:
            result.append(text)
    return result


def _numerator_rule_text(rows: Iterable[dict[str, Any]]) -> str:
    rules = []
    for row in _condition_rows(rows):
        condition = str(row.get("condition_text") or "")
        derivation = str(row.get("derivation_text") or "")
        if derivation:
            operation = _readable_subtraction(row)
            condition = f"先用{operation}，再判断{condition}"
        if condition:
            rules.append(condition)
    return "；".join(rules) or "满足分子追加条件"


def _aggregate_row_text(rows: Iterable[dict[str, Any]]) -> str:
    return next(
        (
            str(row.get("condition_text") or "")
            for row in rows
            if str(row.get("condition_id") or "").endswith("_aggregate")
        ),
        "每条符合条件的业务记录计1次",
    )


def _aggregate_formula(stage: str, rows: Iterable[dict[str, Any]]) -> str:
    aggregate = next(
        (
            row
            for row in rows
            if str(row.get("condition_id") or "").endswith("_aggregate")
        ),
        None,
    )
    text = str((aggregate or {}).get("condition_text") or "")
    items = list((aggregate or {}).get("field_items") or [])
    if "去重计数" in text and items:
        field = str(items[0].get("physical_field") or items[0].get("label") or "-")
        return f"{stage} = COUNT(DISTINCT {field})"
    if stage == "分子":
        return "分子 = SUM(满足分子条件：是=1，否=0)"
    return "分母 = 符合全部范围条件的记录数"


def _is_distinct_aggregate(rows: Iterable[dict[str, Any]]) -> bool:
    return "去重计数" in _aggregate_row_text(rows)


def _derived_operation_text(
    row: dict[str, Any], result: dict[str, Any]
) -> str:
    operation = _readable_subtraction(row)
    items = list(row.get("field_items") or [])
    dialect = str(result.get("dialect") or "mysql").upper()
    if len(items) == 2 and dialect == "MYSQL":
        start = str(items[0].get("physical_field") or "尚未映射")
        end = str(items[1].get("physical_field") or "尚未映射")
        if not start.startswith("未映射(") and not end.startswith("未映射("):
            return (
                f"对每条记录执行{operation}并换算为分钟；MySQL 使用 "
                f"`TIMESTAMPDIFF(MINUTE, {start}, {end})`"
            )
    return f"对每条记录执行{operation}并换算为分钟"


def _readable_subtraction(row: dict[str, Any]) -> str:
    items = list(row.get("field_items") or [])
    if len(items) != 2:
        return str(row.get("derivation_text") or "计算派生值")
    start = str(items[0].get("label") or items[0].get("business_field") or "开始值")
    end = str(items[1].get("label") or items[1].get("business_field") or "结束值")
    prefix_length = 0
    for start_char, end_char in zip(start, end):
        if start_char != end_char:
            break
        prefix_length += 1
    if prefix_length >= 2 and start[prefix_length:] and end[prefix_length:]:
        start = start[prefix_length:]
        end = end[prefix_length:]
    return f"{end}减{start}"


def _source_location_text(lineage: dict[str, Any]) -> str:
    database = str(lineage.get("db_name") or "尚未配置的数据库")
    tables = list(lineage.get("physical_tables") or [])
    if not tables and lineage.get("main_table"):
        tables.append(str(lineage["main_table"]))
    table_text = "、".join(f"`{table}` 表" for table in tables) or "尚未配置的数据表"
    return f"`{database}` 数据库的 {table_text}中"


def _lineage_field_items(lineage: dict[str, Any]) -> list[dict[str, Any]]:
    items = list(lineage.get("field_items") or [])
    if items:
        return items
    return _unique_field_items(
        [
            *(lineage.get("denominator_rows") or []),
            *(lineage.get("numerator_rows") or []),
        ]
    )


def _unique_field_items(rows: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for row in rows:
        items = list(row.get("field_items") or [])
        if not items:
            items = [
                {
                    "business_field": business,
                    "label": business,
                    "physical_field": physical,
                }
                for business, physical in zip(
                    row.get("business_fields") or [],
                    row.get("physical_fields") or [],
                )
            ]
        for item in items:
            key = (
                str(item.get("business_field") or ""),
                str(item.get("physical_field") or ""),
            )
            if key in seen:
                continue
            seen.add(key)
            result.append(item)
    return result


def _format_field_items(items: Iterable[dict[str, Any]]) -> str:
    rendered = []
    for item in items:
        label = str(item.get("label") or item.get("business_field") or "未命名字段")
        physical = str(item.get("physical_field") or "")
        location = "尚未映射" if physical.startswith("未映射(") or not physical else f"`{physical}`"
        rendered.append(f"{label}：{location}")
    return "；".join(rendered) if rendered else "无新增字段"


def _unique_text(values: Iterable[str]) -> list[str]:
    result = []
    for value in values:
        if value and value not in result:
            result.append(value)
    return result


def _missing_lineage_message() -> str:
    return "当前指标的取数关系尚未配置完整，请联系信息科或实施人员完善后再生成。"


def _numerator_outcome(lineage: dict[str, Any]) -> str:
    denominator_subject = _subject_name(str(lineage.get("denominator_name") or ""))
    numerator_subject = _subject_name(str(lineage.get("numerator_name") or ""))
    if denominator_subject and numerator_subject.endswith(denominator_subject):
        prefix = numerator_subject[: -len(denominator_subject)]
        if prefix:
            return prefix
    return "达到指标要求"


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
            [
                "步骤",
                "系统统一名称",
                "本院数据库位置",
                "系统如何判断",
                "规则来源",
                effect_header,
            ],
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
        ["口径项", "本院值", "标准值", "作用条件", "本院数据库位置", "影响范围"],
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


def _trial_conclusion(
    trial: dict[str, Any],
    lineage: dict[str, Any],
    effective_rule: dict[str, Any],
) -> str:
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
    unmatched = denominator - numerator
    rule_name = str(effective_rule.get("rule_name") or "本指标")
    outcome = _numerator_outcome(lineage)
    numerator_caliber = next(
        (
            item
            for item in lineage.get("caliber_rows") or []
            if "分子" in str(item.get("effect_scope") or "")
        ),
        None,
    )
    if numerator_caliber and "到位" in outcome:
        detail = (
            f"其中{numerator}{unit}在本院规定的"
            f"{numerator_caliber.get('current_value') or '-'}内到位，"
            f"另有{unmatched}{unit}未在规定时间内到位"
        )
    else:
        detail = (
            f"其中{numerator}{unit}符合“{lineage.get('numerator_name') or '分子条件'}”，"
            f"另有{unmatched}{unit}未达到要求"
        )
    return (
        f"本期共有{denominator}{unit}{subject}进入统计范围，{detail}。"
        f"因此，{rule_name}为 {numerator} / {denominator} x 100% = {result_value}%。"
    )


def _trial_table(trial: dict[str, Any], lineage: dict[str, Any]) -> str:
    numerator = _optional_int(trial.get("numerator_count"))
    denominator = _optional_int(trial.get("denominator_count"))
    numerator_name = str(lineage.get("numerator_name") or "符合分子条件的数量")
    denominator_name = str(lineage.get("denominator_name") or "符合分母条件的数量")
    run_id = str(trial.get("run_id") or "")
    can_show_details = (
        str(trial.get("status") or "") == "success"
        and re.fullmatch(r"RUN_[A-Za-z0-9_]+", run_id) is not None
        and numerator is not None
        and denominator is not None
        and 0 <= numerator <= denominator
    )

    def action(group: str) -> str:
        return f"{{{{detail:{run_id}:{group}}}}}" if can_show_details else "-"

    rows: list[list[Any]] = [
        [
            "统计范围（分母）",
            denominator if denominator is not None else "未返回",
            denominator_name,
            action("denominator"),
        ],
        [
            "达到要求（分子）",
            numerator if numerator is not None else "未返回",
            numerator_name,
            action("numerator"),
        ],
    ]
    if numerator is not None and denominator is not None and 0 <= numerator <= denominator:
        rows.append(
            [
                "未达到要求",
                denominator - numerator,
                "统计范围数量减去达到要求数量",
                action("unmatched"),
            ]
        )
    rows.append(
        [
            "指标结果",
            f"{_display_value(trial.get('result_value'))}%",
            "分子 / 分母 x 100%",
            "-",
        ]
    )
    return "## 本期聚合结果\n\n" + _markdown_table(
        ["统计项", "数量", "说明", "操作"], rows
    )


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
