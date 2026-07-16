"""Config-driven model registry for Agent model adapters."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from typing import Any, Literal

from app import config
from app.agent_runtime.model_adapter import AgentModelAdapter, AgentModelError
from app.llm.ollama import OllamaClient
from app.llm.ollama_tools import OllamaToolCallingAdapter
from app.llm.openai_adapter import (
    OpenAICompatibleClient,
    OpenAICompatibleToolCallingAdapter,
)


_ENV_REF = re.compile(r"^\$\{([A-Za-z_][A-Za-z0-9_]*)\}$")


@dataclass(frozen=True, slots=True)
class ModelInfo:
    id: str
    name: str
    provider: str
    model: str
    base_url: str
    api_key: str | None = None
    thinking: bool | None = None
    planner_thinking: bool | None = None
    call_timeout_seconds: float | None = None
    request_timeout_seconds: int | None = None

    def model_dump_public(self) -> dict[str, str]:
        return {
            "id": self.id,
            "name": self.name,
            "provider": self.provider,
        }


class ModelRegistry:
    def __init__(self, raw_config: dict[str, Any] | None = None) -> None:
        self.raw_config = dict(raw_config or {})
        self._models = self._load_models()
        default_id = str(self.raw_config.get("default_model") or "").strip()
        self.default_model_id = (
            default_id
            if default_id and any(model.id == default_id for model in self._models)
            else self._models[0].id
        )

    def _load_models(self) -> list[ModelInfo]:
        raw_models = self.raw_config.get("models")
        if isinstance(raw_models, list) and raw_models:
            models = [
                self._parse_model(item)
                for item in raw_models
                if isinstance(item, dict)
            ]
            if models:
                return models
        fallback_model = str(
            self.raw_config.get("agent_model")
            or self.raw_config.get("ollama_model")
            or "qwen3:4B-instruct"
        )
        return [ModelInfo(
            id=fallback_model,
            name=f"{fallback_model}（本地 Ollama）",
            provider="ollama",
            model=fallback_model,
            base_url=str(
                self.raw_config.get("ollama_base_url")
                or "http://127.0.0.1:11434"
            ),
            thinking=None,
        )]

    def _parse_model(self, item: dict[str, Any]) -> ModelInfo:
        model_id = str(item.get("id") or item.get("model") or "").strip()
        provider = str(item.get("provider") or "").strip().lower()
        model = str(item.get("model") or "").strip()
        base_url = str(item.get("base_url") or "").strip()
        if not model_id or provider not in {"ollama", "openai"} or not model:
            raise AgentModelError("模型配置不完整或 provider 不受支持。")
        if not base_url:
            base_url = (
                "http://127.0.0.1:11434"
                if provider == "ollama"
                else "https://api.openai.com/v1"
            )
        api_key = self._resolve_secret(item.get("api_key"))
        return ModelInfo(
            id=model_id,
            name=str(item.get("name") or model_id),
            provider=provider,
            model=model,
            base_url=base_url,
            api_key=api_key,
            thinking=(
                bool(item.get("thinking")) if "thinking" in item else None
            ),
            planner_thinking=(
                bool(item.get("planner_thinking"))
                if "planner_thinking" in item
                else None
            ),
            call_timeout_seconds=(
                float(item["call_timeout_seconds"])
                if item.get("call_timeout_seconds") is not None
                else None
            ),
            request_timeout_seconds=(
                int(item["request_timeout_seconds"])
                if item.get("request_timeout_seconds") is not None
                else None
            ),
        )

    @staticmethod
    def _resolve_secret(value: Any) -> str | None:
        if value is None:
            return None
        raw = str(value).strip()
        match = _ENV_REF.match(raw)
        if match:
            env_name = match.group(1)
            return os.getenv(env_name) or f"${{{env_name}}}"
        return raw

    def list_models(self) -> list[ModelInfo]:
        return list(self._models)

    def get_model(self, model_id: str | None = None) -> ModelInfo:
        target = str(model_id or self.default_model_id or "").strip()
        for model in self._models:
            if model.id == target:
                return model
        raise AgentModelError(f"未知模型：{target}")

    def build_adapter(
        self,
        model_id: str | None = None,
        *,
        role: Literal["planner", "executor"] = "executor",
    ) -> AgentModelAdapter:
        info = self.get_model(model_id)
        if info.provider == "ollama":
            thinking = (
                info.planner_thinking if role == "planner" else info.thinking
            )
            return OllamaToolCallingAdapter(OllamaClient(
                model=info.model,
                base_url=info.base_url,
                timeout_seconds=info.call_timeout_seconds,
                thinking=thinking,
            ))
        if not info.api_key or _ENV_REF.match(info.api_key):
            missing = info.api_key or "API Key"
            raise AgentModelError(f"模型 {info.id} 缺少 API Key：{missing}")
        return OpenAICompatibleToolCallingAdapter(OpenAICompatibleClient(
            model=info.model,
            base_url=info.base_url,
            api_key=info.api_key,
            timeout_seconds=info.call_timeout_seconds,
        ))


_registry: ModelRegistry | None = None


def get_model_registry() -> ModelRegistry:
    global _registry
    if _registry is None:
        _registry = ModelRegistry(config._load_config())
    return _registry
