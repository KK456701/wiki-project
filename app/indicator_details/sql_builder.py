from __future__ import annotations

import re
from typing import Any

from app.rules.calculation import (
    CalculationDefinition,
    ConditionDefinition,
    parse_calculation_definition,
)
from app.sqlgen.context_overrides import (
    apply_execution_field_roles,
    mapped_profile_column,
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
    if run_context.query_profile == "urgent_consult_sqlserver":
        return _build_urgent_consult_sqlserver_query(run_context, row_limit)
    if run_context.query_profile == "inpatient_transfer_48h_sqlserver":
        return _build_inpatient_transfer_sqlserver_query(run_context, row_limit)
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


def _build_urgent_consult_sqlserver_query(
    context: RunContext, row_limit: int
) -> DetailQuery:
    required_params = {
        "hospital_soid",
        "urgent_level_code",
        "arrive_minutes_threshold",
        "start_time",
        "end_time",
    }
    missing = sorted(required_params - set(context.params))
    if missing:
        raise DetailQueryError("急会诊明细缺少参数：" + "、".join(missing))
    definition = parse_calculation_definition(context.calculation_definition)
    columns = [
        DetailColumn(
            field=item.field,
            label=item.label,
            sensitivity=item.sensitivity,
        )
        for item in definition.detail_fields
    ]
    supported = {
        "consult_id",
        "patient_id",
        "dept_id",
        "consult_type",
        "request_time",
        "arrive_time",
        "arrive_minutes",
    }
    unsupported = [item.field for item in columns if item.field not in supported]
    if unsupported:
        raise DetailQueryError(
            "急会诊明细包含未支持字段：" + "、".join(unsupported)
        )
    expressions = {
        "consult_id": "base.consult_id",
        "patient_id": "base.patient_id",
        "dept_id": "base.dept_id",
        "consult_type": "N'急会诊'",
        "request_time": "base.request_time",
        "arrive_time": "base.arrive_time",
        "arrive_minutes": "DATEDIFF(MINUTE, base.request_time, base.arrive_time)",
    }
    selected = [
        f"  {expressions[item.field]} AS [{_identifier(item.field)}]"
        for item in columns
    ]
    selected.extend(
        [
            "  CASE WHEN DATEDIFF(MINUTE, base.request_time, base.arrive_time) "
            "BETWEEN 0 AND :arrive_minutes_threshold THEN 1 ELSE 0 END "
            "AS [__meets_numerator]",
            "  1 AS [__evidence_row_count]",
        ]
    )
    sql = (
        f"SELECT TOP {row_limit}\n"
        + ",\n".join(selected)
        + "\nFROM (\n"
        "  SELECT\n"
        "    apply_record.INP_CONSULT_APPLY_ID AS consult_id,\n"
        "    apply_record.ADMISSION_NUMBER AS patient_id,\n"
        "    apply_record.DEPT_ID AS dept_id,\n"
        "    apply_record.APPLY_CONSULT_SENT_AT AS request_time,\n"
        "    MIN(CASE WHEN invitation.IS_DEL = 0\n"
        "                  AND invitation.SIGNED_AT >= apply_record.APPLY_CONSULT_SENT_AT\n"
        "             THEN invitation.SIGNED_AT END) AS arrive_time\n"
        "  FROM WINDBA.INPATIENT_CONSULT_APPLY AS apply_record\n"
        "  LEFT JOIN WINDBA.INP_CONSULT_INVITATION AS invitation\n"
        "    ON invitation.INP_CONSULT_APPLY_ID = apply_record.INP_CONSULT_APPLY_ID\n"
        "   AND invitation.HOSPITAL_SOID = apply_record.HOSPITAL_SOID\n"
        "  WHERE apply_record.HOSPITAL_SOID = :hospital_soid\n"
        "    AND apply_record.CONSULT_LEVEL_CODE = :urgent_level_code\n"
        "    AND apply_record.IS_DEL = 0\n"
        "    AND apply_record.CONSULT_CANCEL_AT IS NULL\n"
        "    AND apply_record.APPLY_CONSULT_SENT_AT >= :start_time\n"
        "    AND apply_record.APPLY_CONSULT_SENT_AT < :end_time\n"
        "  GROUP BY apply_record.INP_CONSULT_APPLY_ID,\n"
        "           apply_record.ADMISSION_NUMBER, apply_record.DEPT_ID,\n"
        "           apply_record.APPLY_CONSULT_SENT_AT\n"
        ") AS base\n"
        "ORDER BY base.request_time, base.consult_id"
    )
    return DetailQuery(sql=sql, params=dict(context.params), columns=columns)


def _build_inpatient_transfer_sqlserver_query(
    context: RunContext, row_limit: int
) -> DetailQuery:
    required_params = {
        "hospital_soid",
        "excluded_inpatient_business_code",
        "transfer_department_code",
        "transfer_ward_code",
        "icu_org_ids_csv",
        "transfer_minutes_threshold",
        "start_time",
        "end_time",
    }
    missing = sorted(required_params - set(context.params))
    if missing:
        raise DetailQueryError("入院转科明细缺少参数：" + "、".join(missing))
    effective_mapping = apply_execution_field_roles(
        context.field_mapping, context.execution_context
    )
    admit_time_column = mapped_profile_column(
        effective_mapping,
        "admit_time",
        expected_table="INPATIENT_ENCOUNTER",
        alias="encounter",
    )
    period_time_column = mapped_profile_column(
        effective_mapping,
        "period_time",
        expected_table="INPATIENT_ENCOUNTER",
        alias="encounter",
    )
    definition = parse_calculation_definition(context.calculation_definition)
    columns = [
        DetailColumn(
            field=item.field,
            label=str(
                (effective_mapping.get("field_labels") or {}).get(item.field)
                or item.label
            ),
            sensitivity=item.sensitivity,
        )
        for item in definition.detail_fields
    ]
    supported = {
        "admission_id",
        "admit_time",
        "transfer_time",
        "from_dept_id",
        "from_ward_id",
        "to_dept_id",
        "to_ward_id",
        "transfer_minutes",
    }
    unsupported = [item.field for item in columns if item.field not in supported]
    if unsupported:
        raise DetailQueryError(
            "入院转科明细包含未支持字段：" + "、".join(unsupported)
        )
    expressions = {
        "admission_id": "base.admission_id",
        "admit_time": "base.admit_time",
        "transfer_time": "base.transfer_time",
        "from_dept_id": "base.from_dept_id",
        "from_ward_id": "base.from_ward_id",
        "to_dept_id": "base.to_dept_id",
        "to_ward_id": "base.to_ward_id",
        "transfer_minutes": (
            "DATEDIFF(MINUTE, base.admit_time, base.transfer_time)"
        ),
    }
    selected = [
        f"  {expressions[item.field]} AS [{_identifier(item.field)}]"
        for item in columns
    ]
    selected.extend(
        [
            "  CASE WHEN DATEDIFF(MINUTE, base.admit_time, base.transfer_time) "
            "BETWEEN 0 AND :transfer_minutes_threshold THEN 1 ELSE 0 END "
            "AS [__meets_numerator]",
            "  1 AS [__evidence_row_count]",
        ]
    )
    sql = (
        "WITH eligible_encounter AS (\n"
        "  SELECT encounter.ENCOUNTER_ID AS admission_id,\n"
        f"         {admit_time_column} AS admit_time\n"
        "  FROM WINDBA.INPATIENT_ENCOUNTER AS encounter\n"
        "  WHERE encounter.HOSPITAL_SOID = :hospital_soid\n"
        "    AND encounter.IS_DEL = 0\n"
        "    AND encounter.INPAT_ENC_BIZ_TYPE_CODE "
        "<> :excluded_inpatient_business_code\n"
        f"    AND {period_time_column} >= :start_time\n"
        f"    AND {period_time_column} < :end_time\n"
        "),\n"
        "transfer_candidate AS (\n"
        "  SELECT transfer.INPAT_TRANSFER_ID AS transfer_id,\n"
        "         transfer.ENCOUNTER_ID AS admission_id,\n"
        "         transfer.INPAT_TRANSFER_AT AS transfer_time,\n"
        "         transfer.ORIGIN_DEPT_ID AS from_dept_id,\n"
        "         transfer.ORIGIN_WARD_ID AS from_ward_id,\n"
        "         transfer.DESTINATION_DEPT_ID AS to_dept_id,\n"
        "         transfer.DESTINATION_WARD_ID AS to_ward_id\n"
        "  FROM WINDBA.INPAT_TRANSFER AS transfer\n"
        "  WHERE transfer.HOSPITAL_SOID = :hospital_soid\n"
        "    AND transfer.IS_DEL = 0\n"
        "    AND transfer.INPAT_TRANSFER_TYPE_CODE = :transfer_department_code\n"
        "  UNION ALL\n"
        "  SELECT transfer.INPAT_TRANSFER_ID AS transfer_id,\n"
        "         transfer.ENCOUNTER_ID AS admission_id,\n"
        "         transfer.INPAT_TRANSFER_AT AS transfer_time,\n"
        "         transfer.ORIGIN_DEPT_ID AS from_dept_id,\n"
        "         transfer.ORIGIN_WARD_ID AS from_ward_id,\n"
        "         transfer.DESTINATION_DEPT_ID AS to_dept_id,\n"
        "         transfer.DESTINATION_WARD_ID AS to_ward_id\n"
        "  FROM WINDBA.INPAT_TRANSFER AS transfer\n"
        "  WHERE transfer.HOSPITAL_SOID = :hospital_soid\n"
        "    AND transfer.IS_DEL = 0\n"
        "    AND transfer.INPAT_TRANSFER_TYPE_CODE = :transfer_ward_code\n"
        "    AND transfer.ORIGIN_DEPT_ID <> transfer.DESTINATION_DEPT_ID\n"
        "),\n"
        "valid_transfer AS (\n"
        "  SELECT candidate.*,\n"
        "         ROW_NUMBER() OVER (\n"
        "           PARTITION BY candidate.admission_id\n"
        "           ORDER BY candidate.transfer_time, candidate.transfer_id\n"
        "         ) AS event_order\n"
        "  FROM transfer_candidate AS candidate\n"
        "  WHERE (\n"
        "    CASE WHEN CHARINDEX(\n"
        "      ',' + CONVERT(varchar(30), candidate.from_dept_id) + ',',\n"
        "      ',' + :icu_org_ids_csv + ','\n"
        "    ) > 0 THEN 1 ELSE 0 END\n"
        "    + CASE WHEN CHARINDEX(\n"
        "      ',' + CONVERT(varchar(30), candidate.from_ward_id) + ',',\n"
        "      ',' + :icu_org_ids_csv + ','\n"
        "    ) > 0 THEN 1 ELSE 0 END\n"
        "    + CASE WHEN CHARINDEX(\n"
        "      ',' + CONVERT(varchar(30), candidate.to_dept_id) + ',',\n"
        "      ',' + :icu_org_ids_csv + ','\n"
        "    ) > 0 THEN 1 ELSE 0 END\n"
        "    + CASE WHEN CHARINDEX(\n"
        "      ',' + CONVERT(varchar(30), candidate.to_ward_id) + ',',\n"
        "      ',' + :icu_org_ids_csv + ','\n"
        "    ) > 0 THEN 1 ELSE 0 END\n"
        "  ) = 0\n"
        "),\n"
        "base AS (\n"
        "  SELECT encounter.admission_id, encounter.admit_time,\n"
        "         transfer.transfer_time, transfer.from_dept_id,\n"
        "         transfer.from_ward_id, transfer.to_dept_id,\n"
        "         transfer.to_ward_id\n"
        "  FROM eligible_encounter AS encounter\n"
        "  LEFT JOIN valid_transfer AS transfer\n"
        "    ON transfer.admission_id = encounter.admission_id\n"
        "   AND transfer.event_order = 1\n"
        ")\n"
        f"SELECT TOP {row_limit}\n"
        + ",\n".join(selected)
        + "\nFROM base\nORDER BY base.admit_time, base.admission_id"
    )
    return DetailQuery(sql=sql, params=dict(context.params), columns=columns)
