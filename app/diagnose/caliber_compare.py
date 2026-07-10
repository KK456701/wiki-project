"""Execute national and hospital-effective calibers against the same data scope."""

from __future__ import annotations

import time
import uuid
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import Engine

from app.agents.contracts import CaliberComparisonContext, FieldMapping
from app.db.repositories import insert_sql_run_log
from app.db_access.business_db import BusinessDBClient
from app.sqlgen.runner import _bind_sql_params
from app.sqlgen.template_renderer import render_sql
from app.sqlgen.validator import validate_select_sql


class CaliberCompareError(ValueError):
    """Raised when a comparison request cannot be safely prepared."""


def _parse_period_endpoint(value: str, *, is_end: bool) -> datetime:
    normalized = value.strip()
    try:
        if len(normalized) == 10:
            parsed = datetime.strptime(normalized, "%Y-%m-%d")
            return parsed + timedelta(days=1) if is_end else parsed
        return datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise CaliberCompareError(f"统计周期格式无效: {value}") from exc


def parse_diagnose_period(
    stat_period: str | None, now: datetime | None = None
) -> tuple[str, str, str]:
    if not str(stat_period or "").strip():
        current = now or datetime.now()
        start = current.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        end = (
            start.replace(year=start.year + 1, month=1)
            if start.month == 12
            else start.replace(month=start.month + 1)
        )
    else:
        parts = str(stat_period).split("~")
        if len(parts) != 2 or not all(part.strip() for part in parts):
            raise CaliberCompareError("统计周期必须使用 开始时间~结束时间 格式")
        start = _parse_period_endpoint(parts[0], is_end=False)
        end = _parse_period_endpoint(parts[1], is_end=True)

    if end <= start:
        raise CaliberCompareError("统计周期结束时间必须晚于开始时间")
    start_text = start.isoformat(sep=" ", timespec="seconds")
    end_text = end.isoformat(sep=" ", timespec="seconds")
    return start_text, end_text, f"{start_text}~{end_text}"


def _duration_ms(started_at: float) -> int:
    return max(1, int((time.perf_counter() - started_at) * 1000))


def _error_code(exc: Exception) -> str:
    message = str(exc)
    if "SQL 参数缺失" in message:
        return "sql_parameter_missing"
    if isinstance(exc, CaliberCompareError):
        return "sql_validation_failed"
    return "sql_execution_failed"


def _execute_side(
    *,
    runtime_engine: Engine,
    business_db: BusinessDBClient,
    context: CaliberComparisonContext,
    field_mapping: FieldMapping,
    side: str,
    sql_template: str,
    caliber_params: dict[str, Any],
    version: str | int | None,
    stat_start: str,
    stat_end: str,
) -> dict[str, Any]:
    run_id = f"RUN_DIAG_{uuid.uuid4().hex[:12]}"
    sql_id = f"DIAG_{side.upper()}_{context.rule_id}_{uuid.uuid4().hex[:8]}"
    started_at = time.perf_counter()
    status = "failed"
    result_value: float | None = None
    sample_count: int | None = None
    no_sample = False
    error_code = ""
    error_message = ""
    source = getattr(business_db, "source_id", "")
    tool_name = getattr(business_db, "tool_name", "")

    try:
        if not sql_template.strip():
            raise CaliberCompareError("口径 SQL 模板为空")
        custom_rules = dict(field_mapping.get("custom_rules") or {})
        rendered_sql = render_sql(
            sql_template,
            field_mapping.fields,
            field_mapping.main_table,
            custom_rules,
        )
        validation = validate_select_sql(
            rendered_sql, context.hospital_id, field_mapping.main_table
        )
        if not validation.get("ok"):
            raise CaliberCompareError(str(validation.get("error") or "SQL 安全校验失败"))
        bound_params = {
            "hospital_id": context.hospital_id,
            "start_time": stat_start,
            "end_time": stat_end,
            **dict(field_mapping.filters),
            **caliber_params,
        }
        executable_sql = _bind_sql_params(rendered_sql, bound_params)
        query_result = business_db.execute_select(executable_sql)
        source = query_result.source
        tool_name = query_result.tool_name
        first_row = query_result.rows[0] if query_result.rows else {}
        if not first_row or first_row.get("index_value") is None:
            raise CaliberCompareError("口径 SQL 未返回 index_value")
        result_value = round(float(first_row["index_value"]), 2)
        if first_row.get("sample_count") is not None:
            sample_count = int(first_row["sample_count"] or 0)
            no_sample = sample_count == 0
        status = "success"
    except Exception as exc:
        error_code = _error_code(exc)
        error_message = str(exc)

    duration_ms = _duration_ms(started_at)
    insert_sql_run_log(
        runtime_engine,
        run_id,
        sql_id,
        context.hospital_id,
        context.rule_id,
        stat_start,
        stat_end,
        status,
        result_value,
        error_message,
        duration_ms,
        "diagnose_caliber_compare",
    )
    return {
        "side": side,
        "version": version,
        "status": status,
        "result_value": result_value,
        "sample_count": sample_count,
        "no_sample": no_sample,
        "error_code": error_code,
        "error_message": error_message,
        "duration_ms": duration_ms,
        "run_id": run_id,
        "source": source,
        "tool_name": tool_name,
    }


def _classify(
    national: dict[str, Any], hospital: dict[str, Any], tolerance: float
) -> dict[str, Any]:
    national_ok = national["status"] == "success"
    hospital_ok = hospital["status"] == "success"
    if national_ok and not hospital_ok:
        return {
            "conclusion_code": "hospital_caliber_execution_failed",
            "blocking": True,
        }
    if not national_ok and hospital_ok:
        return {
            "conclusion_code": "national_caliber_execution_failed",
            "blocking": True,
        }
    if not national_ok and not hospital_ok:
        return {
            "conclusion_code": "shared_caliber_execution_failed",
            "blocking": True,
        }
    if national["no_sample"] and hospital["no_sample"]:
        return {"conclusion_code": "caliber_no_sample", "blocking": False}

    absolute_delta = round(
        abs(float(national["result_value"]) - float(hospital["result_value"])),
        2,
    )
    national_value = float(national["result_value"])
    relative_delta = (
        round(absolute_delta / abs(national_value) * 100, 2)
        if national_value != 0
        else None
    )
    return {
        "conclusion_code": (
            "caliber_result_diff"
            if absolute_delta > tolerance
            else "caliber_result_same"
        ),
        "blocking": False,
        "absolute_delta": absolute_delta,
        "relative_delta_percent": relative_delta,
    }


def execute_caliber_comparison(
    *,
    runtime_engine: Engine,
    business_db: BusinessDBClient,
    context: CaliberComparisonContext | dict[str, Any],
    field_mapping: FieldMapping | dict[str, Any],
    stat_period: str | None,
    tolerance: float = 0.01,
) -> dict[str, Any]:
    comparison = CaliberComparisonContext.model_validate(context)
    mapping = FieldMapping.model_validate(field_mapping)
    stat_start, stat_end, normalized_period = parse_diagnose_period(stat_period)
    if not comparison.applicable:
        return {
            "applicable": False,
            "reason": comparison.reason,
            "conclusion_code": "caliber_compare_not_applicable",
            "blocking": False,
            "stat_period": normalized_period,
        }

    national = _execute_side(
        runtime_engine=runtime_engine,
        business_db=business_db,
        context=comparison,
        field_mapping=mapping,
        side="national",
        sql_template=comparison.national_sql_template,
        caliber_params=comparison.national_params,
        version=comparison.national_version,
        stat_start=stat_start,
        stat_end=stat_end,
    )
    hospital = _execute_side(
        runtime_engine=runtime_engine,
        business_db=business_db,
        context=comparison,
        field_mapping=mapping,
        side="hospital",
        sql_template=comparison.effective_sql_template,
        caliber_params=comparison.effective_params,
        version=comparison.hospital_version,
        stat_start=stat_start,
        stat_end=stat_end,
    )
    classified = _classify(national, hospital, tolerance)
    return {
        "applicable": True,
        "reason": "",
        "stat_period": normalized_period,
        "national": national,
        "hospital": hospital,
        "overridden_fields": comparison.overridden_fields,
        **classified,
    }
