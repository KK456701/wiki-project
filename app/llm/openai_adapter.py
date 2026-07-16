"""OpenAI-compatible chat completions adapter for Agent tool calling."""

from __future__ import annotations

import asyncio
import json
import re
import urllib.error
import urllib.request
from collections import defaultdict, deque
from typing import Any

from app.agent_runtime.contracts import AgentModelResponse, AgentToolCall
from app.agent_runtime.model_adapter import AgentModelError
from app.config import get_int


class OpenAICompatibleError(RuntimeError):
    """Raised when an OpenAI-compatible endpoint cannot produce a response."""


def _safe_error_detail(value: str, *, limit: int = 240) -> str:
    detail = re.sub(r"\s+", " ", value or "").strip()
    detail = re.sub(r"(?i)(bearer\s+)[A-Za-z0-9._\-]+", r"\1***", detail)
    detail = re.sub(r"sk-[A-Za-z0-9._\-]+", "sk-***", detail)
    if len(detail) > limit:
        detail = f"{detail[:limit].rstrip()}..."
    return detail


def _tool_arguments(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return dict(value)
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError as exc:
            raise AgentModelError("模型返回了无效的工具参数。") from exc
        if isinstance(parsed, dict):
            return parsed
    raise AgentModelError("模型返回了无效的工具参数。")


def _openai_messages(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    converted: list[dict[str, Any]] = []
    pending_ids: dict[str, deque[str]] = defaultdict(deque)
    for message in messages:
        role = str(message.get("role") or "")
        content = str(message.get("content") or "")
        if role not in {"system", "user", "assistant", "tool"}:
            raise AgentModelError("模型消息角色无效。")
        if role == "assistant":
            item: dict[str, Any] = {"role": "assistant", "content": content}
            calls = []
            for index, call in enumerate(message.get("tool_calls") or []):
                name = str(call.get("name") or "")
                call_id = str(call.get("id") or f"call_{index + 1}")
                pending_ids[name].append(call_id)
                calls.append({
                    "id": call_id,
                    "type": "function",
                    "function": {
                        "name": name,
                        "arguments": json.dumps(
                            dict(call.get("arguments") or {}),
                            ensure_ascii=False,
                            separators=(",", ":"),
                        ),
                    },
                })
            if calls:
                item["tool_calls"] = calls
            converted.append(item)
            continue
        if role == "tool":
            tool_name = str(message.get("tool_name") or "")
            tool_call_id = (
                pending_ids[tool_name].popleft()
                if pending_ids.get(tool_name)
                else tool_name or "tool_call"
            )
            converted.append({
                "role": "tool",
                "tool_call_id": tool_call_id,
                "content": content,
            })
            continue
        converted.append({"role": role, "content": content})
    return converted


class OpenAICompatibleClient:
    def __init__(
        self,
        *,
        model: str,
        base_url: str,
        api_key: str,
        timeout_seconds: float | None = None,
    ) -> None:
        self.model = model
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout_seconds = float(
            timeout_seconds or get_int("agent_request_timeout_seconds", 120)
        )

    def chat(
        self,
        *,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        temperature: float = 0.0,
    ) -> dict[str, Any]:
        payload = {
            "model": self.model,
            "messages": messages,
            "tools": tools,
            "temperature": float(temperature),
            "thinking": {"type": "disabled"},
            "stream": False,
        }
        request = urllib.request.Request(
            f"{self.base_url}/chat/completions",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(
                request,
                timeout=self.timeout_seconds,
            ) as response:
                data = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body = ""
            try:
                body = exc.read().decode("utf-8", errors="replace")
            except Exception:
                body = ""
            status = getattr(exc, "code", None)
            message = f"HTTP {status}" if status else "HTTP error"
            detail = _safe_error_detail(body or str(exc))
            if detail:
                message = f"{message}: {detail}"
            raise OpenAICompatibleError(message) from exc
        except TimeoutError as exc:
            raise OpenAICompatibleError("请求超时") from exc
        except urllib.error.URLError as exc:
            raise OpenAICompatibleError("网络连接失败") from exc
        except json.JSONDecodeError as exc:
            raise OpenAICompatibleError("响应解析失败") from exc
        if not isinstance(data, dict) or not isinstance(data.get("choices"), list):
            raise OpenAICompatibleError("missing chat completion choices")
        return data


class OpenAICompatibleToolCallingAdapter:
    def __init__(self, client: OpenAICompatibleClient) -> None:
        self.client = client

    async def chat(
        self,
        *,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        temperature: float = 0.0,
    ) -> AgentModelResponse:
        try:
            data = await asyncio.to_thread(
                self.client.chat,
                messages=_openai_messages(messages),
                tools=tools,
                temperature=temperature,
            )
        except OpenAICompatibleError as exc:
            detail = _safe_error_detail(str(exc))
            message = "模型服务暂时不可用。"
            if detail:
                message = f"{message}（{detail}）"
            raise AgentModelError(message) from exc
        choice = (data.get("choices") or [{}])[0]
        message = choice.get("message") or {}
        calls: list[AgentToolCall] = []
        for index, raw_call in enumerate(message.get("tool_calls") or []):
            if not isinstance(raw_call, dict):
                raise AgentModelError("模型返回了无效的工具调用结构。")
            function = raw_call.get("function") or {}
            if not isinstance(function, dict):
                raise AgentModelError("模型返回了无效的工具调用结构。")
            name = str(function.get("name") or "").strip()
            if not name:
                raise AgentModelError("模型返回了无名称的工具调用。")
            calls.append(AgentToolCall(
                id=str(raw_call.get("id") or f"call_{index + 1}"),
                name=name,
                arguments=_tool_arguments(function.get("arguments") or {}),
            ))
        usage = data.get("usage") or {}
        return AgentModelResponse(
            content=str(message.get("content") or "").strip(),
            tool_calls=calls,
            model=str(data.get("model") or "") or None,
            usage={
                "prompt_tokens": int(usage.get("prompt_tokens") or 0),
                "completion_tokens": int(usage.get("completion_tokens") or 0),
            },
        )
