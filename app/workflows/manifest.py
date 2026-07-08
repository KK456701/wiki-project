"""轻量工作流 manifest 加载与 Trace 节点注解。"""

from __future__ import annotations

from copy import deepcopy
from pathlib import Path
from typing import Any

import yaml


WORKFLOW_ROOT = Path(__file__).resolve().parent
_CACHE: dict[str, dict[str, Any]] = {}


def load_workflow_manifest(workflow_id: str = "core_indicator_chat") -> dict[str, Any]:
    if workflow_id not in _CACHE:
        path = WORKFLOW_ROOT / f"{workflow_id}.yaml"
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        data.setdefault("workflow_id", workflow_id)
        data.setdefault("nodes", [])
        data.setdefault("edges", [])
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
        "failure_hint": "请先补充该节点的 manifest 定义，再定位运行细节。",
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
    return annotated
