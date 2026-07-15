"""把会话临时口径解析为本次执行实际使用的字段映射。"""

from __future__ import annotations

import copy
import re
from typing import Any


_QUALIFIED_FIELD = re.compile(
    r"^[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*$"
)


def apply_execution_field_roles(
    field_mapping: dict[str, Any],
    execution_context: dict[str, Any] | None,
) -> dict[str, Any]:
    """返回只对本次运行生效的映射，不修改已发布的医院映射。"""

    mapping = copy.deepcopy(field_mapping)
    fields = mapping.setdefault("fields", {})
    if not isinstance(fields, dict):
        raise ValueError("医院字段映射格式无效：fields 必须是对象")

    default_time_field = str(fields.get("admit_time") or "").strip()
    if default_time_field:
        fields.setdefault("baseline_admit_time", default_time_field)
        fields.setdefault("period_time", default_time_field)

    context = execution_context or {}
    overrides = context.get("overrides") or {}
    resolved_fields = context.get("resolved_fields") or {}
    if not isinstance(overrides, dict) or not isinstance(resolved_fields, dict):
        return mapping

    _apply_role(
        fields,
        overrides,
        resolved_fields,
        context_key="period_time_field",
        mapping_key="period_time",
    )
    _apply_role(
        fields,
        overrides,
        resolved_fields,
        context_key="elapsed_time_start",
        mapping_key="admit_time",
    )
    uses_ward_period = overrides.get("period_time_field") == "ward_entry_time"
    uses_ward_elapsed = overrides.get("elapsed_time_start") == "ward_entry_time"
    if uses_ward_period or uses_ward_elapsed:
        labels = mapping.setdefault("field_labels", {})
        if not isinstance(labels, dict):
            raise ValueError("医院字段映射格式无效：field_labels 必须是对象")
        if uses_ward_period:
            labels["period_time"] = "首次入区时间"
        if uses_ward_elapsed:
            labels["admit_time"] = "首次入区时间"
    return mapping


def mapped_profile_column(
    field_mapping: dict[str, Any],
    field_name: str,
    *,
    expected_table: str,
    alias: str,
) -> str:
    """将已确认的 ``表.字段`` 映射转换成固定查询配置的安全别名。"""

    fields = field_mapping.get("fields") or {}
    value = str(fields.get(field_name) or "").strip()
    if _QUALIFIED_FIELD.fullmatch(value) is None:
        raise ValueError(f"医院字段映射格式无效：{field_name}")
    table, column = value.split(".", 1)
    if table != expected_table:
        raise ValueError(
            f"医院字段映射对象不一致：{field_name} 应来自 {expected_table}"
        )
    return f"{alias}.{column}"


def _apply_role(
    fields: dict[str, Any],
    overrides: dict[str, Any],
    resolved_fields: dict[str, Any],
    *,
    context_key: str,
    mapping_key: str,
) -> None:
    if context_key not in overrides:
        return
    resolved = str(resolved_fields.get(context_key) or "").strip()
    if not resolved:
        raise ValueError(f"会话口径缺少已确认字段：{context_key}")
    if _QUALIFIED_FIELD.fullmatch(resolved) is None:
        raise ValueError(f"会话口径字段格式无效：{context_key}")
    fields[mapping_key] = resolved
