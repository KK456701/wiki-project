from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import json
import uuid
from typing import TYPE_CHECKING, Any

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext

if TYPE_CHECKING:
    from app.agent_tools.contracts import ToolResult

from .models import EvidenceEnvelope, EvidenceVerification
from .store import EvidenceStore


_SAFE_DATA_KEYS = {
    "rule_id", "rule_name", "definition", "formula", "effective_level",
    "national_version", "hospital_version", "version", "mapping_status",
    "sql_status", "sql_id", "run_id", "result_id", "db_source_id",
    "stat_start", "stat_end", "stat_start_time", "stat_end_time",
    "numerator_count", "denominator_count", "result_value", "sample_count",
    "file_key", "sheet_count", "row_count", "columns", "summary",
    "row_level_comparison_available", "cause_analysis_available",
}


class EvidenceAccessError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def _fingerprint(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


class EvidenceLedger:
    def __init__(self, store: EvidenceStore, *, ttl_days: int = 30) -> None:
        self.store = store
        self.ttl_days = max(1, ttl_days)

    def record_tool_result(
        self,
        *,
        tool_name: str,
        arguments: dict[str, Any],
        result: ToolResult,
        context: AgentRuntimeContext,
        state: AgentRunState,
    ) -> list[str]:
        if not result.ok:
            return []
        now = datetime.now(timezone.utc)
        data = result.data if isinstance(result.data, dict) else {}
        safe_payload = {key: data[key] for key in _SAFE_DATA_KEYS if key in data}
        result_fingerprint = _fingerprint(result.model_dump(mode="json"))
        input_fingerprint = _fingerprint({"tool": tool_name, "arguments": arguments})
        ids: list[str] = []
        subtask_id = state.subtask_id or context.request_id
        for source in result.evidence:
            for fact_type in source.fact_types or ["tool_result"]:
                evidence_id = "EVD_" + uuid.uuid4().hex[:20]
                object_id = source.source_id or str(data.get("sql_id") or data.get("run_id") or "") or None
                sensitive_reference = bool(
                    object_id and str(object_id).startswith(("SQL_", "RUN_", "SNAP_"))
                )
                envelope = EvidenceEnvelope(
                    evidence_id=evidence_id,
                    trace_id=context.trace_id,
                    subtask_id=subtask_id,
                    fact_type=str(fact_type),
                    hospital_id=context.hospital_id,
                    rule_id=str(data.get("rule_id") or state.current_rule_id or "") or None,
                    rule_version=str(source.version or data.get("hospital_version") or data.get("version") or "") or None,
                    stat_start=str(data.get("stat_start") or data.get("stat_start_time") or "") or None,
                    stat_end=str(data.get("stat_end") or data.get("stat_end_time") or "") or None,
                    source_tool=tool_name,
                    source_object_id=object_id,
                    input_fingerprint=input_fingerprint,
                    result_fingerprint=result_fingerprint,
                    confidentiality="sensitive_reference" if sensitive_reference else "internal",
                    created_at=now,
                    expires_at=now + timedelta(days=self.ttl_days),
                    payload_ref=object_id if sensitive_reference else None,
                    safe_payload=safe_payload,
                )
                self.store.save_evidence(envelope)
                ids.append(evidence_id)
        state.evidence_ids.extend(item for item in ids if item not in state.evidence_ids)
        return ids

    def validate_access(
        self,
        evidence_id: str,
        context: AgentRuntimeContext,
        subtask_id: str,
    ) -> EvidenceEnvelope:
        value = self.store.load_evidence(evidence_id)
        if value is None:
            raise EvidenceAccessError("EVIDENCE_NOT_FOUND", "证据对象不存在。")
        if value.hospital_id != context.hospital_id:
            raise EvidenceAccessError("EVIDENCE_HOSPITAL_MISMATCH", "证据不属于当前医院。")
        if value.subtask_id != subtask_id:
            raise EvidenceAccessError("EVIDENCE_SUBTASK_MISMATCH", "证据不属于当前子任务。")
        if value.expires_at and value.expires_at <= datetime.now(timezone.utc):
            raise EvidenceAccessError("EVIDENCE_EXPIRED", "证据对象已过期。")
        return value

    def verify_many(
        self,
        evidence_ids: list[str],
        *,
        context: AgentRuntimeContext,
        subtask_id: str,
        verifier_version: str,
        expected_rule_id: str | None = None,
        expected_stat_start: str | None = None,
        expected_stat_end: str | None = None,
        expected_sql_id: str | None = None,
        legacy_tool_results: list[dict[str, Any]] | None = None,
    ) -> list[str]:
        verified: list[str] = []
        for evidence_id in evidence_ids:
            value = self.validate_access(evidence_id, context, subtask_id)
            if expected_rule_id and value.rule_id and value.rule_id != expected_rule_id:
                raise EvidenceAccessError(
                    "EVIDENCE_RULE_MISMATCH",
                    "证据规则与当前指标不一致。",
                )
            if expected_stat_start and value.stat_start and value.stat_start != expected_stat_start:
                raise EvidenceAccessError(
                    "EVIDENCE_PERIOD_MISMATCH",
                    "证据统计开始时间与当前请求不一致。",
                )
            if expected_stat_end and value.stat_end and value.stat_end != expected_stat_end:
                raise EvidenceAccessError(
                    "EVIDENCE_PERIOD_MISMATCH",
                    "证据统计结束时间与当前请求不一致。",
                )
            evidence_sql_id = str(value.safe_payload.get("sql_id") or "")
            if expected_sql_id and evidence_sql_id and evidence_sql_id != expected_sql_id:
                raise EvidenceAccessError(
                    "EVIDENCE_SQL_MISMATCH",
                    "证据 SQL 对象与当前已校验 SQL 不一致。",
                )
            if legacy_tool_results is not None:
                matching_result = next(
                    (
                        item
                        for item in reversed(legacy_tool_results)
                        if evidence_id in (item.get("evidence_ids") or [])
                    ),
                    None,
                )
                if matching_result is None:
                    raise EvidenceAccessError(
                        "EVIDENCE_LEGACY_RESULT_MISSING",
                        "证据与本轮工具结果无法对应。",
                    )
                normalized_result = dict(matching_result)
                normalized_result["evidence_ids"] = []
                if _fingerprint(normalized_result) != value.result_fingerprint:
                    raise EvidenceAccessError(
                        "EVIDENCE_PAYLOAD_MISMATCH",
                        "Evidence 与本轮工具结果不一致。",
                    )
            if not self.store.is_verified(evidence_id):
                self.store.save_verification(EvidenceVerification(
                    verification_id="EVV_" + uuid.uuid4().hex[:20],
                    evidence_id=evidence_id,
                    trace_id=context.trace_id,
                    subtask_id=subtask_id,
                    hospital_id=context.hospital_id,
                    verifier_version=verifier_version,
                    status="verified",
                    code="PLAN_VERIFIED",
                ))
            verified.append(evidence_id)
        return verified
