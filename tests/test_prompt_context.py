from app.memory.contracts import (
    ActiveRuleContext,
    ContextOverride,
    ConversationContext,
    StatPeriodContext,
    WorkingCaliberContext,
)
from app.memory.prompt_context import build_prompt_context
from app.agents.human_interaction import HumanInteractionAgent


def _context() -> ConversationContext:
    return ConversationContext(
        active_rule=ActiveRuleContext(
            rule_id="MQSI2025_001",
            rule_name="患者入院48小时内转科的比例",
            hospital_id="hospital_001",
        ),
        stat_period=StatPeriodContext(
            start_time="2026-06-01 00:00:00",
            end_time="2026-08-01 00:00:00",
        ),
        working_caliber=WorkingCaliberContext(
            overrides=[
                ContextOverride(
                    key="elapsed_time_start",
                    business_value="ward_entry_time",
                    hospital_field="INPATIENT_ENCOUNTER.WARD_ENTRY_AT",
                    status="ready",
                    source_text="48小时从入区时间开始算",
                )
            ]
        ),
    )


def test_prompt_context_keeps_structured_state_and_last_eight_turns() -> None:
    messages = []
    for index in range(10):
        messages.extend(
            [
                {"role": "user", "content": f"user-{index}"},
                {"role": "assistant", "content": f"assistant-{index}"},
            ]
        )

    result = build_prompt_context(
        messages,
        _context(),
        max_turns=8,
        token_budget=12000,
    )

    assert "患者入院48小时内转科的比例" in result.structured_summary
    assert "2026-06-01 00:00:00" in result.structured_summary
    assert "elapsed_time_start" in result.structured_summary
    assert "user-1" not in result.recent_history
    assert "user-2" in result.recent_history
    assert "assistant-9" in result.recent_history
    assert result.kept_turns == 8


def test_prompt_context_compacts_large_sql_and_respects_budget() -> None:
    messages = [
        {"role": "user", "content": "生成 SQL"},
        {
            "role": "assistant",
            "content": "```sql\nSELECT " + "very_long_column," * 4000 + " 1\n```",
        },
        {"role": "user", "content": "48小时从入区时间开始算"},
        {"role": "assistant", "content": "已记录为当前会话临时口径"},
    ]

    result = build_prompt_context(
        messages,
        _context(),
        max_turns=8,
        token_budget=900,
    )

    assert "内容过长，已压缩" in result.recent_history
    assert "48小时从入区时间开始算" in result.recent_history
    assert result.estimated_tokens <= 900


def test_intent_prompt_includes_structured_summary_and_recent_history() -> None:
    prompt = HumanInteractionAgent._intent_prompt(
        "生成 SQL",
        {
            "rule_name": "患者入院48小时内转科的比例",
            "structured_summary": "48小时起点：入区时间",
            "recent_history": "用户：48小时从入区时间开始算",
        },
    )

    assert "48小时起点：入区时间" in prompt
    assert "用户：48小时从入区时间开始算" in prompt
