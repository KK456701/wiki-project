"""Deterministic intent rules and user-facing answer generation."""

from __future__ import annotations

import re
from typing import Any

from app.agents.contracts import IntentResult


FOLLOW_UP_MARKERS = ["这个", "那个", "它", "上面", "刚才", "之前", "这个指标", "那个指标", "当前", "现在"]
CHAT_EXACTS = {"你好", "您好", "嗨", "hi", "hello", "谢谢", "感谢", "好的", "ok", "OK"}
CHAT_MARKERS = ["你是谁", "你能做什么", "你可以做什么", "怎么使用", "有什么用", "帮助"]
KB_MARKERS = ["指标", "口径", "公式", "定义", "计算", "怎么算", "采用", "医院", "公司", "国标", "SQL", "字段", "急会诊", "会诊"]
SQL_MARKERS = ["生成SQL", "生成 sql", "可执行SQL", "试运行SQL", "SQL怎么写", "生成可执行"]
DIAG_MARKERS = ["排查", "异常", "为什么不对", "为什么算不出来", "根因", "诊断"]
SYNC_MARKERS = ["同步元数据", "同步表结构", "扫描字段"]
TRIAL_MARKERS = ["试运行", "运行SQL", "运行 sql", "执行SQL", "执行 sql"]
CREATE_INDICATOR_MARKERS = [
    "创建指标",
    "新增指标",
    "设计指标",
    "新建指标",
    "创建一个",
    "新增一个",
    "新建一个",
]
STAT_PERIOD_MARKERS = [
    "统计时间",
    "统计区间",
    "起始时间",
    "开始时间",
    "起始日期",
    "开始日期",
    "结束时间",
    "截止时间",
    "结束日期",
    "截止日期",
]
DATE_TOKEN_PATTERN = re.compile(
    r"20\d{2}\s*(?:-|/|年)\s*\d{1,2}\s*(?:-|/|月)\s*\d{1,2}\s*日?"
)

ACTION_QUERY_TEMPLATES = {
    "generate_sql": "生成{rule_name} SQL",
    "trial_run": "试运行{rule_name} SQL",
    "diagnose": "诊断{rule_name}",
}

CONTEXT_ONLY_ACTIONS = {
    "generate_sql": {"生成sql", "生成可执行sql"},
    "trial_run": {"试运行", "试运行sql", "运行sql", "执行sql"},
    "diagnose": {"诊断", "排查", "异常诊断", "根因排查"},
}


def _is_stat_period_request(query: str) -> bool:
    compact = re.sub(r"\s+", "", query or "")
    return bool(
        DATE_TOKEN_PATTERN.search(compact)
        and any(marker in compact for marker in STAT_PERIOD_MARKERS)
    )


def detect_intent_by_rule(query: str) -> str:
    q = (query or "").strip()
    compact = re.sub(r"\s+", "", q)
    feedback_actions = ["应该", "改成", "改为", "调整成", "修改成", "反馈", "不一致"]
    hospital_subjects = ["本院", "我们医院", "我院"]
    query_cues = ["哪个", "什么", "多少", "怎么", "吗", "？", "?", "当前", "采用"]
    if any(marker in compact for marker in CREATE_INDICATOR_MARKERS):
        return "create_indicator"
    if any(marker in compact for marker in SQL_MARKERS):
        return "generate_sql"
    if any(marker in compact for marker in TRIAL_MARKERS):
        return "trial_run"
    if _is_stat_period_request(compact):
        return "query"
    if any(marker in compact for marker in feedback_actions):
        return "feedback"
    if any(subject in compact for subject in hospital_subjects) and "按" in compact and not any(cue in compact for cue in query_cues):
        return "feedback"
    lower = compact.lower()
    if lower in {item.lower() for item in CHAT_EXACTS}:
        return "chat"
    if any(marker in compact for marker in CHAT_MARKERS) and not any(marker in compact for marker in KB_MARKERS):
        return "chat"
    if any(marker in compact for marker in DIAG_MARKERS):
        return "diagnose"
    if any(marker in compact for marker in SYNC_MARKERS):
        return "metadata_sync"
    return "query"


class HumanInteractionAgent:
    agent_id = "human_interaction"

    def understand(
        self,
        query: str,
        memory_context: dict[str, Any] | None = None,
        errors: list[str] | None = None,
    ) -> dict[str, Any]:
        result: dict[str, Any] = {
            "intent": detect_intent_by_rule(query),
            "retrieval_query": query,
            "rewritten_query": query,
            "indicator_name": "",
            "custom_filters": [],
            "context_updates": [],
            "clear_working_caliber": False,
        }
        self._rewrite_contextual_action(result, query, memory_context)
        return result

    def understand_contract(
        self,
        query: str,
        memory_context: dict[str, Any] | None = None,
        errors: list[str] | None = None,
    ) -> IntentResult:
        return IntentResult.model_validate(
            self.understand(query, memory_context=memory_context, errors=errors)
        )

    @classmethod
    def _rewrite_contextual_action(
        cls,
        result: dict[str, Any],
        query: str,
        memory_context: dict[str, Any] | None,
    ) -> None:
        intent = str(result.get("intent") or "")
        template = ACTION_QUERY_TEMPLATES.get(intent)
        if not template:
            return
        rule_name = str(result.get("indicator_name") or "").strip()
        memory_rule_name = str((memory_context or {}).get("rule_name") or "").strip()
        uses_memory = bool(
            memory_rule_name
            and cls.can_reuse_memory(query, intent)
            and (not rule_name or rule_name == memory_rule_name)
        )
        if not rule_name and uses_memory:
            rule_name = memory_rule_name
        if not rule_name:
            return
        if uses_memory:
            result["context_source"] = "memory_last_rule"
        result["indicator_name"] = rule_name
        result["retrieval_query"] = rule_name
        result["rewritten_query"] = template.format(rule_name=rule_name)

    def answer(
        self,
        query: str,
        effective_rule: dict[str, Any],
        errors: list[str] | None = None,
    ) -> tuple[str, str]:
        return self.answer_from_rule(effective_rule), "tool"

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
    def can_reuse_memory(query: str, intent: str) -> bool:
        if intent == "query" and _is_stat_period_request(query):
            return True
        if intent == "feedback" or any(marker in query for marker in FOLLOW_UP_MARKERS):
            return True
        if intent == "query" and any(
            marker in query for marker in ("入区", "恢复本院口径", "清除刚才的调整", "临时口径")
        ):
            return True
        compact = re.sub(r"[\s，。！？、,.!?]+", "", query or "").lower()
        return compact in CONTEXT_ONLY_ACTIONS.get(intent, set())
