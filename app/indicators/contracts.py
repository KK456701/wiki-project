from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


MetricType = Literal["ratio", "count"]
ConditionOperator = Literal[
    "eq",
    "ne",
    "gt",
    "gte",
    "lt",
    "lte",
    "in",
    "not_in",
    "is_null",
    "not_null",
]


class IndicatorCondition(BaseModel):
    field: str
    operator: ConditionOperator
    value: Any = None


class IndicatorSQLPlan(BaseModel):
    main_table: str
    metric_type: MetricType
    subject_field: str
    time_field: str
    hospital_field: str = "hospital_id"
    numerator_conditions: list[IndicatorCondition] = Field(default_factory=list)
    denominator_conditions: list[IndicatorCondition] = Field(default_factory=list)


class IndicatorDraftSpec(BaseModel):
    hospital_id: str
    base_index_code: str | None = None
    proposed_index_code: str
    index_name: str
    index_type: str = "本院新增指标"
    index_desc: str
    stat_cycle: str = "month"
    numerator_rule: str
    denominator_rule: str
    filter_rule: str = ""
    exclude_rule: str = ""
    metric_type: MetricType
    metadata_requirements: list[str] = Field(default_factory=list)
    sql_plan: IndicatorSQLPlan | None = None
    generated_by: str = "llm"


class IndicatorDraft(BaseModel):
    draft_id: str
    hospital_id: str
    base_index_code: str | None = None
    proposed_index_code: str
    index_name: str
    index_type: str
    index_desc: str
    stat_cycle: str
    numerator_rule: str
    denominator_rule: str
    filter_rule: str = ""
    exclude_rule: str = ""
    metric_type: MetricType
    metadata_requirements: list[str] = Field(default_factory=list)
    field_mapping: dict[str, Any] = Field(default_factory=dict)
    sql_plan: dict[str, Any] = Field(default_factory=dict)
    current_sql: str | None = None
    sql_params: dict[str, Any] = Field(default_factory=dict)
    sql_id: str | None = None
    trial_result: dict[str, Any] = Field(default_factory=dict)
    trial_draft_version: int | None = None
    status: str
    current_version: int
    formal_index_code: str | None = None
    generated_by: str = ""
    created_by: str
    updated_by: str
    created_at: str
    updated_at: str
