from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class EvidenceModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class EvidenceEnvelope(EvidenceModel):
    schema_version: str = "evidence-envelope-v1"
    evidence_id: str
    trace_id: str
    subtask_id: str
    fact_type: str
    hospital_id: str
    rule_id: str | None = None
    rule_version: str | None = None
    stat_start: str | None = None
    stat_end: str | None = None
    source_tool: str
    source_object_id: str | None = None
    input_fingerprint: str
    result_fingerprint: str
    confidentiality: Literal["internal", "restricted", "sensitive_reference"] = "internal"
    created_at: datetime = Field(default_factory=utcnow)
    expires_at: datetime | None = None
    payload_ref: str | None = None
    safe_payload: dict[str, Any] = Field(default_factory=dict)


class EvidenceVerification(EvidenceModel):
    schema_version: str = "evidence-verification-v1"
    verification_id: str
    evidence_id: str
    trace_id: str
    subtask_id: str
    hospital_id: str
    verifier_version: str
    status: Literal["verified", "rejected"]
    code: str
    message: str = ""
    verified_at: datetime = Field(default_factory=utcnow)
