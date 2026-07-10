from __future__ import annotations

import re
from typing import Any

from pydantic import ValidationError

from .contracts import IndicatorCondition, IndicatorSQLPlan


class SQLPlanError(ValueError):
    pass


def render_indicator_sql(
    plan: IndicatorSQLPlan | dict[str, Any],
    mappings: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    try:
        contract = (
            plan if isinstance(plan, IndicatorSQLPlan) else IndicatorSQLPlan.model_validate(plan)
        )
    except ValidationError as exc:
        raise SQLPlanError("SQL计算计划不合法") from exc

    table = _identifier(contract.main_table)
    referenced = {
        contract.subject_field,
        contract.time_field,
        contract.hospital_field,
        *(item.field for item in contract.numerator_conditions),
        *(item.field for item in contract.denominator_conditions),
        *(
            item.compare_field
            for item in [
                *contract.numerator_conditions,
                *contract.denominator_conditions,
            ]
            if item.compare_field
        ),
    }
    missing = sorted(field for field in referenced if field not in mappings)
    if missing:
        raise SQLPlanError(f"字段尚未确认：{missing}")
    for field in referenced:
        mapped_table = str(mappings[field].get("table_name") or "")
        if mapped_table != contract.main_table:
            raise SQLPlanError("第一版SQL计划只能使用单一主表")

    columns = {
        field: f"`{_identifier(str(mappings[field].get('column_name') or ''))}`"
        for field in referenced
    }
    denominator_sql, denominator_params = _conditions(
        contract.denominator_conditions, columns, "den"
    )
    numerator_only_sql, numerator_params = _conditions(
        contract.numerator_conditions, columns, "num"
    )
    numerator_sql = _and(denominator_sql, numerator_only_sql)
    subject = columns[contract.subject_field]
    where = (
        f"{columns[contract.hospital_field]} = :hospital_id\n"
        f"  AND {columns[contract.time_field]} >= :start_time\n"
        f"  AND {columns[contract.time_field]} < :end_time"
    )

    if contract.metric_type == "ratio":
        numerator_count = f"COUNT(DISTINCT CASE WHEN {numerator_sql} THEN {subject} END)"
        denominator_count = f"COUNT(DISTINCT CASE WHEN {denominator_sql} THEN {subject} END)"
        sql_text = (
            "SELECT\n"
            f"  CASE WHEN {denominator_count} = 0 THEN 0\n"
            f"       ELSE ROUND({numerator_count} / {denominator_count} * 100, 2)\n"
            "  END AS index_value,\n"
            f"  {denominator_count} AS sample_count\n"
            f"FROM `{table}`\n"
            f"WHERE {where}"
        )
    else:
        count_expression = f"COUNT(DISTINCT CASE WHEN {numerator_sql} THEN {subject} END)"
        sql_text = (
            "SELECT\n"
            f"  {count_expression} AS index_value,\n"
            f"  {count_expression} AS sample_count\n"
            f"FROM `{table}`\n"
            f"WHERE {where}"
        )
    return {
        "sql_text": sql_text,
        "params": {**denominator_params, **numerator_params},
        "main_table": contract.main_table,
        "metric_type": contract.metric_type,
    }


def _conditions(
    conditions: list[IndicatorCondition],
    columns: dict[str, str],
    prefix: str,
) -> tuple[str, dict[str, Any]]:
    clauses: list[str] = []
    params: dict[str, Any] = {}
    for index, condition in enumerate(conditions):
        column = columns[condition.field]
        operator = condition.operator
        base = f"{prefix}_{index}"
        if operator == "is_null":
            clauses.append(f"{column} IS NULL")
        elif operator == "not_null":
            clauses.append(f"{column} IS NOT NULL")
        elif operator in {"in", "not_in"}:
            values = condition.value if isinstance(condition.value, list) else []
            if not values:
                raise SQLPlanError(f"{operator} 条件必须提供非空列表")
            names = []
            for value_index, value in enumerate(values):
                name = f"{base}_{value_index}"
                params[name] = value
                names.append(f":{name}")
            keyword = "IN" if operator == "in" else "NOT IN"
            clauses.append(f"{column} {keyword} ({', '.join(names)})")
        elif operator == "minutes_between_lte":
            if not condition.compare_field:
                raise SQLPlanError("minutes_between_lte 条件缺少 compare_field")
            if not isinstance(condition.value, (int, float)) or condition.value < 0:
                raise SQLPlanError("minutes_between_lte 条件必须提供非负分钟数")
            params[base] = condition.value
            clauses.append(
                "TIMESTAMPDIFF(MINUTE, "
                f"{columns[condition.compare_field]}, {column}) "
                f"BETWEEN 0 AND :{base}"
            )
        else:
            symbols = {
                "eq": "=",
                "ne": "<>",
                "gt": ">",
                "gte": ">=",
                "lt": "<",
                "lte": "<=",
            }
            if condition.value is None:
                raise SQLPlanError(f"{operator} 条件缺少值")
            params[base] = condition.value
            clauses.append(f"{column} {symbols[operator]} :{base}")
    return (" AND ".join(clauses) if clauses else "1=1", params)


def _and(left: str, right: str) -> str:
    if left == "1=1":
        return right
    if right == "1=1":
        return left
    return f"{left} AND {right}"


def _identifier(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value or ""):
        raise SQLPlanError(f"非法标识符：{value}")
    return value
