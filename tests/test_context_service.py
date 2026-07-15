from __future__ import annotations

from app.memory.context_service import ConversationContextService
from app.memory.contracts import ContextOverride, ConversationContext


RULE = {
    "rule_id": "MQSI2025_001",
    "rule_name": "患者入院48小时内转科的比例",
}


def _context() -> ConversationContext:
    context = ConversationContext()
    context.stat_period.start_time = "2026-07-01 00:00:00"
    context.stat_period.end_time = "2026-08-01 00:00:00"
    return context


def test_ambiguous_ward_entry_request_requires_clarification() -> None:
    service = ConversationContextService()

    result = service.resolve(
        "按入区时间算",
        _context(),
        effective_rule=RULE,
        field_mapping={"fields": {}},
    )

    assert result.blocked is True
    assert result.clarification is not None
    assert result.clarification.code == "WARD_ENTRY_SCOPE_REQUIRED"
    assert len(result.clarification.options) == 3
    assert result.snapshot.executable is False


def test_confirmed_elapsed_start_is_saved_but_unmapped_field_blocks_execution() -> None:
    service = ConversationContextService()

    result = service.resolve(
        "48小时从入区时间开始算",
        _context(),
        effective_rule=RULE,
        field_mapping={"fields": {"admit_time": "INPATIENT_ENCOUNTER.ADMITTED_AT"}},
    )

    override = result.context.working_caliber.get("elapsed_time_start")
    assert override is not None
    assert override.business_value == "ward_entry_time"
    assert override.status == "pending_mapping"
    assert result.snapshot.executable is False
    assert result.snapshot.blockers[0].code == "CONTEXT_FIELD_MAPPING_REQUIRED"


def test_confirmed_ward_entry_uses_confirmed_hospital_field() -> None:
    service = ConversationContextService()

    result = service.resolve(
        "48小时从入区时间开始算",
        _context(),
        effective_rule=RULE,
        field_mapping={
            "fields": {
                "admit_time": "INPATIENT_ENCOUNTER.ADMITTED_AT",
                "ward_entry_time": (
                    "INPATIENT_ENCOUNTER.FIRST_ADMITTED_TO_WARD_AT"
                ),
            }
        },
    )

    override = result.context.working_caliber.get("elapsed_time_start")
    assert override is not None
    assert override.status == "ready"
    assert override.hospital_field == (
        "INPATIENT_ENCOUNTER.FIRST_ADMITTED_TO_WARD_AT"
    )
    assert result.snapshot.executable is True
    assert result.snapshot.overrides["elapsed_time_start"] == "ward_entry_time"
    assert result.snapshot.resolved_fields["elapsed_time_start"] == (
        "INPATIENT_ENCOUNTER.FIRST_ADMITTED_TO_WARD_AT"
    )
    assert result.snapshot.source_levels["elapsed_time_start"] == "当前会话临时调整"


def test_restore_hospital_caliber_clears_session_overrides() -> None:
    service = ConversationContextService()
    context = _context()
    context.active_rule.rule_id = "MQSI2025_001"
    context.working_caliber.overrides.append(
        ContextOverride(
            key="elapsed_time_start",
            business_value="ward_entry_time",
            hospital_field="INPATIENT_ENCOUNTER.FIRST_ADMITTED_TO_WARD_AT",
            status="ready",
        )
    )

    result = service.resolve(
        "恢复本院口径",
        context,
        effective_rule=RULE,
        field_mapping={"fields": {}},
    )

    assert result.context.working_caliber.overrides == []
    assert result.snapshot.overrides == {}
    assert result.delta.clear_working_caliber is True


def test_switching_indicator_clears_overrides_but_keeps_stat_period() -> None:
    service = ConversationContextService()
    context = _context()
    context.active_rule.rule_id = "MQSI2025_001"
    context.working_caliber.overrides.append(
        ContextOverride(
            key="elapsed_time_start",
            business_value="ward_entry_time",
            status="pending_mapping",
        )
    )

    result = service.resolve(
        "急会诊及时到位率怎么算",
        context,
        effective_rule={"rule_id": "MQSI2025_005", "rule_name": "急会诊及时到位率"},
        field_mapping={"fields": {}},
    )

    assert result.context.active_rule.rule_id == "MQSI2025_005"
    assert result.context.working_caliber.overrides == []
    assert result.context.stat_period.start_time == "2026-07-01 00:00:00"
