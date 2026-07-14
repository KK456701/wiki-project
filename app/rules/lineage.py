"""将结构化指标定义解析为分子、分母和医院字段血缘。"""

from __future__ import annotations

from typing import Any

from app.rules.calculation import (
    CalculationBranchDefinition,
    CalculationDefinition,
    ConditionDefinition,
    collect_business_dependencies,
)


FIELD_LABELS = {
    "hospital_id": "医院",
    "consult_type": "会诊类型",
    "request_time": "急会诊申请时间",
    "arrive_time": "急会诊到位时间",
    "admission_id": "入院流水号",
    "admit_time": "入院时间",
    "transfer_time": "转科时间",
    "from_dept_id": "转出科室",
    "to_dept_id": "转入科室",
    "rescue_id": "抢救事件",
    "rescue_time": "抢救时间",
    "severity_level": "患者严重程度",
    "rescue_result": "抢救结果",
    "patient_id": "患者",
    "surgery_time": "手术时间",
    "intraoperative_transfusion_flag": "术中输血标志",
    "autologous_reinfusion_flag": "自体血回输标志",
}


def build_indicator_lineage(
    definition: CalculationDefinition,
    mapping: dict[str, Any],
    params: dict[str, Any],
    effective_rule: dict[str, Any],
    stat_start: str,
    stat_end: str,
) -> dict[str, Any]:
    """生成业务说明和预校验共用的确定性字段血缘。"""

    runtime_params = {
        **params,
        "hospital_id": params.get("hospital_id") or effective_rule.get("hospital_id"),
        "start_time": params.get("start_time") or stat_start,
        "end_time": params.get("end_time") or stat_end,
    }
    denominator_rows = [
        _condition_row(
            condition,
            "scope",
            definition,
            mapping,
            runtime_params,
            effective_rule,
        )
        for condition in definition.scope.conditions
    ]
    denominator_rows.extend(
        _condition_row(
            condition,
            "denominator",
            definition,
            mapping,
            runtime_params,
            effective_rule,
        )
        for condition in definition.denominator.conditions
    )
    denominator_rows.append(
        _aggregate_row("denominator", definition.denominator, mapping)
    )

    numerator_rows = [
        {
            "stage": "numerator",
            "condition_id": "inherits_denominator",
            "label": "继承分母",
            "business_fields": [],
            "physical_fields": [],
            "condition_text": "先满足全部分母条件",
            "source": "指标定义",
            "effect": "分子一定是分母的子集",
        }
    ]
    numerator_rows.extend(
        _condition_row(
            condition,
            "numerator",
            definition,
            mapping,
            runtime_params,
            effective_rule,
        )
        for condition in definition.numerator.conditions
    )
    numerator_rows.append(_aggregate_row("numerator", definition.numerator, mapping))

    physical_tables = sorted(
        {
            physical_field.split(".", 1)[0]
            for row in [*denominator_rows, *numerator_rows]
            for physical_field in row["physical_fields"]
            if "." in physical_field and not physical_field.startswith("未映射(")
        }
    )
    return {
        "schema_version": definition.schema_version,
        "denominator_name": definition.denominator.name,
        "numerator_name": definition.numerator.name,
        "denominator_rows": denominator_rows,
        "numerator_rows": numerator_rows,
        "caliber_rows": _caliber_rows(
            definition, mapping, runtime_params, effective_rule
        ),
        "required_business_fields": sorted(
            collect_business_dependencies(definition)
        ),
        "physical_tables": physical_tables,
        "db_name": str(mapping.get("db_name") or ""),
        "main_table": str(mapping.get("main_table") or ""),
    }


def _condition_row(
    condition: ConditionDefinition,
    stage: str,
    definition: CalculationDefinition,
    mapping: dict[str, Any],
    params: dict[str, Any],
    effective_rule: dict[str, Any],
) -> dict[str, Any]:
    business_fields = _source_business_fields(condition.field, definition)
    parameter_names = _condition_parameter_names(condition, params)
    source = _condition_source(parameter_names, stage, effective_rule)
    return {
        "stage": stage,
        "condition_id": condition.id,
        "label": _condition_label(condition, stage, definition),
        "business_fields": business_fields,
        "physical_fields": _physical_fields(business_fields, mapping),
        "condition_text": _condition_text(condition, definition, params),
        "source": source,
        "effect": _condition_effect(stage),
    }


def _aggregate_row(
    stage: str,
    branch: CalculationBranchDefinition,
    mapping: dict[str, Any],
) -> dict[str, Any]:
    aggregate = branch.aggregate
    business_fields = [aggregate.field] if aggregate.field else []
    if aggregate.method == "count_distinct" and aggregate.field:
        condition_text = f"按{_field_label(aggregate.field)}去重计数"
    else:
        condition_text = "每条符合条件的业务记录计1次"
    return {
        "stage": stage,
        "condition_id": f"{stage}_aggregate",
        "label": "计数方式",
        "business_fields": business_fields,
        "physical_fields": _physical_fields(business_fields, mapping),
        "condition_text": condition_text,
        "source": "标准定义",
        "effect": f"得到{branch.name}",
    }


def _source_business_fields(
    field_name: str, definition: CalculationDefinition
) -> list[str]:
    derived = definition.derived_fields.get(field_name)
    if derived is None:
        return [field_name]
    result: list[str] = []
    for source_field in derived.source_fields:
        for item in _source_business_fields(source_field, definition):
            if item not in result:
                result.append(item)
    return result


def _physical_fields(
    business_fields: list[str], mapping: dict[str, Any]
) -> list[str]:
    mapped_fields = mapping.get("fields") or {}
    return [
        str(mapped_fields.get(field_name) or f"未映射({field_name})")
        for field_name in business_fields
    ]


def _condition_parameter_names(
    condition: ConditionDefinition, params: dict[str, Any]
) -> list[str]:
    names = [
        *([condition.parameter] if condition.parameter else []),
        *condition.parameters,
    ]
    names.extend(
        str(value)
        for value in condition.values
        if isinstance(value, str) and value in params
    )
    return list(dict.fromkeys(names))


def _condition_source(
    parameter_names: list[str], stage: str, effective_rule: dict[str, Any]
) -> str:
    overridden = set(effective_rule.get("overridden_fields") or [])
    if any(parameter_name in overridden for parameter_name in parameter_names):
        version = effective_rule.get("hospital_version") or "-"
        return f"本院版本 v{version}"
    if "hospital_id" in parameter_names:
        return "当前医院"
    if {"start_time", "end_time"} & set(parameter_names):
        return "本次统计区间"
    return "标准口径" if stage != "scope" else "本次参数"


def _condition_label(
    condition: ConditionDefinition,
    stage: str,
    definition: CalculationDefinition,
) -> str:
    if condition.id == "hospital_scope":
        return "限定医院"
    if condition.id == "period_scope":
        return "限定统计区间"
    if condition.field in definition.derived_fields:
        return f"判断{definition.derived_fields[condition.field].name}"
    return "分母筛选条件" if stage == "denominator" else "分子追加条件"


def _condition_text(
    condition: ConditionDefinition,
    definition: CalculationDefinition,
    params: dict[str, Any],
) -> str:
    field_label = (
        definition.derived_fields[condition.field].name
        if condition.field in definition.derived_fields
        else _field_label(condition.field)
    )
    if condition.operator == "is_not_null":
        return f"{field_label}不为空"
    if condition.operator in {"equals", "not_equals"}:
        value = params.get(condition.parameter or "", condition.parameter or "-")
        verb = "等于" if condition.operator == "equals" else "不等于"
        return f"{field_label}{verb}{_display_value(condition.parameter, value)}"
    if condition.operator == "half_open_range":
        values = [params.get(name, name) for name in condition.parameters]
        if len(values) == 2:
            return f"{field_label}从{values[0]}至{values[1]}（不含结束时刻）"
    if condition.operator == "inclusive_range":
        values = [
            params.get(value, value) if isinstance(value, str) else value
            for value in condition.values
        ]
        if len(values) == 2:
            upper_parameter = (
                condition.values[1]
                if isinstance(condition.values[1], str)
                else None
            )
            upper = _display_value(upper_parameter, values[1])
            return f"{field_label}为{values[0]}至{upper}"
    return f"{field_label}按{condition.operator}判断"


def _field_label(field_name: str) -> str:
    return FIELD_LABELS.get(field_name, field_name)


def _display_value(parameter_name: str | None, value: Any) -> str:
    if parameter_name and "minutes" in parameter_name:
        return f"{value}分钟"
    if parameter_name and "hours" in parameter_name:
        return f"{value}小时"
    return str(value)


def _condition_effect(stage: str) -> str:
    if stage == "scope":
        return "限定分子和分母共同统计范围"
    if stage == "denominator":
        return "满足时进入分母"
    return "在分母基础上满足时进入分子"


def _caliber_rows(
    definition: CalculationDefinition,
    mapping: dict[str, Any],
    params: dict[str, Any],
    effective_rule: dict[str, Any],
) -> list[dict[str, Any]]:
    overridden = set(effective_rule.get("overridden_fields") or [])
    national_params = effective_rule.get("national_params") or {}
    rows: list[dict[str, Any]] = []
    staged_conditions = [
        ("scope", condition) for condition in definition.scope.conditions
    ] + [
        ("denominator", condition)
        for condition in definition.denominator.conditions
    ] + [
        ("numerator", condition) for condition in definition.numerator.conditions
    ]
    for stage, condition in staged_conditions:
        for parameter_name in _condition_parameter_names(condition, params):
            if parameter_name not in overridden or parameter_name not in params:
                continue
            business_fields = _source_business_fields(condition.field, definition)
            rows.append(
                {
                    "parameter": parameter_name,
                    "current_value": _display_value(
                        parameter_name, params[parameter_name]
                    ),
                    "standard_value": _display_value(
                        parameter_name, national_params.get(parameter_name, "-")
                    ),
                    "condition_id": condition.id,
                    "condition_name": _condition_label(condition, stage, definition),
                    "physical_fields": _physical_fields(business_fields, mapping),
                    "effect_scope": (
                        "只改变分子，不改变分母"
                        if stage == "numerator"
                        else "同时影响分子和分母"
                    ),
                    "version": f"本院版本 v{effective_rule.get('hospital_version') or '-'}",
                }
            )
    return rows
