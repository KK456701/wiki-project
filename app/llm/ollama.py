from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from typing import Iterator


class OllamaError(RuntimeError):
    """Raised when the local Ollama server cannot produce a response."""


class OllamaClient:
    def __init__(
        self,
        model: str | None = None,
        base_url: str | None = None,
        timeout_seconds: float | None = None,
    ) -> None:
        self.model = model or os.getenv("OLLAMA_MODEL", "qwen3:4B-instruct")
        self.base_url = (base_url or os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434")).rstrip("/")
        self.timeout_seconds = float(timeout_seconds or os.getenv("OLLAMA_TIMEOUT_SECONDS", "20"))

    def generate(self, prompt: str) -> str:
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": False,
            "options": {
                "temperature": 0.2,
                "num_predict": 700,
            },
        }
        request = urllib.request.Request(
            f"{self.base_url}/api/generate",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                data = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            raise OllamaError(str(exc)) from exc
        text = str(data.get("response", "")).strip()
        if not text:
            raise OllamaError("empty ollama response")
        return text

    def generate_stream(self, prompt: str) -> Iterator[str]:
        """Stream tokens from Ollama one by one. 真正的逐 token 流式输出."""
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": True,
            "options": {
                "temperature": 0.2,
                "num_predict": 700,
            },
        }
        request = urllib.request.Request(
            f"{self.base_url}/api/generate",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                for line in response:
                    try:
                        data = json.loads(line.decode("utf-8"))
                    except json.JSONDecodeError:
                        continue
                    token = str(data.get("response", ""))
                    if token:
                        yield token
                    if data.get("done", False):
                        break
        except (urllib.error.URLError, TimeoutError) as exc:
            raise OllamaError(str(exc)) from exc


    