from __future__ import annotations

import pytest

from app.indicators.release_adaptation import (
    ReleaseAdaptationError,
    ReleaseAdaptationService,
)


class _ReleaseRepository:
    def __init__(self, detail: dict):
        self.detail = detail

    def read_import(self, import_id: str) -> dict:
        assert import_id == self.detail["import_id"]
        return self.detail


class _DraftRepository:
    def __init__(self, drafts: list[dict] | None = None):
        self.drafts = drafts or []

    def list(self, hospital_id: str, status: str | None = None) -> list[dict]:
        assert hospital_id == "hospital_001"
        return self.drafts


class _GenerationAgent:
    def __init__(self):
        self.calls: list[dict] = []

    def create_adaptation_draft(self, **kwargs):
        self.calls.append(kwargs)
        return {
            "draft_id": "DRAFT_FROM_RELEASE",
            "hospital_id": kwargs["hospital_id"],
            "base_index_code": kwargs["base_index_code"],
            "index_name": kwargs["preferred_name"],
            "generated_by": kwargs["source_id"],
            "status": "metadata_pending",
            "current_version": 1,
        }


def _detail(status: str = "ready_for_adaptation") -> dict:
    return {
        "import_id": "IMP_001",
        "package_id": "REL_001",
        "status": status,
        "signature_status": "verified" if status == "ready_for_adaptation" else "legacy_unsigned",
        "compatibility_status": "compatible" if status == "ready_for_adaptation" else "review_required",
        "items": [
            {
                "item_path": "rules/MQSI2025_005.yaml",
                "item_type": "rule",
                "rule_id": "MQSI2025_005",
                "payload": {
                    "rule_id": "MQSI2025_005",
                    "rule_name": "急会诊及时到位率",
                    "definition": "统计急会诊请求后按规定时间到位的比例。",
                    "formula": "及时到位次数 / 急会诊总次数 × 100%",
                    "recommended_params": {"arrive_minutes_threshold": 10},
                },
            }
        ],
    }


def test_verified_release_rule_creates_hospital_adaptation_draft() -> None:
    generation = _GenerationAgent()
    service = ReleaseAdaptationService(
        release_repository=_ReleaseRepository(_detail()),
        draft_repository=_DraftRepository(),
        generation_agent=generation,
    )

    result = service.create(
        "IMP_001", "MQSI2025_005", "hospital_001", "admin"
    )

    assert result["draft_id"] == "DRAFT_FROM_RELEASE"
    assert result["duplicate"] is False
    assert generation.calls[0]["base_index_code"] == "MQSI2025_005"
    assert generation.calls[0]["source_id"] == (
        "company_release:IMP_001:rules/MQSI2025_005.yaml"
    )
    assert "10" in generation.calls[0]["query"]


def test_same_release_rule_reuses_existing_adaptation_draft() -> None:
    source_id = "company_release:IMP_001:rules/MQSI2025_005.yaml"
    existing = {
        "draft_id": "DRAFT_EXISTING",
        "generated_by": source_id,
        "status": "metadata_pending",
    }
    generation = _GenerationAgent()
    service = ReleaseAdaptationService(
        release_repository=_ReleaseRepository(_detail()),
        draft_repository=_DraftRepository([existing]),
        generation_agent=generation,
    )

    result = service.create(
        "IMP_001", "MQSI2025_005", "hospital_001", "admin"
    )

    assert result == {**existing, "duplicate": True}
    assert generation.calls == []


def test_quarantined_release_cannot_enter_adaptation() -> None:
    service = ReleaseAdaptationService(
        release_repository=_ReleaseRepository(_detail("quarantined")),
        draft_repository=_DraftRepository(),
        generation_agent=_GenerationAgent(),
    )

    with pytest.raises(ReleaseAdaptationError, match="发布包尚未通过签名和兼容性检查"):
        service.create("IMP_001", "MQSI2025_005", "hospital_001", "admin")

