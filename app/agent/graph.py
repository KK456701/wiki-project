from __future__ import annotations

import re
import time
import uuid
from datetime import datetime
from importlib.util import find_spec
from pathlib import Path
from typing import Any, Iterator, Protocol, Tuple, TypedDict

from app.agents.caliber_adaptation import CaliberAdaptationAgent
from app.agents.contracts import IntentResult
from app.agents.human_interaction import HumanInteractionAgent, detect_intent_by_rule
from app.agents.indicator_generation import IndicatorGenerationAgent
from app.agents.metadata_parsing import MetadataParsingAgent
from app.agents.orchestrator import (
    RULE_INTENTS,
    CoreIndicatorOrchestrator,
    PreparedRequest,
)
from app.agents.root_cause_diagnosis import RootCauseDiagnosisAgent
from app.business_source import current_business_source
from app.config import get, get_int
from app.db.engine import create_runtime_engine
from app.db_access.business_db import BusinessDBClient
from app.db_access.dbhub_mcp import DBHubMCPClient
from app.db_access.metadata_provider import DBHubMetadataProvider
from app.kb.tools import DEFAULT_KB_ROOT, KBToolError, KnowledgeBaseTools
from app.llm.ollama import OllamaClient
from app.memory.store import DEFAULT_MEMORY_ROOT, ConversationMemory
from app.memory.context_service import ConversationContextService
from app.memory.prompt_context import build_prompt_context
from app.memory.contracts import (
    ContextResolution,
    ContextStorageError,
    ContextVersionConflict,
    ConversationContext,
)
from app.observability.trace import TraceRecorder
from app.observability.workflow_nodes import record_diagnose_trace_nodes
from app.sqlgen.explanation import (
    format_generation_explanation,
    format_trial_explanation,
)


class LLMClient(Protocol):
    def generate(self, prompt: str) -> str:
        ...


class AgentState(TypedDict, total=False):
    query: str
    hospital_id: str | None
    session_id: str
    memory_context: dict[str, Any]
    intent: str
    rule_id: str | None
    search: dict[str, Any]
    effective_rule: dict[str, Any]
    field_mapping: dict[str, Any]
    change_request: dict[str, Any]
    feedback_preview: dict[str, Any]
    answer: str
    generation_method: str
    workflow_engine: str
    errors: list[str]
    _node_timings: dict[str, int]
    _term_normalization: dict[str, Any]
    _term_normalization_error: str


_DATE_TOKEN_PATTERN = re.compile(
    r"(?<!\d)(20\d{2})\s*(?:-|/|年)\s*(0?[1-9]|1[0-2])"
    r"\s*(?:-|/|月)\s*(0?[1-9]|[12]\d|3[01])\s*日?(?!\d)"
)
_START_DATE_MARKERS = ("起始时间", "开始时间", "起始日期", "开始日期")
_END_DATE_MARKERS = ("结束时间", "截止时间", "结束日期", "截止日期")


def _extract_stat_period_update(query: str) -> dict[str, str]:
    dates: list[datetime] = []
    for match in _DATE_TOKEN_PATTERN.finditer(query or ""):
        try:
            dates.append(datetime(*(int(value) for value in match.groups())))
        except ValueError:
            continue
        if len(dates) == 2:
            break
    if len(dates) == 2:
        if dates[1] <= dates[0]:
            return {}
        return {
            "stat_start_time": dates[0].strftime("%Y-%m-%d 00:00:00"),
            "stat_end_time": dates[1].strftime("%Y-%m-%d 00:00:00"),
        }
    if len(dates) != 1:
        return {}
    normalized = dates[0].strftime("%Y-%m-%d 00:00:00")
    compact = re.sub(r"\s+", "", query or "")
    if any(marker in compact for marker in _START_DATE_MARKERS):
        return {"stat_start_time": normalized}
    if any(marker in compact for marker in _END_DATE_MARKERS):
        return {"stat_end_time": normalized}
    if "从" in compact or "自" in compact:
        return {"stat_start_time": normalized}
    if "到" in compact or "至" in compact:
        return {"stat_end_time": normalized}
    return {}


def _default_stat_period(now: datetime | None = None) -> tuple[str, str]:
    current = now or datetime.now()
    start = current.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    if start.month == 12:
        end = start.replace(year=start.year + 1, month=1)
    else:
        end = start.replace(month=start.month + 1)
    return (
        start.strftime("%Y-%m-%d %H:%M:%S"),
        end.strftime("%Y-%m-%d %H:%M:%S"),
    )


def _resolve_stat_period(
    query: str,
    memory_context: dict[str, Any] | None,
    *,
    use_default: bool,
) -> tuple[str, str] | None:
    updates = _extract_stat_period_update(query)
    context = memory_context or {}
    start = str(context.get("stat_start_time") or "").strip()
    end = str(context.get("stat_end_time") or "").strip()
    if updates:
        if not start or not end:
            start, end = _default_stat_period()
        start = updates.get("stat_start_time", start)
        end = updates.get("stat_end_time", end)
    elif not start or not end:
        return _default_stat_period() if use_default else None
    try:
        if datetime.fromisoformat(end) <= datetime.fromisoformat(start):
            return None
    except ValueError:
        return None
    return start, end


def _stat_period_metadata(
    query: str, memory_context: dict[str, Any] | None
) -> dict[str, str]:
    period = _resolve_stat_period(query, memory_context, use_default=False)
    if period is None:
        return {}
    return {"stat_start_time": period[0], "stat_end_time": period[1]}


def _load_memory_context(
    memory_store: ConversationMemory,
    session_id: str,
    structured_context: ConversationContext | None = None,
) -> dict[str, Any]:
    context = dict(memory_store.last_rule_context(session_id) or {})
    structured = structured_context
    if structured is not None:
        if structured.active_rule.rule_id:
            context["rule_id"] = structured.active_rule.rule_id
            context["rule_name"] = structured.active_rule.rule_name
        if structured.stat_period.start_time and structured.stat_period.end_time:
            context["stat_start_time"] = structured.stat_period.start_time
            context["stat_end_time"] = structured.stat_period.end_time
        if structured.working_caliber.overrides:
            context["working_caliber"] = structured.working_caliber.model_dump(
                mode="json"
            )
    for message in reversed(memory_store.recent_messages(session_id, limit=20)):
        if message.get("role") != "user":
            continue
        content = str(message.get("content") or "")
        if not _extract_stat_period_update(content):
            continue
        period = _resolve_stat_period(content, context, use_default=False)
        if period is None:
            continue
        context["stat_start_time"], context["stat_end_time"] = period
        context["stat_period_source"] = "recent_user_message"
        break
    prompt_context = build_prompt_context(
        memory_store.recent_messages(session_id, limit=40),
        structured,
        max_turns=get_int("ollama_history_turns", 8),
        token_budget=get_int("ollama_prompt_budget_tokens", 12000),
    )
    context["structured_summary"] = prompt_context.structured_summary
    context["recent_history"] = prompt_context.recent_history
    context["prompt_context_stats"] = {
        "kept_turns": prompt_context.kept_turns,
        "trimmed_message_count": prompt_context.trimmed_message_count,
        "estimated_tokens": prompt_context.estimated_tokens,
    }
    return context


def _trace_safe_memory_context(memory_context: dict[str, Any]) -> dict[str, Any]:
    """保留会话定位信息，不在消息元数据和 Trace 中复制原始历史文本。"""
    allowed = (
        "rule_id",
        "rule_name",
        "stat_start_time",
        "stat_end_time",
        "stat_period_source",
        "working_caliber",
        "prompt_context_stats",
    )
    return {
        key: memory_context[key]
        for key in allowed
        if key in memory_context
    }


def _load_structured_context(
    memory_store: ConversationMemory,
    session_id: str,
    hospital_id: str | None,
) -> ConversationContext:
    context = memory_store.load_context(session_id)
    if hospital_id:
        context.active_rule.hospital_id = hospital_id
    return context


def _resolve_and_save_structured_context(
    *,
    memory_store: ConversationMemory,
    session_id: str,
    query: str,
    structured_context: ConversationContext,
    effective_rule: Any,
    field_mapping: Any,
    source_message_id: int | None,
    memory_context: dict[str, Any],
    llm_updates: list[dict[str, Any]] | None = None,
) -> ContextResolution:
    service = ConversationContextService()
    resolution = service.resolve(
        query,
        structured_context,
        effective_rule=effective_rule,
        field_mapping=field_mapping,
        source_message_id=source_message_id,
        llm_updates=llm_updates,
    )
    period = _resolve_stat_period(query, memory_context, use_default=False)
    if period is not None:
        resolution.context.stat_period.start_time = period[0]
        resolution.context.stat_period.end_time = period[1]
        resolution.context.stat_period.source_message_id = source_message_id
    resolution.context.last_action = str(query or "")[:200]
    saved = memory_store.save_context(
        session_id,
        resolution.context,
        expected_version=structured_context.context_version,
    )
    resolution.context = saved
    resolution.snapshot.context_version = saved.context_version
    return resolution


def _context_block_answer(resolution: ContextResolution) -> str:
    if resolution.clarification is not None:
        clarification = resolution.clarification
        options = "\n".join(
            f"{index}. {option}" for index, option in enumerate(clarification.options, 1)
        )
        return f"需要先确认计算范围\n\n{clarification.question}\n\n{options}"
    messages = [item.message for item in resolution.snapshot.blockers]
    return "暂不能按本次临时口径执行\n\n" + "\n".join(
        f"- {message}" for message in messages
    )


def _create_business_db_client(db_name: str | None = None) -> BusinessDBClient:
    active_db = db_name or current_business_source().source_id
    suffix = re.sub(r"[^0-9a-zA-Z]+", "_", active_db).strip("_").lower()
    execute_tool = get(f"dbhub_execute_tool_{suffix}", f"execute_sql_{suffix}")
    source_id = get(f"dbhub_source_id_{suffix}", get(f"dbhub_source_{suffix}", active_db))
    endpoint = get("dbhub_mcp_url", "http://127.0.0.1:8080/mcp")
    timeout_seconds = int(get("dbhub_timeout_seconds", "10"))
    client = DBHubMCPClient(endpoint, execute_tool, timeout_seconds, source_id)
    return BusinessDBClient(client.execute_sql, source_id=source_id, tool_name=execute_tool)


def _create_metadata_provider(db_name: str | None = None) -> DBHubMetadataProvider:
    active_db = db_name or current_business_source().source_id
    suffix = re.sub(r"[^0-9a-zA-Z]+", "_", active_db).strip("_").lower()
    execute_tool = get(f"dbhub_execute_tool_{suffix}", f"execute_sql_{suffix}")
    source_id = get(f"dbhub_source_id_{suffix}", get(f"dbhub_source_{suffix}", active_db))
    endpoint = get("dbhub_mcp_url", "http://127.0.0.1:8080/mcp")
    timeout_seconds = int(get("dbhub_timeout_seconds", "10"))
    client = DBHubMCPClient(endpoint, execute_tool, timeout_seconds, source_id)
    settings = current_business_source()
    dialect = settings.dialect if active_db == settings.source_id else "mysql"
    return DBHubMetadataProvider(
        client.execute_sql,
        dialect=dialect,
        schema_name=settings.schema if dialect == "sqlserver" else "",
    )


def _create_agent_orchestrator(
    kb_root: str | Path,
    tools: Any,
    llm_client: LLMClient | None,
) -> CoreIndicatorOrchestrator:
    from app.diagnose.agent import DiagnoseAgent
    from app.sqlgen.agent import SQLGenerationAgent
    from app.terminology.normalizer import TerminologyNormalizer
    from app.terminology.repository import TerminologyRepository

    runtime_engine = create_runtime_engine()
    business_db = _create_business_db_client()
    indicator_executor = SQLGenerationAgent(
        kb_root=kb_root,
        runtime_engine=runtime_engine,
        business_db=business_db,
        rule_repository=None if isinstance(tools, KnowledgeBaseTools) else tools,
    )
    diagnosis_executor = DiagnoseAgent(
        kb_root=kb_root,
        runtime_engine=runtime_engine,
        business_db=business_db,
        metadata_provider=_create_metadata_provider(),
    )
    terminology_repository = TerminologyRepository(runtime_engine)
    return CoreIndicatorOrchestrator(
        interaction=HumanInteractionAgent(llm_client),
        caliber=CaliberAdaptationAgent(tools),
        indicator_generation=IndicatorGenerationAgent(indicator_executor),
        diagnosis=RootCauseDiagnosisAgent(diagnosis_executor),
        metadata=MetadataParsingAgent(runtime_engine, kb_root),
        terminology_normalizer=TerminologyNormalizer(terminology_repository),
        terminology_repository=terminology_repository,
    )


def _start_trace(session_id: str, hospital_id: str | None, query: str) -> tuple[str, TraceRecorder | None]:
    trace_id = f"TRACE_{uuid.uuid4().hex[:12]}"
    try:
        recorder = TraceRecorder(create_runtime_engine())
        recorder.start_trace(trace_id, session_id, hospital_id, query)
        return trace_id, recorder
    except Exception:
        return trace_id, None


def _record_trace_node(recorder: TraceRecorder | None, trace_id: str, node_name: str, node_type: str, status: str, **kwargs: Any) -> None:
    if recorder is None:
        return
    try:
        recorder.record_node(trace_id, node_name, node_type, status, **kwargs)
    except Exception:
        pass


def _elapsed_ms(start: float) -> int:
    return max(1, int((time.perf_counter() - start) * 1000))


def _record_node_duration(state: AgentState, node_name: str, start: float) -> int:
    duration_ms = _elapsed_ms(start)
    timings = state.setdefault("_node_timings", {})
    timings[node_name] = duration_ms
    return duration_ms


def _node_duration(state: dict[str, Any], node_name: str) -> int:
    timings = state.get("_node_timings")
    if isinstance(timings, dict):
        try:
            return int(timings.get(node_name) or 0)
        except (TypeError, ValueError):
            return 0
    return 0


def _search_match_count(search: dict[str, Any]) -> int:
    for key in ("matches", "results"):
        value = search.get(key)
        if isinstance(value, list):
            return len(value)
    return 0


def _prepared_request_from_state(state: AgentState) -> PreparedRequest:
    search = dict(state.get("search") or {}) or None
    effective_rule = dict(state.get("effective_rule") or {}) or None
    field_mapping = dict(state.get("field_mapping") or {}) or None
    return PreparedRequest(
        query=str(state.get("query") or ""),
        hospital_id=state.get("hospital_id"),
        intent=str(state.get("intent") or "query"),
        retrieval_query=str((state.get("search") or {}).get("query") or state.get("query") or ""),
        rule_id=state.get("rule_id"),
        search=search,
        effective_rule=effective_rule,
        field_mapping=field_mapping,
        term_normalization=state.get("_term_normalization"),
        term_normalization_error=state.get("_term_normalization_error"),
        custom_filters=list(state.get("_custom_filters", [])),  # type: ignore[typeddict-item]
        errors=state.setdefault("errors", []),
    )


def _intent_result_from_state(state: AgentState) -> IntentResult:
    return IntentResult(
        intent=str(state.get("intent") or "query"),
        retrieval_query=str(state.get("_retrieval_query") or state.get("query") or ""),  # type: ignore[typeddict-item]
        rewritten_query=str(state.get("_rewritten_query") or state.get("query") or ""),  # type: ignore[typeddict-item]
        indicator_name=str(state.get("_indicator_name") or ""),  # type: ignore[typeddict-item]
        context_source=state.get("_context_source"),  # type: ignore[typeddict-item]
        custom_filters=list(state.get("_custom_filters") or []),  # type: ignore[typeddict-item]
        context_updates=list(state.get("_context_updates") or []),  # type: ignore[typeddict-item]
        clear_working_caliber=bool(state.get("_clear_working_caliber", False)),  # type: ignore[typeddict-item]
    )


def _apply_prepared_to_state(state: AgentState, prepared: PreparedRequest) -> None:
    state["intent"] = prepared.intent
    state["rule_id"] = prepared.rule_id
    state["_retrieval_query"] = prepared.retrieval_query  # type: ignore[typeddict-unknown-key]
    state["_custom_filters"] = [  # type: ignore[typeddict-unknown-key]
        item.model_dump() for item in prepared.custom_filters
    ]
    if prepared.search is not None:
        state["search"] = prepared.search.model_dump(exclude_none=True)
    if prepared.effective_rule is not None:
        state["effective_rule"] = prepared.effective_rule.model_dump(exclude_none=True)
    if prepared.field_mapping is not None:
        state["field_mapping"] = prepared.field_mapping.model_dump(exclude_none=True)
    if prepared.term_normalization is not None:
        state["_term_normalization"] = prepared.term_normalization.model_dump(
            exclude_none=True
        )
    if prepared.term_normalization_error:
        state["_term_normalization_error"] = prepared.term_normalization_error


def _record_term_normalize_node(
    recorder: TraceRecorder | None,
    trace_id: str,
    hospital_id: str | None,
    normalization: dict[str, Any] | None,
    error: str = "",
    duration_ms: int = 0,
) -> None:
    payload = normalization or {}
    matches = list(payload.get("matches") or [])
    concepts = [
        {
            "matched_text": item.get("matched_text"),
            "concept_code": item.get("concept_code"),
            "canonical_name": item.get("canonical_name"),
            "relation_type": item.get("relation_type"),
            "sql_safe": item.get("sql_safe", False),
        }
        for item in matches
        if isinstance(item, dict)
    ]
    original = str(payload.get("original_text") or "")
    normalized = str(payload.get("normalized_text") or original)
    _record_trace_node(
        recorder,
        trace_id,
        "term_normalize",
        "terminology",
        "fallback" if error else "success",
        input_summary=original,
        output_summary=(
            "术语库不可用，保留原检索词"
            if error
            else f"命中 {len(concepts)} 个标准概念"
        ),
        input_data={"retrieval_query": original, "hospital_id": hospital_id},
        output_data={
            "normalized_query": normalized,
            "matched_concepts": concepts,
            "ambiguity_count": len(payload.get("ambiguities") or []),
            "sql_eligible": bool(payload.get("sql_eligible")),
            "release_version": payload.get("release_version") or "unavailable",
            "fallback_reason": error or None,
        },
        config_data={
            "source": "MySQL 医学术语库",
            "sql_policy": "仅使用已审批且 SQL 安全的本院映射",
        },
        duration_ms=duration_ms,
    )


def _finish_trace(
    recorder: TraceRecorder | None,
    trace_id: str,
    final_status: str,
    final_answer_summary: str = "",
    intent: str = "",
    error_count: int = 0,
    fallback_count: int = 0,
) -> None:
    if recorder is None:
        return
    try:
        recorder.finish_trace(trace_id, final_status, final_answer_summary, intent, error_count, fallback_count)
    except Exception:
        pass


def _record_effective_rule_node(
    recorder: TraceRecorder | None,
    trace_id: str,
    rule_id: str | None,
    hospital_id: str | None,
    effective: dict[str, Any] | None,
    duration_ms: int = 0,
) -> None:
    if not effective:
        return
    _record_trace_node(
        recorder,
        trace_id,
        "effective_rule_resolve",
        "kb_tool",
        "success",
        input_summary=f"{hospital_id or ''}/{rule_id or ''}",
        output_summary=str(effective.get("effective_level") or ""),
        rule_id=str(rule_id or ""),
        input_data={"rule_id": rule_id, "hospital_id": hospital_id},
        output_data={
            "rule_id": effective.get("rule_id") or rule_id,
            "rule_name": effective.get("rule_name"),
            "effective_level": effective.get("effective_level"),
            "rule_source": effective.get("rule_source", "wiki"),
            "national_version": effective.get("national_version"),
            "hospital_version": effective.get("hospital_version"),
            "overridden_fields": effective.get("overridden_fields", []),
            "fallback_chain": effective.get("fallback_chain", []),
            "warnings": effective.get("warnings", []),
        },
        config_data={"tool": "RuleRepository.get_effective_rule"},
        duration_ms=duration_ms,
    )


def _record_context_trace_nodes(
    recorder: TraceRecorder | None,
    trace_id: str,
    query: str,
    resolution: ContextResolution | None,
    *,
    duration_ms: int = 0,
) -> None:
    if resolution is None:
        return
    snapshot = resolution.snapshot
    clarification = resolution.clarification
    overrides = [
        item.model_dump(mode="json")
        for item in resolution.context.working_caliber.overrides
    ]
    blockers = [item.model_dump(mode="json") for item in snapshot.blockers]
    if clarification is not None:
        resolution_status = "clarification_required"
        resolve_summary = clarification.question
    elif blockers:
        resolution_status = "pending_mapping"
        resolve_summary = f"已解析 {len(overrides)} 项临时口径，等待字段映射"
    elif overrides:
        resolution_status = "ready"
        resolve_summary = f"已解析 {len(overrides)} 项当前会话临时口径"
    else:
        resolution_status = "official_caliber"
        resolve_summary = "未检测到临时调整"
    _record_trace_node(
        recorder,
        trace_id,
        "context_resolve",
        "context",
        "warning" if clarification is not None else "success",
        input_summary=query,
        output_summary=resolve_summary,
        rule_id=snapshot.rule_id,
        input_data={
            "query": query,
            "structured_context": {
                "context_version": max(0, resolution.context.context_version - 1),
                "active_rule": resolution.context.active_rule.model_dump(mode="json"),
            },
            "context_updates": overrides,
        },
        output_data={
            "resolution_status": resolution_status,
            "context_version": resolution.context.context_version,
            "clarification": (
                clarification.model_dump(mode="json")
                if clarification is not None
                else None
            ),
            "override_count": len(overrides),
        },
        config_data={
            "authority": "结构化会话状态优先于原始历史消息",
            "ambiguity_policy": "先确认后执行",
        },
        duration_ms=duration_ms,
    )

    caliber_mode = "session_override" if overrides else "hospital_effective"
    if clarification is not None:
        apply_summary = "等待用户确认后再应用临时口径"
    elif blockers:
        apply_summary = "临时口径已保存，需补齐医院字段映射后执行"
    elif overrides:
        apply_summary = f"已应用 {len(overrides)} 项当前会话临时口径"
    else:
        apply_summary = "使用本院生效口径"
    _record_trace_node(
        recorder,
        trace_id,
        "working_caliber_apply",
        "caliber_context",
        "success" if snapshot.executable else "warning",
        input_summary=snapshot.rule_id,
        output_summary=apply_summary,
        rule_id=snapshot.rule_id,
        input_data={
            "rule_id": snapshot.rule_id,
            "hospital_id": resolution.context.active_rule.hospital_id,
            "structured_context": {
                "context_version": resolution.context.context_version,
                "working_caliber": resolution.context.working_caliber.model_dump(
                    mode="json"
                ),
            },
        },
        output_data={
            "execution_context": snapshot.model_dump(mode="json"),
            "caliber_mode": caliber_mode,
            "overrides": overrides,
            "executable": snapshot.executable,
            "blockers": blockers,
        },
        config_data={
            "scope": "当前会话",
            "fallback_policy": "禁止静默回退旧字段",
        },
        duration_ms=1,
    )


def _record_sql_trace_nodes(
    recorder: TraceRecorder | None,
    trace_id: str,
    result: dict[str, Any],
    rule_id: str | None,
    hospital_id: str | None,
) -> None:
    node_timings = result.get("_node_timings") if isinstance(result.get("_node_timings"), dict) else {}

    def result_duration(node_name: str) -> int:
        try:
            return int(node_timings.get(node_name) or 0)
        except (TypeError, ValueError):
            return 0

    precheck = result.get("precheck")
    if isinstance(precheck, dict):
        ok = bool(precheck.get("ok"))
        _record_trace_node(
            recorder,
            trace_id,
            "field_mapping_precheck",
            "metadata_check",
            "success" if ok else "failed",
            input_summary=f"{hospital_id or ''}/{rule_id or ''}",
            output_summary="通过" if ok else str(precheck.get("message") or precheck.get("error") or "字段预校验未通过"),
            rule_id=str(rule_id or ""),
            input_data={"rule_id": rule_id, "hospital_id": hospital_id},
            output_data={
                "ok": ok,
                "missing_mappings": precheck.get("missing_mappings", []),
                "missing_columns": precheck.get("missing_columns", []),
                "main_table": precheck.get("main_table", ""),
                "field_mapping": precheck.get("field_mapping", {}),
            },
            config_data={"tool": "precheck_rule_fields", "metadata_source": "med_metadata_column"},
            duration_ms=result_duration("field_mapping_precheck"),
        )

    if result.get("sql_id") or result.get("sql_text"):
        _record_trace_node(
            recorder,
            trace_id,
            "sql_generate",
            "sqlgen",
            "success" if result.get("status", "success") != "field_precheck_failed" else "skipped",
            input_summary=str(rule_id or ""),
            output_summary=str(result.get("sql_id") or ""),
            rule_id=str(rule_id or ""),
            sql_id=str(result.get("sql_id") or ""),
            input_data={
                "rule_id": rule_id,
                "hospital_id": hospital_id,
                "dialect": result.get("dialect"),
                "params": result.get("params", {}),
            },
            output_data={
                "sql_id": result.get("sql_id"),
                "sql_status": result.get("sql_status"),
                "sql_preview": str(result.get("sql_text") or "")[:1500],
            },
            config_data={"renderer": "Jinja2", "dialect": result.get("dialect", "")},
            duration_ms=result_duration("sql_generate"),
        )

    validation = result.get("validation")
    if isinstance(validation, dict):
        ok = bool(validation.get("ok"))
        _record_trace_node(
            recorder,
            trace_id,
            "sql_validate",
            "sql_validator",
            "success" if ok else "failed",
            input_summary=str(result.get("sql_id") or ""),
            output_summary=str(validation.get("message") or validation.get("error") or ""),
            rule_id=str(rule_id or ""),
            sql_id=str(result.get("sql_id") or ""),
            input_data={"sql_id": result.get("sql_id"), "rule_id": rule_id},
            output_data={
                "ok": ok,
                "message": validation.get("message"),
                "error": validation.get("error"),
            },
            config_data={"allow": "SELECT", "deny": "DDL / DML / 多语句"},
            duration_ms=result_duration("sql_validate"),
        )

    trial = result.get("trial_run")
    if isinstance(trial, dict) and trial:
        business_source = current_business_source().source_id
        business_tool = (
            "execute_sql_"
            + re.sub(r"[^0-9a-zA-Z]+", "_", business_source).strip("_").lower()
        )
        status = str(trial.get("status") or "")
        _record_trace_node(
            recorder,
            trace_id,
            "sql_trial_mcp",
            "mcp_tool",
            "success" if status in {"success", "empty"} else "failed",
            input_summary=str(result.get("sql_id") or ""),
            output_summary=status,
            rule_id=str(rule_id or ""),
            sql_id=str(result.get("sql_id") or ""),
            run_id=str(trial.get("run_id") or ""),
            tool_name=business_tool,
            db_source=str(trial.get("source") or business_source),
            duration_ms=int(trial.get("duration_ms") or 0),
            input_data={
                "sql_id": result.get("sql_id"),
                "params": result.get("params", {}),
                "db_source": str(trial.get("source") or business_source),
            },
            output_data={
                "run_id": trial.get("run_id"),
                "status": status,
                "result_value": trial.get("result_value"),
                "numerator_count": trial.get("numerator_count"),
                "denominator_count": trial.get("denominator_count"),
                "source": trial.get("source"),
                "stat_start": trial.get("stat_start"),
                "stat_end": trial.get("stat_end"),
                "duration_ms": trial.get("duration_ms"),
                "error_message": trial.get("error_message"),
            },
            config_data={"tool": business_tool, "readonly": True},
            error_message=str(trial.get("error_message") or ""),
        )


def detect_intent(query: str) -> str:
    """关键词兜底：chat/query/feedback/generate_sql/diagnose/metadata_sync。"""
    return detect_intent_by_rule(query)


def langgraph_installed() -> bool:
    return find_spec("langgraph") is not None


def workflow_engine_name() -> str:
    return "langgraph" if langgraph_installed() else "deterministic_fallback"


def _run_deterministic(
    state: AgentState,
    tools: Any,
    llm_client: LLMClient | None = None,
    orchestrator: CoreIndicatorOrchestrator | None = None,
) -> AgentState:
    active_orchestrator = orchestrator or _create_agent_orchestrator(
        DEFAULT_KB_ROOT, tools, llm_client
    )
    query = state["query"]
    errors: list[str] = state.setdefault("errors", [])
    intent_data = active_orchestrator.understand_request(
        query, state.get("memory_context"), errors
    )
    state["intent"] = intent_data.intent
    state["_retrieval_query"] = intent_data.retrieval_query  # type: ignore[typeddict-unknown-key]
    state["_rewritten_query"] = intent_data.rewritten_query  # type: ignore[typeddict-unknown-key]
    state["_indicator_name"] = intent_data.indicator_name  # type: ignore[typeddict-unknown-key]
    state["_context_source"] = intent_data.context_source  # type: ignore[typeddict-unknown-key]
    state["_custom_filters"] = [item.model_dump() for item in intent_data.custom_filters]  # type: ignore[typeddict-unknown-key]
    state["_context_updates"] = list(intent_data.context_updates)  # type: ignore[typeddict-unknown-key]
    state["_clear_working_caliber"] = intent_data.clear_working_caliber  # type: ignore[typeddict-unknown-key]
    prepared = active_orchestrator.create_request(
        query, state.get("hospital_id"), intent_data, errors
    )
    if prepared.intent == "chat":
        state["rule_id"] = None
        state["generation_method"] = "chat"
        state["answer"], _ = active_orchestrator.answer(prepared)
        return state
    term_start = time.perf_counter()
    active_orchestrator.normalize_request(prepared)
    _record_node_duration(state, "term_normalize", term_start)
    active_orchestrator.search_request(prepared, state.get("memory_context"))
    _apply_prepared_to_state(state, prepared)
    if not prepared.rule_id:
        state["generation_method"] = "tool"
        state["answer"] = "未命中规则。请提供更明确的指标名称或 rule_id。"
        return state

    active_orchestrator.resolve_request(prepared)
    _apply_prepared_to_state(state, prepared)

    if prepared.intent == "feedback":
        state["feedback_preview"] = active_orchestrator.preview_feedback(prepared)
        state["generation_method"] = "tool"
        state["answer"] = (
            "检测到本院口径反馈，尚未写入待审批。\n"
            f"指标名称：{prepared.effective_rule.rule_name}\n"
            "请在口径差异确认窗口中提交，之后才会进入 pending 等待审批。"
        )
        return state

    state["answer"], state["generation_method"] = active_orchestrator.answer(prepared)
    return state


def _run_langgraph(
    state: AgentState,
    tools: Any,
    llm_client: LLMClient | None = None,
    orchestrator: CoreIndicatorOrchestrator | None = None,
) -> AgentState:
    active_orchestrator = orchestrator or _create_agent_orchestrator(
        DEFAULT_KB_ROOT, tools, llm_client
    )
    try:
        from langgraph.graph import END, StateGraph
    except Exception as exc:
        state["workflow_engine"] = "deterministic_fallback"
        state.setdefault("errors", []).append(f"LangGraph unavailable, using deterministic fallback: {exc}")
        return _run_deterministic(state, tools, llm_client, active_orchestrator)
    state["workflow_engine"] = "langgraph"

    def intent_node(s: AgentState) -> AgentState:
        node_start = time.perf_counter()
        intent_data = active_orchestrator.understand_request(
            s["query"], s.get("memory_context"), s.setdefault("errors", [])
        )
        s["intent"] = intent_data.intent
        s["_retrieval_query"] = intent_data.retrieval_query  # type: ignore[typeddict-unknown-key]
        s["_rewritten_query"] = intent_data.rewritten_query  # type: ignore[typeddict-unknown-key]
        s["_indicator_name"] = intent_data.indicator_name  # type: ignore[typeddict-unknown-key]
        s["_context_source"] = intent_data.context_source  # type: ignore[typeddict-unknown-key]
        s["_custom_filters"] = [item.model_dump() for item in intent_data.custom_filters]  # type: ignore[typeddict-unknown-key]
        s["_context_updates"] = list(intent_data.context_updates)  # type: ignore[typeddict-unknown-key]
        s["_clear_working_caliber"] = intent_data.clear_working_caliber  # type: ignore[typeddict-unknown-key]
        _record_node_duration(s, "intent_detect", node_start)
        return s

    def chat_node(s: AgentState) -> AgentState:
        node_start = time.perf_counter()
        s["rule_id"] = None
        s["generation_method"] = "chat"
        s["answer"] = active_orchestrator.chat_answer()
        _record_node_duration(s, "final_response", node_start)
        return s

    def search_node(s: AgentState) -> AgentState:
        prepared = active_orchestrator.create_request(
            s["query"],
            s.get("hospital_id"),
            _intent_result_from_state(s),
            s.setdefault("errors", []),
        )
        term_start = time.perf_counter()
        active_orchestrator.normalize_request(prepared)
        _record_node_duration(s, "term_normalize", term_start)
        node_start = time.perf_counter()
        active_orchestrator.search_request(prepared, s.get("memory_context"))
        _apply_prepared_to_state(s, prepared)
        _record_node_duration(s, "rule_search", node_start)
        return s

    def query_node(s: AgentState) -> AgentState:
        if not s.get("rule_id"):
            s["generation_method"] = "tool"
            s["answer"] = "未命中规则。请提供更明确的指标名称或 rule_id。"
            return s
        effective_start = time.perf_counter()
        prepared = _prepared_request_from_state(s)
        active_orchestrator.resolve_request(prepared)
        _apply_prepared_to_state(s, prepared)
        _record_node_duration(s, "effective_rule_resolve", effective_start)
        answer_start = time.perf_counter()
        s["answer"], s["generation_method"] = active_orchestrator.answer(prepared)
        _record_node_duration(s, "final_response", answer_start)
        return s

    def feedback_node(s: AgentState) -> AgentState:
        if not s.get("rule_id"):
            s["generation_method"] = "tool"
            s["answer"] = "未命中规则，无法记录反馈。请补充指标名称。"
            return s
        effective_start = time.perf_counter()
        prepared = _prepared_request_from_state(s)
        active_orchestrator.resolve_request(prepared)
        _apply_prepared_to_state(s, prepared)
        _record_node_duration(s, "effective_rule_resolve", effective_start)
        answer_start = time.perf_counter()
        s["feedback_preview"] = active_orchestrator.preview_feedback(prepared)
        s["generation_method"] = "tool"
        s["answer"] = (
            "检测到本院口径反馈，尚未写入待审批。\n"
            f"指标名称：{prepared.effective_rule.rule_name}\n"
            "请在口径差异确认窗口中提交，之后才会进入 pending 等待审批。"
        )
        result = s
        _record_node_duration(result, "final_response", answer_start)
        return result

    def route_after_intent(s: AgentState) -> str:
        return "chat" if s.get("intent") == "chat" else "search"

    def route_after_search(s: AgentState) -> str:
        intent = s.get("intent", "query")
        if intent == "feedback":
            return "feedback"
        if intent in ("generate_sql", "diagnose", "metadata_sync", "trial_run"):
            return intent
        return "query"

    def sql_node(s: AgentState) -> AgentState:
        s["generation_method"] = "tool"
        s["answer"] = "SQL 生成请通过流式对话触发：先查询指标，再输入「生成 SQL」。"
        return s

    def trial_node(s: AgentState) -> AgentState:
        s["generation_method"] = "tool"
        s["answer"] = "试运行请通过流式对话触发：生成 SQL 后输入「试运行」即可。"
        return s

    def diagnose_node(s: AgentState) -> AgentState:
        s["generation_method"] = "tool"
        s["answer"] = ("异常排查请使用 API 接口 POST /api/diagnose/run，"
                        "或通过流式对话触发。系统将执行三层排查：结构校验 → 口径规则 → 数据质量。")
        return s

    def sync_node(s: AgentState) -> AgentState:
        s["generation_method"] = "tool"
        s["answer"] = ("元数据同步请使用 API 接口 POST /api/metadata/sync。"
                        "同步后系统将自动更新表结构和字段映射信息。")
        return s

    graph = StateGraph(AgentState)
    graph.add_node("intent", intent_node)
    graph.add_node("chat", chat_node)
    graph.add_node("search", search_node)
    graph.add_node("query", query_node)
    graph.add_node("feedback", feedback_node)
    graph.add_node("generate_sql", sql_node)
    graph.add_node("trial_run", trial_node)
    graph.add_node("diagnose", diagnose_node)
    graph.add_node("metadata_sync", sync_node)
    graph.set_entry_point("intent")
    graph.add_conditional_edges("intent", route_after_intent, {"chat": "chat", "search": "search"})
    graph.add_conditional_edges("search", route_after_search, {
        "query": "query", "feedback": "feedback",
        "generate_sql": "generate_sql", "trial_run": "trial_run",
        "diagnose": "diagnose", "metadata_sync": "metadata_sync",
    })
    graph.add_edge("chat", END)
    graph.add_edge("query", END)
    graph.add_edge("feedback", END)
    graph.add_edge("generate_sql", END)
    graph.add_edge("trial_run", END)
    graph.add_edge("diagnose", END)
    graph.add_edge("metadata_sync", END)
    result = graph.compile().invoke(state)
    result["workflow_engine"] = "langgraph"
    return result


def _localize_diagnose_text(text: str) -> str:
    value = str(text or "")
    replacements = [
        ("Required business field ", "\u5fc5\u586b\u4e1a\u52a1\u5b57\u6bb5 "),
        (" is nullable in metadata.", " \u5728\u5143\u6570\u636e\u4e2d\u5141\u8bb8\u4e3a\u7a7a\u3002"),
        ("Focus on null rate in data quality checks.", "\u8bf7\u5728\u6570\u636e\u8d28\u91cf\u6821\u9a8c\u4e2d\u91cd\u70b9\u5173\u6ce8\u7a7a\u503c\u7387\u3002"),
        ("Formula contains division but no explicit zero-denominator guard.", "\u516c\u5f0f\u5305\u542b\u9664\u6cd5\uff0c\u4f46\u672a\u770b\u5230\u660e\u786e\u7684\u5206\u6bcd\u4e3a\u96f6\u4fdd\u62a4\u3002"),
        ("Generated SQL should use NULLIF or CASE for denominator protection.", "\u751f\u6210 SQL \u65f6\u5e94\u4f7f\u7528 NULLIF \u6216 CASE \u505a\u5206\u6bcd\u4fdd\u62a4\u3002"),
        ("Business main table sample is small: ", "\u4e1a\u52a1\u4e3b\u8868\u6837\u672c\u91cf\u8f83\u5c0f\uff1a"),
        (" rows.", " \u884c\u3002"),
        ("Confirm this test DB or statistic period has enough coverage.", "\u8bf7\u786e\u8ba4\u5f53\u524d\u6d4b\u8bd5\u5e93\u6216\u7edf\u8ba1\u5468\u671f\u662f\u5426\u8986\u76d6\u8db3\u591f\u6570\u636e\u3002"),
        ("Column ", "\u5b57\u6bb5 "),
        (" null rate is acceptable: ", " \u7a7a\u503c\u7387\u53ef\u63a5\u53d7\uff1a"),
        (" has elevated null rate: ", " \u7a7a\u503c\u7387\u504f\u9ad8\uff1a"),
        ("Required column ", "\u5fc5\u586b\u5b57\u6bb5 "),
        (" has high null rate: ", " \u7a7a\u503c\u7387\u8f83\u9ad8\uff1a"),
        ("Datetime column ", "\u65f6\u95f4\u5b57\u6bb5 "),
        (" contains nulls: ", " \u5b58\u5728\u7a7a\u503c\uff1a"),
        ("Datetime nulls may affect numerator, denominator, or statistic period.", "\u65f6\u95f4\u5b57\u6bb5\u7a7a\u503c\u53ef\u80fd\u5f71\u54cd\u5206\u5b50\u3001\u5206\u6bcd\u6216\u7edf\u8ba1\u5468\u671f\u3002"),
        ("Check source data quality or whether the mapped field is correct.", "\u8bf7\u68c0\u67e5\u6e90\u6570\u636e\u8d28\u91cf\u6216\u786e\u8ba4\u6620\u5c04\u5b57\u6bb5\u662f\u5426\u6b63\u786e\u3002"),
        ("Confirm whether this field participates in filters or grouping.", "\u8bf7\u786e\u8ba4\u8be5\u5b57\u6bb5\u662f\u5426\u53c2\u4e0e\u7b5b\u9009\u6216\u5206\u7ec4\u3002"),
    ]
    for source, target in replacements:
        value = value.replace(source, target)
    return value


def _format_diagnose_answer(diag_result: dict[str, Any]) -> str:
    lines = ["\U0001f4cb \u4e09\u5c42\u5f02\u5e38\u6392\u67e5\u7ed3\u679c\uff1a\n"]
    for layer in diag_result.get("layers", []):
        checks = layer.get("checks", []) or []
        warnings = [c for c in checks if c.get("status") == "warn"]
        failures = [c for c in checks if c.get("status") == "fail"]
        if failures or not layer.get("ok"):
            icon = "\u274c"
            state_text = "\u672a\u901a\u8fc7"
        elif warnings:
            icon = "\u26a0\ufe0f"
            state_text = "\u901a\u8fc7\u4f46\u6709\u98ce\u9669"
        else:
            icon = "\u2705"
            state_text = "\u6b63\u5e38"
        lines.append(f"{icon} \u7b2c{layer.get('layer')}\u5c42\uff1a{layer.get('layer_name')} - {state_text}")
        if failures:
            lines.append(f"   \u6839\u56e0\uff1a{_localize_diagnose_text(layer.get('diagnose_type', ''))}")
            if layer.get("problem_detail"):
                lines.append(f"   \u8be6\u60c5\uff1a{_localize_diagnose_text(layer.get('problem_detail'))}")
            if layer.get("repair_suggest"):
                lines.append(f"   \u5efa\u8bae\uff1a{_localize_diagnose_text(layer.get('repair_suggest'))}")
        elif warnings:
            for check in warnings:
                lines.append(f"   \u6ce8\u610f\uff1a{_localize_diagnose_text(check.get('message', ''))}")
                if check.get("repair_suggest"):
                    lines.append(f"   \u5efa\u8bae\uff1a{_localize_diagnose_text(check.get('repair_suggest'))}")
        else:
            lines.append("   \u6b63\u5e38")
    status = diag_result.get("diagnose_status")
    summary = diag_result.get("summary", "")
    if status == "warning":
        lines.append(f"\n\u603b\u7ed3\uff1a\u8bca\u65ad\u901a\u8fc7\uff0c\u4f46\u5b58\u5728\u6ce8\u610f\u9879\u3002{_localize_diagnose_text(summary)}")
    elif status == "failed":
        lines.append(f"\n\u603b\u7ed3\uff1a\u8bca\u65ad\u672a\u901a\u8fc7\u3002{_localize_diagnose_text(summary)}")
    else:
        lines.append(f"\n\u603b\u7ed3\uff1a{_localize_diagnose_text(summary) or '\u5168\u90e8\u6b63\u5e38'}")
    return "\n".join(lines)


def run_chat(
    query: str,
    hospital_id: str | None = None,
    kb_root: str | Path = DEFAULT_KB_ROOT,
    use_llm: bool = False,
    llm_client: LLMClient | None = None,
    session_id: str | None = None,
    memory: ConversationMemory | None = None,
    rule_repository: Any | None = None,
    orchestrator: CoreIndicatorOrchestrator | None = None,
) -> dict[str, Any]:
    tools = rule_repository or KnowledgeBaseTools(kb_root)
    memory_store = memory or ConversationMemory(DEFAULT_MEMORY_ROOT)
    memory_start = time.perf_counter()
    active_session_id = memory_store.ensure_session(session_id, hospital_id)
    structured_context = _load_structured_context(
        memory_store, active_session_id, hospital_id
    )
    memory_context = _load_memory_context(
        memory_store, active_session_id, structured_context
    )
    memory_duration_ms = _elapsed_ms(memory_start)
    trace_id, trace_recorder = _start_trace(active_session_id, hospital_id, query)
    _record_trace_node(
        trace_recorder,
        trace_id,
        "memory_load",
        "memory",
        "success",
        input_summary=active_session_id,
        output_summary=str(memory_context.get("rule_id") or ""),
        input_data={"session_id": session_id, "active_session_id": active_session_id},
        output_data={
            "active_session_id": active_session_id,
            "memory_context": _trace_safe_memory_context(memory_context),
        },
        config_data={"storage": "SQLite + JSONL"},
        duration_ms=memory_duration_ms,
    )
    state: AgentState = {
        "query": query,
        "hospital_id": hospital_id,
        "session_id": active_session_id,
        "memory_context": memory_context,
        "errors": [],
    }
    user_message_id = memory_store.append_message(
        active_session_id,
        "user",
        query,
        {
            "hospital_id": hospital_id,
            "memory_context": _trace_safe_memory_context(memory_context),
        },
    )
    active_llm = llm_client if use_llm else None
    if use_llm and active_llm is None:
        active_llm = OllamaClient()
    active_orchestrator = orchestrator or _create_agent_orchestrator(
        kb_root, tools, active_llm
    )
    try:
        result = _run_langgraph(
            state, tools, active_llm, orchestrator=active_orchestrator
        )
    except KBToolError as exc:
        result = {
            **state,
            "intent": state.get("intent", "query"),
            "generation_method": "tool",
            "answer": f"\u77e5\u8bc6\u5e93\u5de5\u5177\u8c03\u7528\u5931\u8d25\uff1a{exc}",
            "errors": [str(exc)],
        }
    context_resolution: ContextResolution | None = None
    context_duration_ms = 0
    if result.get("effective_rule") and result.get("intent") != "feedback":
        try:
            context_started = time.perf_counter()
            context_resolution = _resolve_and_save_structured_context(
                memory_store=memory_store,
                session_id=active_session_id,
                query=query,
                structured_context=structured_context,
                effective_rule=result.get("effective_rule"),
                field_mapping=result.get("field_mapping") or {},
                source_message_id=user_message_id,
                memory_context=memory_context,
                llm_updates=list(result.get("_context_updates") or []),
            )
            context_duration_ms = _elapsed_ms(context_started)
            result["execution_context"] = context_resolution.snapshot.model_dump(
                mode="json"
            )
            result["structured_context"] = context_resolution.context.model_dump(
                mode="json"
            )
            if context_resolution.clarification is not None:
                result["status"] = "context_clarification_required"
                result["answer"] = _context_block_answer(context_resolution)
                result["generation_method"] = "tool"
            elif context_resolution.blocked:
                result["status"] = "context_pending_mapping"
                result["answer"] = (
                    str(result.get("answer") or "")
                    + "\n\n"
                    + _context_block_answer(context_resolution)
                ).strip()
        except (ContextStorageError, ContextVersionConflict) as exc:
            result["status"] = "context_storage_failed"
            result["answer"] = f"本轮不能安全保存会话口径：{exc}"
            result.setdefault("errors", []).append(str(exc))
    _record_trace_node(
        trace_recorder,
        trace_id,
        "intent_detect",
        "llm_or_rule",
        "success",
        input_summary=query,
        output_summary=str(result.get("intent", "")),
        input_data={
            "query": query,
            "session_memory": _trace_safe_memory_context(memory_context),
            "use_llm": use_llm,
        },
        output_data={
            "intent": result.get("intent"),
            "retrieval_query": (result.get("search") or {}).get("query", query) if isinstance(result.get("search"), dict) else query,
            "rewritten_query": result.get("_rewritten_query", query),
            "indicator_name": result.get("_indicator_name", ""),
            "custom_filters": result.get("_custom_filters", []),
        },
        config_data={
            "strategy": "规则兜底 + 可选 LLM 意图识别",
            "workflow_engine": result.get("workflow_engine") or workflow_engine_name(),
        },
        duration_ms=_node_duration(result, "intent_detect"),
    )
    if result.get("intent") in RULE_INTENTS:
        _record_term_normalize_node(
            trace_recorder,
            trace_id,
            hospital_id,
            result.get("_term_normalization")
            if isinstance(result.get("_term_normalization"), dict)
            else None,
            str(result.get("_term_normalization_error") or ""),
            duration_ms=_node_duration(result, "term_normalize"),
        )
    if result.get("search") or result.get("rule_id"):
        search_payload = result.get("search") if isinstance(result.get("search"), dict) else {}
        _record_trace_node(
            trace_recorder,
            trace_id,
            "rule_search",
            "kb_tool",
            "success",
            input_summary=query,
            output_summary=str(result.get("rule_id") or ""),
            rule_id=str(result.get("rule_id") or ""),
            input_data={
                "retrieval_query": search_payload.get("query", query),
                "hospital_id": hospital_id,
            },
            output_data={
                "rule_id": result.get("rule_id"),
                "matched_count": _search_match_count(search_payload),
                "context_source": search_payload.get("context_source"),
            },
            config_data={
                "tool": "RuleRepository.search",
                "priority": "MySQL 规则库 > Wiki 只读兜底",
            },
            duration_ms=_node_duration(result, "rule_search"),
        )
    _record_effective_rule_node(
        trace_recorder,
        trace_id,
        str(result.get("rule_id") or "") or None,
        hospital_id,
        result.get("effective_rule") if isinstance(result.get("effective_rule"), dict) else None,
        duration_ms=_node_duration(result, "effective_rule_resolve"),
    )
    _record_context_trace_nodes(
        trace_recorder,
        trace_id,
        query,
        context_resolution,
        duration_ms=context_duration_ms,
    )
    result.setdefault("generation_method", "tool")
    result.setdefault("workflow_engine", workflow_engine_name())
    result.setdefault("session_id", active_session_id)
    result.setdefault("memory_context", memory_context)
    result["orchestrator"] = active_orchestrator.orchestrator_id
    result["agent_owner"] = active_orchestrator.owner_for_intent(
        str(result.get("intent") or "query")
    )
    result["trace_id"] = trace_id
    effective_rule = result.get("effective_rule") or {}
    memory_store.append_message(
        active_session_id,
        "assistant",
        str(result.get("answer", "")),
        {
            "intent": result.get("intent"),
            "rule_id": result.get("rule_id"),
            "rule_name": effective_rule.get("rule_name"),
            "generation_method": result.get("generation_method"),
            "errors": result.get("errors", []),
            "has_feedback_preview": bool(result.get("feedback_preview")),
            "execution_context": result.get("execution_context"),
            **_stat_period_metadata(query, memory_context),
        },
    )
    error_count = len(result.get("errors", []) or [])
    _record_trace_node(
        trace_recorder,
        trace_id,
        "final_response",
        "agent",
        "success" if error_count == 0 else "fallback",
        output_summary=str(result.get("answer", ""))[:500],
        rule_id=str(result.get("rule_id") or ""),
        input_data={
            "intent": result.get("intent"),
            "rule_id": result.get("rule_id"),
            "generation_method": result.get("generation_method"),
            "errors": result.get("errors", []),
        },
        output_data={
            "answer_preview": str(result.get("answer", ""))[:1000],
            "trace_id": trace_id,
            "final_status": "success" if error_count == 0 else "fallback",
        },
        config_data={
            "storage": "ConversationMemory + TraceRecorder",
        },
        duration_ms=_node_duration(result, "final_response"),
    )
    _finish_trace(
        trace_recorder,
        trace_id,
        "success" if error_count == 0 else "fallback",
        str(result.get("answer", ""))[:500],
        intent=str(result.get("intent", "")),
        error_count=error_count,
        fallback_count=1 if error_count else 0,
    )
    return dict(result)


def run_chat_stream(
    query: str,
    hospital_id: str | None = None,
    kb_root: str | Path = DEFAULT_KB_ROOT,
    use_llm: bool = False,
    llm_client: LLMClient | None = None,
    session_id: str | None = None,
    memory: ConversationMemory | None = None,
    rule_repository: Any | None = None,
    orchestrator: CoreIndicatorOrchestrator | None = None,
) -> Iterator[Tuple[str, dict[str, Any]]]:
    """真正的流式对话：逐 token 从 Ollama 产出并立即 yield。

    返回 (event_type, payload) 元组的生成器：
      - ("meta", {...})         会话元信息
      - ("token", {"text": "..."})  逐 token
      - ("feedback_preview", {...})  反馈对比预览
      - ("done", {...})         最终结果
    """
    tools = rule_repository or KnowledgeBaseTools(kb_root)
    memory_store = memory or ConversationMemory(DEFAULT_MEMORY_ROOT)
    memory_start = time.perf_counter()
    active_session_id = memory_store.ensure_session(session_id, hospital_id)
    structured_context = _load_structured_context(
        memory_store, active_session_id, hospital_id
    )
    memory_context = _load_memory_context(
        memory_store, active_session_id, structured_context
    )
    memory_duration_ms = _elapsed_ms(memory_start)
    trace_id, trace_recorder = _start_trace(active_session_id, hospital_id, query)
    _record_trace_node(
        trace_recorder,
        trace_id,
        "memory_load",
        "memory",
        "success",
        input_summary=active_session_id,
        output_summary=str(memory_context.get("rule_id") or ""),
        input_data={"session_id": session_id, "active_session_id": active_session_id},
        output_data={
            "active_session_id": active_session_id,
            "memory_context": _trace_safe_memory_context(memory_context),
        },
        config_data={"storage": "SQLite + JSONL"},
        duration_ms=memory_duration_ms,
    )

    state: AgentState = {
        "query": query,
        "hospital_id": hospital_id,
        "session_id": active_session_id,
        "memory_context": memory_context,
        "errors": [],
    }
    user_message_id = memory_store.append_message(
        active_session_id, "user", query,
        {
            "hospital_id": hospital_id,
            "memory_context": _trace_safe_memory_context(memory_context),
        },
    )

    active_llm = llm_client if use_llm else None
    if use_llm and active_llm is None:
        active_llm = OllamaClient()
    active_orchestrator = orchestrator or _create_agent_orchestrator(
        kb_root, tools, active_llm
    )

    yield ("meta", {
        "session_id": active_session_id, "intent": None,
        "rule_id": None, "generation_method": "preparing",
        "trace_id": trace_id,
    })
    yield ("progress", {"message": "\u6b63\u5728\u8bc6\u522b\u95ee\u9898\u610f\u56fe"})

    # ---- Phase 1: 意图识别 + 知识库检索（同步，很快） ----
    errors: list[str] = state.setdefault("errors", [])
    intent_start = time.perf_counter()
    intent_data = active_orchestrator.understand_request(
        query, memory_context, errors
    )
    intent_payload = intent_data.model_dump(exclude_none=True)
    intent_duration_ms = _elapsed_ms(intent_start)
    state["intent"] = intent_data.intent
    state["_custom_filters"] = intent_payload.get("custom_filters", [])  # type: ignore[typeddict-unknown-key]
    state["_rewritten_query"] = intent_data.rewritten_query  # type: ignore[typeddict-unknown-key]
    state["_indicator_name"] = intent_data.indicator_name  # type: ignore[typeddict-unknown-key]
    state["_retrieval_query"] = intent_data.retrieval_query  # type: ignore[typeddict-unknown-key]
    state["_context_source"] = intent_data.context_source  # type: ignore[typeddict-unknown-key]
    state["_context_updates"] = list(intent_data.context_updates)  # type: ignore[typeddict-unknown-key]
    state["_clear_working_caliber"] = intent_data.clear_working_caliber  # type: ignore[typeddict-unknown-key]
    prepared = active_orchestrator.create_request(
        query, hospital_id, intent_data, errors
    )
    _record_trace_node(
        trace_recorder,
        trace_id,
        "intent_detect",
        "llm_or_rule",
        "success",
        input_summary=query,
        output_summary=str(state.get("intent", "")),
        input_data={
            "query": query,
            "session_memory": _trace_safe_memory_context(memory_context),
            "use_llm": use_llm,
        },
        output_data={
            "intent": state.get("intent"),
            "retrieval_query": intent_data.retrieval_query,
            "rewritten_query": intent_data.rewritten_query,
            "indicator_name": intent_data.indicator_name,
            "context_source": intent_data.context_source,
            "custom_filters": intent_payload.get("custom_filters", []),
        },
        config_data={
            "strategy": "规则兜底 + 可选 LLM 意图识别",
        },
        duration_ms=intent_duration_ms,
    )

    if state["intent"] == "chat":
        yield ("progress", {"message": "\u6b63\u5728\u6574\u7406\u666e\u901a\u5bf9\u8bdd\u56de\u7b54"})
        answer, _ = active_orchestrator.answer(prepared)
        yield ("meta", {
            "session_id": active_session_id, "intent": "chat",
            "rule_id": None, "generation_method": "chat",
        })
        yield ("token", {"text": answer})
        memory_store.append_message(active_session_id, "assistant", answer, {
            "intent": "chat", "rule_id": None,
            "generation_method": "chat", "errors": errors,
        })
        _record_trace_node(
            trace_recorder,
            trace_id,
            "final_response",
            "agent",
            "success",
            output_summary=answer[:500],
            input_data={
                "intent": "chat",
                "rule_id": None,
                "generation_method": "chat",
                "errors": errors,
            },
            output_data={
                "answer_preview": answer[:1000],
                "trace_id": trace_id,
                "final_status": "success",
            },
            config_data={
                "storage": "ConversationMemory + TraceRecorder",
            },
        )
        _finish_trace(trace_recorder, trace_id, "success", answer[:500], intent="chat", error_count=len(errors))
        yield ("done", {
            "session_id": active_session_id, "intent": "chat",
            "rule_id": None, "generation_method": "chat",
            "answer": answer, "errors": errors, "trace_id": trace_id,
            "orchestrator": active_orchestrator.orchestrator_id,
            "agent_owner": active_orchestrator.owner_for_intent("chat"),
        })
        return

    if state["intent"] == "metadata_sync":
        answer = "元数据同步请使用 API：POST /api/metadata/sync。需提供 hospital_id、db_name。"
        yield ("meta", {
            "session_id": active_session_id,
            "intent": "metadata_sync",
            "rule_id": None,
            "generation_method": "tool",
        })
        yield ("token", {"text": answer})
        memory_store.append_message(active_session_id, "assistant", answer, {
            "intent": "metadata_sync",
            "rule_id": None,
            "generation_method": "tool",
            "errors": errors,
        })
        _record_trace_node(
            trace_recorder,
            trace_id,
            "final_response",
            "agent",
            "success",
            output_summary=answer,
            input_data={"intent": "metadata_sync", "rule_id": None},
            output_data={"answer_preview": answer, "trace_id": trace_id, "final_status": "success"},
            config_data={"storage": "ConversationMemory + TraceRecorder"},
        )
        _finish_trace(trace_recorder, trace_id, "success", answer, intent="metadata_sync")
        yield ("done", {
            "session_id": active_session_id,
            "intent": "metadata_sync",
            "rule_id": None,
            "generation_method": "tool",
            "answer": answer,
            "errors": errors,
            "trace_id": trace_id,
            "orchestrator": active_orchestrator.orchestrator_id,
            "agent_owner": active_orchestrator.owner_for_intent("metadata_sync"),
        })
        return

    term_start = time.perf_counter()
    active_orchestrator.normalize_request(prepared)
    term_duration_ms = _elapsed_ms(term_start)
    term_payload = (
        prepared.term_normalization.model_dump(exclude_none=True)
        if prepared.term_normalization is not None
        else None
    )
    _record_term_normalize_node(
        trace_recorder,
        trace_id,
        hospital_id,
        term_payload,
        prepared.term_normalization_error or "",
        duration_ms=term_duration_ms,
    )

    try:
        yield ("progress", {"message": "正在检索 MySQL 指标规则库"})
        search_query = prepared.retrieval_query or query
        search_start = time.perf_counter()
        active_orchestrator.search_request(prepared, memory_context)
        search = prepared.search.model_dump(exclude_none=True) if prepared.search else {}
        search_duration_ms = _elapsed_ms(search_start)
    except KBToolError as exc:
        yield ("meta", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": None, "generation_method": "tool",
        })
        yield ("token", {"text": f"知识库工具调用失败：{exc}"})
        yield ("done", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": None, "generation_method": "tool",
            "answer": f"知识库工具调用失败：{exc}", "errors": [str(exc)],
            "orchestrator": active_orchestrator.orchestrator_id,
            "agent_owner": active_orchestrator.owner_for_intent(str(state.get("intent") or "query")),
        })
        return

    _apply_prepared_to_state(state, prepared)
    rule_id = prepared.rule_id
    _record_trace_node(
        trace_recorder,
        trace_id,
        "rule_search",
        "kb_tool",
        "success",
        input_summary=search_query,
        output_summary=str(rule_id or ""),
        rule_id=str(rule_id or ""),
        input_data={
            "retrieval_query": search_query,
            "hospital_id": hospital_id,
        },
        output_data={
            "rule_id": rule_id,
            "matched_count": _search_match_count(search),
            "context_source": search.get("context_source"),
        },
        config_data={
            "tool": "RuleRepository.search",
            "priority": "MySQL 规则库 > Wiki 只读兜底",
        },
        duration_ms=search_duration_ms,
    )

    if not rule_id:
        answer = "未命中规则。请提供更明确的指标名称或 rule_id。"
        yield ("meta", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": None, "generation_method": "tool",
        })
        yield ("token", {"text": answer})
        memory_store.append_message(active_session_id, "assistant", answer, {
            "intent": state.get("intent"), "rule_id": None,
            "generation_method": "tool", "errors": errors,
        })
        yield ("done", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": None, "generation_method": "tool",
            "answer": answer, "errors": errors,
            "orchestrator": active_orchestrator.orchestrator_id,
            "agent_owner": active_orchestrator.owner_for_intent(str(state.get("intent") or "query")),
        })
        return

    effective_start = time.perf_counter()
    active_orchestrator.resolve_request(prepared)
    _apply_prepared_to_state(state, prepared)
    effective = prepared.effective_rule.model_dump(exclude_none=True)
    effective_duration_ms = _elapsed_ms(effective_start)
    _record_effective_rule_node(trace_recorder, trace_id, rule_id, state.get("hospital_id"), effective, duration_ms=effective_duration_ms)

    context_resolution: ContextResolution | None = None
    execution_context: dict[str, Any] = {}
    if state["intent"] != "feedback":
        try:
            context_started = time.perf_counter()
            context_resolution = _resolve_and_save_structured_context(
                memory_store=memory_store,
                session_id=active_session_id,
                query=query,
                structured_context=structured_context,
                effective_rule=prepared.effective_rule,
                field_mapping=prepared.field_mapping,
                source_message_id=user_message_id,
                memory_context=memory_context,
                llm_updates=list(intent_data.context_updates),
            )
            execution_context = context_resolution.snapshot.model_dump(mode="json")
            _record_context_trace_nodes(
                trace_recorder,
                trace_id,
                query,
                context_resolution,
                duration_ms=_elapsed_ms(context_started),
            )
        except (ContextStorageError, ContextVersionConflict) as exc:
            answer = f"本轮不能安全保存会话口径：{exc}"
            errors.append(str(exc))
            yield ("token", {"text": answer})
            yield ("done", {
                "session_id": active_session_id,
                "intent": state.get("intent"),
                "rule_id": rule_id,
                "generation_method": "tool",
                "status": "context_storage_failed",
                "answer": answer,
                "errors": errors,
                "trace_id": trace_id,
                "orchestrator": active_orchestrator.orchestrator_id,
                "agent_owner": active_orchestrator.owner_for_intent(
                    str(state.get("intent") or "query")
                ),
            })
            return

        must_stop = context_resolution.clarification is not None or (
            context_resolution.blocked
            and state["intent"] in ("generate_sql", "trial_run", "diagnose")
        )
        if must_stop:
            answer = _context_block_answer(context_resolution)
            status = (
                "context_clarification_required"
                if context_resolution.clarification is not None
                else "context_blocked"
            )
            yield ("token", {"text": answer})
            memory_store.append_message(
                active_session_id,
                "assistant",
                answer,
                {
                    "intent": state.get("intent"),
                    "rule_id": rule_id,
                    "rule_name": effective.get("rule_name"),
                    "generation_method": "tool",
                    "errors": errors,
                    "execution_context": execution_context,
                },
            )
            _finish_trace(
                trace_recorder,
                trace_id,
                "blocked",
                answer[:500],
                intent=str(state.get("intent") or "query"),
                error_count=0,
            )
            yield ("done", {
                "session_id": active_session_id,
                "intent": state.get("intent"),
                "rule_id": rule_id,
                "generation_method": "tool",
                "status": status,
                "answer": answer,
                "errors": errors,
                "execution_context": execution_context,
                "trace_id": trace_id,
                "orchestrator": active_orchestrator.orchestrator_id,
                "agent_owner": active_orchestrator.owner_for_intent(
                    str(state.get("intent") or "query")
                ),
            })
            return

    # ---- Phase 2: 反馈模式（模板化，无需流式） ----
    if state["intent"] == "feedback":
        yield ("progress", {"message": "\u6b63\u5728\u751f\u6210\u53e3\u5f84\u5dee\u5f02\u786e\u8ba4"})
        state["feedback_preview"] = active_orchestrator.preview_feedback(prepared)
        state["generation_method"] = "tool"
        state["answer"] = (
            "检测到本院口径反馈，尚未写入待审批。\n"
            f"指标名称：{effective['rule_name']}\n"
            "请在口径差异确认窗口中提交，之后才会进入 pending 等待审批。"
        )
        answer = str(state.get("answer", ""))
        yield ("meta", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": rule_id, "generation_method": "tool",
        })
        yield ("token", {"text": answer})
        if state.get("feedback_preview"):
            yield ("feedback_preview", state["feedback_preview"])
        memory_store.append_message(active_session_id, "assistant", answer, {
            "intent": state.get("intent"), "rule_id": rule_id,
            "rule_name": effective.get("rule_name"),
            "generation_method": "tool", "errors": errors,
            "has_feedback_preview": True,
            **_stat_period_metadata(query, memory_context),
        })
        preview = state.get("feedback_preview")
        _record_trace_node(
            trace_recorder,
            trace_id,
            "feedback_preview",
            "agent",
            "success" if preview else "failed",
            input_summary=query,
            output_summary=str(rule_id or ""),
            rule_id=str(rule_id or ""),
            input_data={"query": query, "rule_id": rule_id, "hospital_id": state.get("hospital_id")},
            output_data=preview if isinstance(preview, dict) else {"error": "feedback preview missing"},
            config_data={"write_policy": "用户确认后才提交 pending"},
        )
        _record_trace_node(
            trace_recorder,
            trace_id,
            "final_response",
            "agent",
            "success",
            output_summary=str(answer)[:500],
            rule_id=str(rule_id or ""),
            input_data={"intent": state.get("intent"), "rule_id": rule_id, "generation_method": "tool", "errors": errors},
            output_data={"answer_preview": str(answer)[:1000], "trace_id": trace_id, "final_status": "success"},
            config_data={"storage": "ConversationMemory + TraceRecorder"},
        )
        _finish_trace(trace_recorder, trace_id, "success", str(answer)[:500], intent=str(state.get("intent", "")), error_count=len(errors))
        yield ("done", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": rule_id, "generation_method": "tool",
            "answer": answer, "errors": errors,
            "feedback_preview": state.get("feedback_preview"),
            "trace_id": trace_id,
            "orchestrator": active_orchestrator.orchestrator_id,
            "agent_owner": active_orchestrator.owner_for_intent("feedback"),
        })
        return

    # ---- Phase 2b: SQL 生成 / 异常排查 / 元数据同步 ----
    if state["intent"] == "generate_sql":
        yield ("progress", {"message": "\u6b63\u5728\u751f\u6210\u53ef\u5ba1\u6838 SQL"})
        yield ("meta", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": rule_id, "generation_method": "sqlgen",
        })
        start, end = _resolve_stat_period(
            query, memory_context, use_default=True
        ) or _default_stat_period()
        try:

            result = active_orchestrator.generate_indicator(
                prepared,
                stat_start_time=start,
                stat_end_time=end,
                trial_run=False,
                execution_context=execution_context,
            )
            _record_sql_trace_nodes(trace_recorder, trace_id, result, rule_id, state.get("hospital_id"))
            if result.get("status") == "field_precheck_failed":
                answer = f"❌ 暂不能生成 SQL\n\n{result.get('message', '')}"
            else:
                answer = format_generation_explanation(
                    result=result,
                    effective_rule=effective,
                    lineage=result.get("lineage") or {},
                    hospital_id=str(state.get("hospital_id") or ""),
                    stat_start=start,
                    stat_end=end,
                )
            yield ("token", {"text": answer})
        except Exception as exc:
            answer = f"SQL 生成失败：{exc}"
            _record_trace_node(
                trace_recorder,
                trace_id,
                "sql_generate",
                "sqlgen",
                "failed",
                input_summary=str(rule_id),
                output_summary="SQL 生成失败",
                rule_id=str(rule_id or ""),
                input_data={"rule_id": rule_id, "hospital_id": state.get("hospital_id")},
                output_data={"error": str(exc)},
                error_code=type(exc).__name__,
                error_message=str(exc),
            )
            yield ("token", {"text": answer})
        memory_store.append_message(active_session_id, "assistant", answer, {
            "intent": state.get("intent"), "rule_id": rule_id,
            "rule_name": effective.get("rule_name"),
            "generation_method": "sqlgen", "errors": errors,
            "stat_start_time": start,
            "stat_end_time": end,
            "execution_context": execution_context,
        })
        _record_trace_node(
            trace_recorder,
            trace_id,
            "final_response",
            "agent",
            "success" if not errors else "fallback",
            output_summary=str(answer)[:500],
            rule_id=str(rule_id or ""),
            input_data={"intent": state.get("intent"), "rule_id": rule_id, "generation_method": "sqlgen", "errors": errors},
            output_data={"answer_preview": str(answer)[:1000], "trace_id": trace_id, "final_status": "success" if not errors else "fallback"},
            config_data={"storage": "ConversationMemory + TraceRecorder"},
        )
        _finish_trace(trace_recorder, trace_id, "success" if not errors else "fallback", str(answer)[:500], intent=str(state.get("intent", "")), error_count=len(errors), fallback_count=1 if errors else 0)
        yield ("done", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": rule_id, "generation_method": "sqlgen",
            "answer": answer, "errors": errors, "trace_id": trace_id,
            "execution_context": execution_context,
            "orchestrator": active_orchestrator.orchestrator_id,
            "agent_owner": active_orchestrator.owner_for_intent("generate_sql"),
        })
        return

    if state["intent"] == "trial_run":
        yield ("progress", {"message": "\u6b63\u5728\u6267\u884c SQL \u8bd5\u8fd0\u884c"})
        yield ("meta", {
            "session_id": active_session_id, "intent": "trial_run",
            "rule_id": rule_id, "generation_method": "trial_run",
        })
        start, end = _resolve_stat_period(
            query, memory_context, use_default=True
        ) or _default_stat_period()
        try:

            result = active_orchestrator.generate_indicator(
                prepared,
                stat_start_time=start,
                stat_end_time=end,
                trial_run=True,
                execution_context=execution_context,
            )
            _record_sql_trace_nodes(trace_recorder, trace_id, result, rule_id, state.get("hospital_id"))
            answer = format_trial_explanation(
                result=result,
                effective_rule=effective,
                lineage=result.get("lineage") or {},
                hospital_id=str(state.get("hospital_id") or ""),
                stat_start=start,
                stat_end=end,
            )
            yield ("token", {"text": answer})
        except Exception as exc:
            answer = f"试运行失败：{exc}"
            _record_trace_node(
                trace_recorder,
                trace_id,
                "sql_trial_mcp",
                "mcp_tool",
                "failed",
                input_summary=str(rule_id),
                output_summary="试运行失败",
                rule_id=str(rule_id or ""),
                input_data={"rule_id": rule_id, "hospital_id": state.get("hospital_id")},
                output_data={"error": str(exc)},
                tool_name=(
                    "execute_sql_"
                    + re.sub(
                        r"[^0-9a-zA-Z]+",
                        "_",
                        current_business_source().source_id,
                    ).strip("_").lower()
                ),
                db_source=current_business_source().source_id,
                error_code=type(exc).__name__,
                error_message=str(exc),
            )
            yield ("token", {"text": answer})
        memory_store.append_message(active_session_id, "assistant", answer, {
            "intent": "trial_run", "rule_id": rule_id,
            "rule_name": effective.get("rule_name"),
            "generation_method": "trial_run", "errors": errors,
            "stat_start_time": start,
            "stat_end_time": end,
            "execution_context": execution_context,
        })
        _record_trace_node(
            trace_recorder,
            trace_id,
            "final_response",
            "agent",
            "success" if not errors else "fallback",
            output_summary=str(answer)[:500],
            rule_id=str(rule_id or ""),
            input_data={"intent": "trial_run", "rule_id": rule_id, "generation_method": "trial_run", "errors": errors},
            output_data={"answer_preview": str(answer)[:1000], "trace_id": trace_id, "final_status": "success" if not errors else "fallback"},
            config_data={"storage": "ConversationMemory + TraceRecorder"},
        )
        _finish_trace(trace_recorder, trace_id, "success" if not errors else "fallback", str(answer)[:500], intent="trial_run", error_count=len(errors), fallback_count=1 if errors else 0)
        yield ("done", {
            "session_id": active_session_id, "intent": "trial_run",
            "rule_id": rule_id, "generation_method": "trial_run",
            "answer": answer, "errors": errors, "trace_id": trace_id,
            "execution_context": execution_context,
            "orchestrator": active_orchestrator.orchestrator_id,
            "agent_owner": active_orchestrator.owner_for_intent("trial_run"),
        })
        return

    if state["intent"] == "diagnose":
        yield ("progress", {"message": "\u6b63\u5728\u542f\u52a8\u4e09\u5c42\u5f02\u5e38\u6392\u67e5"})
        yield ("meta", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": rule_id, "generation_method": "diagnose",
        })
        try:
            yield ("progress", {"message": "\u6b63\u5728\u6821\u9a8c\u7cfb\u7edf\u7ed3\u6784\u548c\u5143\u6570\u636e"})
            yield ("progress", {"message": "\u6b63\u5728\u6821\u9a8c\u53e3\u5f84\u89c4\u5219\u548c\u6570\u636e\u8d28\u91cf"})
            diag_result = active_orchestrator.diagnose(
                prepared
            )
            record_diagnose_trace_nodes(trace_recorder, trace_id, diag_result, rule_id, state.get("hospital_id"))
            yield ("progress", {"message": "\u6b63\u5728\u6574\u7406\u8bca\u65ad\u7ed3\u679c"})
            answer = _format_diagnose_answer(diag_result)
        except Exception as exc:
            answer = f"排查失败：{exc}"
        yield ("token", {"text": answer})
        memory_store.append_message(active_session_id, "assistant", answer, {
            "intent": state.get("intent"), "rule_id": rule_id,
            "rule_name": effective.get("rule_name"),
            "generation_method": "diagnose", "errors": errors,
        })
        _record_trace_node(
            trace_recorder,
            trace_id,
            "final_response",
            "agent",
            "success" if not errors else "fallback",
            output_summary=str(answer)[:500],
            rule_id=str(rule_id or ""),
            input_data={"intent": state.get("intent"), "rule_id": rule_id, "generation_method": "diagnose", "errors": errors},
            output_data={"answer_preview": str(answer)[:1000], "trace_id": trace_id, "final_status": "success" if not errors else "fallback"},
            config_data={"storage": "ConversationMemory + TraceRecorder"},
        )
        _finish_trace(trace_recorder, trace_id, "success" if not errors else "fallback", str(answer)[:500], intent=str(state.get("intent", "")), error_count=len(errors), fallback_count=1 if errors else 0)
        yield ("done", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": rule_id, "generation_method": "diagnose",
            "answer": answer, "errors": errors, "trace_id": trace_id,
            "orchestrator": active_orchestrator.orchestrator_id,
            "agent_owner": active_orchestrator.owner_for_intent("diagnose"),
        })
        return

    if state["intent"] == "metadata_sync":
        answer = "元数据同步请使用 API：POST /api/metadata/sync。需提供 hospital_id、db_name。"
        yield ("token", {"text": answer})
        memory_store.append_message(active_session_id, "assistant", answer, {
            "intent": state.get("intent"), "rule_id": rule_id,
            "generation_method": "tool", "errors": errors,
        })
        yield ("done", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": rule_id, "generation_method": "tool",
            "answer": answer, "errors": errors,
            "orchestrator": active_orchestrator.orchestrator_id,
            "agent_owner": active_orchestrator.owner_for_intent("metadata_sync"),
        })
        return

    # ---- Phase 3: 查询模式 —— 真正的流式 LLM 生成 ----
    answer_start = time.perf_counter()
    if active_llm is None:
        # 无 LLM，直接用模板回答
        yield ("progress", {"message": "\u6b63\u5728\u6309\u77e5\u8bc6\u5e93\u6a21\u677f\u751f\u6210\u56de\u7b54"})
        answer = active_orchestrator.answer_from_rule(effective)
        generation_method = "tool"
        yield ("meta", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": rule_id, "generation_method": generation_method,
        })
        yield ("token", {"text": answer})
    else:
        generation_method = "llm_stream"
        full_answer = ""
        try:
            yield ("progress", {"message": "\u6b63\u5728\u8c03\u7528 Ollama \u751f\u6210\u6700\u7ec8\u56de\u7b54"})
            prompt = active_orchestrator.build_answer_prompt(query, effective)
            yield ("meta", {
                "session_id": active_session_id, "intent": state.get("intent"),
                "rule_id": rule_id, "generation_method": "llm_stream",
            })
            for token in active_llm.generate_stream(prompt):  # type: ignore[union-attr]
                full_answer += token
                yield ("token", {"text": token})

            answer = full_answer.strip()

            # 流式生成完成后做 guard 校验
            if not answer or not active_orchestrator.answer_passes_guard(answer, effective):
                errors.append("LLM_ANSWER_FAILED_FACT_GUARD")
                fallback = active_orchestrator.answer_from_rule(effective)
                guard_note = "\n\n为避免模型误写公式或 SQL 状态，已切换为知识库标准答案：\n" + fallback
                yield ("token", {"text": guard_note})
                answer = fallback
                generation_method = "llm_guarded_fallback"

        except Exception as exc:
            errors.append(str(exc))
            answer = active_orchestrator.answer_from_rule(effective)
            generation_method = "tool_fallback"
            yield ("token", {"text": answer})

    if context_resolution is not None and context_resolution.blocked:
        answer = (
            answer
            + "\n\n"
            + _context_block_answer(context_resolution)
        ).strip()
        yield ("token", {"text": "\n\n" + _context_block_answer(context_resolution)})

    # ---- Phase 4: 记录记忆，返回 done ----
    answer_duration_ms = _elapsed_ms(answer_start)
    memory_store.append_message(active_session_id, "assistant", answer, {
        "intent": state.get("intent"), "rule_id": rule_id,
        "rule_name": effective.get("rule_name"),
        "generation_method": generation_method, "errors": errors,
        "execution_context": execution_context,
        **_stat_period_metadata(query, memory_context),
    })
    _record_trace_node(
        trace_recorder,
        trace_id,
        "final_response",
        "agent",
        "success" if not errors else "fallback",
        output_summary=str(answer)[:500],
        rule_id=str(rule_id or ""),
        input_data={
            "intent": state.get("intent"),
            "rule_id": rule_id,
            "generation_method": generation_method,
            "errors": errors,
        },
        output_data={
            "answer_preview": str(answer)[:1000],
            "trace_id": trace_id,
            "final_status": "success" if not errors else "fallback",
        },
        config_data={
            "storage": "ConversationMemory + TraceRecorder",
        },
        duration_ms=answer_duration_ms,
    )
    _finish_trace(
        trace_recorder,
        trace_id,
        "success" if not errors else "fallback",
        str(answer)[:500],
        intent=str(state.get("intent", "")),
        error_count=len(errors),
        fallback_count=1 if errors else 0,
    )
    yield ("done", {
        "session_id": active_session_id, "intent": state.get("intent"),
        "rule_id": rule_id, "generation_method": generation_method,
        "answer": answer, "errors": errors, "trace_id": trace_id,
        "execution_context": execution_context,
        "orchestrator": active_orchestrator.orchestrator_id,
        "agent_owner": active_orchestrator.owner_for_intent(str(state.get("intent") or "query")),
    })
