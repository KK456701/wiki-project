from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Protocol, TypedDict

from app.kb.tools import DEFAULT_KB_ROOT, KBToolError, KnowledgeBaseTools
from app.llm.ollama import OllamaClient
from app.memory.store import DEFAULT_MEMORY_ROOT, ConversationMemory


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
    errors: list[str]


FEEDBACK_MARKERS = ["本院", "我们医院", "我院", "应该", "改成", "反馈", "口径", "按"]


def detect_intent(query: str) -> str:
    if any(marker in query for marker in FEEDBACK_MARKERS) and any(marker in query for marker in ["应该", "改", "反馈", "按"]):
        return "feedback"
    return "query"


def _intent_prompt(query: str) -> str:
    return f"""你是医疗质量指标知识库的意图识别器。

用户输入：
{query}

请判断用户是在普通提问，还是在反馈/修正本院指标口径。
只输出 JSON，不要解释，不要输出 Markdown。

JSON 格式：
{{
  "intent": "query 或 feedback",
  "question_type": "指标公式查询/指标定义查询/制度分类查询/指标列表查询/原文依据查询/本院口径查询/反馈修正/其他",
  "indicator_name": "涉及的指标名称，没有则为空",
  "theme_name": "涉及的制度主题，没有则为空",
  "feedback_type": "计算口径修正/定义修正/分子修正/分母修正/排除条件修正/其他",
  "feedback_summary": "如果是反馈，概括用户反馈；否则为空",
  "retrieval_query": "用于检索本地 Markdown Wiki 的查询语句",
  "need_clarification": false,
  "clarification_question": ""
}}
"""


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


def _detect_intent(query: str, llm_client: LLMClient | None, errors: list[str]) -> str:
    if llm_client is not None:
        try:
            data = _extract_json_object(llm_client.generate(_intent_prompt(query)))
            intent = str(data.get("intent", "")).strip().lower()
            if intent in {"query", "feedback"}:
                return intent
            errors.append("LLM_INTENT_INVALID_JSON")
        except Exception as exc:
            errors.append(str(exc))
    return detect_intent(query)



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
        lines.append("字段和 SQL 状态：待医院字段映射确认，当前不能生成可执行 SQL。")
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
    return steps


def _build_answer_prompt(query: str, rule: dict[str, Any]) -> str:
    steps = "\n".join(f"{idx}. {step}" for idx, step in enumerate(_process_steps(rule), start=1))
    return f"""你是医疗质量安全核心制度指标解释助手。

请严格基于下面的知识库事实回答用户问题，不要编造字段、表名或 SQL。
如果字段或 SQL 状态为不可用，必须明确说明不能生成可执行 SQL。

用户问题：
{query}

可审计处理过程：
{steps}

知识库事实：
- 指标名称：{rule.get("rule_name", "")}
- rule_id：{rule.get("rule_id", "")}
- 当前采用层级：{rule.get("effective_level", "")}
- 定义：{rule.get("definition", "")}
- 计算公式：{rule.get("formula", "")}
- 实现状态：{rule.get("implementation_status", "")}
- 字段状态：{rule.get("field_status", "")}
- SQL 状态：{rule.get("sql_status", "")}
- 警告：{", ".join(rule.get("warnings", []))}

回答要求：
1. 先给结论，再给定义和公式。
2. 说明当前采用的是本院、公司还是国标层级。
3. 如 SQL 不可用，明确说明原因。
4. 不输出模型思维链，只输出可审计依据。
"""


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
    state["intent"] = _detect_intent(query, llm_client, state.setdefault("errors", []))
    search = tools.search(query, limit=5)
    state["search"] = search
    rule_id = search.get("resolved_rule_id")
    state["rule_id"] = rule_id
    _apply_memory_context_if_needed(state)
    rule_id = state.get("rule_id")
    if not rule_id:
        state["generation_method"] = "tool"
        state["answer"] = "未命中规则。请提供更明确的指标名称或 rule_id。"
        return state

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
    except Exception:
        return _run_deterministic(state, tools, llm_client)

    def intent_node(s: AgentState) -> AgentState:
        s["intent"] = _detect_intent(s["query"], llm_client, s.setdefault("errors", []))
        return s

    def search_node(s: AgentState) -> AgentState:
        s["search"] = tools.search(s["query"], limit=5)
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

    def route_after_search(s: AgentState) -> str:
        return "feedback" if s.get("intent") == "feedback" else "query"

    graph = StateGraph(AgentState)
    graph.add_node("intent", intent_node)
    graph.add_node("search", search_node)
    graph.add_node("query", query_node)
    graph.add_node("feedback", feedback_node)
    graph.set_entry_point("intent")
    graph.add_edge("intent", "search")
    graph.add_conditional_edges("search", route_after_search, {"query": "query", "feedback": "feedback"})
    graph.add_edge("query", END)
    graph.add_edge("feedback", END)
    return graph.compile().invoke(state)


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
    result.setdefault("generation_method", "tool")
    result.setdefault("session_id", active_session_id)
    result.setdefault("memory_context", memory_context)
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
    return dict(result)
