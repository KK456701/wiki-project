"""将已识别术语解析为本院已审批的 SQL 参数值。"""

from __future__ import annotations

from app.terminology.contracts import (
    TermNormalizationResult,
    TermSQLBinding,
    TermSQLBindingResult,
)
from app.terminology.repository import TerminologyRepository


def resolve_sql_bindings(
    normalization: TermNormalizationResult,
    hospital_id: str,
    rule_id: str,
    repository: TerminologyRepository,
) -> TermSQLBindingResult:
    if normalization.ambiguities:
        return TermSQLBindingResult(
            ok=False,
            problem_code="TERM_AMBIGUOUS",
            message="存在多个可能的医学概念，请先确认具体含义。",
        )
    candidates = [
        match
        for match in normalization.matches
        if rule_id in match.linked_rule_ids and match.business_field_keys
    ]
    unsafe = [
        match for match in candidates
        if not match.sql_safe or match.relation_type in {"related", "forbidden"}
    ]
    if unsafe:
        return TermSQLBindingResult(
            ok=False,
            problem_code="TERM_SQL_UNSAFE",
            message=f"“{unsafe[0].matched_text}”仅用于检索，不能直接作为 SQL 条件。",
        )
    bindings: list[TermSQLBinding] = []
    missing: list[str] = []
    for match in candidates:
        mappings = repository.active_hospital_mappings(
            hospital_id, [match.concept_code]
        )
        if not mappings:
            missing.append(match.concept_code)
            continue
        values = list(
            dict.fromkeys(
                str(item["local_value"]).strip()
                for item in mappings
                if str(item.get("local_value") or "").strip()
            )
        )
        if not values:
            missing.append(match.concept_code)
            continue
        for field_key in match.business_field_keys:
            bindings.append(
                TermSQLBinding(
                    concept_code=match.concept_code,
                    business_field_key=field_key,
                    parameter_name=f"{field_key}_value",
                    values=values,
                )
            )
    if missing:
        return TermSQLBindingResult(
            ok=False,
            problem_code="TERM_LOCAL_MAPPING_REQUIRED",
            message="已识别医学术语，但本院尚未配置对应编码或数据值。",
            missing_concepts=sorted(set(missing)),
        )
    return TermSQLBindingResult(ok=True, bindings=bindings)
