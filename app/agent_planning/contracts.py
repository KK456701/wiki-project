from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field


class PlanningContract(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class PlanIntent(str, Enum):
    GENERAL_CHAT = "general_chat"
    RULE_EXPLANATION = "rule_explanation"
    INDICATOR_SQL_PREPARE = "indicator_sql_prepare"
    INDICATOR_TRIAL_RUN = "indicator_trial_run"
    INDICATOR_DIAGNOSIS = "indicator_diagnosis"
    RULE_CHANGE_PREVIEW = "rule_change_preview"
    UPLOAD_ANALYSIS = "upload_analysis"
    IMPLEMENTATION_VALIDATION = "implementation_validation"
    UNKNOWN = "unknown"


class RequestedOutput(str, Enum):
    DEFINITION = "definition"
    FORMULA = "formula"
    IMPLEMENTATION_STATUS = "implementation_status"
    PREPARED_SQL_HANDLE = "prepared_sql_handle"
    TRIAL_RESULT = "trial_result"
    DIAGNOSIS = "diagnosis"
    CHANGE_PREVIEW = "change_preview"
    FILE_ANALYSIS = "file_analysis"
    IMPLEMENTATION_VALIDATION_REPORT = "implementation_validation_report"
    EXPLANATION = "explanation"


class TargetIndicator(PlanningContract):
    raw_name: str = ""
    rule_id: str | None = None


class TimeExpression(PlanningContract):
    raw_text: str = ""
    start_time: str | None = None
    end_time: str | None = None


class SemanticAmbiguity(PlanningContract):
    field: str
    description: str


class RequestPlan(PlanningContract):
    schema_version: str = "request-plan-v1"
    intent: PlanIntent
    goal: str = Field(min_length=1, max_length=500)
    target_indicator: TargetIndicator = Field(default_factory=TargetIndicator)
    time_expression: TimeExpression = Field(default_factory=TimeExpression)
    requested_outputs: list[RequestedOutput] = Field(default_factory=list)
    constraints: list[str] = Field(default_factory=list)
    semantic_ambiguities: list[SemanticAmbiguity] = Field(default_factory=list)


class PlanCapability(str, Enum):
    RESOLVE_INDICATOR = "resolve_indicator"
    RESOLVE_EFFECTIVE_RULE = "resolve_effective_rule"
    RESOLVE_TIME_RANGE = "resolve_time_range"
    INSPECT_IMPLEMENTATION = "inspect_implementation"
    PREPARE_VERIFIED_SQL = "prepare_verified_sql"
    EXECUTE_TRIAL_RUN = "execute_trial_run"
    DIAGNOSE_INDICATOR = "diagnose_indicator"
    PREVIEW_RULE_CHANGE = "preview_rule_change"
    ANALYZE_UPLOADED_FILE = "analyze_uploaded_file"
    VALIDATE_IMPLEMENTATION = "validate_implementation"
    COMPOSE_ANSWER = "compose_answer"


class PlanNode(PlanningContract):
    capability: PlanCapability
    capability_version: str = "1.0"
    requires: set[str] = Field(default_factory=set)
    produces: set[str] = Field(default_factory=set)
    tool_name: str | None = None
    policy_action: str = "agent.tool.execute"
    verifier: str = "fact_present"
    retry_policy: str = "none"
    answer_mode: str = "evidence_only"


class CompiledPlan(PlanningContract):
    plan_id: str
    schema_version: str = "compiled-plan-ir-v1"
    request_plan_version: str = "request-plan-v1"
    capability_registry_version: str = "capability-registry-v1"
    prompt_version: str = "agent-prompts-v1"
    model_adapter_version: str = "agent-model-adapter-v1"
    verifier_version: str = "plan-verifier-v1"
    intent: PlanIntent
    goal: str
    nodes: list[PlanNode]
    required_facts: set[str] = Field(default_factory=set)
    requested_outputs: set[RequestedOutput] = Field(default_factory=set)
