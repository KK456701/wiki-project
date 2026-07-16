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


def test_trace_bridge_records_full_safe_agent_nodes_and_finish_status() -> None:
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
        "arguments": {"query": "急会诊", "authorization": "Bearer secret"},
        "duration_ms": 12,
        "result": {
            "ok": True,
            "code": "RULE_SEARCHED",
            "data": {"sql_text": "SELECT aggregate_count"},
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
    assert "SELECT aggregate_count" in serialized
    assert any(kwargs["tool_name"] == "search_indicator_rules" for _, kwargs in recorder.nodes)
    result_node = recorder.nodes[1][1]
    assert result_node["input_data"] == {
        "tool_name": "search_indicator_rules",
        "arguments": {"query": "急会诊", "authorization": "[REDACTED]"},
    }
    assert [kwargs["node_name"] for _, kwargs in recorder.nodes] == [
        "tool_gateway",
        "tool_result",
    ]
    gateway_node = recorder.nodes[0][1]
    assert gateway_node["status"] == "success"
    assert gateway_node["output_summary"] == "TOOL_CALL_ACCEPTED"
    assert recorder.finished[0][1]["intent"] == "agent_tool_calling"
    assert recorder.finished[0][1]["final_status"] == "success"


def test_trace_bridge_records_typed_stage_with_complete_safe_payload() -> None:
    recorder = FakeRecorder()
    bridge = AgentTraceBridge(recorder, "TRACE_STAGE")

    bridge.handle({
        "event": "trace_node",
        "node_name": "planner_llm",
        "node_type": "llm",
        "status": "failed",
        "duration_ms": 1980,
        "input_data": {
            "messages": [
                {"role": "system", "content": "完整系统提示"},
                {"role": "user", "content": "你好"},
            ],
            "authorization": "Bearer secret",
        },
        "output_data": {
            "raw_content": '{"target_indicator":null}',
            "validation_error": "target_indicator must be object",
        },
        "processing_data": {
            "description": "解析模型 JSON 并校验 RequestPlan",
        },
        "config_data": {
            "prompt_file": "agent_planner.txt",
            "prompt_version": "abc123",
        },
    })

    node = recorder.nodes[0][1]
    assert node["node_name"] == "planner_llm"
    assert node["node_type"] == "llm"
    assert node["duration_ms"] == 1980
    assert node["input_data"]["messages"][0]["content"] == "完整系统提示"
    assert node["input_data"]["authorization"] == "[REDACTED]"
    assert node["output_data"]["raw_content"] == '{"target_indicator":null}'
    assert node["processing_data"]["description"] == "解析模型 JSON 并校验 RequestPlan"
    assert node["config_data"]["prompt_file"] == "agent_planner.txt"


def test_trace_bridge_records_memory_failure_without_sensitive_details() -> None:
    recorder = FakeRecorder()
    bridge = AgentTraceBridge(recorder, "TRACE_002")

    bridge.record_memory_failure("database_password=secret")

    node = recorder.nodes[0][1]
    assert node["node_name"] == "memory_save"
    assert node["status"] == "failed"
    assert node["error_code"] == "AGENT_MEMORY_SAVE_FAILED"
    assert "secret" not in str(node)
