"""最终回答中完成性事实声明的确定性证据守卫。"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True, slots=True)
class ClaimRule:
    fact_type: str
    patterns: tuple[str, ...]


CLAIM_RULES = (
    ClaimRule(
        "sql_validation",
        (r"SQL.{0,16}(?:校验通过|已验证|可以执行|可执行)",),
    ),
    ClaimRule(
        "trial_run",
        (
            r"(?:试运行|本次运行).{0,40}(?:指标值|分子|分母|样本量|结果)",
            r"(?:分子|分母|样本量|指标值)\s*(?:为|是|[:：])?\s*-?\d",
        ),
    ),
    ClaimRule(
        "diagnosis",
        (r"(?:诊断|排查).{0,30}(?:发现|结论|根因|异常原因)",),
    ),
    ClaimRule(
        "indicator_draft",
        (
            r"(?:已创建|创建了).{0,16}(?:指标)?草稿",
            r"草稿.{0,12}(?:已创建|创建成功)",
        ),
    ),
    ClaimRule(
        "rule_change_preview",
        (
            r"(?:已生成|生成了).{0,20}(?:口径|规则)?.{0,10}(?:变更|差异)?预览",
            r"(?:导致|因此|所以|将使|会使).{0,30}(?:偏高|偏低|更高|更低|上升|下降|增加|减少)",
        ),
    ),
    ClaimRule(
        "formal_change",
        (
            r"(?:已提交|提交成功|已审批|审批通过|已发布|发布成功|已回退|回退成功)",
        ),
    ),
)


_FACT_LABELS = {
    "sql_validation": "SQL 安全校验",
    "trial_run": "试运行聚合结果",
    "diagnosis": "诊断报告",
    "indicator_draft": "指标工作草稿",
    "rule_change_preview": "口径变更预览",
    "formal_change": "正式提交、审批、发布或回退授权",
}


def normalize_agent_answer(answer: str) -> str:
    """把常见模型 LaTeX 公式和对话格式前缀收敛为可读纯文本。"""
    if not answer:
        return answer

    # 去除模型模仿的对话格式前缀
    normalized = re.sub(r"^\s*(?:助手|用户|AI|Human|Assistant|User)\s*[:：]\s*", "", answer, flags=re.IGNORECASE)

    normalized = normalized.replace("\\[", "").replace("\\]", "")
    normalized = re.sub(r"(?m)^[ \t]*\$\$[ \t]*\n?", "", normalized)
    normalized = normalized.replace("$$", "")
    normalized = re.sub(r"\\text\{([^{}]*)\}", r"\1", normalized)
    normalized = re.sub(
        r"\\frac\{([^{}]*)\}\{([^{}]*)\}",
        lambda match: f"{match.group(1).strip()} ÷ {match.group(2).strip()}",
        normalized,
    )
    normalized = normalized.replace("\\times", "×").replace("\\%", "%")
    normalized = normalized.replace("\\left", "").replace("\\right", "")
    normalized = re.sub(r"[ \t]+", " ", normalized)
    normalized = re.sub(r" *\n *", "\n", normalized)
    normalized = re.sub(r"\n{3,}", "\n\n", normalized)
    return normalized.strip()


def _available_fact_types(evidence: list[dict[str, Any]]) -> set[str]:
    return {
        str(fact_type)
        for item in evidence
        if isinstance(item, dict)
        for fact_type in (item.get("fact_types") or [])
        if fact_type
    }


def missing_fact_types(
    answer: str,
    evidence: list[dict[str, Any]],
) -> set[str]:
    if not answer:
        return set()
    available = _available_fact_types(evidence)
    required = {
        rule.fact_type
        for rule in CLAIM_RULES
        if any(
            re.search(pattern, answer, flags=re.IGNORECASE)
            for pattern in rule.patterns
        )
    }
    missing = required - available
    if "formal_change" in required:
        missing.add("formal_change")
    return missing


def evidence_correction_prompt(missing: set[str]) -> str:
    labels = [
        label
        for fact_type, label in _FACT_LABELS.items()
        if fact_type in missing
    ]
    joined = "、".join(labels) or "对应工具"
    return (
        f"当前回答缺少{joined}证据。请先调用可见工具取得证据，"
        "或删除相关完成性结论；不得编造医院结果。"
    )
