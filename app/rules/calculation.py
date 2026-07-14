"""结构化指标计算定义、校验和医院补丁合成。"""

from __future__ import annotations

import copy
import json
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ConditionDefinition(_StrictModel):
    id: str
    field: str
    operator: Literal[
        "equals",
        "not_equals",
        "half_open_range",
        "inclusive_range",
        "is_not_null",
    ]
    parameter: str | None = None
    parameters: list[str] = Field(default_factory=list)
    values: list[Any] = Field(default_factory=list)


class DerivedFieldDefinition(_StrictModel):
    name: str
    operation: Literal["timestamp_diff_minutes"]
    source_fields: list[str]


class AggregateDefinition(_StrictModel):
    method: Literal["count_rows", "count_distinct"]
    field: str | None = None


class ScopeDefinition(_StrictModel):
    conditions: list[ConditionDefinition] = Field(default_factory=list)


class CalculationBranchDefinition(_StrictModel):
    name: str
    inherits: Literal["scope", "denominator"]
    conditions: list[ConditionDefinition] = Field(default_factory=list)
    aggregate: AggregateDefinition


class ResultDefinition(_StrictModel):
    operation: Literal["ratio_percent"]
    numerator: Literal["numerator"]
    denominator: Literal["denominator"]


class DetailFieldDefinition(_StrictModel):
    field: str
    label: str
    sensitivity: Literal["none", "patient_id", "name", "phone", "id_card"] = "none"


class CalculationDefinition(_StrictModel):
    schema_version: Literal[1]
    scope: ScopeDefinition
    derived_fields: dict[str, DerivedFieldDefinition] = Field(default_factory=dict)
    denominator: CalculationBranchDefinition
    numerator: CalculationBranchDefinition
    result: ResultDefinition
    detail_fields: list[DetailFieldDefinition] = Field(default_factory=list)


def parse_calculation_definition(payload: Any) -> CalculationDefinition:
    """将 YAML/MySQL JSON 统一解析为计算定义。"""

    if isinstance(payload, CalculationDefinition):
        return payload
    if isinstance(payload, str):
        payload = json.loads(payload)
    if not isinstance(payload, dict):
        raise ValueError("计算定义必须是对象")
    return CalculationDefinition.model_validate(payload)


def collect_business_dependencies(
    definition: CalculationDefinition,
) -> set[str]:
    """收集计算定义最终依赖的原始业务字段。"""

    dependencies: set[str] = set()

    def collect(field_name: str, visiting: set[str]) -> None:
        derived = definition.derived_fields.get(field_name)
        if derived is None:
            dependencies.add(field_name)
            return
        if field_name in visiting:
            return
        next_visiting = {*visiting, field_name}
        for source_field in derived.source_fields:
            collect(source_field, next_visiting)

    conditions = [
        *definition.scope.conditions,
        *definition.denominator.conditions,
        *definition.numerator.conditions,
    ]
    for condition in conditions:
        collect(condition.field, set())
    for branch in (definition.denominator, definition.numerator):
        if branch.aggregate.field:
            collect(branch.aggregate.field, set())
    return dependencies


def validate_calculation_definition(
    definition: CalculationDefinition,
    business_fields: dict[str, Any],
    params: dict[str, Any],
) -> list[str]:
    """返回面向实施人员的结构错误，空列表表示定义可用。"""

    errors: list[str] = []
    business_names = set(business_fields)
    derived_names = set(definition.derived_fields)
    all_names = business_names | derived_names

    if definition.denominator.inherits != "scope":
        errors.append("分母必须继承统计范围 scope")
    if definition.numerator.inherits != "denominator":
        errors.append("分子必须继承分母 denominator")

    seen_condition_ids: set[str] = set()
    conditions = [
        *definition.scope.conditions,
        *definition.denominator.conditions,
        *definition.numerator.conditions,
    ]
    for condition in conditions:
        if condition.id in seen_condition_ids:
            errors.append(f"条件编号重复 {condition.id}")
        seen_condition_ids.add(condition.id)
        if condition.field not in all_names:
            errors.append(f"条件 {condition.id} 引用了未定义字段 {condition.field}")
        parameter_names = [
            *([condition.parameter] if condition.parameter else []),
            *condition.parameters,
        ]
        for parameter_name in parameter_names:
            if parameter_name not in params:
                errors.append(
                    f"条件 {condition.id} 引用了未配置参数 {parameter_name}"
                )

    for branch_name, branch in (
        ("分母", definition.denominator),
        ("分子", definition.numerator),
    ):
        aggregate_field = branch.aggregate.field
        if branch.aggregate.method == "count_distinct" and not aggregate_field:
            errors.append(f"{branch_name}去重计数缺少去重字段")
        if aggregate_field and aggregate_field not in all_names:
            errors.append(f"{branch_name}聚合引用了未定义字段 {aggregate_field}")

    for derived_name, derived in definition.derived_fields.items():
        if len(derived.source_fields) < 2:
            errors.append(f"派生字段 {derived_name} 至少需要两个来源字段")
        for source_field in derived.source_fields:
            if source_field not in all_names:
                errors.append(
                    f"派生字段 {derived_name} 引用了未定义字段 {source_field}"
                )

    seen_detail_fields: set[str] = set()
    for detail_field in definition.detail_fields:
        if detail_field.field in seen_detail_fields:
            errors.append(f"明细字段重复 {detail_field.field}")
        seen_detail_fields.add(detail_field.field)
        if detail_field.field not in all_names:
            errors.append(
                f"明细字段 {detail_field.field} 未在业务字段或派生字段中定义"
            )

    cycle_keys: set[tuple[str, ...]] = set()

    def find_cycle(field_name: str, path: tuple[str, ...]) -> None:
        if field_name in path:
            cycle = (*path[path.index(field_name) :], field_name)
            normalized = tuple(sorted(set(cycle)))
            if normalized not in cycle_keys:
                cycle_keys.add(normalized)
                errors.append("派生字段存在循环依赖：" + " -> ".join(cycle))
            return
        derived = definition.derived_fields.get(field_name)
        if derived is None:
            return
        for source_field in derived.source_fields:
            find_cycle(source_field, (*path, field_name))

    for derived_name in definition.derived_fields:
        find_cycle(derived_name, ())

    return list(dict.fromkeys(errors))


def merge_calculation_patch(
    base: dict[str, Any], patch: dict[str, Any] | None
) -> dict[str, Any]:
    """合成已审批医院补丁，不修改标准定义对象。"""

    merged = copy.deepcopy(base)
    if not patch:
        return merged
    if "schema_version" in patch:
        raise ValueError("医院计算补丁不允许修改 schema_version")

    allowed_top_level = {
        "scope",
        "derived_fields",
        "denominator",
        "numerator",
        "result",
        "detail_fields",
    }
    unknown = set(patch) - allowed_top_level
    if unknown:
        raise ValueError("医院计算补丁包含未知节点：" + "、".join(sorted(unknown)))

    def merge_node(target: dict[str, Any], changes: dict[str, Any]) -> None:
        for key, value in changes.items():
            if value is None:
                raise ValueError(f"医院计算补丁不允许删除计算定义节点 {key}")
            current = target.get(key)
            if isinstance(current, dict) and isinstance(value, dict):
                merge_node(current, value)
            else:
                target[key] = copy.deepcopy(value)

    merge_node(merged, patch)
    return merged
