"""Intent understanding and user-facing answer generation."""

from __future__ import annotations

import json
import re
from typing import Any, Protocol

from app.prompts import answer_prompt_template, intent_prompt_system


class LLMClient(Protocol):
    def generate(self, prompt: str) -> str: ...


FOLLOW_UP_MARKERS = ["这个", "那个", "它", "上面", "刚才", "之前", "这个指标", "那个指标", "当前", "现在"]
CHAT_EXACTS = {"你好", "您好", "嗨", "hi", "hello", "谢谢", "感谢", "好的", "ok", "OK"}
CHAT_MARKERS = ["你是谁", "你能做什么", "你可以做什么", "怎么使用", "有什么用", "帮助"]
KB_MARKERS = ["指标", "口径", "公式", "定义", "计算", "怎么算", "采用", "医院", "公司", "国标", "SQL", "字段", "急会诊", "会诊"]
SQL_MARKERS = ["生成SQL", "生成 sql", "可执行SQL", "试运行SQL", "SQL怎么写", "生成可执行"]
DIAG_MARKERS = ["排查", "异常", "为什么不对", "为什么算不出来", "根因", "诊断"]
SYNC_MARKERS = ["同步元数据", "同步表结构", "扫描字段"]
TRIAL_MARKERS = ["试运行", "运行SQL", "运行 sql", "执行SQL", "执行 sql"]


def detect_intent_by_rule(query: str) -> str:
    q = (query or "").strip()
    compact = re.sub(r"\s+", "", q)
    feedback_actions = ["应该", "改成", "改为", "调整成", "修改成", "反馈", "不一致"]
    hospital_subjects = ["本院", "我们医院", "我院"]
    query_cues = ["哪个", "什么", "多少", "怎么", "吗", "？", "?", "当前", "采用"]
    if any(marker in compact for marker in feedback_actions):
        return "feedback"
    if any(subject in compact for subject in hospital_subjects) and "按" in compact and not any(cue in compact for cue in query_cues):
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


def _extract_json_object(value: str) -> dict[str, Any]:
    cleaned = re.sub(r"<think>[\s\S]*?</think>", "", value or "").strip()
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


class HumanInteractionAgent:
    agent_id = "human_interaction"

    def __init__(self, llm_client: LLMClient | None = None):
        self.llm_client = llm_client

    def understand(
        self,
        query: str,
        memory_context: dict[str, Any] | None = None,
        errors: list[str] | None = None,
    ) -> dict[str, Any]:
        error_list = errors if errors is not None else []
        result: dict[str, Any] = {
            "intent": detect_intent_by_rule(query),
            "retrieval_query": query,
            "indicator_name": "",
            "custom_filters": [],
        }
        if self.llm_client is None:
            return result
        try:
            data = _extract_json_object(
                self.llm_client.generate(self._intent_prompt(query, memory_context))
            )
            intent = str(data.get("intent", "")).strip().lower()
            valid = {"query", "feedback", "chat", "generate_sql", "diagnose", "metadata_sync", "trial_run"}
            if intent in valid:
                result["intent"] = intent
            else:
                error_list.append("LLM_INTENT_INVALID_JSON")
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
            error_list.append(str(exc))
        return result

    def answer(
        self,
        query: str,
        effective_rule: dict[str, Any],
        errors: list[str] | None = None,
    ) -> tuple[str, str]:
        error_list = errors if errors is not None else []
        fallback = self.answer_from_rule(effective_rule)
        if self.llm_client is None:
            return fallback, "tool"
        try:
            answer = self.llm_client.generate(
                self.build_answer_prompt(query, effective_rule)
            ).strip()
            if answer and self.answer_passes_guard(answer, effective_rule):
                return answer, "llm"
            error_list.append("LLM_ANSWER_FAILED_FACT_GUARD")
            return fallback, "llm_guarded_fallback"
        except Exception as exc:
            error_list.append(str(exc))
            return fallback, "tool_fallback"

    @staticmethod
    def chat_answer() -> str:
        return (
            "你好，我是核心制度指标 Agent。"
            "我可以帮你查询指标定义、计算公式、当前医院口径和国标依据；"
            "也可以在你反馈本院口径不一致时，生成差异确认并提交审批。"
        )

    @staticmethod
    def answer_from_rule(rule: dict[str, Any]) -> str:
        lines = [
            f"命中指标：{rule['rule_name']}（{rule['rule_id']}）。",
            f"当前采用层级：{rule['effective_level']}。",
        ]
        if "hospital_override_not_configured" in rule.get("warnings", []):
            lines.append("当前医院未配置已审核本院口径，已按 Wiki 兜底规则回答。")
        if rule.get("definition"):
            lines.append(f"定义：{rule['definition']}")
        if rule.get("formula"):
            lines.append(f"计算公式：{rule['formula']}")
        if rule.get("implementation_status"):
            lines.append(f"实现状态：{rule['implementation_status']}")
        if rule.get("sql_status") != "available":
            lines.append("SQL 状态：不可用，原因：字段映射或 SQL 未审核，禁止生成可执行 SQL。")
        else:
            lines.append("SQL 状态：可用。你可以直接输入「生成 SQL」，系统会生成可执行 SQL 并支持试运行。")
        return "\n".join(lines)

    @staticmethod
    def answer_passes_guard(answer: str, rule: dict[str, Any]) -> bool:
        normalize = lambda text: "".join(str(text).split()).replace("（", "(").replace("）", ")")
        formula = str(rule.get("formula") or "").strip()
        if formula and normalize(formula) not in normalize(answer):
            return False
        return rule.get("sql_status") == "available" or "SQL" in answer

    def build_answer_prompt(self, query: str, rule: dict[str, Any]) -> str:
        steps = [
            f"识别并命中规则：{rule.get('rule_name', '')}（{rule.get('rule_id', '')}）",
            "以国标为基础合成本院生效口径，必要时只读回退 Wiki",
            f"当前采用层级：{rule.get('effective_level', '')}",
        ]
        if rule.get("sql_status") != "available":
            steps.append("字段映射或 SQL 未审核，禁止生成可执行 SQL")
        else:
            steps.append("字段映射已确认，可生成 SQL")
        return answer_prompt_template().format(
            query=query,
            steps="\n".join(f"{index}. {step}" for index, step in enumerate(steps, 1)),
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

    @staticmethod
    def can_reuse_memory(query: str, intent: str) -> bool:
        return intent == "feedback" or any(marker in query for marker in FOLLOW_UP_MARKERS)

    @staticmethod
    def _intent_prompt(
        query: str, memory_context: dict[str, Any] | None = None
    ) -> str:
        history_block = ""
        if memory_context and memory_context.get("rule_name"):
            history_block = (
                "\n上一轮对话上下文：\n"
                f"- 上一轮用户查询的指标是：「{memory_context['rule_name']}」\n"
                "- 如果当前问题是追问，请结合上一轮指标，并明确指标名。\n"
            )
        return intent_prompt_system().format(history_block=history_block, query=query)
