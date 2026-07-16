from app.workflows.manifest import annotate_trace_node, validate_workflow_manifest


def test_agent_runtime_trace_manifest_is_valid() -> None:
    result = validate_workflow_manifest("agent_runtime")

    assert result["ok"] is True
    assert result["node_count"] == 12


def test_agent_runtime_manifest_separates_dispatch_from_final_answer_model() -> None:
    dispatch = annotate_trace_node(
        {
            "node_name": "deterministic_tool_dispatch",
            "input_data": {
                "request_plan": {},
                "decision": {},
                "state": {},
                "user_message": "怎么算",
            },
            "output_data": {"tool_call": {}},
        },
        "agent_runtime",
    )
    executor = annotate_trace_node(
        {
            "node_name": "executor_llm",
            "input_data": {"messages": []},
            "output_data": {"content": "回答"},
        },
        "agent_runtime",
    )

    assert dispatch["node_title"] == "编译工具调用"
    assert dispatch["manifest_type"] == "code"
    assert executor["node_title"] == "生成最终回答"


def test_missing_manifest_degrades_to_unregistered_node() -> None:
    node = annotate_trace_node(
        {"node_name": "unknown", "status": "success"},
        "missing_agent_workflow",
    )

    assert node["contract_status"] == "warning"
    assert node["node_title"] == "unknown"
