from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine

from app.agent_evidence import (
    EvidenceAccessError,
    EvidenceEnvelope,
    EvidenceLedger,
    EvidenceStore,
    ensure_evidence_schema,
)
from app.agent_runtime.contracts import AgentRuntimeContext


def _context(*, hospital_id: str = "hospital_001") -> AgentRuntimeContext:
    return AgentRuntimeContext(
        user_id="user_001",
        hospital_id=hospital_id,
        session_id="session_001",
        user_role="implementer",
        permissions=frozenset({"indicator_read"}),
        request_id="request_001",
        trace_id="trace_001",
        db_source_id="db_001",
    )


def _ledger(tmp_path) -> EvidenceLedger:
    engine = create_engine(f"sqlite:///{tmp_path / 'evidence.db'}")
    ensure_evidence_schema(engine)
    return EvidenceLedger(
        EvidenceStore(engine, tmp_path / "evidence.jsonl")
    )


def _save_evidence(
    ledger: EvidenceLedger,
    *,
    evidence_id: str = "EVD_001",
    hospital_id: str = "hospital_001",
    subtask_id: str = "request_001",
    expires_at=None,
) -> None:
    ledger.store.save_evidence(EvidenceEnvelope(
        evidence_id=evidence_id,
        trace_id="trace_001",
        subtask_id=subtask_id,
        fact_type="trial_run",
        hospital_id=hospital_id,
        rule_id="RULE_001",
        stat_start="2026-01-01 00:00:00",
        stat_end="2026-04-01 00:00:00",
        source_tool="trial_run_indicator_sql",
        source_object_id="RUN_001",
        input_fingerprint="a" * 64,
        result_fingerprint="b" * 64,
        expires_at=expires_at or datetime.now(timezone.utc) + timedelta(days=1),
        payload_ref="RUN_001",
        confidentiality="sensitive_reference",
        safe_payload={"sql_id": "SQL_001", "run_id": "RUN_001"},
    ))


@pytest.mark.parametrize(
    ("hospital_id", "subtask_id", "code"),
    [
        ("hospital_002", "request_001", "EVIDENCE_HOSPITAL_MISMATCH"),
        ("hospital_001", "request_002", "EVIDENCE_SUBTASK_MISMATCH"),
    ],
)
def test_evidence_rejects_cross_tenant_and_cross_subtask(
    tmp_path, hospital_id, subtask_id, code
):
    ledger = _ledger(tmp_path)
    _save_evidence(ledger)

    with pytest.raises(EvidenceAccessError) as error:
        ledger.validate_access("EVD_001", _context(hospital_id=hospital_id), subtask_id)

    assert error.value.code == code


def test_evidence_rejects_expired_object(tmp_path):
    ledger = _ledger(tmp_path)
    _save_evidence(
        ledger,
        expires_at=datetime.now(timezone.utc) - timedelta(seconds=1),
    )

    with pytest.raises(EvidenceAccessError) as error:
        ledger.validate_access("EVD_001", _context(), "request_001")

    assert error.value.code == "EVIDENCE_EXPIRED"


def test_evidence_verification_checks_rule_period_and_sql_chain(tmp_path):
    ledger = _ledger(tmp_path)
    _save_evidence(ledger)

    verified = ledger.verify_many(
        ["EVD_001"],
        context=_context(),
        subtask_id="request_001",
        verifier_version="test-verifier-v1",
        expected_rule_id="RULE_001",
        expected_stat_start="2026-01-01 00:00:00",
        expected_stat_end="2026-04-01 00:00:00",
        expected_sql_id="SQL_001",
    )

    assert verified == ["EVD_001"]
    assert ledger.store.is_verified("EVD_001") is True

    with pytest.raises(EvidenceAccessError) as error:
        ledger.verify_many(
            ["EVD_001"],
            context=_context(),
            subtask_id="request_001",
            verifier_version="test-verifier-v1",
            expected_sql_id="SQL_OLD",
        )

    assert error.value.code == "EVIDENCE_SQL_MISMATCH"


def test_evidence_rejects_dual_write_payload_mismatch(tmp_path):
    ledger = _ledger(tmp_path)
    _save_evidence(ledger)

    with pytest.raises(EvidenceAccessError) as error:
        ledger.verify_many(
            ["EVD_001"],
            context=_context(),
            subtask_id="request_001",
            verifier_version="test-verifier-v1",
            legacy_tool_results=[{
                "ok": True,
                "status": "success",
                "code": "TRIAL_RUN_COMPLETED",
                "summary": "tampered",
                "data": {},
                "evidence": [],
                "evidence_ids": ["EVD_001"],
                "warnings": [],
                "retryable": False,
            }],
        )

    assert error.value.code == "EVIDENCE_PAYLOAD_MISMATCH"
