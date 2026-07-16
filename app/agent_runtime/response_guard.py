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
)


_FACT_LABELS = {
    "sql_validation": "SQL 安全校验",
    "trial_run": "试运行聚合结果",
    "diagnosis": "诊断报告",
}


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
    return required - available


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
