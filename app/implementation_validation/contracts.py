from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class ValidationContract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ValidationStageStatus(str, Enum):
    PASSED = "passed"
    WARNING = "warning"
    FAILED = "failed"
    SKIPPED = "skipped"


class ValidationStageResult(ValidationContract):
    stage_id: str
    stage_name: str
    status: ValidationStageStatus
    summary: str
    finding_codes: list[str] = Field(default_factory=list)
    safe_details: dict[str, Any] = Field(default_factory=dict)
    duration_ms: int = Field(default=0, ge=0)


class ImplementationValidationReport(ValidationContract):
    schema_version: str = "implementation-validation-report-v1"
    report_id: str
    hospital_id: str
    rule_id: str
    rule_name: str = ""
    stat_start: str
    stat_end: str
    overall_status: ValidationStageStatus
    stages: list[ValidationStageResult]
    sql_id: str | None = None
    run_id: str | None = None
    result_value: float | int | None = None
    numerator_count: float | int | None = None
    denominator_count: float | int | None = None
    file_key: str | None = None
    created_at: datetime
