"""Agent 工具注册和模型 Schema 生成。"""

from __future__ import annotations

from collections.abc import Iterable

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import AgentTool


class ToolRegistryError(ValueError):
    pass


class ToolRegistry:
    def __init__(self, tools: Iterable[AgentTool] | None = None) -> None:
        self._tools: dict[str, AgentTool] = {}
        for tool in tools or []:
            self.register(tool)

    def register(self, tool: AgentTool) -> None:
        if tool.name in self._tools:
            raise ToolRegistryError(f"工具名称重复：{tool.name}")
        self._tools[tool.name] = tool

    def get(self, name: str) -> AgentTool:
        try:
            return self._tools[name]
        except KeyError as exc:
            raise ToolRegistryError(f"工具未注册：{name}") from exc

    def all(self) -> list[AgentTool]:
        return list(self._tools.values())

    def list_for_context(
        self,
        context: AgentRuntimeContext,
        state: AgentRunState,
    ) -> list[AgentTool]:
        result: list[AgentTool] = []
        for tool in self._tools.values():
            if not tool.required_permissions.issubset(context.permissions):
                continue
            if tool.availability is not None and not tool.availability(context, state):
                continue
            result.append(tool)
        return result

    @staticmethod
    def to_ollama_schema(tools: Iterable[AgentTool]) -> list[dict]:
        return [
            {
                "type": "function",
                "function": {
                    "name": tool.name,
                    "description": tool.description,
                    "parameters": tool.input_model.model_json_schema(),
                },
            }
            for tool in tools
        ]
