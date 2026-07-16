import json
from unittest.mock import patch

import pytest

from app.llm.ollama import OllamaClient, OllamaError


class _Response:
    def __init__(self, body: bytes = b'{"response":"ok"}', lines=None):
        self.body = body
        self.lines = lines or []

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def read(self):
        return self.body

    def __iter__(self):
        return iter(self.lines)


def test_generate_sends_configured_context_window_and_timeout() -> None:
    client = OllamaClient(
        model="qwen3:4b-instruct",
        base_url="http://ollama.local",
        timeout_seconds=60,
        num_ctx=16384,
    )

    with patch(
        "app.llm.ollama.urllib.request.urlopen",
        return_value=_Response(),
    ) as urlopen:
        assert client.generate("hello") == "ok"

    request = urlopen.call_args.args[0]
    payload = json.loads(request.data.decode("utf-8"))
    assert payload["options"]["num_ctx"] == 16384
    assert urlopen.call_args.kwargs["timeout"] == 60


def test_stream_sends_same_context_window() -> None:
    client = OllamaClient(num_ctx=16384)
    response = _Response(
        lines=[
            b'{"response":"a","done":false}\n',
            b'{"response":"b","done":true}\n',
        ]
    )

    with patch(
        "app.llm.ollama.urllib.request.urlopen",
        return_value=response,
    ) as urlopen:
        assert "".join(client.generate_stream("hello")) == "ab"

    request = urlopen.call_args.args[0]
    payload = json.loads(request.data.decode("utf-8"))
    assert payload["options"]["num_ctx"] == 16384


def test_chat_posts_tools_to_chat_endpoint_with_configured_options() -> None:
    response = _Response(body=json.dumps({
        "model": "qwen3:4b-instruct",
        "message": {
            "role": "assistant",
            "content": "",
            "tool_calls": [{
                "function": {
                    "name": "search_indicator_rules",
                    "arguments": {"query": "急会诊"},
                }
            }],
        },
    }, ensure_ascii=False).encode("utf-8"))
    client = OllamaClient(
        model="qwen3:4b-instruct",
        base_url="http://ollama.local",
        timeout_seconds=30,
        num_ctx=16384,
    )

    with patch(
        "app.llm.ollama.urllib.request.urlopen",
        return_value=response,
    ) as urlopen:
        result = client.chat(
            messages=[{"role": "user", "content": "急会诊怎么算"}],
            tools=[{
                "type": "function",
                "function": {
                    "name": "search_indicator_rules",
                    "description": "搜索指标",
                    "parameters": {"type": "object", "properties": {}},
                },
            }],
            temperature=0.0,
        )

    request = urlopen.call_args.args[0]
    payload = json.loads(request.data.decode("utf-8"))
    assert request.full_url == "http://ollama.local/api/chat"
    assert payload["stream"] is False
    assert payload["tools"][0]["function"]["name"] == "search_indicator_rules"
    assert payload["options"]["temperature"] == 0.0
    assert payload["options"]["num_ctx"] == 16384
    assert urlopen.call_args.kwargs["timeout"] == 30
    assert result["message"]["tool_calls"]


def test_chat_rejects_response_without_message() -> None:
    client = OllamaClient(base_url="http://ollama.local")
    with patch(
        "app.llm.ollama.urllib.request.urlopen",
        return_value=_Response(body=b'{"done":true}'),
    ):
        with pytest.raises(OllamaError, match="missing ollama chat message"):
            client.chat(messages=[], tools=[])


@pytest.mark.parametrize(
    ("thinking", "expected"),
    [(True, True), (False, None)],
)
def test_chat_only_sends_think_for_thinking_models(thinking, expected) -> None:
    client = OllamaClient(
        model="qwen3:8b" if thinking else "qwen3:4b-instruct",
        base_url="http://ollama.local",
        thinking=thinking,
    )
    response = _Response(body=b'{"message":{"role":"assistant","content":"ok"}}')

    with patch(
        "app.llm.ollama.urllib.request.urlopen",
        return_value=response,
    ) as urlopen:
        client.chat(messages=[{"role": "user", "content": "hello"}], tools=[])

    payload = json.loads(urlopen.call_args.args[0].data.decode("utf-8"))
    assert payload.get("think") is expected
    assert "thinking" not in payload
