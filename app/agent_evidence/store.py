from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from sqlalchemy import Column, Index, MetaData, String, Table, Text, insert, inspect, select
from sqlalchemy.engine import Engine

from .models import EvidenceEnvelope, EvidenceVerification


_METADATA = MetaData()
_EVIDENCE = Table(
    "med_agent_evidence",
    _METADATA,
    Column("evidence_id", String(80), primary_key=True),
    Column("schema_version", String(40), nullable=False),
    Column("trace_id", String(128), nullable=False),
    Column("subtask_id", String(128), nullable=False),
    Column("fact_type", String(80), nullable=False),
    Column("hospital_id", String(128), nullable=False),
    Column("rule_id", String(128), nullable=True),
    Column("rule_version", String(80), nullable=True),
    Column("stat_start", String(40), nullable=True),
    Column("stat_end", String(40), nullable=True),
    Column("source_tool", String(80), nullable=False),
    Column("source_object_id", String(128), nullable=True),
    Column("input_fingerprint", String(64), nullable=False),
    Column("result_fingerprint", String(64), nullable=False),
    Column("confidentiality", String(32), nullable=False),
    Column("created_at", String(40), nullable=False),
    Column("expires_at", String(40), nullable=True),
    Column("payload_ref", String(255), nullable=True),
    Column("safe_payload_json", Text, nullable=False),
    Index("ix_agent_evidence_trace", "trace_id", "subtask_id"),
    Index("ix_agent_evidence_hospital_created", "hospital_id", "created_at"),
)
_VERIFICATION = Table(
    "med_agent_evidence_verification",
    _METADATA,
    Column("verification_id", String(80), primary_key=True),
    Column("schema_version", String(40), nullable=False),
    Column("evidence_id", String(80), nullable=False),
    Column("trace_id", String(128), nullable=False),
    Column("subtask_id", String(128), nullable=False),
    Column("hospital_id", String(128), nullable=False),
    Column("verifier_version", String(80), nullable=False),
    Column("status", String(20), nullable=False),
    Column("code", String(80), nullable=False),
    Column("message", Text, nullable=False),
    Column("verified_at", String(40), nullable=False),
    Index("ix_agent_verification_evidence", "evidence_id", "status"),
)


def ensure_evidence_schema(engine: Engine) -> list[str]:
    existing = {
        table.name: inspect(engine).has_table(table.name)
        for table in (_EVIDENCE, _VERIFICATION)
    }
    _METADATA.create_all(engine, tables=[_EVIDENCE, _VERIFICATION])
    return [name for name, existed in existing.items() if not existed]


class EvidenceStore:
    def __init__(self, engine: Engine, jsonl_path: Path | None = None) -> None:
        self.engine = engine
        self.jsonl_path = jsonl_path or Path("runtime") / "agent_evidence.jsonl"

    def _append_jsonl(self, event: dict[str, Any]) -> None:
        self.jsonl_path.parent.mkdir(parents=True, exist_ok=True)
        with self.jsonl_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")

    def save_evidence(self, value: EvidenceEnvelope) -> None:
        payload = value.model_dump(mode="json")
        row = dict(payload)
        row["safe_payload_json"] = json.dumps(row.pop("safe_payload"), ensure_ascii=False, sort_keys=True)
        try:
            with self.engine.begin() as connection:
                connection.execute(insert(_EVIDENCE).values(**row))
        except Exception:
            self._append_jsonl({"event": "evidence", "payload": payload})

    def save_verification(self, value: EvidenceVerification) -> None:
        payload = value.model_dump(mode="json")
        try:
            with self.engine.begin() as connection:
                connection.execute(insert(_VERIFICATION).values(**payload))
        except Exception:
            self._append_jsonl({"event": "verification", "payload": payload})

    def load_evidence(self, evidence_id: str) -> EvidenceEnvelope | None:
        try:
            with self.engine.connect() as connection:
                row = connection.execute(
                    select(_EVIDENCE).where(_EVIDENCE.c.evidence_id == evidence_id)
                ).mappings().first()
            if row is not None:
                payload = dict(row)
                payload["safe_payload"] = json.loads(payload.pop("safe_payload_json") or "{}")
                return EvidenceEnvelope.model_validate(payload)
        except Exception:
            pass
        return self._load_jsonl("evidence", evidence_id, "evidence_id", EvidenceEnvelope)

    def is_verified(self, evidence_id: str) -> bool:
        try:
            with self.engine.connect() as connection:
                value = connection.execute(
                    select(_VERIFICATION.c.verification_id).where(
                        _VERIFICATION.c.evidence_id == evidence_id,
                        _VERIFICATION.c.status == "verified",
                    )
                ).first()
            if value is not None:
                return True
        except Exception:
            pass
        verification = self._load_jsonl(
            "verification", evidence_id, "evidence_id", EvidenceVerification
        )
        return verification is not None and verification.status == "verified"

    def _load_jsonl(self, event: str, value: str, key: str, model):
        if not self.jsonl_path.exists():
            return None
        for line in reversed(self.jsonl_path.read_text(encoding="utf-8").splitlines()):
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                continue
            payload = item.get("payload") or {}
            if item.get("event") == event and str(payload.get(key) or "") == value:
                return model.model_validate(payload)
        return None
