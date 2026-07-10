"""轻量工作流 manifest 加载与 Trace 节点注解。"""

from __future__ import annotations

from copy import deepcopy
from pathlib import Path
from typing import Any

import yaml


WORKFLOW_ROOT = Path(__file__).resolve().parent
_CACHE: dict[str, dict[str, Any]] = {}
_ALLOWED_FAILURE_POLICIES = {"stop", "continue", "fallback"}
_ALLOWED_AGENT_OWNERS = {
    "metadata_parsing",
    "indicator_generation",
    "caliber_adaptation",
    "root_cause_diagnosis",
    "human_interaction",
}


def _default_failure_code(node_id: str) -> str:
    safe = "".join(ch if ch.isalnum() else "_" for ch in node_id).strip("_").upper()
    return f"{safe or 'WORKFLOW_NODE'}_FAILED"


def _with_contract_defaults(node: dict[str, Any]) -> dict[str, Any]:
    node_id = str(node.get("id") or "")
    enriched = dict(node)
    enriched.setdefault("required", False)
    enriched.setdefault("on_failure", "continue")
    enriched.setdefault("failure_code", _default_failure_code(node_id))
    enriched.setdefault("required_inputs", [])
    enriched.setdefault("required_outputs", [])
    enriched.setdefault("registered", True)
    enriched.setdefault("agent_owner", "")
    return enriched


def load_workflow_manifest(workflow_id: str = "core_indicator_chat") -> dict[str, Any]:
    if workflow_id not in _CACHE:
        path = WORKFLOW_ROOT / f"{workflow_id}.yaml"
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        data.setdefault("workflow_id", workflow_id)
        data.setdefault("nodes", [])
        data.setdefault("edges", [])
        data["nodes"] = [_with_contract_defaults(dict(node)) for node in data.get("nodes", [])]
        _CACHE[workflow_id] = data
    return deepcopy(_CACHE[workflow_id])


def get_workflow_node(workflow_id: str, node_id: str) -> dict[str, Any]:
    manifest = load_workflow_manifest(workflow_id)
    for node in manifest.get("nodes", []):
        if node.get("id") == node_id:
            return deepcopy(node)
    return {
        "id": node_id,
        "title": node_id,
        "type": "",
        "description": "该节点尚未在工作流 manifest 中登记。",
        "inputs": [],
        "outputs": [],
        "config": {},
        "required": False,
        "on_failure": "continue",
        "failure_code": _default_failure_code(node_id),
        "required_inputs": [],
        "required_outputs": [],
        "failure_hint": "请先补充该节点的 manifest 定义，再定位运行细节。",
        "registered": False,
        "agent_owner": "",
    }


def default_failure_code_for_node(node_id: str, workflow_id: str = "core_indicator_chat") -> str:
    return str(get_workflow_node(workflow_id, node_id).get("failure_code") or _default_failure_code(node_id))


def _missing_keys(data: dict[str, Any], required_keys: list[str]) -> list[str]:
    missing = []
    for key in required_keys:
        if key not in data or data.get(key) in (None, ""):
            missing.append(key)
    return missing


def validate_trace_node_contract(node: dict[str, Any], workflow_id: str = "core_indicator_chat") -> dict[str, Any]:
    node_id = str(node.get("node_name") or node.get("id") or "")
    meta = get_workflow_node(workflow_id, node_id)
    missing_inputs = _missing_keys(dict(node.get("input_data") or {}), list(meta.get("required_inputs") or []))
    missing_outputs = _missing_keys(dict(node.get("output_data") or {}), list(meta.get("required_outputs") or []))
    status = "ok"
    if missing_inputs or missing_outputs or not meta.get("registered", True):
        status = "warning"
    return {
        "contract_status": status,
        "missing_inputs": missing_inputs,
        "missing_outputs": missing_outputs,
        "node_required": bool(meta.get("required", False)),
        "on_failure": meta.get("on_failure") or "continue",
        "failure_code": meta.get("failure_code") or _default_failure_code(node_id),
    }


def validate_workflow_manifest(manifest_or_id: dict[str, Any] | str = "core_indicator_chat") -> dict[str, Any]:
    manifest = load_workflow_manifest(manifest_or_id) if isinstance(manifest_or_id, str) else deepcopy(manifest_or_id)
    issues: list[dict[str, str]] = []
    node_ids: set[str] = set()

    for index, raw_node in enumerate(manifest.get("nodes", [])):
        node = _with_contract_defaults(dict(raw_node))
        node_id = str(node.get("id") or "")
        if not node_id:
            issues.append({"severity": "error", "message": f"nodes[{index}] 缺少 id"})
            continue
        if node_id in node_ids:
            issues.append({"severity": "error", "message": f"节点 id 重复: {node_id}"})
        node_ids.add(node_id)
        for field in ("title", "type", "description", "failure_hint"):
            if not node.get(field):
                issues.append({"severity": "error", "message": f"节点 {node_id} 缺少 {field}"})
        if node.get("on_failure") not in _ALLOWED_FAILURE_POLICIES:
            issues.append({"severity": "error", "message": f"节点 {node_id} on_failure 非法: {node.get('on_failure')}"})
        if node.get("agent_owner") not in _ALLOWED_AGENT_OWNERS:
            issues.append({"severity": "error", "message": f"节点 {node_id} agent_owner 非法: {node.get('agent_owner')}"})
        for field in ("inputs", "outputs", "required_inputs", "required_outputs"):
            if not isinstance(node.get(field), list):
                issues.append({"severity": "error", "message": f"节点 {node_id} 的 {field} 必须是列表"})

    for edge in manifest.get("edges", []):
        source = str(edge.get("from") or "")
        target = str(edge.get("to") or "")
        if source not in node_ids:
            issues.append({"severity": "error", "message": f"边引用了不存在的起点节点: {source}"})
        if target not in node_ids:
            issues.append({"severity": "error", "message": f"边引用了不存在的终点节点: {target}"})

    return {
        "ok": not any(issue["severity"] == "error" for issue in issues),
        "workflow_id": manifest.get("workflow_id"),
        "node_count": len(manifest.get("nodes", [])),
        "edge_count": len(manifest.get("edges", [])),
        "issues": issues,
    }


def annotate_trace_node(node: dict[str, Any], workflow_id: str = "core_indicator_chat") -> dict[str, Any]:
    node_id = str(node.get("node_name") or node.get("id") or "")
    meta = get_workflow_node(workflow_id, node_id)
    annotated = dict(node)
    annotated.setdefault("workflow_id", workflow_id)
    annotated["node_title"] = meta.get("title") or node_id
    annotated["node_description"] = meta.get("description") or ""
    annotated["expected_inputs"] = list(meta.get("inputs") or [])
    annotated["expected_outputs"] = list(meta.get("outputs") or [])
    annotated["node_config"] = dict(meta.get("config") or {})
    annotated["failure_hint"] = meta.get("failure_hint") or ""
    annotated["manifest_type"] = meta.get("type") or annotated.get("node_type") or ""
    annotated["agent_owner"] = meta.get("agent_owner") or ""
    annotated["required_inputs"] = list(meta.get("required_inputs") or [])
    annotated["required_outputs"] = list(meta.get("required_outputs") or [])
    annotated.update(validate_trace_node_contract(annotated, workflow_id))
    return annotated
