from __future__ import annotations

import json
import re
import uuid
from datetime import datetime
from typing import Any, Protocol

from pydantic import ValidationError

from app.prompts import format_prompt

from .contracts import IndicatorDraftSpec


class DraftParseError(ValueError):
    pass


class _RetryableDraftParseError(DraftParseError):
    pass


class LLMClient(Protocol):
    def generate(self, prompt: str) -> str: ...


class IndicatorDraftParser:
    def __init__(self, llm_client: LLMClient):
        self.llm_client = llm_client

    def parse(self, query: str, hospital_id: str) -> IndicatorDraftSpec:
        raw = self.llm_client.generate(_prompt(query))
        try:
            return self._parse_response(raw, hospital_id)
        except _RetryableDraftParseError as exc:
            repaired = self.llm_client.generate(
                _repair_prompt(query, raw, str(exc))
            )
            return self._parse_response(repaired, hospital_id)

    @staticmethod
    def _parse_response(raw: str, hospital_id: str) -> IndicatorDraftSpec:
        data = _extract_json(raw)
        if not data:
            raise _RetryableDraftParseError(
                "无法解析模型返回的指标设计稿，请补充指标定义后重试。"
            )
        if any(key in data for key in ("sql", "sql_text", "sql_template")):
            raise DraftParseError("模型不能直接提供SQL，只能生成结构化计算计划。")

        declared_requirements = data.get("metadata_requirements")
        if not isinstance(declared_requirements, list) or not all(
            isinstance(value, str) for value in declared_requirements
        ):
            raise _RetryableDraftParseError(
                "metadata_requirements 必须是字符串数组"
            )

        tables = [str(value).strip() for value in data.pop("required_tables", []) if str(value).strip()]
        requires_join = bool(data.pop("requires_join", False))
        sql_plan = data.get("sql_plan") if isinstance(data.get("sql_plan"), dict) else {}
        sql_plan["hospital_field"] = "hospital_id"
        data["sql_plan"] = sql_plan
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
            contract = IndicatorDraftSpec.model_validate(data)
        except ValidationError as exc:
            fields = sorted(
                {
                    ".".join(str(value) for value in item["loc"])
                    for item in exc.errors()
                    if item.get("loc")
                }
            )
            raise _RetryableDraftParseError(
                f"指标设计稿结构不完整或不合法：{', '.join(fields) or '未知字段'}"
            ) from exc

        if contract.sql_plan is None:
            raise _RetryableDraftParseError(
                "指标设计稿缺少 sql_plan 结构化计算计划"
            )
        declared = {value.strip() for value in declared_requirements if value.strip()}
        referenced = {
            contract.sql_plan.subject_field,
            contract.sql_plan.time_field,
            contract.sql_plan.hospital_field,
            *(
                condition.field
                for condition in [
                    *contract.sql_plan.numerator_conditions,
                    *contract.sql_plan.denominator_conditions,
                ]
            ),
            *(
                condition.compare_field
                for condition in [
                    *contract.sql_plan.numerator_conditions,
                    *contract.sql_plan.denominator_conditions,
                ]
                if condition.compare_field
            ),
        }
        unknown = sorted(referenced - declared)
        if unknown:
            raise _RetryableDraftParseError(
                "计算计划使用了未在 metadata_requirements 声明的字段："
                + ", ".join(unknown)
            )
        return contract


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
                compare_field = str(condition.get("compare_field") or "").strip()
                if compare_field:
                    result.append(compare_field)
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
    return format_prompt("indicator_draft_parser", query=query)


def _repair_prompt(query: str, raw: str, error: str) -> str:
    return format_prompt(
        "indicator_draft_repair",
        query=query,
        raw_content=raw[:6000],
        validation_error=error,
    )
