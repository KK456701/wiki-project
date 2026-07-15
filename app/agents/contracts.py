"""五类 Agent 之间传递的结构化数据契约。"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.terminology.contracts import TermNormalizationResult


IntentType = Literal[
    "chat",
    "query",
    "feedback",
    "generate_sql",
    "trial_run",
    "diagnose",
    "metadata_sync",
    "create_indicator",
]


class AgentContract(BaseModel):
    """提供 Pydantic 校验，并暂时兼容原有字典访问方式。"""

    model_config = ConfigDict(
        extra="allow", validate_assignment=True, populate_by_name=True
    )

    def __getitem__(self, key: str) -> Any:
        if key in type(self).model_fields or key in (self.model_extra or {}):
            return getattr(self, key)
        raise KeyError(key)

    def __setitem__(self, key: str, value: Any) -> None:
        setattr(self, key, value)

    def get(self, key: str, default: Any = None) -> Any:
        try:
            return self[key]
        except KeyError:
            return default

    def setdefault(self, key: str, default: Any = None) -> Any:
        current = self.get(key)
        if current is None:
            self[key] = default
            return default
        return current

    def keys(self):
        return self.model_dump().keys()

    def values(self):
        return self.model_dump().values()

    def items(self):
        return self.model_dump().items()


class CustomFilter(AgentContract):
    field: str = ""
    operator: str = ""
    value: Any = None


class IntentResult(AgentContract):
    intent: IntentType
    retrieval_query: str = ""
    rewritten_query: str = ""
    indicator_name: str = ""
    context_source: str | None = None
    custom_filters: list[CustomFilter] = Field(default_factory=list)
    context_updates: list[dict[str, Any]] = Field(default_factory=list)
    clear_working_caliber: bool = False


class RuleMatch(AgentContract):
    rule_id: str = ""
    rule_name: str = ""
    category: str = ""
    content: str = ""
    type: str = ""


class RuleSearchResult(AgentContract):
    query: str
    resolved_rule_id: str | None = None
    matches: list[RuleMatch] = Field(default_factory=list)
    results: list[RuleMatch] = Field(default_factory=list)
    rule_source: str | None = None
    warnings: list[str] = Field(default_factory=list)
    context_source: str | None = None

    @model_validator(mode="before")
    @classmethod
    def normalize_match_collection(cls, value: Any) -> Any:
        if not isinstance(value, dict):
            return value
        normalized = dict(value)
        if not normalized.get("matches") and isinstance(normalized.get("results"), list):
            normalized["matches"] = normalized["results"]
        if not normalized.get("results") and isinstance(normalized.get("matches"), list):
            normalized["results"] = normalized["matches"]
        return normalized

    @property
    def match_count(self) -> int:
        return len(self.matches)


class EffectiveRule(AgentContract):
    rule_id: str
    rule_name: str = ""
    effective_level: str = ""
    definition: str = ""
    formula: str = ""
    standard_sql: str = ""
    effective_params: dict[str, Any] = Field(default_factory=dict)
    calculation_definition: dict[str, Any] = Field(default_factory=dict)
    national_calculation_definition: dict[str, Any] = Field(default_factory=dict)
    warnings: list[str] = Field(default_factory=list)


class CaliberComparisonContext(AgentContract):
    rule_id: str
    hospital_id: str
    applicable: bool = False
    reason: str = ""
    national_sql_template: str = ""
    national_params: dict[str, Any] = Field(default_factory=dict)
    national_version: str | None = None
    effective_sql_template: str = ""
    effective_params: dict[str, Any] = Field(default_factory=dict)
    hospital_version: int | None = None
    overridden_fields: list[str] = Field(default_factory=list)


class FieldMapping(AgentContract):
    rule_id: str = ""
    hospital_id: str = ""
    db_name: str = ""
    main_table: str = ""
    dialect: str = "mysql"
    fields: dict[str, str] = Field(default_factory=dict)
    filters: dict[str, Any] = Field(default_factory=dict)
    status: str = "missing"
    mapping_items: list[dict[str, Any]] = Field(
        default_factory=list, alias="items"
    )
    relations: list[dict[str, Any]] = Field(default_factory=list)
    query_profile: str = ""


class MetadataPrecheckResult(AgentContract):
    ok: bool
    error: str | None = None
    required_business_fields: list[str] = Field(default_factory=list)
    missing_mappings: list[str] = Field(default_factory=list)
    unconfirmed_mappings: list[str] = Field(default_factory=list)
    missing_columns: list[str] = Field(default_factory=list)
    type_mismatches: list[str] = Field(default_factory=list)
    missing_relations: list[str] = Field(default_factory=list)
    issues: list[str] = Field(default_factory=list)
    dialect: str = "mysql"
    db_name: str = ""
    main_table: str = ""
    field_mapping: dict[str, str] = Field(default_factory=dict)
    filters: dict[str, Any] = Field(default_factory=dict)


class MetadataSyncResult(AgentContract):
    batch_id: str
    status: str = "success"


class SQLValidationResult(AgentContract):
    ok: bool
    message: str = ""
    error: str = ""


class TrialRunResult(AgentContract):
    run_id: str = ""
    status: str = ""
    result_value: float | int | None = None
    duration_ms: int = 0
    error_message: str | None = None


class SQLGenerationResult(AgentContract):
    status: str = "success"
    sql_id: str | None = None
    sql_text: str | None = None
    sql_status: str | None = None
    validation: SQLValidationResult | None = None
    precheck: MetadataPrecheckResult | None = None
    trial_run: TrialRunResult | None = None
    dialect: str = "mysql"
    params: dict[str, Any] = Field(default_factory=dict)
    calculation_definition: dict[str, Any] = Field(default_factory=dict)
    field_mapping: dict[str, Any] = Field(default_factory=dict)
    lineage: dict[str, Any] = Field(default_factory=dict)
    execution_context: dict[str, Any] = Field(default_factory=dict)
    message: str = ""
    node_timings: dict[str, int] = Field(default_factory=dict, alias="_node_timings")


class DiagnosisLayer(AgentContract):
    layer: int
    layer_name: str = ""
    ok: bool
    checks: list[dict[str, Any]] = Field(default_factory=list)

    @model_validator(mode="before")
    @classmethod
    def fill_standard_layer_name(cls, value: Any) -> Any:
        if not isinstance(value, dict) or value.get("layer_name"):
            return value
        normalized = dict(value)
        normalized["layer_name"] = {
            1: "结构适配校验",
            2: "口径规则校验",
            3: "数据质量校验",
        }.get(normalized.get("layer"), "")
        return normalized


class DiagnosisStatPeriod(AgentContract):
    start: str | None = None
    end: str | None = None


class PastedDiagnosisEvidence(AgentContract):
    raw_text: str
    question: str = ""
    rule_id: str | None = None
    sql_text: str = ""
    declared_params: dict[str, Any] = Field(default_factory=dict)
    claimed_result: dict[str, Any] = Field(default_factory=dict)
    stat_period: DiagnosisStatPeriod = Field(default_factory=DiagnosisStatPeriod)
    parse_warnings: list[str] = Field(default_factory=list)
    model_parse_status: str = "not_used"


class DiagnosisResult(AgentContract):
    ok: bool = True
    diagnose_status: str = "success"
    report_id: str | None = None
    layers: list[DiagnosisLayer] = Field(default_factory=list)
    summary: str = ""
    user_summary: str = ""
    evidence: PastedDiagnosisEvidence | None = None
    findings: list[dict[str, Any]] = Field(default_factory=list)
    execution_results: dict[str, Any] = Field(default_factory=dict)
    trace_events: list[dict[str, Any]] = Field(default_factory=list)


class PreparedRequest(AgentContract):
    query: str
    hospital_id: str | None
    intent: IntentType
    retrieval_query: str = ""
    rule_id: str | None = None
    search: RuleSearchResult | None = None
    effective_rule: EffectiveRule | None = None
    field_mapping: FieldMapping | None = None
    term_normalization: TermNormalizationResult | None = None
    term_normalization_error: str | None = None
    custom_filters: list[CustomFilter] = Field(default_factory=list)
    errors: list[str] = Field(default_factory=list)
