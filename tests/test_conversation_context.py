from __future__ import annotations

import pytest

from app.memory.contracts import ContextVersionConflict
from app.memory.store import ConversationMemory


def test_context_round_trip_and_version_conflict(tmp_path) -> None:
    memory = ConversationMemory(tmp_path)
    session_id = memory.ensure_session("session-1", "hospital_001")

    context = memory.load_context(session_id)
    assert context.context_version == 0
    context.active_rule.rule_id = "MQSI2025_001"
    context.active_rule.rule_name = "患者入院48小时内转科的比例"

    saved = memory.save_context(session_id, context, expected_version=0)

    assert saved.context_version == 1
    restored = memory.load_context(session_id)
    assert restored.active_rule.rule_id == "MQSI2025_001"
    assert restored.active_rule.rule_name == "患者入院48小时内转科的比例"
    with pytest.raises(ContextVersionConflict):
        memory.save_context(session_id, context, expected_version=0)


def test_context_is_isolated_between_sessions(tmp_path) -> None:
    memory = ConversationMemory(tmp_path)
    first_session = memory.ensure_session("session-1", "hospital_001")
    second_session = memory.ensure_session("session-2", "hospital_001")
    first = memory.load_context(first_session)
    first.working_caliber.scope = "session"
    first.working_caliber.overrides = [
        {
            "key": "elapsed_time_start",
            "business_value": "ward_entry_time",
            "status": "pending_mapping",
            "source_text": "48小时从入区时间开始算",
        }
    ]

    memory.save_context(first_session, first, expected_version=0)

    assert memory.load_context(first_session).working_caliber.overrides
    assert memory.load_context(second_session).working_caliber.overrides == []


def test_append_message_returns_persisted_message_id(tmp_path) -> None:
    memory = ConversationMemory(tmp_path)
    session_id = memory.ensure_session("session-1", "hospital_001")

    message_id = memory.append_message(session_id, "user", "按入区时间算")

    assert isinstance(message_id, int)
    assert message_id > 0
