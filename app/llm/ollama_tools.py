"""Ollama 私有工具调用协议到 Agent 契约的适配器。"""

from __future__ import annotations

import asyncio
import json
from typing import Any

from app.agent_runtime.contracts import AgentModelResponse, AgentToolCall
from app.agent_runtime.model_adapter import AgentModelError
from app.llm.ollama import OllamaClient, OllamaError


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


def _ollama_messages(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    converted: list[dict[str, Any]] = []
    for message in messages:
        role = str(message.get("role") or "")
        if role not in {"system", "user", "assistant", "tool"}:
            raise AgentModelError("模型消息角色无效。")
        item: dict[str, Any] = {
            "role": role,
            "content": str(message.get("content") or ""),
        }
        if role == "assistant" and message.get("tool_calls"):
            item["tool_calls"] = [
                {
                    "function": {
                        "name": str(call["name"]),
                        "arguments": dict(call.get("arguments") or {}),
                    }
                }
                for call in message["tool_calls"]
            ]
        if role == "tool":
            item["tool_name"] = str(message.get("tool_name") or "")
        converted.append(item)
    return converted


class OllamaToolCallingAdapter:
    def __init__(self, client: OllamaClient | None = None) -> None:
        self.client = client or OllamaClient()

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
                messages=_ollama_messages(messages),
                tools=tools,
                temperature=temperature,
            )
        except OllamaError as exc:
            raise AgentModelError("模型服务暂时不可用。") from exc
        message = data.get("message") or {}
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
        usage: dict[str, Any] = {
            "prompt_tokens": int(data.get("prompt_eval_count") or 0),
            "completion_tokens": int(data.get("eval_count") or 0),
        }
        if "thinking" in message:
            thinking = str(message.get("thinking") or "")
            usage["thinking_present"] = bool(thinking)
            usage["thinking_chars"] = len(thinking)
        if data.get("done_reason") is not None:
            usage["done_reason"] = str(data.get("done_reason") or "")
        return AgentModelResponse(
            content=str(message.get("content") or "").strip(),
            tool_calls=calls,
            model=str(data.get("model") or "") or None,
            usage=usage,
        )
