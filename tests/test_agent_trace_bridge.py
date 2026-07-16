from app.agent_runtime.tracing import AgentTraceBridge


class FakeRecorder:
    def __init__(self):
        self.started = []
        self.nodes = []
        self.finished = []

    def start_trace(self, *args, **kwargs):
        self.started.append((args, kwargs))

    def record_node(self, *args, **kwargs):
        self.nodes.append((args, kwargs))

    def finish_trace(self, *args, **kwargs):
        self.finished.append((args, kwargs))


def test_trace_bridge_records_redacted_agent_nodes_and_finish_status() -> None:
    recorder = FakeRecorder()
    bridge = AgentTraceBridge(recorder, "TRACE_001")
    bridge.start(session_id="s1", hospital_id="h1", user_query="查询指标")

    bridge.handle({"event": "model_start", "step": 1, "model_name": "fake"})
    bridge.handle({
        "event": "tool_call",
        "tool_name": "search_indicator_rules",
        "arguments": {"query": "急会诊", "password": "secret"},
        "risk_level": "read",
    })
    bridge.handle({
        "event": "tool_result",
        "tool_name": "search_indicator_rules",
        "duration_ms": 12,
        "result": {
            "ok": True,
            "code": "RULE_SEARCHED",
            "data": {"sql_text": "SELECT secret"},
            "evidence": [{"source": "fixture", "fact_types": ["rule_identity"]}],
        },
    })
    bridge.handle({
        "event": "agent_done",
        "stop_reason": "final_answer",
        "step_count": 2,
        "model_name": "fake",
        "answer": "已完成。",
    })

    assert recorder.started[0][1]["workflow_id"] == "agent_runtime"
    serialized = str(recorder.nodes)
    assert "secret" not in serialized
    assert "SELECT" not in serialized
    assert any(kwargs["tool_name"] == "search_indicator_rules" for _, kwargs in recorder.nodes)
    assert recorder.finished[0][1]["intent"] == "agent_tool_calling"
    assert recorder.finished[0][1]["final_status"] == "success"
