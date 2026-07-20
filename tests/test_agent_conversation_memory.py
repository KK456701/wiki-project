from datetime import datetime, timezone

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_runtime.memory import AgentConversationMemory
from app.memory.store import ConversationMemory


NOW = datetime(2026, 7, 16, 8, 30, tzinfo=timezone.utc)


def _context(*, user_id="u1", hospital_id="h1", session_id="s1"):
    return AgentRuntimeContext(
        user_id=user_id,
        hospital_id=hospital_id,
        session_id=session_id,
        user_role="implementer",
        permissions=frozenset({"indicator_read"}),
        request_id="REQ_001",
        trace_id="TRACE_001",
        db_source_id="hospital_db",
    )


def _completed_state(rule_id="MQSI2025_005"):
    return AgentRunState(
        current_rule_id=rule_id,
        validated_sql_ids=["SQL_001"],
        last_run_id="RUN_001",
        last_tool_results=[{
            "ok": True,
            "status": "success",
            "code": "EFFECTIVE_RULE_FOUND",
            "summary": "已读取生效规则。",
            "data": {
                "rule_id": rule_id,
                "rule_name": "急会诊及时到位率",
                "stat_start": "2026-06-01 00:00:00",
                "stat_end": "2026-07-17 00:00:00",
            },
            "evidence": [],
            "warnings": [],
            "retryable": False,
        }],
    )


def test_memory_restores_structured_rule_safe_ids_and_last_eight_turns(tmp_path):
    store = ConversationMemory(tmp_path)
    memory = AgentConversationMemory(
        store=store,
        max_turns=8,
        token_budget=12000,
        now_provider=lambda: NOW,
    )
    context = _context()

    for index in range(9):
        session = memory.open(context)
        session.append_user(f"问题{index}")
        session.complete(f"问题{index}", f"回答{index}", _completed_state())

    restored = memory.open(context)
    system_prompt = restored.state.messages[0]["content"]

    assert restored.state.current_rule_id == "MQSI2025_005"
    assert restored.state.validated_sql_ids == ["SQL_001"]
    assert restored.state.last_run_id == "RUN_001"
    assert restored.context.active_rule.rule_name == "急会诊及时到位率"
    assert restored.context.stat_period.start_time == "2026-06-01 00:00:00"
    assert "问题0" not in system_prompt
    assert "问题1" in system_prompt
    assert "回答8" in system_prompt
    assert "当前日期：2026-07-16" in system_prompt
    assert len(store.recent_messages(restored.storage_session_id, limit=100)) == 18


def test_memory_restores_compound_rule_ids(tmp_path):
    memory = AgentConversationMemory(store=ConversationMemory(tmp_path))
    context = _context()
    session = memory.open(context)
    session.append_user("查询两个指标")
    state = _completed_state()
    state.current_rule_ids = ["MQSI2025_001", "MQSI2025_005"]
    session.complete("查询两个指标", "两个结果", state)

    restored = memory.open(context)

    assert restored.state.current_rule_ids == ["MQSI2025_001", "MQSI2025_005"]


def test_same_public_session_is_isolated_by_hospital_and_user(tmp_path):
    memory = AgentConversationMemory(store=ConversationMemory(tmp_path))

    sessions = {
        memory.open(_context(user_id="u1", hospital_id="h1", session_id="same")).storage_session_id,
        memory.open(_context(user_id="u2", hospital_id="h1", session_id="same")).storage_session_id,
        memory.open(_context(user_id="u1", hospital_id="h2", session_id="same")).storage_session_id,
    }

    assert len(sessions) == 3


def test_memory_metadata_excludes_tool_results_sql_and_sensitive_values(tmp_path):
    store = ConversationMemory(tmp_path)
    memory = AgentConversationMemory(store=store)
    session = memory.open(_context())
    session.append_user("生成 SQL")
    state = _completed_state()
    state.last_tool_results[0]["data"].update({
        "sql_text": "SELECT patient_name FROM secret_table",
        "password": "secret",
    })

    session.complete("生成 SQL", "SQL 已准备。", state)

    messages = store.recent_messages(session.storage_session_id, limit=10)
    metadata_text = str(messages[-1]["metadata"])
    assert "agent_state" in messages[-1]["metadata"]
    assert "SQL_001" in metadata_text
    assert "SELECT" not in metadata_text
    assert "patient_name" not in metadata_text
    assert "password" not in metadata_text
    assert "secret" not in metadata_text


def test_restored_rule_identity_opens_rule_tool_without_restoring_rule_facts(tmp_path):
    memory = AgentConversationMemory(store=ConversationMemory(tmp_path))
    context = _context()
    first = memory.open(context)
    first.append_user("急会诊及时到位率怎么算？")
    first.complete("急会诊及时到位率怎么算？", "回答", _completed_state())

    restored = memory.open(context)

    assert restored.state.evidence == [{
        "source": "conversation_memory",
        "source_id": "MQSI2025_005",
        "fact_types": ["rule_identity"],
    }]


def test_memory_exposes_bounded_recent_history_to_planner(tmp_path):
    memory = AgentConversationMemory(store=ConversationMemory(tmp_path))
    context = _context()
    first = memory.open(context)
    first.append_user("患者入院 48 小时内转科的比例怎么算")
    first.complete(
        "患者入院 48 小时内转科的比例怎么算",
        "可以查询从6月1日至今的结果。",
        _completed_state("MQSI2025_001"),
    )

    restored = memory.open(context)

    assert "患者入院 48 小时内转科" in restored.state.recent_history
    assert "从6月1日至今" in restored.state.recent_history


def test_memory_restores_latest_uploaded_file_key(tmp_path):
    memory = AgentConversationMemory(store=ConversationMemory(tmp_path))
    context = _context()
    first = memory.open(context)
    first.append_user("帮我分析刚上传的文件")
    state = _completed_state()
    state.current_upload_file_key = "h1_85a68d23d925_无标题.xlsx"

    first.complete("帮我分析刚上传的文件", "分析完成。", state)

    restored = memory.open(context)
    assert (
        restored.state.current_upload_file_key
        == "h1_85a68d23d925_无标题.xlsx"
    )
