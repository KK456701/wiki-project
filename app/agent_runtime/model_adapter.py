"""AgentRunner 使用的模型无关接口。"""

from __future__ import annotations

from typing import Any, Protocol

from app.agent_runtime.contracts import AgentModelResponse


class AgentModelError(RuntimeError):
    pass


class AgentModelAdapter(Protocol):
    async def chat(
        self,
        *,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        temperature: float = 0.0,
    ) -> AgentModelResponse: ...
