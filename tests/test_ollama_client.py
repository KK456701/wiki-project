import json
from unittest.mock import patch

from app.llm.ollama import OllamaClient


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
