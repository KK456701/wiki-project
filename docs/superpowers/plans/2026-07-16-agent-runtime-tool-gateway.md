# Agent Runtime 与 Tool Gateway 基础设施 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立不接真实模型和数据库的 Agent Runtime 状态契约、工具契约、动态注册表、安全策略与异步 Tool Gateway，为后续 Ollama 工具调用循环提供稳定基础。

**Architecture:** 本计划只新增 `app/agent_runtime` 和 `app/agent_tools` 两个独立包，不修改旧 `graph.py`、API 和业务服务。Runtime 保存服务端注入上下文及单次运行状态；Registry 生成 Ollama 工具 Schema 并按权限/状态筛选；Gateway 统一执行参数校验、权限、重复调用、超时、线程适配、脱敏和结果标准化。

**Tech Stack:** Python 3.12.7、Pydantic 2.13.3、标准库 `asyncio`/`dataclasses`/`hashlib`/`inspect`、现有 `unittest`/`pytest` 测试体系。

## Global Constraints

- 默认中文注释、错误说明、测试名称语义和提交主题。
- 不修改或删除 `/api/chat`、`/api/chat/stream`、`app/agent/graph.py` 和旧工作流。
- 不增加第三方依赖，不接 Ollama、DBHub、MySQL 或 SQL Server。
- `user_id`、`hospital_id`、角色和权限只能来自 `AgentRuntimeContext`，不能出现在工具输入 Schema 中。
- 所有 Pydantic 边界模型使用 `extra="forbid"`；集合和列表使用 `default_factory`。
- Gateway 必须同时支持同步和异步 handler；同步 handler 通过 `asyncio.to_thread` 执行。
- 超时、重复调用、权限拒绝和执行异常统一转换为 `ToolResult`，不得向调用方抛出内部堆栈。
- Trace 回调只能收到脱敏参数和脱敏结果；密码、令牌、连接信息和 `sql_text` 必须替换为 `[REDACTED]`。
- 本计划不实现正式规则写入、审批、发布、恢复或匿名 Agent API。
- 每项任务遵循 TDD：先看到预期失败，再写最小实现，再运行匹配测试并单独提交推送。

## File Structure

```text
app/
├── agent_runtime/
│   ├── __init__.py        对外导出运行上下文、运行状态和模型响应契约
│   └── contracts.py       AgentRuntimeContext、AgentRunState、AgentModelResponse
└── agent_tools/
    ├── __init__.py        对外导出工具契约、Registry、Policy、Gateway
    ├── contracts.py       ToolResult、ToolEvidence、AgentTool、风险和状态枚举
    ├── registry.py        工具注册、动态筛选和 Ollama Schema 生成
    ├── policy.py          调用指纹、重复调用决策和递归脱敏
    └── gateway.py         参数/权限/超时/线程/异常/Trace 的统一执行边界

tests/
├── test_agent_runtime_contracts.py
├── test_agent_tool_registry.py
├── test_agent_tool_policy.py
├── test_agent_tool_gateway.py
└── test_agent_runtime_foundation.py
```

---

### Task 1: 建立 Agent Runtime 契约

**Files:**
- Create: `app/agent_runtime/__init__.py`
- Create: `app/agent_runtime/contracts.py`
- Test: `tests/test_agent_runtime_contracts.py`

**Interfaces:**
- Consumes: Pydantic `BaseModel`、`ConfigDict`、`Field`。
- Produces: `AgentRuntimeContext`、`AgentRunState`、`AgentToolCall`、`AgentModelResponse`、`AgentStopReason`，供 Registry、Gateway 和后续 Runner 使用。

- [ ] **Step 1: 写入失败测试**

```python
import unittest

from pydantic import ValidationError


class AgentRuntimeContractsTest(unittest.TestCase):
    def test_runtime_context_rejects_model_supplied_extra_fields_and_is_frozen(self) -> None:
        from app.agent_runtime.contracts import AgentRuntimeContext

        context = AgentRuntimeContext(
            user_id="user_001",
            hospital_id="hospital_001",
            session_id="session_001",
            user_role="implementer",
            permissions=frozenset({"indicator_read"}),
            request_id="REQ_001",
            trace_id="TRACE_001",
        )

        self.assertEqual(context.hospital_id, "hospital_001")
        with self.assertRaises(ValidationError):
            AgentRuntimeContext(
                user_id="user_001",
                hospital_id="hospital_001",
                session_id="session_001",
                user_role="implementer",
                request_id="REQ_001",
                trace_id="TRACE_001",
                database_password="secret",
            )
        with self.assertRaises(ValidationError):
            context.hospital_id = "hospital_002"

    def test_run_state_uses_isolated_mutable_defaults(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        first = AgentRunState()
        second = AgentRunState()
        first.messages.append({"role": "user", "content": "问题"})
        first.tool_call_counts["fingerprint"] = 1

        self.assertEqual(second.messages, [])
        self.assertEqual(second.tool_call_counts, {})

    def test_model_response_parses_nested_tool_calls(self) -> None:
        from app.agent_runtime.contracts import AgentModelResponse

        response = AgentModelResponse.model_validate(
            {
                "content": "",
                "tool_calls": [
                    {
                        "id": "call_001",
                        "name": "search_indicator_rules",
                        "arguments": {"query": "急会诊及时到位率"},
                    }
                ],
                "model": "qwen3:4b-instruct",
            }
        )

        self.assertEqual(response.tool_calls[0].name, "search_indicator_rules")
        self.assertEqual(response.tool_calls[0].arguments["query"], "急会诊及时到位率")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 运行测试并确认按预期失败**

Run: `python -m pytest tests/test_agent_runtime_contracts.py -q`

Expected: FAIL，错误包含 `ModuleNotFoundError: No module named 'app.agent_runtime'`。

- [ ] **Step 3: 实现最小运行时契约**

```python
# app/agent_runtime/contracts.py
"""工具调用型 Agent 的服务端运行契约。"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


AgentStopReason = Literal[
    "final_answer",
    "need_clarification",
    "max_steps",
    "repeated_tool_call",
    "tool_error",
    "request_timeout",
    "cancelled",
    "context_conflict",
]


class RuntimeContract(BaseModel):
    model_config = ConfigDict(extra="forbid", validate_assignment=True)


class AgentRuntimeContext(RuntimeContract):
    model_config = ConfigDict(extra="forbid", frozen=True)

    user_id: str
    hospital_id: str
    session_id: str
    user_role: str
    permissions: frozenset[str] = Field(default_factory=frozenset)
    request_id: str
    trace_id: str
    db_source_id: str | None = None


class AgentRunState(RuntimeContract):
    messages: list[dict[str, Any]] = Field(default_factory=list)
    step_count: int = 0
    tool_call_counts: dict[str, int] = Field(default_factory=dict)
    evidence: list[dict[str, Any]] = Field(default_factory=list)
    last_tool_results: list[dict[str, Any]] = Field(default_factory=list)
    stop_reason: AgentStopReason | None = None
    cancelled: bool = False


class AgentToolCall(RuntimeContract):
    id: str | None = None
    name: str
    arguments: dict[str, Any] = Field(default_factory=dict)


class AgentModelResponse(RuntimeContract):
    content: str = ""
    tool_calls: list[AgentToolCall] = Field(default_factory=list)
    model: str | None = None
    usage: dict[str, Any] = Field(default_factory=dict)
```

```python
# app/agent_runtime/__init__.py
from .contracts import (
    AgentModelResponse,
    AgentRunState,
    AgentRuntimeContext,
    AgentStopReason,
    AgentToolCall,
)

__all__ = [
    "AgentModelResponse",
    "AgentRunState",
    "AgentRuntimeContext",
    "AgentStopReason",
    "AgentToolCall",
]
```

- [ ] **Step 4: 运行测试并确认通过**

Run: `python -m pytest tests/test_agent_runtime_contracts.py -q`

Expected: `3 passed`。

- [ ] **Step 5: 检查差异并提交**

```powershell
git diff --check
git add app/agent_runtime/__init__.py app/agent_runtime/contracts.py tests/test_agent_runtime_contracts.py
git commit -m "refactor: 建立 Agent Runtime 运行契约"
git push
```

### Task 2: 建立工具契约和动态 Registry

**Files:**
- Create: `app/agent_tools/__init__.py`
- Create: `app/agent_tools/contracts.py`
- Create: `app/agent_tools/registry.py`
- Test: `tests/test_agent_tool_registry.py`

**Interfaces:**
- Consumes: Task 1 的 `AgentRuntimeContext`、`AgentRunState`。
- Produces: `AgentTool`、`ToolResult`、`ToolEvidence`、`ToolRiskLevel`、`ToolRegistry`、`ToolRegistryError`、Ollama function Schema。

- [ ] **Step 1: 写入 Registry 失败测试**

```python
import unittest

from pydantic import BaseModel, ConfigDict


class SearchInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str


def _handler(arguments, context, state):
    return {"ok": True, "status": "success", "code": "OK", "summary": arguments.query}


class AgentToolRegistryTest(unittest.TestCase):
    def _tool(self, **overrides):
        from app.agent_tools.contracts import AgentTool, ToolRiskLevel

        payload = {
            "name": "search_indicator_rules",
            "description": "根据指标名称或同义词搜索核心制度指标。",
            "input_model": SearchInput,
            "handler": _handler,
            "risk_level": ToolRiskLevel.READ,
            "required_permissions": frozenset({"indicator_read"}),
        }
        payload.update(overrides)
        return AgentTool(**payload)

    def test_registry_rejects_duplicate_names(self) -> None:
        from app.agent_tools.registry import ToolRegistry, ToolRegistryError

        registry = ToolRegistry()
        registry.register(self._tool())
        with self.assertRaises(ToolRegistryError):
            registry.register(self._tool())

    def test_registry_emits_ollama_function_schema_without_runtime_context(self) -> None:
        from app.agent_tools.registry import ToolRegistry

        registry = ToolRegistry([self._tool()])
        schema = registry.to_ollama_schema(registry.all())

        self.assertEqual(schema[0]["type"], "function")
        self.assertEqual(schema[0]["function"]["name"], "search_indicator_rules")
        properties = schema[0]["function"]["parameters"]["properties"]
        self.assertEqual(set(properties), {"query"})
        self.assertNotIn("hospital_id", properties)
        self.assertNotIn("user_id", properties)

    def test_registry_filters_missing_permissions(self) -> None:
        from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
        from app.agent_tools.registry import ToolRegistry

        registry = ToolRegistry([self._tool()])
        context = AgentRuntimeContext(
            user_id="u1", hospital_id="h1", session_id="s1",
            user_role="doctor", permissions=frozenset(),
            request_id="r1", trace_id="t1",
        )

        self.assertEqual(registry.list_for_context(context, AgentRunState()), [])

    def test_registry_applies_state_availability(self) -> None:
        from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
        from app.agent_tools.registry import ToolRegistry

        tool = self._tool(
            availability=lambda _context, state: state.step_count > 0,
        )
        registry = ToolRegistry([tool])
        context = AgentRuntimeContext(
            user_id="u1", hospital_id="h1", session_id="s1",
            user_role="implementer", permissions=frozenset({"indicator_read"}),
            request_id="r1", trace_id="t1",
        )

        self.assertEqual(registry.list_for_context(context, AgentRunState()), [])
        self.assertEqual(
            [item.name for item in registry.list_for_context(context, AgentRunState(step_count=1))],
            ["search_indicator_rules"],
        )

    def test_tool_definition_rejects_invalid_name_and_timeout(self) -> None:
        with self.assertRaises(ValueError):
            self._tool(name="Invalid Tool")
        with self.assertRaises(ValueError):
            self._tool(timeout_seconds=0)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 运行测试并确认按预期失败**

Run: `python -m pytest tests/test_agent_tool_registry.py -q`

Expected: FAIL，错误包含 `ModuleNotFoundError: No module named 'app.agent_tools'`。

- [ ] **Step 3: 实现工具契约**

```python
# app/agent_tools/contracts.py
"""模型可见工具和统一结果契约。"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext


class ToolRiskLevel(str, Enum):
    READ = "read"
    CONTROLLED_EXECUTION = "controlled_execution"
    PREVIEW_ONLY = "preview_only"


class ToolContract(BaseModel):
    model_config = ConfigDict(extra="forbid", validate_assignment=True)


ToolStatus = Literal[
    "success",
    "not_found",
    "need_clarification",
    "preview_ready",
    "validation_failed",
    "forbidden",
    "unavailable",
    "timeout",
    "cancelled",
    "error",
]


class ToolEvidence(ToolContract):
    source: str
    source_id: str | None = None
    version: str | None = None
    fact_types: list[str] = Field(default_factory=list)


class ToolResult(ToolContract):
    ok: bool
    status: ToolStatus
    code: str
    summary: str
    data: dict[str, Any] = Field(default_factory=dict)
    evidence: list[ToolEvidence] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    retryable: bool = False


ToolHandler = Callable[[BaseModel, AgentRuntimeContext, AgentRunState], Any]
ToolAvailability = Callable[[AgentRuntimeContext, AgentRunState], bool]


@dataclass(frozen=True, slots=True)
class AgentTool:
    name: str
    description: str
    input_model: type[BaseModel]
    handler: ToolHandler
    risk_level: ToolRiskLevel
    timeout_seconds: float = 30.0
    required_permissions: frozenset[str] = field(default_factory=frozenset)
    availability: ToolAvailability | None = None

    def __post_init__(self) -> None:
        if not re.fullmatch(r"[a-z][a-z0-9_]{2,63}", self.name):
            raise ValueError("工具名称必须是 3 到 64 位小写 snake_case")
        if not self.description.strip():
            raise ValueError("工具描述不能为空")
        if self.timeout_seconds <= 0:
            raise ValueError("工具超时必须大于 0 秒")
        if not issubclass(self.input_model, BaseModel):
            raise ValueError("工具输入必须是 Pydantic BaseModel")
```

- [ ] **Step 4: 实现动态 Registry**

```python
# app/agent_tools/registry.py
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
```

```python
# app/agent_tools/__init__.py（Task 2 首次内容）
from .contracts import AgentTool, ToolEvidence, ToolResult, ToolRiskLevel
from .registry import ToolRegistry, ToolRegistryError

__all__ = [
    "AgentTool",
    "ToolEvidence",
    "ToolRegistry",
    "ToolRegistryError",
    "ToolResult",
    "ToolRiskLevel",
]
```

- [ ] **Step 5: 运行测试并确认通过**

Run: `python -m pytest tests/test_agent_tool_registry.py -q`

Expected: `5 passed`。

- [ ] **Step 6: 检查差异并提交**

```powershell
git diff --check
git add app/agent_tools/__init__.py app/agent_tools/contracts.py app/agent_tools/registry.py tests/test_agent_tool_registry.py
git commit -m "refactor: 建立 Agent 工具契约与注册表"
git push
```

### Task 3: 建立重复调用和脱敏策略

**Files:**
- Create: `app/agent_tools/policy.py`
- Modify: `app/agent_tools/__init__.py`
- Test: `tests/test_agent_tool_policy.py`

**Interfaces:**
- Consumes: `AgentRunState.tool_call_counts` 和任意 JSON 兼容工具参数。
- Produces: `RepeatDecision`、`ToolExecutionPolicy.note_call()`、`tool_call_fingerprint()`、`redact_payload()`，供 Task 4 Gateway 使用。

- [ ] **Step 1: 写入失败测试**

```python
import unittest


class AgentToolPolicyTest(unittest.TestCase):
    def test_fingerprint_is_stable_across_argument_key_order(self) -> None:
        from app.agent_tools.policy import tool_call_fingerprint

        left = tool_call_fingerprint("search_indicator_rules", {"query": "急会诊", "limit": 5})
        right = tool_call_fingerprint("search_indicator_rules", {"limit": 5, "query": "急会诊"})

        self.assertEqual(left, right)

    def test_policy_allows_first_warns_second_and_stops_third_call(self) -> None:
        from app.agent_runtime.contracts import AgentRunState
        from app.agent_tools.policy import RepeatDecision, ToolExecutionPolicy

        state = AgentRunState()
        policy = ToolExecutionPolicy()
        arguments = {"query": "急会诊"}

        self.assertEqual(policy.note_call(state, "search_indicator_rules", arguments), RepeatDecision.ALLOW)
        self.assertEqual(policy.note_call(state, "search_indicator_rules", arguments), RepeatDecision.DUPLICATE)
        self.assertEqual(policy.note_call(state, "search_indicator_rules", arguments), RepeatDecision.STOP)
        self.assertEqual(state.stop_reason, "repeated_tool_call")

    def test_redaction_masks_nested_sensitive_values_without_changing_safe_fields(self) -> None:
        from app.agent_tools.policy import redact_payload

        payload = {
            "query": "急会诊",
            "authorization": "Bearer secret",
            "nested": {
                "database_password": "123456",
                "sql_text": "SELECT * FROM patient",
                "safe_count": 3,
            },
        }

        redacted = redact_payload(payload)

        self.assertEqual(redacted["query"], "急会诊")
        self.assertEqual(redacted["authorization"], "[REDACTED]")
        self.assertEqual(redacted["nested"]["database_password"], "[REDACTED]")
        self.assertEqual(redacted["nested"]["sql_text"], "[REDACTED]")
        self.assertEqual(redacted["nested"]["safe_count"], 3)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 运行测试并确认按预期失败**

Run: `python -m pytest tests/test_agent_tool_policy.py -q`

Expected: FAIL，错误包含 `ModuleNotFoundError: No module named 'app.agent_tools.policy'`。

- [ ] **Step 3: 实现指纹、重复决策和递归脱敏**

```python
# app/agent_tools/policy.py
"""Tool Gateway 使用的确定性安全策略。"""

from __future__ import annotations

import hashlib
import json
from enum import Enum
from typing import Any

from app.agent_runtime.contracts import AgentRunState


_SENSITIVE_KEY_PARTS = (
    "password",
    "secret",
    "token",
    "authorization",
    "connection",
    "db_url",
    "sql_text",
)


class RepeatDecision(str, Enum):
    ALLOW = "allow"
    DUPLICATE = "duplicate"
    STOP = "stop"


def tool_call_fingerprint(tool_name: str, arguments: dict[str, Any]) -> str:
    canonical = json.dumps(arguments, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(f"{tool_name}:{canonical}".encode("utf-8")).hexdigest()


def redact_payload(value: Any, key: str = "") -> Any:
    normalized_key = key.lower()
    if any(part in normalized_key for part in _SENSITIVE_KEY_PARTS):
        return "[REDACTED]"
    if isinstance(value, dict):
        return {str(item_key): redact_payload(item_value, str(item_key)) for item_key, item_value in value.items()}
    if isinstance(value, list):
        return [redact_payload(item) for item in value]
    if isinstance(value, tuple):
        return [redact_payload(item) for item in value]
    return value


class ToolExecutionPolicy:
    def note_call(
        self,
        state: AgentRunState,
        tool_name: str,
        arguments: dict[str, Any],
    ) -> RepeatDecision:
        fingerprint = tool_call_fingerprint(tool_name, arguments)
        previous = state.tool_call_counts.get(fingerprint, 0)
        state.tool_call_counts[fingerprint] = previous + 1
        if previous == 0:
            return RepeatDecision.ALLOW
        if previous == 1:
            return RepeatDecision.DUPLICATE
        state.stop_reason = "repeated_tool_call"
        return RepeatDecision.STOP
```

- [ ] **Step 4: 更新工具包导出**

在 `app/agent_tools/__init__.py` 增加：

```python
from .policy import RepeatDecision, ToolExecutionPolicy, redact_payload, tool_call_fingerprint
```

并把以下名称加入 `__all__`：

```python
"RepeatDecision",
"ToolExecutionPolicy",
"redact_payload",
"tool_call_fingerprint",
```

- [ ] **Step 5: 运行测试并确认通过**

Run: `python -m pytest tests/test_agent_tool_policy.py -q`

Expected: `3 passed`。

- [ ] **Step 6: 检查差异并提交**

```powershell
git diff --check
git add app/agent_tools/__init__.py app/agent_tools/policy.py tests/test_agent_tool_policy.py
git commit -m "refactor: 增加 Agent 工具重复调用与脱敏策略"
git push
```

### Task 4: 实现异步 Tool Gateway

**Files:**
- Create: `app/agent_tools/gateway.py`
- Modify: `app/agent_tools/__init__.py`
- Test: `tests/test_agent_tool_gateway.py`

**Interfaces:**
- Consumes: `ToolRegistry.get()`、`AgentTool`、`ToolExecutionPolicy`、`AgentRuntimeContext`、`AgentRunState`。
- Produces: `ToolGateway.execute(tool_name, raw_arguments, context, state) -> ToolResult`，供后续 AgentRunner 唯一调用。

- [ ] **Step 1: 写入 Gateway 失败测试**

```python
import asyncio
import unittest

from pydantic import BaseModel, ConfigDict


class QueryInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str


class AgentToolGatewayTest(unittest.IsolatedAsyncioTestCase):
    def _context(self, permissions=frozenset({"indicator_read"})):
        from app.agent_runtime.contracts import AgentRuntimeContext

        return AgentRuntimeContext(
            user_id="user_001", hospital_id="hospital_001", session_id="session_001",
            user_role="implementer", permissions=permissions,
            request_id="REQ_001", trace_id="TRACE_001",
        )

    def _gateway(self, handler, *, timeout=1.0, permissions=frozenset({"indicator_read"}), trace_events=None):
        from app.agent_tools.contracts import AgentTool, ToolRiskLevel
        from app.agent_tools.gateway import ToolGateway
        from app.agent_tools.registry import ToolRegistry

        tool = AgentTool(
            name="search_indicator_rules",
            description="搜索核心制度指标。",
            input_model=QueryInput,
            handler=handler,
            risk_level=ToolRiskLevel.READ,
            timeout_seconds=timeout,
            required_permissions=permissions,
        )
        return ToolGateway(
            ToolRegistry([tool]),
            trace_callback=(trace_events.append if trace_events is not None else None),
        )

    async def test_unknown_tool_returns_standard_result(self) -> None:
        from app.agent_runtime.contracts import AgentRunState
        from app.agent_tools.gateway import ToolGateway
        from app.agent_tools.registry import ToolRegistry

        result = await ToolGateway(ToolRegistry()).execute(
            "missing_tool", {}, self._context(), AgentRunState()
        )

        self.assertFalse(result.ok)
        self.assertEqual(result.code, "TOOL_NOT_FOUND")

    async def test_extra_hospital_id_is_rejected_before_handler(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        called = False
        def handler(arguments, context, state):
            nonlocal called
            called = True
            return {"ok": True, "status": "success", "code": "OK", "summary": "ok"}

        result = await self._gateway(handler).execute(
            "search_indicator_rules",
            {"query": "急会诊", "hospital_id": "hospital_002"},
            self._context(),
            AgentRunState(),
        )

        self.assertFalse(called)
        self.assertEqual(result.code, "INVALID_TOOL_ARGUMENTS")

    async def test_permission_is_checked_again_at_execution(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        result = await self._gateway(lambda *_: None).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(frozenset()), AgentRunState(),
        )

        self.assertEqual(result.code, "PERMISSION_DENIED")

    async def test_sync_handler_runs_and_receives_server_context(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        def handler(arguments, context, state):
            return {
                "ok": True,
                "status": "success",
                "code": "RULE_FOUND",
                "summary": f"{context.hospital_id}:{arguments.query}",
            }

        result = await self._gateway(handler).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(), AgentRunState(),
        )

        self.assertTrue(result.ok)
        self.assertEqual(result.summary, "hospital_001:急会诊")

    async def test_async_handler_runs(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        async def handler(arguments, context, state):
            return {"ok": True, "status": "success", "code": "OK", "summary": arguments.query}

        result = await self._gateway(handler).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(), AgentRunState(),
        )

        self.assertTrue(result.ok)

    async def test_timeout_is_standardized(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        async def handler(arguments, context, state):
            await asyncio.sleep(0.05)
            return {"ok": True, "status": "success", "code": "OK", "summary": "late"}

        result = await self._gateway(handler, timeout=0.001).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(), AgentRunState(),
        )

        self.assertEqual(result.status, "timeout")
        self.assertEqual(result.code, "TOOL_TIMEOUT")
        self.assertTrue(result.retryable)

    async def test_handler_exception_does_not_expose_internal_message(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        def handler(arguments, context, state):
            raise RuntimeError("database_password=secret")

        result = await self._gateway(handler).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(), AgentRunState(),
        )

        self.assertEqual(result.code, "TOOL_EXECUTION_FAILED")
        self.assertNotIn("secret", result.summary)
        self.assertNotIn("secret", str(result.data))

    async def test_second_duplicate_is_not_executed_and_third_stops_run(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        calls = 0
        def handler(arguments, context, state):
            nonlocal calls
            calls += 1
            return {"ok": True, "status": "success", "code": "OK", "summary": "ok"}

        gateway = self._gateway(handler)
        state = AgentRunState()
        arguments = {"query": "急会诊"}

        first = await gateway.execute("search_indicator_rules", arguments, self._context(), state)
        second = await gateway.execute("search_indicator_rules", arguments, self._context(), state)
        third = await gateway.execute("search_indicator_rules", arguments, self._context(), state)

        self.assertTrue(first.ok)
        self.assertTrue(second.retryable)
        self.assertFalse(third.retryable)
        self.assertEqual(calls, 1)
        self.assertEqual(state.stop_reason, "repeated_tool_call")

    async def test_trace_callback_receives_redacted_arguments_and_results(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        events = []
        def handler(arguments, context, state):
            return {
                "ok": True, "status": "success", "code": "OK", "summary": "ok",
                "data": {"sql_text": "SELECT patient_name FROM patient"},
            }

        await self._gateway(handler, trace_events=events).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(), AgentRunState(),
        )

        self.assertEqual(events[0]["event"], "tool_call")
        self.assertEqual(events[-1]["event"], "tool_result")
        self.assertEqual(events[-1]["result"]["data"]["sql_text"], "[REDACTED]")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 运行测试并确认按预期失败**

Run: `python -m pytest tests/test_agent_tool_gateway.py -q`

Expected: FAIL，错误包含 `ModuleNotFoundError: No module named 'app.agent_tools.gateway'`。

- [ ] **Step 3: 实现异步 Gateway**

```python
# app/agent_tools/gateway.py
"""模型工具调用的唯一执行边界。"""

from __future__ import annotations

import asyncio
import inspect
from collections.abc import Callable
from typing import Any

from pydantic import ValidationError

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import ToolResult
from app.agent_tools.policy import RepeatDecision, ToolExecutionPolicy, redact_payload
from app.agent_tools.registry import ToolRegistry, ToolRegistryError


TraceCallback = Callable[[dict[str, Any]], None]


class ToolGateway:
    def __init__(
        self,
        registry: ToolRegistry,
        *,
        policy: ToolExecutionPolicy | None = None,
        trace_callback: TraceCallback | None = None,
    ) -> None:
        self.registry = registry
        self.policy = policy or ToolExecutionPolicy()
        self.trace_callback = trace_callback

    async def execute(
        self,
        tool_name: str,
        raw_arguments: dict[str, Any],
        context: AgentRuntimeContext,
        state: AgentRunState,
    ) -> ToolResult:
        try:
            tool = self.registry.get(tool_name)
        except ToolRegistryError:
            return ToolResult(
                ok=False, status="not_found", code="TOOL_NOT_FOUND",
                summary=f"工具不可用：{tool_name}",
            )

        if not tool.required_permissions.issubset(context.permissions):
            return ToolResult(
                ok=False, status="forbidden", code="PERMISSION_DENIED",
                summary="当前用户没有执行该工具所需的权限。",
            )

        try:
            arguments = tool.input_model.model_validate(raw_arguments)
        except ValidationError as exc:
            return ToolResult(
                ok=False, status="validation_failed", code="INVALID_TOOL_ARGUMENTS",
                summary="工具参数不符合约束。",
                data={"errors": exc.errors(include_url=False, include_input=False)},
            )

        decision = self.policy.note_call(state, tool_name, raw_arguments)
        if decision is RepeatDecision.DUPLICATE:
            return ToolResult(
                ok=False, status="validation_failed", code="AGENT_REPEATED_TOOL_CALL",
                summary="该工具已使用相同参数调用过，请根据已有结果选择下一步。",
                retryable=True,
            )
        if decision is RepeatDecision.STOP:
            return ToolResult(
                ok=False, status="validation_failed", code="AGENT_REPEATED_TOOL_CALL",
                summary="工具被重复调用，已停止本次 Agent 循环。",
                retryable=False,
            )

        self._emit({
            "event": "tool_call",
            "tool_name": tool.name,
            "arguments": redact_payload(raw_arguments),
            "risk_level": tool.risk_level.value,
        })
        try:
            value = await asyncio.wait_for(
                self._invoke(tool.handler, arguments, context, state),
                timeout=tool.timeout_seconds,
            )
            result = value if isinstance(value, ToolResult) else ToolResult.model_validate(value)
        except TimeoutError:
            result = ToolResult(
                ok=False, status="timeout", code="TOOL_TIMEOUT",
                summary="工具执行超时，未获得可用结果。", retryable=True,
            )
        except Exception:
            result = ToolResult(
                ok=False, status="error", code="TOOL_EXECUTION_FAILED",
                summary="工具执行失败，内部错误已记录。", retryable=False,
            )

        self._emit({
            "event": "tool_result",
            "tool_name": tool.name,
            "result": redact_payload(result.model_dump(mode="json")),
        })
        return result

    @staticmethod
    async def _invoke(handler, arguments, context, state):
        if inspect.iscoroutinefunction(handler):
            return await handler(arguments, context, state)
        value = await asyncio.to_thread(handler, arguments, context, state)
        if inspect.isawaitable(value):
            return await value
        return value

    def _emit(self, event: dict[str, Any]) -> None:
        if self.trace_callback is None:
            return
        try:
            self.trace_callback(event)
        except Exception:
            return
```

- [ ] **Step 4: 更新工具包导出**

在 `app/agent_tools/__init__.py` 增加：

```python
from .gateway import ToolGateway
```

并把 `"ToolGateway"` 加入 `__all__`。

- [ ] **Step 5: 运行 Gateway 测试并确认通过**

Run: `python -m pytest tests/test_agent_tool_gateway.py -q`

Expected: `9 passed`。

- [ ] **Step 6: 运行本阶段全部测试**

Run:

```powershell
python -m pytest `
  tests/test_agent_runtime_contracts.py `
  tests/test_agent_tool_registry.py `
  tests/test_agent_tool_policy.py `
  tests/test_agent_tool_gateway.py -q
```

Expected: `20 passed`。

- [ ] **Step 7: 检查差异并提交**

```powershell
git diff --check
git add app/agent_tools/__init__.py app/agent_tools/gateway.py tests/test_agent_tool_gateway.py
git commit -m "refactor: 建立 Agent 工具统一执行网关"
git push
```

### Task 5: 增加基础设施集成验收

**Files:**
- Create: `tests/test_agent_runtime_foundation.py`

**Interfaces:**
- Consumes: Task 1 至 Task 4 的公开导出。
- Produces: 一个不依赖真实模型和数据库的完整 `Context → Registry → Schema → Gateway → ToolResult` 验收闭环。

- [ ] **Step 1: 写入基础设施集成测试**

```python
import unittest

from pydantic import BaseModel, ConfigDict


class SearchInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str


class AgentRuntimeFoundationTest(unittest.IsolatedAsyncioTestCase):
    async def test_context_registry_gateway_and_result_form_closed_loop(self) -> None:
        from app.agent_runtime import AgentRunState, AgentRuntimeContext
        from app.agent_tools import (
            AgentTool,
            ToolGateway,
            ToolRegistry,
            ToolResult,
            ToolRiskLevel,
        )

        def search(arguments, context, state):
            return ToolResult(
                ok=True,
                status="success",
                code="RULE_FOUND",
                summary="已找到急会诊及时到位率",
                data={"rule_id": "MQSI2025_005", "hospital_id": context.hospital_id},
            )

        registry = ToolRegistry([
            AgentTool(
                name="search_indicator_rules",
                description="根据用户问法搜索核心制度指标。",
                input_model=SearchInput,
                handler=search,
                risk_level=ToolRiskLevel.READ,
                required_permissions=frozenset({"indicator_read"}),
            )
        ])
        context = AgentRuntimeContext(
            user_id="user_001", hospital_id="hospital_001", session_id="session_001",
            user_role="implementer", permissions=frozenset({"indicator_read"}),
            request_id="REQ_001", trace_id="TRACE_001",
        )
        state = AgentRunState()

        schemas = registry.to_ollama_schema(registry.list_for_context(context, state))
        result = await ToolGateway(registry).execute(
            "search_indicator_rules",
            {"query": "急会诊及时到位率怎么算"},
            context,
            state,
        )

        self.assertEqual(schemas[0]["function"]["name"], "search_indicator_rules")
        self.assertTrue(result.ok)
        self.assertEqual(result.data["rule_id"], "MQSI2025_005")
        self.assertEqual(result.data["hospital_id"], "hospital_001")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 运行集成测试并确认通过**

Run: `python -m pytest tests/test_agent_runtime_foundation.py -q`

Expected: `1 passed`。

- [ ] **Step 3: 运行相关旧契约和 Trace 回归测试**

Run:

```powershell
python -m pytest `
  tests/test_agent_contracts.py `
  tests/test_observability_trace.py `
  tests/test_agent_runtime_contracts.py `
  tests/test_agent_tool_registry.py `
  tests/test_agent_tool_policy.py `
  tests/test_agent_tool_gateway.py `
  tests/test_agent_runtime_foundation.py -q
```

Expected: 全部通过；不得改变旧 Agent 契约或 Trace 行为。

- [ ] **Step 4: 运行完整测试套件**

Run: `python -m pytest -q`

Expected: 全部通过；如果发现与本阶段无关的既有失败，只记录完整失败命令和错误，不顺手修改无关功能。

- [ ] **Step 5: 完成最终差异检查并提交**

```powershell
git diff --check
git status --short
git add tests/test_agent_runtime_foundation.py
git commit -m "test: 增加 Agent Runtime 基础闭环验收"
git push
```

## Completion Criteria

完成本计划后必须满足：

1. 新增包不被旧对话入口导入，旧行为保持不变。
2. Runtime Context 冻结且禁止附加服务端字段。
3. Registry 能生成不含用户、医院和凭据字段的 Ollama Schema。
4. Registry 能按权限和运行状态动态筛选工具。
5. Gateway 是 handler 的唯一执行入口，能处理同步、异步、超时、权限、非法参数和异常。
6. 同参数第一次执行、第二次提示、第三次停止。
7. Trace 回调中的敏感参数和结果已脱敏。
8. 相关旧测试和完整测试套件均有新鲜验证结果。
9. 每个任务形成独立中文 Conventional Commit 并推送。
