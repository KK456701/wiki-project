# Agent 首批只读工具 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在既有 Agent Runtime 和 Tool Gateway 上实现指标搜索、生效规则查询、实施状态检查三个只读工具，形成不连接真实模型的安全闭环。

**Architecture:** 使用一个聚焦的 `read_tools.py` 封装只读输入契约、依赖注入、结果投影和工具目录工厂。工具复用现有 `CaliberAdaptationAgent` 与可选 `TerminologyNormalizer`，医院身份只从 `AgentRuntimeContext` 注入；Registry 根据已验证的规则上下文动态暴露后续工具，Gateway 在执行时再次复核可用性。

**Tech Stack:** Python 3.12.7、Pydantic 2.13.3、现有 `app.agent_tools`、`CaliberAdaptationAgent`、`TerminologyNormalizer`、`unittest`/`pytest`。

## Global Constraints

- 默认使用中文注释、错误说明、测试语义和提交主题。
- 不修改 `/api/chat`、`/api/chat/stream`、`app/agent/graph.py` 或旧固定流程。
- 不增加第三方依赖，不连接 Ollama、DBHub 或患者业务数据库。
- `hospital_id`、`user_id`、权限和数据库信息不得出现在模型工具输入 Schema。
- 工具输入使用 `extra="forbid"`，字符串去除首尾空白并限制长度。
- 三个工具风险均为 `READ`，执行权限均为 `indicator_read`。
- 搜索必须优先使用术语归一结果，并将服务端 `context.hospital_id` 传给医院范围检索。
- 生效规则结果不得返回 `standard_sql`、SQL 模板、数据库名或连接信息，只返回 SQL 可用状态。
- 实施状态不得读取患者明细；字段映射结果不得返回 `db_name`。
- `ToolResult.evidence` 必须说明来源、规则 ID、版本和支持的事实类型。
- 动态隐藏不是安全边界；Gateway 必须在执行时再次复核 `availability`，异常时默认拒绝。
- 每个任务遵循 TDD：先看到预期失败，再写最小实现、运行相关回归、独立提交并推送。

## File Structure

```text
app/agent_tools/
├── gateway.py          执行时复核工具 availability
├── read_tools.py       只读输入、依赖、三个 handler、结果投影和目录工厂
└── __init__.py         导出只读工具公开接口

tests/
├── test_agent_tool_gateway.py       Gateway 可用性回归
├── test_agent_read_tools.py         三个只读 handler 的领域与安全测试
└── test_agent_read_tool_catalog.py  动态 Registry 和 Gateway 闭环验收
```

---

### Task 1: Gateway 执行时复核工具可用性

**Files:**
- Modify: `app/agent_tools/gateway.py`
- Modify: `tests/test_agent_tool_gateway.py`

**Interfaces:**
- Consumes: `AgentTool.availability(context, state) -> bool`。
- Produces: 不可用或可用性检查异常时返回 `ToolResult(code="TOOL_UNAVAILABLE", status="unavailable")`，且不调用 handler。

- [ ] **Step 1: 写入失败测试**

在测试辅助方法 `_gateway` 增加 `availability=None` 参数，并传给 `AgentTool`：

```python
def _gateway(
    self,
    handler,
    *,
    timeout=1.0,
    permissions=frozenset({"indicator_read"}),
    trace_events=None,
    availability=None,
):
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
        availability=availability,
    )
    return ToolGateway(
        ToolRegistry([tool]),
        trace_callback=(trace_events.append if trace_events is not None else None),
    )
```

增加两个测试：

```python
async def test_unavailable_tool_is_rejected_before_handler(self) -> None:
    from app.agent_runtime.contracts import AgentRunState

    called = False

    def handler(arguments, context, state):
        nonlocal called
        called = True
        return {"ok": True, "status": "success", "code": "OK", "summary": "ok"}

    result = await self._gateway(
        handler,
        availability=lambda _context, _state: False,
    ).execute(
        "search_indicator_rules",
        {"query": "急会诊"},
        self._context(),
        AgentRunState(),
    )

    self.assertFalse(called)
    self.assertEqual(result.status, "unavailable")
    self.assertEqual(result.code, "TOOL_UNAVAILABLE")

async def test_availability_exception_fails_closed(self) -> None:
    from app.agent_runtime.contracts import AgentRunState

    def unavailable(_context, _state):
        raise RuntimeError("internal state error")

    result = await self._gateway(
        lambda *_: None,
        availability=unavailable,
    ).execute(
        "search_indicator_rules",
        {"query": "急会诊"},
        self._context(),
        AgentRunState(),
    )

    self.assertEqual(result.code, "TOOL_UNAVAILABLE")
    self.assertNotIn("internal state error", result.summary)
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -m pytest tests/test_agent_tool_gateway.py -q`

Expected: FAIL；handler 被调用或结果不是 `TOOL_UNAVAILABLE`。

- [ ] **Step 3: 写入最小实现**

在 `ToolGateway.execute()` 的权限检查之后、参数校验之前增加：

```python
if tool.availability is not None:
    try:
        available = bool(tool.availability(context, state))
    except Exception:
        available = False
    if not available:
        return ToolResult(
            ok=False,
            status="unavailable",
            code="TOOL_UNAVAILABLE",
            summary="当前运行状态不允许执行该工具。",
        )
```

- [ ] **Step 4: 运行测试并确认通过**

Run: `python -m pytest tests/test_agent_tool_gateway.py -q`

Expected: `11 passed`。

- [ ] **Step 5: 检查并提交**

```powershell
git diff --check
git add app/agent_tools/gateway.py tests/test_agent_tool_gateway.py
git commit -m "fix: 在工具执行时复核动态可用性"
git push
```

### Task 2: 实现指标搜索只读工具

**Files:**
- Create: `app/agent_tools/read_tools.py`
- Create: `tests/test_agent_read_tools.py`

**Interfaces:**
- Consumes: `services.terminology.normalize(text, hospital_id)` 和 `services.caliber.search_for_hospital_contract(query, hospital_id, limit)`。
- Produces: `ReadToolServices`、`SearchIndicatorRulesInput`、`RuleReferenceInput`、`search_indicator_rules(...) -> ToolResult`。

- [ ] **Step 1: 写入失败测试**

```python
import unittest

from app.agents.contracts import RuleSearchResult
from app.terminology.contracts import TermMatch, TermNormalizationResult


class FakeTerminology:
    def __init__(self, result: TermNormalizationResult) -> None:
        self.result = result
        self.calls = []

    def normalize(self, text, hospital_id):
        self.calls.append((text, hospital_id))
        return self.result


class FakeCaliber:
    def __init__(self, search_result=None) -> None:
        self.search_result = search_result or RuleSearchResult(query="", matches=[])
        self.search_calls = []

    def search_for_hospital_contract(self, query, hospital_id, limit=5):
        self.search_calls.append((query, hospital_id, limit))
        return self.search_result.model_copy(update={"query": query})


class AgentReadToolsTest(unittest.TestCase):
    def _context(self):
        from app.agent_runtime import AgentRuntimeContext

        return AgentRuntimeContext(
            user_id="user_001",
            hospital_id="hospital_001",
            session_id="session_001",
            user_role="implementer",
            permissions=frozenset({"indicator_read"}),
            request_id="REQ_001",
            trace_id="TRACE_001",
        )

    def test_search_uses_linked_rule_and_server_hospital_context(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import (
            ReadToolServices,
            SearchIndicatorRulesInput,
            search_indicator_rules,
        )

        terminology = FakeTerminology(TermNormalizationResult(
            original_text="急会诊怎么算",
            normalized_text="急会诊及时到位率怎么算",
            release_version="TERM_2026_07",
            matches=[TermMatch(
                matched_text="急会诊",
                concept_code="IND_MQSI2025_005",
                canonical_name="急会诊及时到位率",
                relation_type="colloquial",
                retrieval_enabled=True,
                sql_safe=True,
                linked_rule_ids=["MQSI2025_005"],
            )],
        ))
        caliber = FakeCaliber(RuleSearchResult(
            query="MQSI2025_005",
            resolved_rule_id="MQSI2025_005",
            matches=[{
                "rule_id": "MQSI2025_005",
                "rule_name": "急会诊及时到位率",
                "type": "mysql_standard",
            }],
            rule_source="mysql",
        ))

        result = search_indicator_rules(
            SearchIndicatorRulesInput(query="急会诊怎么算"),
            self._context(),
            AgentRunState(),
            ReadToolServices(caliber=caliber, terminology=terminology),
        )

        self.assertTrue(result.ok)
        self.assertEqual(caliber.search_calls, [("MQSI2025_005", "hospital_001", 5)])
        self.assertEqual(result.data["resolved_rule_id"], "MQSI2025_005")
        self.assertEqual(result.evidence[0].source_id, "MQSI2025_005")

    def test_search_returns_clarification_without_repository_call_for_ambiguity(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import (
            ReadToolServices,
            SearchIndicatorRulesInput,
            search_indicator_rules,
        )

        terminology = FakeTerminology(TermNormalizationResult(
            original_text="转科率",
            normalized_text="转科率",
            ambiguities=[{"text": "转科率", "concept_codes": ["A", "B"]}],
        ))
        caliber = FakeCaliber()

        result = search_indicator_rules(
            SearchIndicatorRulesInput(query="转科率"),
            self._context(),
            AgentRunState(),
            ReadToolServices(caliber=caliber, terminology=terminology),
        )

        self.assertEqual(result.status, "need_clarification")
        self.assertEqual(caliber.search_calls, [])

    def test_search_returns_not_found_as_standard_result(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import (
            ReadToolServices,
            SearchIndicatorRulesInput,
            search_indicator_rules,
        )

        caliber = FakeCaliber(RuleSearchResult(query="未知指标", matches=[]))
        result = search_indicator_rules(
            SearchIndicatorRulesInput(query="未知指标"),
            self._context(),
            AgentRunState(),
            ReadToolServices(caliber=caliber),
        )

        self.assertEqual(result.status, "not_found")
        self.assertEqual(result.code, "RULE_NOT_FOUND")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -m pytest tests/test_agent_read_tools.py -q`

Expected: FAIL，错误包含 `ModuleNotFoundError: No module named 'app.agent_tools.read_tools'`。

- [ ] **Step 3: 实现输入契约、依赖和搜索 handler**

创建 `app/agent_tools/read_tools.py`：

```python
"""核心制度指标的模型可见只读工具。"""

from __future__ import annotations

from dataclasses import dataclass
from functools import partial
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import (
    AgentTool,
    ToolEvidence,
    ToolResult,
    ToolRiskLevel,
)
from app.agent_tools.registry import ToolRegistry


class ReadToolInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class SearchIndicatorRulesInput(ReadToolInput):
    query: str = Field(min_length=1, max_length=200)
    limit: int = Field(default=5, ge=1, le=10)


class RuleReferenceInput(ReadToolInput):
    rule_id: str = Field(min_length=1, max_length=128)


@dataclass(frozen=True, slots=True)
class ReadToolServices:
    caliber: Any
    terminology: Any | None = None


def _normalization_payload(result: Any) -> dict[str, Any]:
    if result is None:
        return {}
    return {
        "normalized_text": str(result.normalized_text),
        "release_version": str(result.release_version),
        "matches": [
            {
                "matched_text": item.matched_text,
                "concept_code": item.concept_code,
                "canonical_name": item.canonical_name,
                "linked_rule_ids": list(item.linked_rule_ids),
            }
            for item in result.matches
            if item.retrieval_enabled
        ],
    }


def _retrieval_query(query: str, normalization: Any | None) -> str:
    if normalization is None:
        return query
    linked_rule_ids = sorted({
        rule_id
        for item in normalization.matches
        if item.retrieval_enabled
        for rule_id in item.linked_rule_ids
        if rule_id
    })
    if len(linked_rule_ids) == 1:
        return linked_rule_ids[0]
    return str(normalization.normalized_text or query)


def search_indicator_rules(
    arguments: SearchIndicatorRulesInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: ReadToolServices,
) -> ToolResult:
    del state
    normalization = (
        services.terminology.normalize(arguments.query, context.hospital_id)
        if services.terminology is not None
        else None
    )
    if normalization is not None and normalization.ambiguities:
        return ToolResult(
            ok=False,
            status="need_clarification",
            code="TERM_AMBIGUOUS",
            summary="问题中的术语存在多个可能含义，请明确具体指标。",
            data={
                "ambiguities": list(normalization.ambiguities),
                "terminology": _normalization_payload(normalization),
            },
        )

    retrieval_query = _retrieval_query(arguments.query, normalization)
    search = services.caliber.search_for_hospital_contract(
        retrieval_query,
        context.hospital_id,
        limit=arguments.limit,
    )
    payload = search.model_dump(mode="json")
    payload["retrieval_query"] = retrieval_query
    if normalization is not None:
        payload["terminology"] = _normalization_payload(normalization)
    matches = list(payload.get("matches") or [])
    resolved_rule_id = str(payload.get("resolved_rule_id") or "")
    if not matches and not resolved_rule_id:
        return ToolResult(
            ok=False,
            status="not_found",
            code="RULE_NOT_FOUND",
            summary="未找到匹配的核心制度指标。",
            data=payload,
            warnings=list(payload.get("warnings") or []),
        )

    evidence = [ToolEvidence(
        source=str(payload.get("rule_source") or "rule_repository"),
        source_id=resolved_rule_id or None,
        fact_types=["rule_identity"],
    )]
    if normalization is not None and normalization.release_version:
        evidence.append(ToolEvidence(
            source="terminology",
            version=str(normalization.release_version),
            fact_types=["term_normalization"],
        ))
    return ToolResult(
        ok=True,
        status="success",
        code="RULE_SEARCHED",
        summary=f"找到 {len(matches)} 个匹配指标。",
        data=payload,
        evidence=evidence,
        warnings=list(payload.get("warnings") or []),
    )
```

- [ ] **Step 4: 运行测试并确认通过**

Run: `python -m pytest tests/test_agent_read_tools.py -q`

Expected: `3 passed`。

- [ ] **Step 5: 检查并提交**

```powershell
git diff --check
git add app/agent_tools/read_tools.py tests/test_agent_read_tools.py
git commit -m "feat: 增加指标搜索只读工具"
git push
```

### Task 3: 实现生效规则和实施状态只读工具

**Files:**
- Modify: `app/agent_tools/read_tools.py`
- Modify: `tests/test_agent_read_tools.py`

**Interfaces:**
- Consumes: `caliber.resolve_contract(rule_id, hospital_id)`、`caliber.field_mapping_contract(rule_id, hospital_id)`。
- Produces: `get_effective_rule(...) -> ToolResult`、`inspect_indicator_implementation(...) -> ToolResult`；结果不含 SQL 文本和 `db_name`。

- [ ] **Step 1: 在 FakeCaliber 增加只读结果**

```python
from app.agents.contracts import EffectiveRule, FieldMapping


class FakeCaliber:
    def __init__(self, search_result=None, rule=None, mapping=None) -> None:
        self.search_result = search_result or RuleSearchResult(query="", matches=[])
        self.rule = rule
        self.mapping = mapping
        self.search_calls = []
        self.resolve_calls = []
        self.mapping_calls = []

    def search_for_hospital_contract(self, query, hospital_id, limit=5):
        self.search_calls.append((query, hospital_id, limit))
        return self.search_result.model_copy(update={"query": query})

    def resolve_contract(self, rule_id, hospital_id):
        self.resolve_calls.append((rule_id, hospital_id))
        if self.rule is None:
            raise LookupError(rule_id)
        return self.rule

    def field_mapping_contract(self, rule_id, hospital_id):
        self.mapping_calls.append((rule_id, hospital_id))
        return self.mapping or FieldMapping(rule_id=rule_id, hospital_id=hospital_id)
```

- [ ] **Step 2: 写入失败测试**

```python
def _effective_rule(self):
    return EffectiveRule.model_validate({
        "rule_id": "MQSI2025_005",
        "rule_name": "急会诊及时到位率",
        "effective_level": "hospital",
        "definition": "急会诊在规定时间内到位的比例。",
        "formula": "及时到位例数 / 急会诊总例数 × 100%",
        "standard_sql": "SELECT patient_name FROM patient",
        "sql_status": "available",
        "field_status": "configured",
        "field_contract": {
            "business_fields": {
                "consult_apply_time": {},
                "consult_arrive_time": {},
            }
        },
        "national_version": "2025",
        "hospital_version": 2,
        "overridden_fields": ["denominator_rule"],
        "rule_source": "mysql",
    })

def test_get_effective_rule_returns_safe_projection(self) -> None:
    from app.agent_runtime import AgentRunState
    from app.agent_tools.read_tools import (
        ReadToolServices,
        RuleReferenceInput,
        get_effective_rule,
    )

    caliber = FakeCaliber(rule=self._effective_rule())
    result = get_effective_rule(
        RuleReferenceInput(rule_id="MQSI2025_005"),
        self._context(),
        AgentRunState(),
        ReadToolServices(caliber=caliber),
    )

    self.assertTrue(result.ok)
    self.assertEqual(caliber.resolve_calls, [("MQSI2025_005", "hospital_001")])
    self.assertEqual(result.data["sql_status"], "available")
    self.assertNotIn("standard_sql", result.data)
    self.assertNotIn("SELECT", str(result.data))
    self.assertEqual(result.evidence[0].version, "2")

def test_get_effective_rule_standardizes_not_found(self) -> None:
    from app.agent_runtime import AgentRunState
    from app.agent_tools.read_tools import (
        ReadToolServices,
        RuleReferenceInput,
        get_effective_rule,
    )

    result = get_effective_rule(
        RuleReferenceInput(rule_id="missing"),
        self._context(),
        AgentRunState(),
        ReadToolServices(caliber=FakeCaliber()),
    )

    self.assertEqual(result.status, "not_found")
    self.assertEqual(result.code, "RULE_NOT_FOUND")

def test_inspect_implementation_derives_gaps_without_database_name(self) -> None:
    from app.agent_runtime import AgentRunState
    from app.agent_tools.read_tools import (
        ReadToolServices,
        RuleReferenceInput,
        inspect_indicator_implementation,
    )

    mapping = FieldMapping.model_validate({
        "rule_id": "MQSI2025_005",
        "hospital_id": "hospital_001",
        "db_name": "patient_prod",
        "dialect": "sqlserver",
        "main_table": "consultation",
        "fields": {"consult_apply_time": "consultation.apply_time"},
        "status": "pending",
        "items": [{
            "business_field": "consult_apply_time",
            "db_name": "patient_prod",
            "table_name": "consultation",
            "column_name": "apply_time",
            "data_type": "datetime",
            "status": "pending",
        }],
        "relations": [{
            "left_table": "consultation",
            "left_column": "encounter_id",
            "right_table": "encounter",
            "right_column": "id",
            "join_type": "inner",
            "relation_source": "confirmed_mapping",
            "status": "confirmed",
            "db_name": "patient_prod",
        }],
    })
    caliber = FakeCaliber(rule=self._effective_rule(), mapping=mapping)

    result = inspect_indicator_implementation(
        RuleReferenceInput(rule_id="MQSI2025_005"),
        self._context(),
        AgentRunState(),
        ReadToolServices(caliber=caliber),
    )

    self.assertTrue(result.ok)
    self.assertEqual(result.data["missing_mappings"], ["consult_arrive_time"])
    self.assertEqual(result.data["unconfirmed_mappings"], ["consult_apply_time"])
    self.assertNotIn("db_name", str(result.data))
```

- [ ] **Step 3: 运行测试并确认失败**

Run: `python -m pytest tests/test_agent_read_tools.py -q`

Expected: FAIL，无法导入 `get_effective_rule` 或 `inspect_indicator_implementation`。

- [ ] **Step 4: 实现安全投影和两个 handler**

追加到 `read_tools.py`：

```python
_RULE_RESULT_FIELDS = (
    "rule_id",
    "rule_name",
    "category",
    "effective_level",
    "definition",
    "formula",
    "numerator_rule",
    "denominator_rule",
    "filter_rule",
    "exclude_rule",
    "calculation_definition",
    "field_contract",
    "field_status",
    "sql_status",
    "national_version",
    "hospital_version",
    "overridden_fields",
    "fallback_chain",
    "rule_source",
    "warnings",
)


def _safe_rule_payload(rule: Any) -> dict[str, Any]:
    raw = rule.model_dump(mode="json")
    return {key: raw[key] for key in _RULE_RESULT_FIELDS if key in raw}


def _rule_evidence(payload: dict[str, Any]) -> list[ToolEvidence]:
    version = payload.get("hospital_version")
    if version is None:
        version = payload.get("national_version")
    return [ToolEvidence(
        source=str(payload.get("rule_source") or "rule_repository"),
        source_id=str(payload.get("rule_id") or "") or None,
        version=str(version) if version is not None and str(version) else None,
        fact_types=["definition", "formula", "effective_level", "implementation_status"],
    )]


def get_effective_rule(
    arguments: RuleReferenceInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: ReadToolServices,
) -> ToolResult:
    del state
    try:
        rule = services.caliber.resolve_contract(arguments.rule_id, context.hospital_id)
    except LookupError:
        return ToolResult(
            ok=False,
            status="not_found",
            code="RULE_NOT_FOUND",
            summary="当前医院未找到该指标的生效规则。",
            data={"rule_id": arguments.rule_id},
        )
    payload = _safe_rule_payload(rule)
    return ToolResult(
        ok=True,
        status="success",
        code="EFFECTIVE_RULE_FOUND",
        summary=f"已读取 {payload.get('rule_name') or arguments.rule_id} 的生效规则。",
        data=payload,
        evidence=_rule_evidence(payload),
        warnings=list(payload.get("warnings") or []),
    )


def _required_business_fields(payload: dict[str, Any]) -> list[str]:
    contract = payload.get("field_contract") or {}
    if not isinstance(contract, dict):
        return []
    fields = contract.get("business_fields") or {}
    if isinstance(fields, dict):
        return sorted(str(key) for key in fields)
    return []


def _safe_mapping_items(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    allowed = (
        "business_field",
        "table_name",
        "column_name",
        "data_type",
        "status",
    )
    return [
        {key: item[key] for key in allowed if key in item}
        for item in items
    ]


def _safe_relations(relations: list[dict[str, Any]]) -> list[dict[str, Any]]:
    allowed = (
        "left_table",
        "left_column",
        "right_table",
        "right_column",
        "join_type",
        "relation_source",
        "status",
    )
    return [
        {key: item[key] for key in allowed if key in item}
        for item in relations
    ]


def inspect_indicator_implementation(
    arguments: RuleReferenceInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: ReadToolServices,
) -> ToolResult:
    del state
    try:
        rule = services.caliber.resolve_contract(arguments.rule_id, context.hospital_id)
    except LookupError:
        return ToolResult(
            ok=False,
            status="not_found",
            code="RULE_NOT_FOUND",
            summary="当前医院未找到该指标，无法检查实施状态。",
            data={"rule_id": arguments.rule_id},
        )
    mapping = services.caliber.field_mapping_contract(
        arguments.rule_id,
        context.hospital_id,
    )
    rule_payload = _safe_rule_payload(rule)
    required = _required_business_fields(rule_payload)
    mapped = sorted(str(key) for key in mapping.fields)
    missing = sorted(set(required) - set(mapped))
    raw_items = [dict(item) for item in mapping.mapping_items]
    unconfirmed = sorted({
        str(item.get("business_field") or "")
        for item in raw_items
        if str(item.get("status") or "") != "confirmed"
        and str(item.get("business_field") or "")
    })
    payload = {
        "rule_id": arguments.rule_id,
        "hospital_id": context.hospital_id,
        "status": mapping.status,
        "dialect": mapping.dialect,
        "main_table": mapping.main_table,
        "mapped_fields": mapped,
        "required_business_fields": required,
        "missing_mappings": missing,
        "unconfirmed_mappings": unconfirmed,
        "mapping_items": _safe_mapping_items(raw_items),
        "relations": _safe_relations([dict(item) for item in mapping.relations]),
        "query_profile": mapping.query_profile,
        "sql_status": rule_payload.get("sql_status", "unavailable"),
    }
    return ToolResult(
        ok=True,
        status="success",
        code="IMPLEMENTATION_INSPECTED",
        summary=(
            "指标实施映射已确认。"
            if not missing and not unconfirmed and mapping.status == "confirmed"
            else "指标实施仍有缺失或未确认映射。"
        ),
        data=payload,
        evidence=[ToolEvidence(
            source=str(rule_payload.get("rule_source") or "rule_repository"),
            source_id=arguments.rule_id,
            fact_types=["field_mapping", "implementation_status"],
        )],
        warnings=[
            message
            for message, present in (
                ("存在缺失字段映射。", bool(missing)),
                ("存在未确认字段映射。", bool(unconfirmed)),
            )
            if present
        ],
    )
```

- [ ] **Step 5: 运行测试并确认通过**

Run: `python -m pytest tests/test_agent_read_tools.py -q`

Expected: `6 passed`。

- [ ] **Step 6: 检查并提交**

```powershell
git diff --check
git add app/agent_tools/read_tools.py tests/test_agent_read_tools.py
git commit -m "feat: 增加规则与实施状态只读工具"
git push
```

### Task 4: 建立只读工具目录与动态闭环

**Files:**
- Modify: `app/agent_tools/read_tools.py`
- Modify: `app/agent_tools/__init__.py`
- Create: `tests/test_agent_read_tool_catalog.py`

**Interfaces:**
- Consumes: `AgentRunState.last_tool_results` 和 `AgentRunState.evidence` 中经过验证的规则引用。
- Produces: `build_read_tools(services) -> list[AgentTool]`、`build_read_tool_registry(services) -> ToolRegistry`。

- [ ] **Step 1: 写入失败测试**

```python
import unittest

from app.agents.contracts import EffectiveRule, FieldMapping, RuleSearchResult


class CatalogCaliber:
    def search_for_hospital_contract(self, query, hospital_id, limit=5):
        return RuleSearchResult(
            query=query,
            resolved_rule_id="MQSI2025_005",
            matches=[{
                "rule_id": "MQSI2025_005",
                "rule_name": "急会诊及时到位率",
            }],
            rule_source="mysql",
        )

    def resolve_contract(self, rule_id, hospital_id):
        return EffectiveRule.model_validate({
            "rule_id": rule_id,
            "rule_name": "急会诊及时到位率",
            "formula": "及时到位例数 / 急会诊总例数 × 100%",
            "sql_status": "available",
            "rule_source": "mysql",
        })

    def field_mapping_contract(self, rule_id, hospital_id):
        return FieldMapping(
            rule_id=rule_id,
            hospital_id=hospital_id,
            status="confirmed",
        )


class AgentReadToolCatalogTest(unittest.IsolatedAsyncioTestCase):
    def _context(self):
        from app.agent_runtime import AgentRuntimeContext

        return AgentRuntimeContext(
            user_id="user_001",
            hospital_id="hospital_001",
            session_id="session_001",
            user_role="implementer",
            permissions=frozenset({"indicator_read"}),
            request_id="REQ_001",
            trace_id="TRACE_001",
        )

    async def test_catalog_exposes_search_then_rule_tools_after_verified_result(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools import ToolGateway
        from app.agent_tools.read_tools import ReadToolServices, build_read_tool_registry

        registry = build_read_tool_registry(ReadToolServices(caliber=CatalogCaliber()))
        context = self._context()
        state = AgentRunState()

        self.assertEqual(
            [tool.name for tool in registry.list_for_context(context, state)],
            ["search_indicator_rules"],
        )
        search_result = await ToolGateway(registry).execute(
            "search_indicator_rules",
            {"query": "急会诊"},
            context,
            state,
        )
        state.last_tool_results.append(search_result.model_dump(mode="json"))

        self.assertEqual(
            [tool.name for tool in registry.list_for_context(context, state)],
            [
                "search_indicator_rules",
                "get_effective_rule",
                "inspect_indicator_implementation",
            ],
        )
        result = await ToolGateway(registry).execute(
            "get_effective_rule",
            {"rule_id": "MQSI2025_005"},
            context,
            state,
        )
        self.assertTrue(result.ok)
        self.assertEqual(result.code, "EFFECTIVE_RULE_FOUND")

    async def test_hidden_rule_tool_is_also_unavailable_at_gateway(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools import ToolGateway
        from app.agent_tools.read_tools import ReadToolServices, build_read_tool_registry

        registry = build_read_tool_registry(ReadToolServices(caliber=CatalogCaliber()))
        result = await ToolGateway(registry).execute(
            "get_effective_rule",
            {"rule_id": "MQSI2025_005"},
            self._context(),
            AgentRunState(),
        )

        self.assertEqual(result.code, "TOOL_UNAVAILABLE")

    def test_verified_rule_evidence_exposes_follow_up_tools(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import ReadToolServices, build_read_tool_registry

        registry = build_read_tool_registry(ReadToolServices(caliber=CatalogCaliber()))
        state = AgentRunState(evidence=[{
            "source": "mysql",
            "source_id": "MQSI2025_005",
            "fact_types": ["rule_identity"],
        }])

        self.assertEqual(
            [tool.name for tool in registry.list_for_context(self._context(), state)],
            [
                "search_indicator_rules",
                "get_effective_rule",
                "inspect_indicator_implementation",
            ],
        )

    def test_catalog_schemas_never_expose_runtime_context(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import ReadToolServices, build_read_tool_registry

        registry = build_read_tool_registry(ReadToolServices(caliber=CatalogCaliber()))
        state = AgentRunState(last_tool_results=[{
            "ok": True,
            "data": {"resolved_rule_id": "MQSI2025_005"},
        }])
        schemas = registry.to_ollama_schema(
            registry.list_for_context(self._context(), state)
        )

        serialized = str(schemas)
        self.assertNotIn("hospital_id", serialized)
        self.assertNotIn("user_id", serialized)
        self.assertNotIn("db_name", serialized)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -m pytest tests/test_agent_read_tool_catalog.py -q`

Expected: FAIL，无法导入 `build_read_tool_registry`。

- [ ] **Step 3: 实现动态目录工厂**

追加到 `read_tools.py`：

```python
def _state_has_verified_rule(
    context: AgentRuntimeContext,
    state: AgentRunState,
) -> bool:
    del context

    def is_rule_evidence(value: Any) -> bool:
        return (
            isinstance(value, dict)
            and bool(value.get("source_id"))
            and "rule_identity" in (value.get("fact_types") or [])
        )

    for item in [*state.last_tool_results, *state.evidence]:
        if not isinstance(item, dict):
            continue
        data = item.get("data") if isinstance(item.get("data"), dict) else item
        if data.get("resolved_rule_id") or data.get("rule_id"):
            return True
        if is_rule_evidence(item):
            return True
        evidence_items = item.get("evidence") or []
        if any(is_rule_evidence(evidence) for evidence in evidence_items):
            return True
    return False


def build_read_tools(services: ReadToolServices) -> list[AgentTool]:
    permission = frozenset({"indicator_read"})
    return [
        AgentTool(
            name="search_indicator_rules",
            description="根据指标名称、简称、错别字、医学同义词或主题搜索当前医院可用的核心制度指标。",
            input_model=SearchIndicatorRulesInput,
            handler=partial(search_indicator_rules, services=services),
            risk_level=ToolRiskLevel.READ,
            required_permissions=permission,
        ),
        AgentTool(
            name="get_effective_rule",
            description="读取当前医院指定指标的定义、公式、生效层级、版本和 SQL 可用状态，不返回 SQL 文本。",
            input_model=RuleReferenceInput,
            handler=partial(get_effective_rule, services=services),
            risk_level=ToolRiskLevel.READ,
            required_permissions=permission,
            availability=_state_has_verified_rule,
        ),
        AgentTool(
            name="inspect_indicator_implementation",
            description="检查当前医院指定指标的字段映射、缺失项、未确认项、关联关系和实施状态，不读取患者数据。",
            input_model=RuleReferenceInput,
            handler=partial(inspect_indicator_implementation, services=services),
            risk_level=ToolRiskLevel.READ,
            required_permissions=permission,
            availability=_state_has_verified_rule,
        ),
    ]


def build_read_tool_registry(services: ReadToolServices) -> ToolRegistry:
    return ToolRegistry(build_read_tools(services))
```

- [ ] **Step 4: 更新公开导出**

在 `app/agent_tools/__init__.py` 增加：

```python
from .read_tools import (
    ReadToolServices,
    RuleReferenceInput,
    SearchIndicatorRulesInput,
    build_read_tool_registry,
    build_read_tools,
    get_effective_rule,
    inspect_indicator_implementation,
    search_indicator_rules,
)
```

并将以下名称加入 `__all__`：

```python
"ReadToolServices",
"RuleReferenceInput",
"SearchIndicatorRulesInput",
"build_read_tool_registry",
"build_read_tools",
"get_effective_rule",
"inspect_indicator_implementation",
"search_indicator_rules",
```

- [ ] **Step 5: 运行只读工具全部测试**

Run:

```powershell
python -m pytest `
  tests/test_agent_tool_gateway.py `
  tests/test_agent_read_tools.py `
  tests/test_agent_read_tool_catalog.py -q
```

Expected: `21 passed`。

- [ ] **Step 6: 运行 Agent Runtime 相关回归**

Run:

```powershell
python -m pytest `
  tests/test_agent_runtime_contracts.py `
  tests/test_agent_runtime_foundation.py `
  tests/test_agent_tool_registry.py `
  tests/test_agent_tool_policy.py `
  tests/test_agent_tool_gateway.py `
  tests/test_agent_read_tools.py `
  tests/test_agent_read_tool_catalog.py `
  tests/test_agent_contracts.py `
  tests/test_specialized_agents.py -q
```

Expected: 全部通过。

- [ ] **Step 7: 运行完整测试套件**

Run: `python -m pytest -q`

Expected: 全部通过；不得顺手修改与本阶段无关的既有警告。

- [ ] **Step 8: 检查并提交**

```powershell
git diff --check
git status --short
git add app/agent_tools/__init__.py app/agent_tools/read_tools.py tests/test_agent_read_tool_catalog.py
git commit -m "feat: 建立 Agent 首批只读工具目录"
git push
```

## Completion Criteria

1. `search_indicator_rules` 使用术语归一和服务端医院上下文搜索。
2. 术语歧义、未找到和成功结果均形成稳定 `ToolResult`。
3. `get_effective_rule` 返回定义、公式、层级、版本和 SQL 状态，但不返回 SQL 文本。
4. `inspect_indicator_implementation` 返回字段映射缺口和关系，不返回数据库名或患者数据。
5. 三个工具的 Schema 均不含用户、医院、权限和数据库上下文字段。
6. 未确定指标时 Registry 只暴露搜索；有已验证规则结果后暴露后续两个工具。
7. Gateway 对隐藏工具执行时再次复核，并在可用性检查异常时默认拒绝。
8. 每个成功工具结果包含可追溯证据和事实类型。
9. 相关旧测试及完整测试套件均有新鲜通过结果。
10. 每个任务形成独立中文 Conventional Commit 并推送。
