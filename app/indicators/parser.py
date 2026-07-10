from __future__ import annotations

import json
import re
import uuid
from datetime import datetime
from typing import Any, Protocol

from pydantic import ValidationError

from .contracts import IndicatorDraftSpec


class DraftParseError(ValueError):
    pass


class LLMClient(Protocol):
    def generate(self, prompt: str) -> str: ...


class IndicatorDraftParser:
    def __init__(self, llm_client: LLMClient):
        self.llm_client = llm_client

    def parse(self, query: str, hospital_id: str) -> IndicatorDraftSpec:
        raw = self.llm_client.generate(_prompt(query))
        data = _extract_json(raw)
        if not data:
            raise DraftParseError("无法解析模型返回的指标设计稿，请补充指标定义后重试。")
        if any(key in data for key in ("sql", "sql_text", "sql_template")):
            raise DraftParseError("模型不能直接提供SQL，只能生成结构化计算计划。")

        tables = [str(value).strip() for value in data.pop("required_tables", []) if str(value).strip()]
        requires_join = bool(data.pop("requires_join", False))
        sql_plan = data.get("sql_plan") if isinstance(data.get("sql_plan"), dict) else {}
        main_table = str(sql_plan.get("main_table") or "").strip()
        if main_table and main_table not in tables:
            tables.append(main_table)
        if requires_join or len(set(tables)) > 1:
            raise DraftParseError("第一版暂不支持多表关联，请先确定单一统计主表。")

        data["hospital_id"] = hospital_id
        data["proposed_index_code"] = _provisional_code(hospital_id)
        data["base_index_code"] = str(data.get("base_index_code") or "").strip() or None
        data["generated_by"] = "llm"
        data["metadata_requirements"] = _requirements(data, sql_plan)
        try:
            return IndicatorDraftSpec.model_validate(data)
        except ValidationError as exc:
            fields = sorted({str(item["loc"][-1]) for item in exc.errors() if item.get("loc")})
            raise DraftParseError(
                f"指标设计稿结构不完整或不合法：{', '.join(fields) or '未知字段'}"
            ) from exc


def _requirements(data: dict[str, Any], sql_plan: dict[str, Any]) -> list[str]:
    result = [
        str(value).strip()
        for value in data.get("metadata_requirements") or []
        if str(value).strip()
    ]
    for key in ("subject_field", "time_field", "hospital_field"):
        value = str(sql_plan.get(key) or "").strip()
        if value:
            result.append(value)
    for collection in ("numerator_conditions", "denominator_conditions"):
        for condition in sql_plan.get(collection) or []:
            if isinstance(condition, dict) and str(condition.get("field") or "").strip():
                result.append(str(condition["field"]).strip())
    return list(dict.fromkeys(result))


def _extract_json(value: str) -> dict[str, Any]:
    cleaned = re.sub(r"<think>[\s\S]*?</think>", "", value or "").strip()
    cleaned = re.sub(r"^```(?:json)?", "", cleaned, flags=re.IGNORECASE).strip()
    cleaned = re.sub(r"```$", "", cleaned).strip()
    match = re.search(r"\{[\s\S]*\}", cleaned)
    if not match:
        return {}
    try:
        parsed = json.loads(match.group(0))
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _provisional_code(hospital_id: str) -> str:
    match = re.search(r"(\d+)$", hospital_id or "")
    if match:
        prefix = f"HOSP{int(match.group(1)):03d}"
    else:
        clean = re.sub(r"[^A-Za-z0-9]", "", hospital_id or "HOSP")[:8].upper()
        prefix = f"HOSP{clean}"
    return f"{prefix}_{datetime.now().strftime('%Y%m%d')}_{uuid.uuid4().hex[:4].upper()}"


def _prompt(query: str) -> str:
    return f"""你是医院医务指标设计助手。把用户描述转换为结构化指标设计稿。
只输出 JSON，不要解释，不要 Markdown，不要输出任何 SQL。

第一版限制：只允许单表；metric_type 只能是 ratio 或 count；条件操作符只能是
eq、ne、gt、gte、lt、lte、in、not_in、is_null、not_null。
如果必须多表关联，设置 requires_join=true，并列出 required_tables。

JSON 必须包含：
index_name、index_type、index_desc、stat_cycle、numerator_rule、
denominator_rule、filter_rule、exclude_rule、metric_type、
metadata_requirements、required_tables、requires_join、sql_plan。
sql_plan 必须包含 main_table、metric_type、subject_field、time_field、
hospital_field、numerator_conditions、denominator_conditions。
如果明确基于已有指标，增加 base_index_code；否则为空。

用户描述：
{query}

只输出 JSON。"""
