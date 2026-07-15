from __future__ import annotations

from typing import Any
import re

from app.business_source import current_business_source
from app.observability.trace import TraceRecorder


_MONITORING_BLOCKED_KEYS = {
    "patient_id",
    "patient_name",
    "patient_no",
    "id_card",
    "rows",
}


def _execute_tool(source_id: str) -> str:
    suffix = re.sub(r"[^0-9a-zA-Z]+", "_", source_id).strip("_").lower()
    return f"execute_sql_{suffix}"


def _safe_monitoring_payload(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            str(key): _safe_monitoring_payload(item)
            for key, item in value.items()
            if "sql" not in str(key).lower()
            and str(key).lower() not in _MONITORING_BLOCKED_KEYS
        }
    if isinstance(value, list):
        return [_safe_monitoring_payload(item) for item in value]
    if isinstance(value, str) and value.lstrip().upper().startswith(
        ("SELECT ", "INSERT ", "UPDATE ", "DELETE ")
    ):
        return "[已脱敏]"
    return value


def record_monitoring_trace_nodes(
    recorder: TraceRecorder | None,
    trace_id: str,
    events: list[dict[str, Any]],
) -> None:
    if recorder is None:
        return
    node_types = {
        "monitor_plan_load": "plan_store",
        "monitor_lease_acquire": "lease",
        "monitor_period_resolve": "period",
        "monitor_indicator_execute_mcp": "mcp_tool",
        "monitor_wave_detect": "rule",
        "monitor_alert_create": "alert",
        "monitor_auto_diagnose": "diagnose",
    }
    for event in events:
        node_name = str(event["node_name"])
        input_data = _safe_monitoring_payload(event.get("input_data") or {})
        output_data = _safe_monitoring_payload(event.get("output_data") or {})
        config_data = _safe_monitoring_payload(event.get("config_data") or {})
        business_source = str(
            output_data.get("data_source") or current_business_source().source_id
        )
        recorder.record_node(
            trace_id,
            node_name,
            node_types.get(node_name, "monitoring"),
            str(event.get("status") or "success"),
            input_summary=str(event.get("input_summary") or ""),
            output_summary=str(event.get("output_summary") or ""),
            error_code=str(event.get("error_code") or ""),
            error_message=str(event.get("error_message") or ""),
            tool_name=(
                _execute_tool(business_source)
                if node_name == "monitor_indicator_execute_mcp"
                else ""
            ),
            db_source=(
                business_source
                if node_name == "monitor_indicator_execute_mcp"
                else ""
            ),
            run_id=str(output_data.get("run_id") or ""),
            rule_id=str(
                input_data.get("rule_id") or output_data.get("rule_id") or ""
            ),
            duration_ms=int(event.get("duration_ms") or 0),
            input_data=input_data,
            output_data=output_data,
            config_data=config_data,
        )


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
        2: {
            "layer": 2,
            "tool": _execute_tool(current_business_source().source_id),
            "readonly": True,
            "comparison": "国标口径 vs 本院生效口径",
        },
        3: {"tool": _execute_tool(current_business_source().source_id)},
    }
    for index, layer in enumerate(diag_result.get("layers", []), start=1):
        node_name = node_names.get(index)
        if not node_name:
            continue
        status = _layer_status(layer)
        comparison = (
            layer.get("caliber_comparison") or {} if index == 2 else {}
        )
        national = comparison.get("national") or {}
        hospital = comparison.get("hospital") or {}
        comparison_tool = str(
            national.get("tool_name") or hospital.get("tool_name") or ""
        )
        comparison_source = str(
            national.get("source") or hospital.get("source") or ""
        )
        business_source = comparison_source or current_business_source().source_id
        duration_ms = (
            int(national.get("duration_ms") or 0)
            + int(hospital.get("duration_ms") or 0)
            if index == 2
            else 0
        )
        output_summary = str(
            comparison.get("conclusion_code")
            or layer.get("diagnose_type")
            or layer.get("layer_name")
            or status
        )
        input_data = {
            "rule_id": rule_id,
            "hospital_id": hospital_id,
            "layer": index,
        }
        if index == 2:
            input_data.update(
                {
                    "caliber_context": {
                        "applicable": comparison.get("applicable", False),
                        "overridden_fields": comparison.get(
                            "overridden_fields", []
                        ),
                    },
                    "field_mapping": {"status": "resolved"},
                    "stat_period": comparison.get("stat_period"),
                }
            )
        recorder.record_node(
            trace_id,
            node_name,
            node_types[index],
            status,
            input_summary=f"{hospital_id or ''}/{rule_id or ''}",
            output_summary=output_summary,
            rule_id=str(rule_id or ""),
            run_id=str(hospital.get("run_id") or national.get("run_id") or ""),
            tool_name=(
                comparison_tool
                if index == 2
                else (_execute_tool(business_source) if index == 3 else "")
            ),
            db_source=(
                comparison_source
                if index == 2
                else (business_source if index in {1, 3} else "")
            ),
            duration_ms=duration_ms,
            input_data=input_data,
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
