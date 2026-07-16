import asyncio
import json
import urllib.error
from unittest.mock import patch

import pytest

from app.agent_runtime.model_adapter import AgentModelError
from app.llm.openai_adapter import OpenAICompatibleClient, OpenAICompatibleToolCallingAdapter


class _Response:
    def __init__(self, body: dict):
        self.body = json.dumps(body, ensure_ascii=False).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def read(self):
        return self.body

    def close(self):
        return None


def test_openai_client_posts_chat_completion_with_tools_and_auth() -> None:
    client = OpenAICompatibleClient(
        model="deepseek-v4-pro",
        base_url="https://api.deepseek.com",
        api_key="secret-key",
        timeout_seconds=30,
    )

    with patch(
        "app.llm.openai_adapter.urllib.request.urlopen",
        return_value=_Response({
            "model": "deepseek-v4-pro",
            "choices": [{"message": {"role": "assistant", "content": "ok"}}],
            "usage": {"prompt_tokens": 3, "completion_tokens": 2},
        }),
    ) as urlopen:
        result = client.chat(
            messages=[{"role": "user", "content": "hello"}],
            tools=[{"type": "function", "function": {"name": "search_indicator_rules"}}],
            temperature=0.0,
        )

    request = urlopen.call_args.args[0]
    payload = json.loads(request.data.decode("utf-8"))
    assert request.full_url == "https://api.deepseek.com/chat/completions"
    assert request.headers["Authorization"] == "Bearer secret-key"
    assert payload["model"] == "deepseek-v4-pro"
    assert payload["thinking"] == {"type": "disabled"}
    assert payload["tools"][0]["function"]["name"] == "search_indicator_rules"
    assert payload["temperature"] == 0.0
    assert urlopen.call_args.kwargs["timeout"] == 30
    assert result["choices"][0]["message"]["content"] == "ok"


def test_openai_adapter_parses_tool_calls_and_usage() -> None:
    client = OpenAICompatibleClient(
        model="deepseek-v4-pro",
        base_url="https://api.deepseek.com",
        api_key="secret-key",
    )
    with patch(
        "app.llm.openai_adapter.urllib.request.urlopen",
        return_value=_Response({
            "model": "deepseek-v4-pro",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": {
                            "name": "get_effective_rule",
                            "arguments": "{\"rule_id\":\"MQSI2025_005\"}",
                        },
                    }],
                }
            }],
            "usage": {"prompt_tokens": 11, "completion_tokens": 7},
        }),
    ):
        response = asyncio.run(OpenAICompatibleToolCallingAdapter(client).chat(
            messages=[{"role": "user", "content": "规则"}],
            tools=[],
        ))

    assert response.model == "deepseek-v4-pro"
    assert response.tool_calls[0].id == "call_1"
    assert response.tool_calls[0].name == "get_effective_rule"
    assert response.tool_calls[0].arguments == {"rule_id": "MQSI2025_005"}
    assert response.usage == {"prompt_tokens": 11, "completion_tokens": 7}


def test_openai_adapter_rejects_invalid_tool_arguments() -> None:
    client = OpenAICompatibleClient(
        model="deepseek-v4-pro",
        base_url="https://api.deepseek.com",
        api_key="secret-key",
    )
    with patch(
        "app.llm.openai_adapter.urllib.request.urlopen",
        return_value=_Response({
            "choices": [{
                "message": {
                    "tool_calls": [{
                        "type": "function",
                        "function": {
                            "name": "search_indicator_rules",
                            "arguments": "not-json",
                        },
                    }],
                }
            }],
        }),
    ):
        with pytest.raises(AgentModelError):
            asyncio.run(
                OpenAICompatibleToolCallingAdapter(client).chat(messages=[], tools=[])
            )


def test_openai_adapter_hides_internal_error() -> None:
    client = OpenAICompatibleClient(
        model="deepseek-v4-pro",
        base_url="https://api.deepseek.com",
        api_key="secret-key",
    )
    with patch(
        "app.llm.openai_adapter.urllib.request.urlopen",
        side_effect=TimeoutError("secret-key"),
    ):
        with pytest.raises(AgentModelError, match="模型服务暂时不可用") as raised:
            asyncio.run(
                OpenAICompatibleToolCallingAdapter(client).chat(messages=[], tools=[])
            )
    assert "secret-key" not in str(raised.value)


def test_openai_adapter_surfaces_sanitized_http_error_body() -> None:
    client = OpenAICompatibleClient(
        model="deepseek-v4-pro",
        base_url="https://api.deepseek.com",
        api_key="secret-key",
    )
    http_error = urllib.error.HTTPError(
        "https://api.deepseek.com/chat/completions",
        400,
        "Bad Request",
        {},
        _Response({
            "error": {
                "message": "invalid tool_call_id, Authorization: Bearer secret-key"
            }
        }),
    )

    with patch(
        "app.llm.openai_adapter.urllib.request.urlopen",
        side_effect=http_error,
    ):
        with pytest.raises(AgentModelError) as raised:
            asyncio.run(
                OpenAICompatibleToolCallingAdapter(client).chat(messages=[], tools=[])
            )

    message = str(raised.value)
    assert "HTTP 400" in message
    assert "invalid tool_call_id" in message
    assert "secret-key" not in message
