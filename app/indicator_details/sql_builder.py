from __future__ import annotations

import re
from typing import Any

from app.rules.calculation import (
    CalculationDefinition,
    ConditionDefinition,
    parse_calculation_definition,
)

from .models import DetailColumn, DetailQuery, RunContext


class DetailQueryError(ValueError):
    pass


def _identifier(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value or ""):
        raise DetailQueryError(f"字段映射包含非法标识符：{value}")
    return value


def _mapped_column(
    field: str, context: RunContext, *, required_for_detail: bool = False
) -> str:
    mapped = (context.field_mapping.get("fields") or {}).get(field)
    if not mapped:
        prefix = "明细字段尚未完成本院映射" if required_for_detail else "计算字段尚未完成本院映射"
        raise DetailQueryError(f"{prefix}：{field}")
    parts = str(mapped).split(".")
    if len(parts) != 2:
        raise DetailQueryError(f"字段映射格式错误：{field}")
    table, column = (_identifier(part) for part in parts)
    if table != context.main_table:
        raise DetailQueryError("第一版明细查询只支持单一主表")
    return f"`{table}`.`{column}`"


def _field_expression(
    field: str,
    definition: CalculationDefinition,
    context: RunContext,
    *,
    required_for_detail: bool = False,
) -> str:
    derived = definition.derived_fields.get(field)
    if derived is None:
        return _mapped_column(field, context, required_for_detail=required_for_detail)
    if derived.operation != "timestamp_diff_minutes" or len(derived.source_fields) != 2:
        raise DetailQueryError(f"明细查询暂不支持派生字段：{field}")
    start = _field_expression(derived.source_fields[0], definition, context)
    end = _field_expression(derived.source_fields[1], definition, context)
    return f"TIMESTAMPDIFF(MINUTE, {start}, {end})"


def _parameter_ref(name: str, params: dict[str, Any]) -> str:
    if name not in params:
        raise DetailQueryError(f"明细查询缺少口径参数：{name}")
    return f":{_identifier(name)}"


def _value_ref(
    value: Any,
    params: dict[str, Any],
    generated: dict[str, Any],
    name: str,
) -> str:
    if isinstance(value, str) and value in params:
        return _parameter_ref(value, params)
    safe_name = _identifier(name)
    generated[safe_name] = value
    return f":{safe_name}"


def _condition_expression(
    condition: ConditionDefinition,
    definition: CalculationDefinition,
    context: RunContext,
    generated: dict[str, Any],
) -> str:
    field = _field_expression(condition.field, definition, context)
    if condition.operator == "equals":
        if not condition.parameter:
            raise DetailQueryError(f"条件 {condition.id} 缺少参数")
        return f"{field} = {_parameter_ref(condition.parameter, context.params)}"
    if condition.operator == "not_equals":
        if not condition.parameter:
            raise DetailQueryError(f"条件 {condition.id} 缺少参数")
        return f"{field} <> {_parameter_ref(condition.parameter, context.params)}"
    if condition.operator == "half_open_range":
        if len(condition.parameters) != 2:
            raise DetailQueryError(f"条件 {condition.id} 必须提供起止参数")
        start, end = condition.parameters
        return (
            f"{field} >= {_parameter_ref(start, context.params)} "
            f"AND {field} < {_parameter_ref(end, context.params)}"
        )
    if condition.operator == "inclusive_range":
        if len(condition.values) != 2:
            raise DetailQueryError(f"条件 {condition.id} 必须提供上下界")
        start = _value_ref(
            condition.values[0], context.params, generated, f"detail_{condition.id}_start"
        )
        end = _value_ref(
            condition.values[1], context.params, generated, f"detail_{condition.id}_end"
        )
        return f"{field} BETWEEN {start} AND {end}"
    if condition.operator == "is_not_null":
        return f"{field} IS NOT NULL"
    raise DetailQueryError(f"明细查询不支持条件运算：{condition.operator}")


def _conditions_expression(
    conditions: list[ConditionDefinition],
    definition: CalculationDefinition,
    context: RunContext,
    generated: dict[str, Any],
) -> str:
    expressions = [
        _condition_expression(item, definition, context, generated)
        for item in conditions
    ]
    return " AND ".join(f"({item})" for item in expressions) if expressions else "1=1"


def build_detail_query(
    context: RunContext | dict[str, Any], row_limit: int = 20_001
) -> DetailQuery:
    run_context = (
        context if isinstance(context, RunContext) else RunContext.model_validate(context)
    )
    if not 1 <= row_limit <= 20_001:
        raise DetailQueryError("明细查询行数限制必须在1至20,001之间")
    main_table = _identifier(run_context.main_table)
    if str(run_context.field_mapping.get("main_table") or main_table) != main_table:
        raise DetailQueryError("运行快照中的主表不一致")
    definition = parse_calculation_definition(run_context.calculation_definition)
    if not definition.detail_fields:
        raise DetailQueryError("当前指标尚未配置可核对的明细字段")

    columns = [
        DetailColumn(
            field=item.field,
            label=item.label,
            sensitivity=item.sensitivity,
        )
        for item in definition.detail_fields
    ]
    field_expressions = {
        column.field: _field_expression(
            column.field,
            definition,
            run_context,
            required_for_detail=column.field not in definition.derived_fields,
        )
        for column in columns
    }
    generated: dict[str, Any] = {}
    denominator = _conditions_expression(
        [*definition.scope.conditions, *definition.denominator.conditions],
        definition,
        run_context,
        generated,
    )
    numerator = _conditions_expression(
        definition.numerator.conditions,
        definition,
        run_context,
        generated,
    )
    aggregate = definition.denominator.aggregate
    if aggregate.method != definition.numerator.aggregate.method:
        raise DetailQueryError("分子和分母计数方式不一致，无法生成明细")

    if aggregate.method == "count_distinct":
        if not aggregate.field:
            raise DetailQueryError("去重计数缺少统计对象字段")
        subject = _field_expression(aggregate.field, definition, run_context)
        select_columns = []
        for column in columns:
            expression = field_expressions[column.field]
            selected = expression if column.field == aggregate.field else f"MIN({expression})"
            select_columns.append(f"  {selected} AS `{_identifier(column.field)}`")
        select_columns.extend(
            [
                f"  MAX(CASE WHEN {numerator} THEN 1 ELSE 0 END) AS `__meets_numerator`",
                "  COUNT(*) AS `__evidence_row_count`",
            ]
        )
        sql = (
            "SELECT\n"
            + ",\n".join(select_columns)
            + f"\nFROM `{main_table}`\n"
            + f"WHERE {denominator}\n"
            + f"GROUP BY {subject}\nORDER BY {subject}\nLIMIT {row_limit}"
        )
    else:
        select_columns = [
            f"  {field_expressions[column.field]} AS `{_identifier(column.field)}`"
            for column in columns
        ]
        select_columns.extend(
            [
                f"  CASE WHEN {numerator} THEN 1 ELSE 0 END AS `__meets_numerator`",
                "  1 AS `__evidence_row_count`",
            ]
        )
        order_expression = field_expressions[columns[0].field]
        sql = (
            "SELECT\n"
            + ",\n".join(select_columns)
            + f"\nFROM `{main_table}`\n"
            + f"WHERE {denominator}\nORDER BY {order_expression}\nLIMIT {row_limit}"
        )
    return DetailQuery(
        sql=sql,
        params={**run_context.params, **generated},
        columns=columns,
    )
