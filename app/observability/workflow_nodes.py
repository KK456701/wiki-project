from __future__ import annotations

from typing import Any

from app.observability.trace import TraceRecorder


def _layer_status(layer: dict[str, Any]) -> str:
    if not layer.get("ok", False):
        return "failed"
    if any(check.get("status") == "warn" for check in layer.get("checks", [])):
        return "warning"
    return "success"


def record_diagnose_trace_nodes(
    recorder: TraceRecorder | None,
    trace_id: str,
    diag_result: dict[str, Any],
    rule_id: str | None,
    hospital_id: str | None,
) -> None:
    if recorder is None:
        return
    node_names = {
        1: "diagnose_structure_mcp",
        2: "diagnose_rule_check",
        3: "diagnose_data_check_mcp",
    }
    node_types = {
        1: "diagnose",
        2: "diagnose",
        3: "diagnose",
    }
    configs = {
        1: {"provider": "DBHub 实时元数据，失败后回退运行库缓存"},
        2: {"layer": 2},
        3: {"tool": "execute_sql_hospital_demo_data"},
    }
    for index, layer in enumerate(diag_result.get("layers", []), start=1):
        node_name = node_names.get(index)
        if not node_name:
            continue
        status = _layer_status(layer)
        recorder.record_node(
            trace_id,
            node_name,
            node_types[index],
            status,
            input_summary=f"{hospital_id or ''}/{rule_id or ''}",
            output_summary=str(layer.get("diagnose_type") or layer.get("layer_name") or status),
            rule_id=str(rule_id or ""),
            tool_name="execute_sql_hospital_demo_data" if index == 3 else "",
            db_source="hospital_demo_data" if index in {1, 3} else "",
            input_data={"rule_id": rule_id, "hospital_id": hospital_id, "layer": index},
            output_data=layer,
            config_data=configs[index],
        )


def record_metadata_sync_trace_node(
    recorder: TraceRecorder | None,
    trace_id: str,
    result: dict[str, Any],
    hospital_id: str,
    db_name: str,
) -> None:
    if recorder is None:
        return
    recorder.record_node(
        trace_id,
        "metadata_sync_mcp",
        "mcp_tool",
        "success",
        input_summary=f"{hospital_id}/{db_name}",
        output_summary=str(result.get("batch_id") or ""),
        run_id=str(result.get("batch_id") or ""),
        tool_name=f"execute_sql_{db_name}",
        db_source=db_name,
        input_data={"hospital_id": hospital_id, "db_name": db_name, "source": "dbhub"},
        output_data={
            "batch_id": result.get("batch_id"),
            "table_count": result.get("table_count"),
            "column_count": result.get("column_count"),
            "changes": result.get("changes", []),
            "affected_rules": result.get("affected_rules", []),
        },
        config_data={"tool": f"execute_sql_{db_name}", "source": "DBHub MCP"},
    )


def record_review_trace_node(
    recorder: TraceRecorder | None,
    trace_id: str,
    node_name: str,
    status: str,
    input_data: dict[str, Any],
    output_data: dict[str, Any],
) -> None:
    if recorder is None:
        return
    recorder.record_node(
        trace_id,
        node_name,
        "review",
        status,
        input_summary=str(input_data.get("change_id") or input_data.get("rule_id") or ""),
        output_summary=str(output_data.get("status") or output_data.get("active_version_id") or ""),
        rule_id=str(input_data.get("rule_id") or output_data.get("rule_id") or ""),
        input_data=input_data,
        output_data=output_data,
        config_data={"storage": "core-rules-wiki review files + runtime indexes"},
    )

