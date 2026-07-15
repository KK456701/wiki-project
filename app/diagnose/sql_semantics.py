"""提取指标 SQL 中可确定识别的口径语义。"""

from __future__ import annotations

import re

from pydantic import BaseModel, Field


class SqlSemanticProfile(BaseModel):
    tables: list[str] = Field(default_factory=list)
    columns: list[str] = Field(default_factory=list)
    period_fields: list[str] = Field(default_factory=list)
    elapsed_pairs: list[dict[str, str]] = Field(default_factory=list)
    upper_boundary_mode: str = "unknown"
    icu_scope_strategy: str = "unknown"
    event_selection: str = "unknown"
    null_handling: list[str] = Field(default_factory=list)
    zero_denominator_guard: bool = False
    parse_warnings: list[str] = Field(default_factory=list)


class DiagnosisFinding(BaseModel):
    code: str
    category: str
    severity: str
    title: str
    evidence: str
    impact: str
    suggestion: str


_IDENT = r"(?:\[[^\]]+\]|[A-Za-z_][A-Za-z0-9_$#]*)"


def _plain(value: str) -> str:
    return value.strip().strip("[]").upper()


def _unique(values: list[str]) -> list[str]:
    return list(dict.fromkeys(value for value in values if value))


def _field(value: str) -> str:
    return _plain(value.split(".")[-1])


def profile_sql(sql: str, dialect: str = "sqlserver") -> SqlSemanticProfile:
    del dialect
    normalized = re.sub(r"--[^\r\n]*", " ", sql)
    tables = [
        ".".join(_plain(part) for part in re.split(r"\s*\.\s*", raw))
        for raw in re.findall(
            rf"(?is)\b(?:FROM|JOIN)\s+({_IDENT}(?:\s*\.\s*{_IDENT}){{0,2}})",
            normalized,
        )
    ]
    columns = [
        _plain(column)
        for _, column in re.findall(r"\b([A-Za-z_][\w]*)\.([A-Za-z_][\w]*)\b", normalized)
    ]

    period_fields: list[str] = []
    period_pattern = re.compile(
        r"(?is)([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)?)\s*(?:>=|>)\s*(?::(?:start|begin|from)[A-Za-z0-9_]*|@(?:start|begin|from)[A-Za-z0-9_]*|'\d{4}-\d{2}-\d{2}[^']*')"
    )
    period_fields.extend(_field(match) for match in period_pattern.findall(normalized))

    elapsed_pairs: list[dict[str, str]] = []
    for start, end in re.findall(
        r"(?is)DATEDIFF\s*\(\s*MINUTE\s*,\s*([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)?)\s*,\s*([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)?)\s*\)",
        normalized,
    ):
        elapsed_pairs.append({"start": _field(start), "end": _field(end)})
    for start in re.findall(
        r"(?is)DATEADD\s*\(\s*(?:HOUR|MINUTE)\s*,\s*[-+]?\d+\s*,\s*([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)?)\s*\)",
        normalized,
    ):
        elapsed_pairs.append({"start": _field(start), "end": "EVENT_TIME"})

    upper_boundary_mode = "unknown"
    if re.search(r"(?is)\bBETWEEN\s+0\s+AND\s+", normalized):
        upper_boundary_mode = "inclusive"
    elif re.search(r"(?is)<\s*DATEADD\s*\(", normalized):
        upper_boundary_mode = "exclusive"
    elif re.search(r"(?is)<=\s*DATEADD\s*\(", normalized):
        upper_boundary_mode = "inclusive"

    upper = normalized.upper()
    if "ORGANIZATION" in upper and "ORG_NO" in upper and "ICU" in upper:
        icu_scope_strategy = "organization_code_lookup"
    elif "ICU_ORG_IDS" in upper or "CHARINDEX" in upper:
        icu_scope_strategy = "configured_id_list"
    else:
        icu_scope_strategy = "unknown"

    if re.search(r"(?is)\bEXISTS\s*\(", normalized):
        event_selection = "any_matching_event"
    elif "ROW_NUMBER" in upper and re.search(r"(?is)EVENT_ORDER\s*=\s*1", normalized):
        event_selection = "earliest_matching_event"
    else:
        event_selection = "unknown"

    null_handling = _unique(
        _field(item)
        for item in re.findall(
            r"(?is)ISNULL\s*\(\s*([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)?)",
            normalized,
        )
    )
    zero_guard = bool(
        re.search(r"(?is)NULLIF\s*\(\s*COUNT", normalized)
        or re.search(r"(?is)CASE\s+WHEN\s+COUNT(?:_BIG)?\s*\([^)]*\)\s*=\s*0", normalized)
    )
    warnings: list[str] = []
    if not period_fields:
        warnings.append("未识别到明确的统计时间字段。")
    if not elapsed_pairs:
        warnings.append("未识别到明确的时间差计算起点。")

    return SqlSemanticProfile(
        tables=_unique(tables),
        columns=_unique(columns),
        period_fields=_unique(period_fields),
        elapsed_pairs=elapsed_pairs,
        upper_boundary_mode=upper_boundary_mode,
        icu_scope_strategy=icu_scope_strategy,
        event_selection=event_selection,
        null_handling=null_handling,
        zero_denominator_guard=zero_guard,
        parse_warnings=warnings,
    )


def _finding(
    code: str,
    title: str,
    evidence: str,
    impact: str,
    suggestion: str,
) -> DiagnosisFinding:
    return DiagnosisFinding(
        code=code,
        category="caliber",
        severity="warning",
        title=title,
        evidence=evidence,
        impact=impact,
        suggestion=suggestion,
    )


def compare_sql_profiles(
    baseline: SqlSemanticProfile,
    candidate: SqlSemanticProfile,
) -> list[DiagnosisFinding]:
    findings: list[DiagnosisFinding] = []
    if baseline.period_fields and candidate.period_fields and baseline.period_fields != candidate.period_fields:
        findings.append(_finding(
            "period_field_changed",
            "统计范围使用的时间字段不同",
            f"系统使用 {', '.join(baseline.period_fields)}；用户 SQL 使用 {', '.join(candidate.period_fields)}。",
            "两段 SQL 纳入统计的患者批次可能不同，分母会直接变化。",
            "请业务确认统计周期应按入院时间还是首次入区时间。",
        ))

    baseline_starts = _unique([item.get("start", "") for item in baseline.elapsed_pairs])
    candidate_starts = _unique([item.get("start", "") for item in candidate.elapsed_pairs])
    if baseline_starts and candidate_starts and baseline_starts != candidate_starts:
        findings.append(_finding(
            "elapsed_start_field_changed",
            "48 小时计时起点不同",
            f"系统从 {', '.join(baseline_starts)} 起算；用户 SQL 从 {', '.join(candidate_starts)} 起算。",
            "同一条转科记录的耗时可能不同，分子会变化。",
            "请确认 48 小时从入院、入区还是其他业务节点开始。",
        ))

    if (
        baseline.upper_boundary_mode != "unknown"
        and candidate.upper_boundary_mode != "unknown"
        and baseline.upper_boundary_mode != candidate.upper_boundary_mode
    ):
        findings.append(_finding(
            "upper_boundary_inclusive_changed",
            "48 小时边界是否包含正好 48 小时不同",
            f"系统边界为 {baseline.upper_boundary_mode}；用户 SQL 为 {candidate.upper_boundary_mode}。",
            "正好发生在第 48 小时的记录只会被其中一段 SQL 计入。",
            "请在口径中明确写明是否包含正好 48 小时。",
        ))

    for attr, code, title, impact, suggestion in (
        (
            "icu_scope_strategy", "icu_scope_strategy_changed", "ICU 排除范围来源不同",
            "不同 ICU 组织范围会改变符合条件的转科记录。",
            "请确认采用本院组织字典还是固定组织 ID 清单。",
        ),
        (
            "event_selection", "event_selection_changed", "转科事件选择方式不同",
            "选择最早一次转科与判断任意一次符合条件，可能得到不同分子。",
            "请明确一次入院存在多次转科时应取哪一次。",
        ),
    ):
        before = getattr(baseline, attr)
        after = getattr(candidate, attr)
        if before != "unknown" and after != "unknown" and before != after:
            findings.append(_finding(code, title, f"系统为 {before}；用户 SQL 为 {after}。", impact, suggestion))

    if baseline.null_handling != candidate.null_handling:
        findings.append(_finding(
            "null_handling_changed",
            "空值记录的处理方式不同",
            f"系统显式处理 {baseline.null_handling or '无'}；用户 SQL 显式处理 {candidate.null_handling or '无'}。",
            "空值参与不等于、排除或时间判断时，两段 SQL 的纳入结果可能不同。",
            "请逐项确认空值应视为无效、未发生还是保留。",
        ))
    return findings
