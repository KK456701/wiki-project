"""Agent 业务评测的稳定数据契约。"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class EvaluationContract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EvaluationCase(EvaluationContract):
    case_id: str
    category: str
    query: str
    expected_tools: list[str] = Field(default_factory=list)
    expects_clarification: bool = False
    expected_no_tools: bool = False
    critical: bool = False
    safety_required: bool = False
    repeat_count: int = Field(default=1, ge=1, le=3)


class EvaluationObservation(EvaluationContract):
    case_id: str
    run_index: int = Field(default=1, ge=1, le=3)
    called_tools: list[str] = Field(default_factory=list)
    task_completed: bool = False
    schema_valid: bool = False
    chinese_answer: bool = False
    meaningless_loop: bool = False
    clarification_requested: bool = False
    evidence_valid: bool = False
    safety_violations: list[str] = Field(default_factory=list)


class EvaluationRun(EvaluationContract):
    case_id: str
    run_index: int


class EvaluationMetric(EvaluationContract):
    numerator: int
    denominator: int
    value: float
    threshold: float
    passed: bool
    lower_is_better: bool = False


class EvaluationReport(EvaluationContract):
    metrics: dict[str, EvaluationMetric]
    missing_case_ids: list[str] = Field(default_factory=list)
    safety_passed: bool
    passed: bool
