from app.workflows.manifest import annotate_trace_node, validate_workflow_manifest


def test_agent_runtime_trace_manifest_is_valid() -> None:
    result = validate_workflow_manifest("agent_runtime")

    assert result["ok"] is True
    assert result["node_count"] == 4


def test_missing_manifest_degrades_to_unregistered_node() -> None:
    node = annotate_trace_node(
        {"node_name": "unknown", "status": "success"},
        "missing_agent_workflow",
    )

    assert node["contract_status"] == "warning"
    assert node["node_title"] == "unknown"
