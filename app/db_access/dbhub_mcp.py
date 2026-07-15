"""DBHub MCP HTTP 客户端。"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
import uuid
from typing import Any


class DBHubMCPError(RuntimeError):
    """DBHub MCP 调用失败。"""


class DBHubMCPClient:
    def __init__(self, endpoint: str, execute_tool: str, timeout_seconds: int = 10, source_id: str = ""):
        self.endpoint = endpoint.rstrip("/")
        self.execute_tool = execute_tool
        self.timeout_seconds = timeout_seconds
        self.source_id = source_id or execute_tool
        self.last_duration_ms = 0

    def execute_sql(self, sql: str) -> list[dict[str, Any]]:
        payload = {
            "jsonrpc": "2.0",
            "id": uuid.uuid4().hex,
            "method": "tools/call",
            "params": {
                "name": self.execute_tool,
                "arguments": {"sql": sql},
            },
        }
        started = time.perf_counter()
        data = _post_json(self.endpoint, payload, self.timeout_seconds)
        if "error" in data:
            raise DBHubMCPError(f"DBHub MCP 调用失败: {data['error']}")
        result = data.get("result", data)
        error = _extract_error(result)
        if isinstance(result, dict) and result.get("isError"):
            raise DBHubMCPError(f"DBHub MCP 执行失败: {error or '工具返回错误'}")
        rows = _extract_rows(result)
        if rows is None:
            suffix = f": {error}" if error else ""
            raise DBHubMCPError(f"DBHub MCP 返回格式中没有可解析的 rows{suffix}")
        self.last_duration_ms = int((time.perf_counter() - started) * 1000)
        return rows


def dbhub_sources(api_base_url: str = "http://127.0.0.1:8080", timeout_seconds: int = 5) -> dict[str, Any]:
    return _get_json(f"{api_base_url.rstrip('/')}/api/sources", timeout_seconds)


def _post_json(url: str, payload: dict[str, Any], timeout_seconds: int) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            return _loads_json_or_sse(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise DBHubMCPError(f"无法访问 DBHub MCP: {exc}") from exc


def _get_json(url: str, timeout_seconds: int) -> dict[str, Any]:
    try:
        with urllib.request.urlopen(url, timeout=timeout_seconds) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise DBHubMCPError(f"无法访问 DBHub API: {exc}") from exc


def _loads_json_or_sse(text: str) -> dict[str, Any]:
    stripped = text.strip()
    if not stripped:
        return {}
    if stripped.startswith("{"):
        return json.loads(stripped)
    data_lines = []
    for line in stripped.splitlines():
        if line.startswith("data:"):
            data_lines.append(line.removeprefix("data:").strip())
    if data_lines:
        return json.loads("\n".join(data_lines))
    return json.loads(stripped)


def _extract_rows(payload: Any) -> list[dict[str, Any]] | None:
    if isinstance(payload, list):
        return [dict(row) for row in payload if isinstance(row, dict)]
    if not isinstance(payload, dict):
        return None
    for key in ("rows", "data"):
        value = payload.get(key)
        if isinstance(value, list):
            return [dict(row) for row in value if isinstance(row, dict)]
        if isinstance(value, dict):
            rows = _extract_rows(value)
            if rows is not None:
                return rows
    structured = payload.get("structuredContent")
    if isinstance(structured, dict):
        rows = _extract_rows(structured)
        if rows is not None:
            return rows
    content = payload.get("content")
    if isinstance(content, list):
        for item in content:
            if not isinstance(item, dict):
                continue
            text = item.get("text")
            if not isinstance(text, str):
                continue
            try:
                parsed = json.loads(text)
            except json.JSONDecodeError:
                continue
            rows = _extract_rows(parsed)
            if rows is not None:
                return rows
    return None


def _extract_error(payload: Any) -> str:
    if isinstance(payload, dict):
        value = payload.get("error")
        if value:
            return str(value)
        for item in payload.get("content") or []:
            if not isinstance(item, dict):
                continue
            text = item.get("text")
            if not isinstance(text, str) or not text.strip():
                continue
            try:
                parsed = json.loads(text)
            except json.JSONDecodeError:
                return text.strip()
            nested = _extract_error(parsed)
            if nested:
                return nested
    return ""
