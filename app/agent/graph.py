from __future__ import annotations

import json
import re
import uuid
from importlib.util import find_spec
from pathlib import Path
from typing import Any, Iterator, Protocol, Tuple, TypedDict

from app.config import get
from app.db.engine import create_runtime_engine
from app.db_access.business_db import BusinessDBClient
from app.db_access.dbhub_mcp import DBHubMCPClient
from app.db_access.metadata_provider import DBHubMetadataProvider
from app.kb.tools import DEFAULT_KB_ROOT, KBToolError, KnowledgeBaseTools
from app.llm.ollama import OllamaClient
from app.memory.store import DEFAULT_MEMORY_ROOT, ConversationMemory
from app.observability.trace import TraceRecorder
from app.observability.workflow_nodes import record_diagnose_trace_nodes
from app.prompts import answer_prompt_template, intent_prompt_system


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


FEEDBACK_MARKERS = ["本院", "我们医院", "我院", "应该", "改成", "改为", "调整成", "修改成", "反馈", "按"]

FOLLOW_UP_MARKERS = ["这个", "那个", "它", "上面", "刚才", "之前", "这个指标", "那个指标", "当前", "现在"]

CHAT_EXACTS = {"你好", "您好", "嗨", "hi", "hello", "谢谢", "感谢", "好的", "ok", "OK"}
CHAT_MARKERS = ["你是谁", "你能做什么", "你可以做什么", "怎么使用", "有什么用", "帮助"]
KB_MARKERS = ["指标", "口径", "公式", "定义", "计算", "怎么算", "采用", "医院", "公司", "国标", "SQL", "字段", "急会诊", "会诊"]
SQL_MARKERS = ["生成SQL", "生成 sql", "可执行SQL", "试运行SQL", "SQL怎么写", "生成可执行"]
DIAG_MARKERS = ["排查", "异常", "为什么不对", "为什么算不出来", "根因", "诊断"]
SYNC_MARKERS = ["同步元数据", "同步表结构", "扫描字段"]
TRIAL_MARKERS = ["试运行", "运行SQL", "运行 sql", "执行SQL", "执行 sql"]


def _create_business_db_client(db_name: str = "hospital_demo_data") -> BusinessDBClient:
    suffix = re.sub(r"[^0-9a-zA-Z]+", "_", db_name).strip("_").lower()
    execute_tool = get(f"dbhub_execute_tool_{suffix}", f"execute_sql_{suffix}")
    source_id = get(f"dbhub_source_id_{suffix}", get(f"dbhub_source_{suffix}", db_name))
    endpoint = get("dbhub_mcp_url", "http://127.0.0.1:8080/mcp")
    timeout_seconds = int(get("dbhub_timeout_seconds", "10"))
    client = DBHubMCPClient(endpoint, execute_tool, timeout_seconds, source_id)
    return BusinessDBClient(client.execute_sql, source_id=source_id, tool_name=execute_tool)


def _create_metadata_provider(db_name: str = "hospital_demo_data") -> DBHubMetadataProvider:
    suffix = re.sub(r"[^0-9a-zA-Z]+", "_", db_name).strip("_").lower()
    execute_tool = get(f"dbhub_execute_tool_{suffix}", f"execute_sql_{suffix}")
    source_id = get(f"dbhub_source_id_{suffix}", get(f"dbhub_source_{suffix}", db_name))
    endpoint = get("dbhub_mcp_url", "http://127.0.0.1:8080/mcp")
    timeout_seconds = int(get("dbhub_timeout_seconds", "10"))
    client = DBHubMCPClient(endpoint, execute_tool, timeout_seconds, source_id)
    return DBHubMetadataProvider(client.execute_sql)


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
            "fallback_chain": effective.get("fallback_chain", []),
            "warnings": effective.get("warnings", []),
        },
        config_data={"tool": "KnowledgeBaseTools.get_effective_rule"},
    )


def _record_sql_trace_nodes(
    recorder: TraceRecorder | None,
    trace_id: str,
    result: dict[str, Any],
    rule_id: str | None,
    hospital_id: str | None,
) -> None:
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
        )

    trial = result.get("trial_run")
    if isinstance(trial, dict) and trial:
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
            tool_name="execute_sql_hospital_demo_data",
            db_source="hospital_demo_data",
            duration_ms=int(trial.get("duration_ms") or 0),
            input_data={
                "sql_id": result.get("sql_id"),
                "params": result.get("params", {}),
                "db_source": "hospital_demo_data",
            },
            output_data={
                "run_id": trial.get("run_id"),
                "status": status,
                "result_value": trial.get("result_value"),
                "duration_ms": trial.get("duration_ms"),
                "error_message": trial.get("error_message"),
            },
            config_data={"tool": "execute_sql_hospital_demo_data", "readonly": True},
            error_message=str(trial.get("error_message") or ""),
        )


def detect_intent(query: str) -> str:
    """关键词兜底：chat/query/feedback/generate_sql/diagnose/metadata_sync。"""
    q = (query or "").strip()
    compact = re.sub(r"\s+", "", q)
    feedback_actions = ["\u5e94\u8be5", "\u6539\u6210", "\u6539\u4e3a", "\u8c03\u6574\u6210", "\u4fee\u6539\u6210", "\u53cd\u9988", "\u4e0d\u4e00\u81f4"]
    hospital_subjects = ["\u672c\u9662", "\u6211\u4eec\u533b\u9662", "\u6211\u9662"]
    query_cues = ["\u54ea\u4e2a", "\u4ec0\u4e48", "\u591a\u5c11", "\u600e\u4e48", "\u5417", "\uff1f", "?", "\u5f53\u524d", "\u91c7\u7528"]
    if any(marker in compact for marker in feedback_actions):
        return "feedback"
    if any(subject in compact for subject in hospital_subjects) and "\u6309" in compact and not any(cue in compact for cue in query_cues):
        return "feedback"
    lower = compact.lower()
    if lower in {item.lower() for item in CHAT_EXACTS}:
        return "chat"
    if any(marker in compact for marker in CHAT_MARKERS) and not any(marker in compact for marker in KB_MARKERS):
        return "chat"
    if any(marker in compact for marker in SQL_MARKERS):
        return "generate_sql"
    if any(marker in compact for marker in DIAG_MARKERS):
        return "diagnose"
    if any(marker in compact for marker in SYNC_MARKERS):
        return "metadata_sync"
    if any(marker in compact for marker in TRIAL_MARKERS):
        return "trial_run"
    return "query"


def langgraph_installed() -> bool:
    return find_spec("langgraph") is not None


def workflow_engine_name() -> str:
    return "langgraph" if langgraph_installed() else "deterministic_fallback"


def _intent_prompt(query: str, memory_context: dict[str, Any] | None = None) -> str:
    history_block = ""
    if memory_context:
        last_rule = memory_context.get("rule_name", "")
        if last_rule:
            history_block = f"""
上一轮对话上下文：
- 上一轮用户查询的指标是：「{last_rule}」
- 如果当前问题是追问（如"这个"、"当前"、"它"、"现在"），请结合上一轮指标来理解，并在 indicator_name 和 retrieval_query 中明确写出指标名。
"""
    return intent_prompt_system().format(history_block=history_block, query=query)


def _extract_json_object(text: str) -> dict[str, Any]:
    cleaned = re.sub(r"<think>[\s\S]*?</think>", "", text or "").strip()
    cleaned = re.sub(r"^```json", "", cleaned).strip()
    cleaned = re.sub(r"^```", "", cleaned).strip()
    cleaned = re.sub(r"```$", "", cleaned).strip()
    match = re.search(r"\{[\s\S]*\}", cleaned)
    if not match:
        return {}
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError:
        return {}


def _detect_intent(
    query: str,
    llm_client: LLMClient | None,
    errors: list[str],
    memory_context: dict[str, Any] | None = None,
) -> dict[str, str]:
    """返回 {"intent":..., "retrieval_query":..., "indicator_name":..., "custom_filters":...}。

    LLM 可用时优先用 LLM 做意图识别 + 查询改写 + 过滤条件提取；
    LLM 不可用或失败时回退到关键词规则，retrieval_query=原始query。
    """
    result: dict[str, Any] = {
        "intent": detect_intent(query),
        "retrieval_query": query,
        "indicator_name": "",
        "custom_filters": [],
    }
    if llm_client is not None:
        try:
            data = _extract_json_object(llm_client.generate(_intent_prompt(query, memory_context)))
            intent = str(data.get("intent", "")).strip().lower()
            if intent in {"query", "feedback", "chat", "generate_sql", "diagnose", "metadata_sync", "trial_run"}:
                result["intent"] = intent
            else:
                errors.append("LLM_INTENT_INVALID_JSON")
            retrieval = str(data.get("retrieval_query", "")).strip()
            if result["intent"] == "chat":
                result["retrieval_query"] = ""
            elif retrieval:
                result["retrieval_query"] = retrieval
            indicator = str(data.get("indicator_name", "")).strip()
            if indicator:
                result["indicator_name"] = indicator
            filters = data.get("custom_filters")
            if isinstance(filters, list):
                result["custom_filters"] = filters
        except Exception as exc:
            errors.append(str(exc))
    return result



def _apply_memory_context_if_needed(state: AgentState) -> None:
    if state.get("rule_id"):
        return
    context = state.get("memory_context") or {}
    context_rule_id = context.get("rule_id")
    if not context_rule_id:
        return
    query = state.get("query", "")
    can_use_context = state.get("intent") == "feedback" or any(marker in query for marker in FOLLOW_UP_MARKERS)
    if not can_use_context:
        return
    state["rule_id"] = str(context_rule_id)
    search = state.setdefault("search", {"query": query, "matches": []})
    search["resolved_rule_id"] = str(context_rule_id)
    search["context_source"] = "memory_last_rule"


def _answer_chat(query: str) -> str:
    return (
        "你好，我是核心制度指标 Agent。"
        "我可以帮你查询指标定义、计算公式、当前医院口径、公司标准和国标依据；"
        "也可以在你反馈本院口径不一致时，生成差异确认并提交审批。"
    )


def _answer_from_rule(rule: dict[str, Any]) -> str:
    lines = [
        f"命中指标：{rule['rule_name']}（{rule['rule_id']}）。",
        f"当前采用层级：{rule['effective_level']}。",
    ]
    if "hospital_override_not_configured" in rule.get("warnings", []):
        lines.append("当前医院未配置已审核本院口径，已按公司标准并回退国标依据回答。")
    if rule.get("definition"):
        lines.append(f"定义：{rule['definition']}")
    if rule.get("formula"):
        lines.append(f"计算公式：{rule['formula']}")
    if rule.get("implementation_status"):
        lines.append(f"实现状态：{rule['implementation_status']}")
    if rule.get("sql_status") != "available":
        lines.append("SQL 状态：不可用，原因：字段映射或 SQL 未审核，禁止生成可执行 SQL。")
    else:
        lines.append("💡 SQL 状态：可用。你可以直接输入「生成 SQL」，我会为你生成可执行的 SQL 语句并试运行。")
    return "\n".join(lines)


def _process_steps(rule: dict[str, Any]) -> list[str]:
    steps = [
        f"识别并命中规则：{rule['rule_name']}（{rule['rule_id']}）",
        "按医院口径 > 公司标准 > 国标顺序解析有效规则",
        f"当前采用层级：{rule['effective_level']}",
    ]
    if "hospital_override_not_configured" in rule.get("warnings", []):
        steps.append("当前医院未配置已审核本院口径，回退公司标准和国标依据")
    if rule.get("sql_status") != "available":
        steps.append("字段映射或 SQL 未审核，禁止生成可执行 SQL")
    else:
        steps.append("字段映射已确认，可生成 SQL")
    return steps


def _build_answer_prompt(query: str, rule: dict[str, Any]) -> str:
    steps = "\n".join(f"{idx}. {step}" for idx, step in enumerate(_process_steps(rule), start=1))
    return answer_prompt_template().format(
        query=query,
        steps=steps,
        rule_name=rule.get("rule_name", ""),
        rule_id=rule.get("rule_id", ""),
        effective_level=rule.get("effective_level", ""),
        definition=rule.get("definition", ""),
        formula=rule.get("formula", ""),
        implementation_status=rule.get("implementation_status", ""),
        field_status=rule.get("field_status", ""),
        sql_status=rule.get("sql_status", ""),
        warnings=", ".join(rule.get("warnings", [])),
    )


def _normalize_fact(text: str) -> str:
    return "".join(str(text).split()).replace("（", "(").replace("）", ")")


def _llm_answer_passes_guard(answer: str, rule: dict[str, Any]) -> bool:
    formula = str(rule.get("formula") or "").strip()
    if formula and _normalize_fact(formula) not in _normalize_fact(answer):
        return False
    if rule.get("sql_status") != "available" and "SQL" not in answer:
        return False
    return True


def _generate_answer(query: str, rule: dict[str, Any], llm_client: LLMClient | None, errors: list[str]) -> tuple[str, str]:
    if llm_client is None:
        return _answer_from_rule(rule), "tool"
    try:
        answer = llm_client.generate(_build_answer_prompt(query, rule)).strip()
        if answer and _llm_answer_passes_guard(answer, rule):
            return answer, "llm"
        errors.append("LLM_ANSWER_FAILED_FACT_GUARD")
        return _answer_from_rule(rule), "llm_guarded_fallback"
    except Exception as exc:
        errors.append(str(exc))
    return _answer_from_rule(rule), "tool_fallback"


def _preview_feedback(state: AgentState, tools: KnowledgeBaseTools, effective: dict[str, Any]) -> AgentState:
    state["feedback_preview"] = tools.build_feedback_preview(
        str(state["rule_id"]),
        state.get("hospital_id"),
        state["query"],
    )
    state["generation_method"] = "tool"
    state["answer"] = (
        "\u68c0\u6d4b\u5230\u672c\u9662\u53e3\u5f84\u53cd\u9988\uff0c\u5c1a\u672a\u5199\u5165\u5f85\u5ba1\u6279\u3002\n"
        f"\u6307\u6807\u540d\u79f0\uff1a{effective['rule_name']}\n"
        "\u8bf7\u5728\u53e3\u5f84\u5dee\u5f02\u786e\u8ba4\u7a97\u53e3\u4e2d\u9009\u62e9\u2018\u8bf7\u6c42\u53d8\u66f4\u2019\uff0c\u63d0\u4ea4\u540e\u624d\u4f1a\u8fdb\u5165 pending \u7b49\u5f85\u5ba1\u6279\u3002"
    )
    return state

def _run_deterministic(state: AgentState, tools: KnowledgeBaseTools, llm_client: LLMClient | None = None) -> AgentState:
    query = state["query"]
    errors: list[str] = state.setdefault("errors", [])
    intent_data = _detect_intent(query, llm_client, errors, state.get("memory_context"))
    state["intent"] = intent_data["intent"]
    if state["intent"] == "chat":
        state["rule_id"] = None
        state["generation_method"] = "chat"
        state["answer"] = _answer_chat(query)
        return state
    search_query = intent_data["retrieval_query"] or query
    search = tools.search(search_query, limit=5)
    state["search"] = search
    rule_id = search.get("resolved_rule_id")
    state["rule_id"] = rule_id
    _apply_memory_context_if_needed(state)
    rule_id = state.get("rule_id")
    if not rule_id:
        state["generation_method"] = "tool"
        state["answer"] = "未命中规则。请提供更明确的指标名称或 rule_id。"
        return state

    yield ("progress", {"message": "\u5df2\u547d\u4e2d\u6307\u6807\uff0c\u6b63\u5728\u8bfb\u53d6\u533b\u9662\u4f18\u5148\u53e3\u5f84"})
    effective = tools.get_effective_rule(rule_id, state.get("hospital_id"))
    state["effective_rule"] = effective
    state["field_mapping"] = tools.get_field_mapping(rule_id)

    if state["intent"] == "feedback":
        return _preview_feedback(state, tools, effective)

    state["answer"], state["generation_method"] = _generate_answer(query, effective, llm_client, state.setdefault("errors", []))
    return state


def _run_langgraph(state: AgentState, tools: KnowledgeBaseTools, llm_client: LLMClient | None = None) -> AgentState:
    try:
        from langgraph.graph import END, StateGraph
    except Exception as exc:
        state["workflow_engine"] = "deterministic_fallback"
        state.setdefault("errors", []).append(f"LangGraph unavailable, using deterministic fallback: {exc}")
        return _run_deterministic(state, tools, llm_client)
    state["workflow_engine"] = "langgraph"

    def intent_node(s: AgentState) -> AgentState:
        intent_data = _detect_intent(s["query"], llm_client, s.setdefault("errors", []), s.get("memory_context"))
        s["intent"] = intent_data["intent"]
        s["_retrieval_query"] = intent_data["retrieval_query"]  # type: ignore[typeddict-unknown-key]
        return s

    def chat_node(s: AgentState) -> AgentState:
        s["rule_id"] = None
        s["generation_method"] = "chat"
        s["answer"] = _answer_chat(s["query"])
        return s

    def search_node(s: AgentState) -> AgentState:
        search_query = s.get("_retrieval_query") or s["query"]  # type: ignore[typeddict-item]
        s["search"] = tools.search(str(search_query), limit=5)
        s["rule_id"] = s["search"].get("resolved_rule_id")
        _apply_memory_context_if_needed(s)
        return s

    def query_node(s: AgentState) -> AgentState:
        if not s.get("rule_id"):
            s["generation_method"] = "tool"
            s["answer"] = "未命中规则。请提供更明确的指标名称或 rule_id。"
            return s
        effective = tools.get_effective_rule(str(s["rule_id"]), s.get("hospital_id"))
        s["effective_rule"] = effective
        s["field_mapping"] = tools.get_field_mapping(str(s["rule_id"]))
        s["answer"], s["generation_method"] = _generate_answer(s["query"], effective, llm_client, s.setdefault("errors", []))
        return s

    def feedback_node(s: AgentState) -> AgentState:
        if not s.get("rule_id"):
            s["generation_method"] = "tool"
            s["answer"] = "未命中规则，无法记录反馈。请补充指标名称。"
            return s
        effective = tools.get_effective_rule(str(s["rule_id"]), s.get("hospital_id"))
        s["effective_rule"] = effective
        return _preview_feedback(s, tools, effective)

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
) -> dict[str, Any]:
    tools = KnowledgeBaseTools(kb_root)
    memory_store = memory or ConversationMemory(DEFAULT_MEMORY_ROOT)
    active_session_id = memory_store.ensure_session(session_id, hospital_id)
    memory_context = memory_store.last_rule_context(active_session_id) or {}
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
        output_data={"active_session_id": active_session_id, "memory_context": memory_context},
        config_data={"storage": "SQLite + JSONL"},
    )
    state: AgentState = {
        "query": query,
        "hospital_id": hospital_id,
        "session_id": active_session_id,
        "memory_context": memory_context,
        "errors": [],
    }
    memory_store.append_message(
        active_session_id,
        "user",
        query,
        {"hospital_id": hospital_id, "memory_context": memory_context},
    )
    active_llm = llm_client if use_llm else None
    if use_llm and active_llm is None:
        active_llm = OllamaClient()
    try:
        result = _run_langgraph(state, tools, active_llm)
    except KBToolError as exc:
        result = {
            **state,
            "intent": state.get("intent", "query"),
            "generation_method": "tool",
            "answer": f"\u77e5\u8bc6\u5e93\u5de5\u5177\u8c03\u7528\u5931\u8d25\uff1a{exc}",
            "errors": [str(exc)],
        }
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
            "session_memory": memory_context,
            "use_llm": use_llm,
        },
        output_data={
            "intent": result.get("intent"),
            "retrieval_query": (result.get("search") or {}).get("query", query) if isinstance(result.get("search"), dict) else query,
            "custom_filters": result.get("_custom_filters", []),
        },
        config_data={
            "strategy": "规则兜底 + 可选 LLM 意图识别",
            "workflow_engine": result.get("workflow_engine") or workflow_engine_name(),
        },
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
                "matched_count": len(search_payload.get("results", [])) if isinstance(search_payload.get("results"), list) else 0,
            },
            config_data={
                "tool": "KnowledgeBaseTools.search",
                "priority": "医院口径 > 公司标准 > 国标",
            },
        )
    _record_effective_rule_node(
        trace_recorder,
        trace_id,
        str(result.get("rule_id") or "") or None,
        hospital_id,
        result.get("effective_rule") if isinstance(result.get("effective_rule"), dict) else None,
    )
    result.setdefault("generation_method", "tool")
    result.setdefault("workflow_engine", workflow_engine_name())
    result.setdefault("session_id", active_session_id)
    result.setdefault("memory_context", memory_context)
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
) -> Iterator[Tuple[str, dict[str, Any]]]:
    """真正的流式对话：逐 token 从 Ollama 产出并立即 yield。

    返回 (event_type, payload) 元组的生成器：
      - ("meta", {...})         会话元信息
      - ("token", {"text": "..."})  逐 token
      - ("feedback_preview", {...})  反馈对比预览
      - ("done", {...})         最终结果
    """
    tools = KnowledgeBaseTools(kb_root)
    memory_store = memory or ConversationMemory(DEFAULT_MEMORY_ROOT)
    active_session_id = memory_store.ensure_session(session_id, hospital_id)
    memory_context = memory_store.last_rule_context(active_session_id) or {}
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
        output_data={"active_session_id": active_session_id, "memory_context": memory_context},
        config_data={"storage": "SQLite + JSONL"},
    )

    state: AgentState = {
        "query": query,
        "hospital_id": hospital_id,
        "session_id": active_session_id,
        "memory_context": memory_context,
        "errors": [],
    }
    memory_store.append_message(
        active_session_id, "user", query,
        {"hospital_id": hospital_id, "memory_context": memory_context},
    )

    active_llm = llm_client if use_llm else None
    if use_llm and active_llm is None:
        active_llm = OllamaClient()

    yield ("meta", {
        "session_id": active_session_id, "intent": None,
        "rule_id": None, "generation_method": "preparing",
        "trace_id": trace_id,
    })
    yield ("progress", {"message": "\u6b63\u5728\u8bc6\u522b\u95ee\u9898\u610f\u56fe"})

    # ---- Phase 1: 意图识别 + 知识库检索（同步，很快） ----
    errors: list[str] = state.setdefault("errors", [])
    intent_data = _detect_intent(query, active_llm, errors, memory_context)
    state["intent"] = intent_data["intent"]
    state["_custom_filters"] = intent_data.get("custom_filters", [])  # type: ignore[typeddict-unknown-key]
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
            "session_memory": memory_context,
            "use_llm": use_llm,
        },
        output_data={
            "intent": state.get("intent"),
            "retrieval_query": intent_data.get("retrieval_query"),
            "custom_filters": intent_data.get("custom_filters", []),
        },
        config_data={
            "strategy": "规则兜底 + 可选 LLM 意图识别",
        },
    )

    if state["intent"] == "chat":
        yield ("progress", {"message": "\u6b63\u5728\u6574\u7406\u666e\u901a\u5bf9\u8bdd\u56de\u7b54"})
        answer = _answer_chat(query)
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
        })
        return

    try:
        yield ("progress", {"message": "\u6b63\u5728\u68c0\u7d22\u672c\u5730 Wiki \u77e5\u8bc6\u5e93"})
        search_query = intent_data["retrieval_query"] or query
        search = tools.search(search_query, limit=5)
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
        })
        return

    state["search"] = search
    state["rule_id"] = search.get("resolved_rule_id")
    _apply_memory_context_if_needed(state)
    rule_id = state.get("rule_id")
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
            "matched_count": len(search.get("results", [])) if isinstance(search.get("results"), list) else 0,
        },
        config_data={
            "tool": "KnowledgeBaseTools.search",
            "priority": "医院口径 > 公司标准 > 国标",
        },
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
        })
        return

    effective = tools.get_effective_rule(rule_id, state.get("hospital_id"))
    state["effective_rule"] = effective
    state["field_mapping"] = tools.get_field_mapping(rule_id)
    _record_effective_rule_node(trace_recorder, trace_id, rule_id, state.get("hospital_id"), effective)

    # ---- Phase 2: 反馈模式（模板化，无需流式） ----
    if state["intent"] == "feedback":
        yield ("progress", {"message": "\u6b63\u5728\u751f\u6210\u53e3\u5f84\u5dee\u5f02\u786e\u8ba4"})
        state = _preview_feedback(state, tools, effective)
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
        })
        return

    # ---- Phase 2b: SQL 生成 / 异常排查 / 元数据同步 ----
    if state["intent"] == "generate_sql":
        yield ("progress", {"message": "\u6b63\u5728\u751f\u6210\u53ef\u5ba1\u6838 SQL"})
        yield ("meta", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": rule_id, "generation_method": "sqlgen",
        })
        try:
            from datetime import datetime as dt
            from app.sqlgen.agent import SQLGenerationAgent

            now = dt.now()
            start = f"{now.year}-{now.month:02d}-01 00:00:00"
            if now.month == 12:
                end = f"{now.year + 1}-01-01 00:00:00"
            else:
                end = f"{now.year}-{now.month + 1:02d}-01 00:00:00"

            sql_agent = SQLGenerationAgent(
                kb_root=kb_root, runtime_engine=create_runtime_engine(),
                business_db=_create_business_db_client("hospital_demo_data"),
            )
            result = sql_agent.generate(
                query=query, hospital_id=str(state.get("hospital_id") or ""),
                rule_id=str(rule_id), effective_rule=effective,
                stat_start_time=start, stat_end_time=end, trial_run=False,
                custom_filters=state.get("_custom_filters", []),
            )
            _record_sql_trace_nodes(trace_recorder, trace_id, result, rule_id, state.get("hospital_id"))
            if result.get("status") == "field_precheck_failed":
                answer = f"❌ 暂不能生成 SQL\n\n{result.get('message', '')}"
            else:
                # 解释材料只影响展示，不应把已生成的 SQL 误记为失败。
                spec: dict[str, Any] = {}
                mapping: dict[str, Any] = {}
                try:
                    import yaml
                    from app.metadata.precheck import find_spec_dir, load_yaml as _load_yaml

                    spec_dir = find_spec_dir(kb_root, str(rule_id)) if (kb_root / "sql-specs").exists() else None
                    spec_path = spec_dir / "rule_sql_spec.yaml" if spec_dir else None
                    if spec_path and spec_path.exists():
                        spec = _load_yaml(spec_path) or {}
                    mapping_path = kb_root / "hospital-mappings" / str(state.get("hospital_id") or "") / f"{rule_id}.yaml"
                    if mapping_path.exists():
                        with open(mapping_path, encoding="utf-8") as f:
                            mapping = yaml.safe_load(f) or {}
                except Exception:
                    spec = {}
                    mapping = {}

                field_lines = ["📋 字段映射："]
                for bf_name in (spec.get("required_business_fields") or []):
                    col = (mapping.get("fields") or {}).get(bf_name, "未映射")
                    field_lines.append(f"  · {bf_name} → {col}")
                field_lines.append(f"  主表：{mapping.get('main_table', '')}")
                field_lines.append(f"  数据库：{mapping.get('db_name', '')}")
                field_lines.append(f"  数据库类型：{mapping.get('dialect', 'mysql').upper()}")

                # 规格说明 + 当前口径参数
                num = spec.get("numerator", {})
                den = spec.get("denominator", {})
                params = result.get("params", {})

                spec_lines = ["📐 计算逻辑："]
                spec_lines.append(f"  分子（{num.get('name', '')}）：{', '.join(num.get('logic', []))}")
                spec_lines.append(f"  分母（{den.get('name', '')}）：{', '.join(den.get('logic', []))}")

                # 参数
                param_lines = ["⚙️ 参数："]
                for k, v in params.items():
                    param_lines.append(f"  · {k} = {v}")

                # 自定义口径规则（含 YAML custom_rules + 阈值差异）
                custom_rules = mapping.get("custom_rules") or {}
                rule_lines: list[str] = []
                has_custom = bool(custom_rules.get("exclude_depts") or custom_rules.get("count_multiple_transfers"))
                if effective.get("effective_level") == "hospital":
                    has_custom = True  # 医院层级本身就有自定义
                if has_custom:
                    rule_lines = ["🔧 本院自定义口径："]
                    # 阈值差异
                    for k, v in params.items():
                        if k not in ("hospital_id", "consult_type_value", "start_time", "end_time"):
                            rule_lines.append(f"  · {k} = {v}（医院自定义，非公司默认）")
                    if custom_rules.get("exclude_depts"):
                        rule_lines.append(f"  · 排除科室：{', '.join(custom_rules['exclude_depts'])}")
                    if custom_rules.get("count_multiple_transfers"):
                        rule_lines.append("  · 多次转科：分别计数（不去重）")

                answer = (
                    f"✅ SQL 已生成\n"
                    f"SQL ID：{result.get('sql_id', '')}\n"
                    f"安全校验：{result['validation'].get('message', result['validation'].get('error',''))}\n\n"
                    + "\n".join(field_lines) + "\n\n"
                    + "\n".join(spec_lines) + "\n\n"
                    + "\n".join(param_lines) + "\n\n"
                    + ("\n".join(rule_lines) + "\n\n" if custom_rules else "")
                    + f"```sql\n{result.get('sql_text', '')}\n```"
                )
                trial = result.get("trial_run", {})
                if trial:
                    answer += f"\n🧪 试运行：{trial.get('status', '')}，{trial.get('duration_ms', 0)}ms"
                    if trial.get("result_value") is not None:
                        answer += f"，结果：{trial['result_value']}%"
                    if trial.get("error_message"):
                        answer += f"\n错误：{trial['error_message']}"
                else:
                    answer += "\n\n💡 如需试运行此 SQL，请输入「**试运行**」。将使用当前月份的统计数据在只读库中执行并返回结果。"
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
        })
        return

    if state["intent"] == "trial_run":
        yield ("progress", {"message": "\u6b63\u5728\u6267\u884c SQL \u8bd5\u8fd0\u884c"})
        yield ("meta", {
            "session_id": active_session_id, "intent": "trial_run",
            "rule_id": rule_id, "generation_method": "trial_run",
        })
        try:
            from datetime import datetime as dt
            from app.sqlgen.agent import SQLGenerationAgent

            now = dt.now()
            start = f"{now.year}-{now.month:02d}-01 00:00:00"
            end = f"{now.year}-{now.month + 1:02d}-01 00:00:00" if now.month < 12 else f"{now.year + 1}-01-01 00:00:00"

            sql_agent = SQLGenerationAgent(
                kb_root=kb_root, runtime_engine=create_runtime_engine(),
                business_db=_create_business_db_client("hospital_demo_data"),
            )
            result = sql_agent.generate(
                query=query, hospital_id=str(state.get("hospital_id") or ""),
                rule_id=str(rule_id), effective_rule=effective,
                stat_start_time=start, stat_end_time=end, trial_run=True,
                custom_filters=state.get("_custom_filters", []),
            )
            _record_sql_trace_nodes(trace_recorder, trace_id, result, rule_id, state.get("hospital_id"))
            trial = result.get("trial_run", {})
            answer = (
                f"```sql\n{result.get('sql_text', '')}\n```\n\n"
                f"🧪 试运行完成\n"
                f"运行 ID：{trial.get('run_id', '')}\n"
                f"状态：{trial.get('status', '')}\n"
                f"耗时：{trial.get('duration_ms', 0)}ms"
            )
            if trial.get("result_value") is not None:
                answer += f"\n结果：**{trial['result_value']}%**"
            if trial.get("error_message"):
                answer += f"\n错误：{trial['error_message']}"
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
                tool_name="execute_sql_hospital_demo_data",
                db_source="hospital_demo_data",
                error_code=type(exc).__name__,
                error_message=str(exc),
            )
            yield ("token", {"text": answer})
        memory_store.append_message(active_session_id, "assistant", answer, {
            "intent": "trial_run", "rule_id": rule_id,
            "rule_name": effective.get("rule_name"),
            "generation_method": "trial_run", "errors": errors,
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
        })
        return

    if state["intent"] == "diagnose":
        yield ("progress", {"message": "\u6b63\u5728\u542f\u52a8\u4e09\u5c42\u5f02\u5e38\u6392\u67e5"})
        yield ("meta", {
            "session_id": active_session_id, "intent": state.get("intent"),
            "rule_id": rule_id, "generation_method": "diagnose",
        })
        try:
            from app.diagnose.agent import DiagnoseAgent
            yield ("progress", {"message": "\u6b63\u5728\u6821\u9a8c\u7cfb\u7edf\u7ed3\u6784\u548c\u5143\u6570\u636e"})
            diag_agent = DiagnoseAgent(
                kb_root=kb_root, runtime_engine=create_runtime_engine(),
                business_db=_create_business_db_client("hospital_demo_data"),
                metadata_provider=_create_metadata_provider("hospital_demo_data"),
            )
            yield ("progress", {"message": "\u6b63\u5728\u6821\u9a8c\u53e3\u5f84\u89c4\u5219\u548c\u6570\u636e\u8d28\u91cf"})
            diag_result = diag_agent.run(
                hospital_id=str(state.get("hospital_id") or ""),
                rule_id=str(rule_id), effective_rule=effective,
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
        })
        return

    # ---- Phase 3: 查询模式 —— 真正的流式 LLM 生成 ----
    if active_llm is None:
        # 无 LLM，直接用模板回答
        yield ("progress", {"message": "\u6b63\u5728\u6309\u77e5\u8bc6\u5e93\u6a21\u677f\u751f\u6210\u56de\u7b54"})
        answer = _answer_from_rule(effective)
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
            prompt = _build_answer_prompt(query, effective)
            yield ("meta", {
                "session_id": active_session_id, "intent": state.get("intent"),
                "rule_id": rule_id, "generation_method": "llm_stream",
            })
            for token in active_llm.generate_stream(prompt):  # type: ignore[union-attr]
                full_answer += token
                yield ("token", {"text": token})

            answer = full_answer.strip()

            # 流式生成完成后做 guard 校验
            if not answer or not _llm_answer_passes_guard(answer, effective):
                errors.append("LLM_ANSWER_FAILED_FACT_GUARD")
                fallback = _answer_from_rule(effective)
                guard_note = "\n\n为避免模型误写公式或 SQL 状态，已切换为知识库标准答案：\n" + fallback
                yield ("token", {"text": guard_note})
                answer = fallback
                generation_method = "llm_guarded_fallback"

        except Exception as exc:
            errors.append(str(exc))
            answer = _answer_from_rule(effective)
            generation_method = "tool_fallback"
            yield ("token", {"text": answer})

    # ---- Phase 4: 记录记忆，返回 done ----
    memory_store.append_message(active_session_id, "assistant", answer, {
        "intent": state.get("intent"), "rule_id": rule_id,
        "rule_name": effective.get("rule_name"),
        "generation_method": generation_method, "errors": errors,
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
    })
