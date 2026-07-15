"""提取用户粘贴的 SQL、参数和聚合结果。"""

from __future__ import annotations

import json
import re
from typing import Any

from pydantic import ValidationError

from app.agents.contracts import (
    DiagnosisStatPeriod,
    PastedDiagnosisEvidence,
)


_SQL_START = re.compile(
    r"(?im)^\s*(?:USE\s+\[?[\w-]+\]?\s*;|DECLARE\s+@\w+|;?WITH\s+\w+\s+AS\s*\(|SELECT\s+)"
)
_DATE_VALUE = re.compile(r"^\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?)?$")


def _parse_scalar(raw: str) -> Any:
    value = raw.strip()
    if value.upper() == "NULL":
        return None
    if value.startswith("N'") and value.endswith("'"):
        return value[2:-1].replace("''", "'")
    if value.startswith("'") and value.endswith("'"):
        return value[1:-1].replace("''", "'")
    if re.fullmatch(r"[-+]?\d+", value):
        return int(value)
    if re.fullmatch(r"[-+]?\d+\.\d+", value):
        return float(value)
    return value


def _extract_sql(raw_text: str) -> str:
    blocks = re.findall(r"```\s*([\w+-]*)\s*\r?\n(.*?)```", raw_text, re.DOTALL)
    for language, content in blocks:
        if language.lower() in {"sql", "tsql", "mssql", "sqlserver"}:
            return content.strip()
    for _, content in blocks:
        if _SQL_START.search(content):
            return content.strip()

    match = _SQL_START.search(raw_text)
    if not match:
        return ""
    tail = raw_text[match.start():].strip()
    last_semicolon = tail.rfind(";")
    return tail[: last_semicolon + 1].strip() if last_semicolon >= 0 else tail


def _extract_question(raw_text: str) -> str:
    without_blocks = re.sub(r"```.*?```", "", raw_text, flags=re.DOTALL)
    sql_match = _SQL_START.search(without_blocks)
    if sql_match:
        without_blocks = without_blocks[: sql_match.start()]
    for line in without_blocks.splitlines():
        candidate = line.strip()
        if candidate:
            return candidate
    return raw_text.strip() if not _SQL_START.search(raw_text) else ""


def _extract_declared_params(sql_text: str) -> dict[str, Any]:
    pattern = re.compile(
        r"(?is)\bDECLARE\s+@(\w+)\s+[\w\[\]]+(?:\s*\([^;=]+\))?\s*=\s*(N?'(?:''|[^'])*'|NULL|[-+]?\d+(?:\.\d+)?)\s*;"
    )
    return {name: _parse_scalar(value) for name, value in pattern.findall(sql_text)}


def _first_number(text: str, patterns: list[str]) -> float | int | None:
    for pattern in patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            value = float(match.group(1))
            return int(value) if value.is_integer() else value
    return None


def _extract_claimed_result(raw_text: str) -> dict[str, Any]:
    result: dict[str, Any] = {}
    numerator = _first_number(
        raw_text,
        [r"分子(?:\s*\([^)]*\))?\s*(?:为|是|=|[:：])?\s*(\d+(?:\.\d+)?)"],
    )
    denominator = _first_number(
        raw_text,
        [r"分母(?:\s*\([^)]*\))?\s*(?:为|是|=|[:：])?\s*(\d+(?:\.\d+)?)"],
    )
    index_value = _first_number(
        raw_text,
        [r"(?:指标结果|结果|比例|及时率)\s*(?:为|是|=|[:：])?\s*(\d+(?:\.\d+)?)\s*%"],
    )
    sample_count = _first_number(
        raw_text,
        [r"(?:样本数|样本量)\s*(?:为|是|=|[:：])?\s*(\d+(?:\.\d+)?)"],
    )
    for key, value in (
        ("numerator_count", numerator),
        ("denominator_count", denominator),
        ("index_value", index_value),
        ("sample_count", sample_count),
    ):
        if value is not None:
            result[key] = value
    return result


def _extract_period(params: dict[str, Any], raw_text: str) -> DiagnosisStatPeriod:
    start = next(
        (str(value) for name, value in params.items() if re.search(r"begin|start|from", name, re.I) and value is not None),
        None,
    )
    end = next(
        (str(value) for name, value in params.items() if re.search(r"end|to", name, re.I) and value is not None),
        None,
    )
    if not start or not end:
        dates = re.findall(r"\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2}:\d{2})?", raw_text)
        valid_dates = [item for item in dates if _DATE_VALUE.match(item)]
        if not start and valid_dates:
            start = valid_dates[0]
        if not end and len(valid_dates) > 1:
            end = valid_dates[1]
    return DiagnosisStatPeriod(start=start, end=end)


def _parse_model_json(raw: str) -> dict[str, Any]:
    text = raw.strip()
    fenced = re.search(r"```(?:json)?\s*(.*?)```", text, re.DOTALL | re.IGNORECASE)
    if fenced:
        text = fenced.group(1).strip()
    return json.loads(text)


def _model_prompt(raw_text: str, rule_id: str | None) -> str:
    return (
        "请从以下医院本地诊断文本中提取问题、SQL参数和用户声称的聚合结果。"
        "只返回JSON，不判断SQL安全，不补造数据。字段为question、rule_id、sql_text、"
        "declared_params、claimed_result、stat_period、parse_warnings。\n"
        f"当前指标：{rule_id or '未确认'}\n文本：\n{raw_text}"
    )


def extract_pasted_evidence(
    raw_text: str,
    *,
    rule_id: str | None,
    llm_client: Any | None = None,
) -> PastedDiagnosisEvidence:
    """确定性证据优先，模型只补充无法从文本直接确认的描述。"""

    sql_text = _extract_sql(raw_text)
    params = _extract_declared_params(sql_text)
    deterministic = PastedDiagnosisEvidence(
        raw_text=raw_text,
        question=_extract_question(raw_text),
        rule_id=rule_id,
        sql_text=sql_text,
        declared_params=params,
        claimed_result=_extract_claimed_result(raw_text),
        stat_period=_extract_period(params, raw_text),
    )
    if llm_client is None:
        return deterministic

    try:
        payload = _parse_model_json(llm_client.generate(_model_prompt(raw_text, rule_id)))
        model = PastedDiagnosisEvidence(raw_text=raw_text, **payload)
    except (json.JSONDecodeError, TypeError, ValidationError, ValueError, AttributeError) as exc:
        deterministic.model_parse_status = "invalid"
        deterministic.parse_warnings.append(f"模型证据解析未采用：{exc}")
        return deterministic

    overridden = False
    for field_name in ("rule_id", "sql_text", "declared_params", "claimed_result", "stat_period"):
        deterministic_value = getattr(deterministic, field_name)
        model_value = getattr(model, field_name)
        if deterministic_value and model_value and deterministic_value != model_value:
            overridden = True
    if not deterministic.question and model.question:
        deterministic.question = model.question
    deterministic.parse_warnings.extend(model.parse_warnings)
    if overridden:
        deterministic.parse_warnings.append("模型结果与原文冲突，已采用确定性解析结果。")
        deterministic.model_parse_status = "accepted_with_overrides"
    else:
        deterministic.model_parse_status = "accepted"
    return deterministic
