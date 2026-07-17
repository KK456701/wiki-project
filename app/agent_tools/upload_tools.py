"""上传 Excel 指标明细的模型可见分析工具。"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from functools import partial
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import AgentTool, ToolEvidence, ToolResult, ToolRiskLevel

_UPLOAD_ROOT = Path(__file__).resolve().parents[2] / "runtime" / "uploads"
_UPLOAD_ROOT.mkdir(parents=True, exist_ok=True)


class AnalyzeUploadedIndicatorsInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    file_key: str = Field(min_length=1, max_length=128)


@dataclass(frozen=True, slots=True)
class UploadToolServices:
    pass


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def parse_excel_preview(file_path: Path) -> dict[str, Any]:
    """解析 Excel 并返回摘要数据，不暴露患者明细。"""
    try:
        from openpyxl import load_workbook
    except ImportError:
        return {"error": "openpyxl 未安装，无法解析 Excel。"}

    try:
        wb = load_workbook(file_path, read_only=True, data_only=True)
    except Exception as exc:
        return {"error": f"Excel 文件无法打开：{exc}"}

    sheets_info: list[dict[str, Any]] = []
    total_rows = 0
    headers_sample: dict[str, list[str]] = {}

    for sheet_name in wb.sheetnames:
        ws = wb[sheet_name]
        rows = list(ws.iter_rows(min_row=1, max_row=min(ws.max_row or 0, 5001), values_only=True))
        if not rows:
            continue

        headers = [str(cell) if cell is not None else "" for cell in rows[0]]
        data_rows = rows[1:]
        sheet_total = len(data_rows)
        total_rows += sheet_total

        # 数值列统计
        numeric_stats: dict[str, dict[str, float]] = {}
        for col_idx, header in enumerate(headers):
            if not header:
                continue
            values = [
                float(row[col_idx])
                for row in data_rows
                if col_idx < len(row) and row[col_idx] is not None
                and isinstance(row[col_idx], (int, float))
            ]
            if values:
                numeric_stats[header] = {
                    "min": round(min(values), 4),
                    "max": round(max(values), 4),
                    "sum": round(sum(values), 4),
                    "avg": round(sum(values) / len(values), 4),
                    "count": len(values),
                }

        sheets_info.append({
            "sheet_name": sheet_name,
            "headers": headers[:30],
            "row_count": sheet_total,
            "numeric_columns": numeric_stats,
        })
        if headers and sheet_name not in headers_sample:
            headers_sample[sheet_name] = headers[:30]

    # 尝试检测指标结构
    indicator_hints = _detect_indicator_structure(headers_sample, sheets_info)

    result = {
        "file_name": file_path.name,
        "sheet_count": len(sheets_info),
        "total_rows": total_rows,
        "sheets": sheets_info,
        "indicator_hints": indicator_hints,
    }
    wb.close()
    return result


def _detect_indicator_structure(
    headers_sample: dict[str, list[str]],
    sheets_info: list[dict[str, Any]],
) -> dict[str, Any]:
    """检测 Excel 中可能是指标相关结构的列。"""
    all_headers = set()
    for headers in headers_sample.values():
        all_headers.update(h.lower() for h in headers if h)

    hints: dict[str, Any] = {}

    # 常见指标列名
    numerator_keywords = {"分子", "numerator", "num"}
    denominator_keywords = {"分母", "denominator", "denom"}
    rate_keywords = {"指标率", "率", "rate", "ratio", "percentage", "比例"}
    period_keywords = {"统计周期", "时间", "period", "date", "月份", "季度", "年份"}
    name_keywords = {"指标名称", "indicator", "name", "名称"}

    found = {
        "numerator_cols": [h for h in all_headers if any(kw in h for kw in numerator_keywords)],
        "denominator_cols": [h for h in all_headers if any(kw in h for kw in denominator_keywords)],
        "rate_cols": [h for h in all_headers if any(kw in h for kw in rate_keywords)],
        "period_cols": [h for h in all_headers if any(kw in h for kw in period_keywords)],
        "name_cols": [h for h in all_headers if any(kw in h for kw in name_keywords)],
    }
    hints["detected_columns"] = found
    hints["looks_like_indicator_data"] = (
        bool(found["numerator_cols"] or found["rate_cols"])
        and bool(found["denominator_cols"] or found["rate_cols"])
    )

    # 尝试提取指标率数值
    for sheet in sheets_info:
        numeric = sheet.get("numeric_columns") or {}
        potential_rates = []
        for col_name, stats in numeric.items():
            col_lower = col_name.lower()
            if any(kw in col_lower for kw in rate_keywords):
                potential_rates.append({"column": col_name, "stats": stats, "role": "rate"})
            elif any(kw in col_lower for kw in numerator_keywords):
                potential_rates.append({"column": col_name, "stats": stats, "role": "numerator"})
            elif any(kw in col_lower for kw in denominator_keywords):
                potential_rates.append({"column": col_name, "stats": stats, "role": "denominator"})
        if potential_rates:
            hints.setdefault("potential_indicator_values", []).extend(potential_rates)

    return hints


def _stat_value(stats: dict[str, Any]) -> float | None:
    value = stats.get("avg")
    if value is None:
        value = stats.get("sum")
    return float(value) if value is not None else None


def build_aggregate_comparison(
    preview: dict[str, Any],
    system_result: dict[str, Any],
) -> dict[str, Any]:
    """构造汇总级对比，不把单行汇总误报成患者级差异。"""
    hints = preview.get("indicator_hints") or {}
    candidates = hints.get("potential_indicator_values") or []
    values_by_role: dict[str, float] = {}
    columns_by_role: dict[str, str] = {}
    for candidate in candidates:
        role = str(candidate.get("role") or "")
        if role not in {"numerator", "denominator", "rate"} or role in values_by_role:
            continue
        value = _stat_value(candidate.get("stats") or {})
        if value is None:
            continue
        values_by_role[role] = value
        columns_by_role[role] = str(candidate.get("column") or "")

    definitions = (
        ("denominator", "分母", "system_denominator", "人次", 0.01),
        ("numerator", "分子", "system_numerator", "人次", 0.01),
        ("rate", "指标率", "system_rate", "百分点", 0.01),
    )
    metrics: list[dict[str, Any]] = []
    for role, label, system_key, unit, tolerance in definitions:
        if role not in values_by_role or system_result.get(system_key) is None:
            continue
        uploaded = values_by_role[role]
        system = float(system_result[system_key])
        difference = round(uploaded - system, 4)
        metrics.append({
            "metric": label,
            "role": role,
            "source_column": columns_by_role[role],
            "system_value": round(system, 4),
            "uploaded_value": round(uploaded, 4),
            "difference": difference,
            "unit": unit,
            "match": abs(difference) < tolerance,
        })

    return {
        "comparison_level": "aggregate",
        "comparison_direction": "上传文件值 - 系统值",
        "row_level_comparison_available": False,
        "row_level_note": (
            "上传文件仅包含汇总值，未提供入院流水号等逐条标识，"
            "因此只能核对分子、分母和指标率，不能判断具体记录的交集与差集。"
        ),
        "system_stat_period": system_result.get("system_stat_period"),
        "metrics": metrics,
        "matched_count": sum(1 for item in metrics if item["match"]),
        "different_count": sum(1 for item in metrics if not item["match"]),
    }


def _save_upload(file_content: bytes, filename: str, hospital_id: str) -> str:
    safe_name = f"{hospital_id}_{_utcnow().strftime('%Y%m%d%H%M%S')}_{filename}"
    file_path = _UPLOAD_ROOT / safe_name
    file_path.write_bytes(file_content)
    return safe_name


def analyze_uploaded_indicators(
    arguments: AnalyzeUploadedIndicatorsInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: UploadToolServices,
) -> ToolResult:
    del services
    file_path = _UPLOAD_ROOT / arguments.file_key
    if not file_path.exists() or not file_path.is_file():
        return ToolResult(
            ok=False,
            status="not_found",
            code="UPLOAD_NOT_FOUND",
            summary="未找到已上传的文件，请先上传 Excel 文件。",
        )

    # 安全检查：只能访问自己医院的文件
    if not file_path.name.startswith(f"{context.hospital_id}_"):
        return ToolResult(
            ok=False,
            status="forbidden",
            code="UPLOAD_ACCESS_DENIED",
            summary="无权访问其他医院的上传文件。",
        )

    preview = parse_excel_preview(file_path)
    if "error" in preview:
        return ToolResult(
            ok=False,
            status="error",
            code="EXCEL_PARSE_ERROR",
            summary=preview["error"],
        )

    # 构造与当前试运行结果的对比
    comparison = {}
    for result in reversed(state.last_tool_results):
        if not isinstance(result, dict):
            continue
        data = result.get("data") or {}
        if isinstance(data, dict) and data.get("stat_start"):
            comparison = {
                "system_stat_period": f"{data.get('stat_start')} 至 {data.get('stat_end')}",
                "system_numerator": data.get("numerator_count"),
                "system_denominator": data.get("denominator_count"),
                "system_rate": data.get("result_value"),
            }
            break

    hints = preview.get("indicator_hints") or {}
    aggregate_comparison = (
        build_aggregate_comparison(preview, comparison) if comparison else None
    )
    comparisons_detail = (
        [
            {
                "column": item["source_column"],
                "role": item["role"],
                "excel_value": item["uploaded_value"],
                "system_value": item["system_value"],
                "difference": item["difference"],
                "match": item["match"],
            }
            for item in aggregate_comparison["metrics"]
        ]
        if aggregate_comparison
        else []
    )

    return ToolResult(
        ok=True,
        status="success",
        code="UPLOAD_ANALYZED",
        summary=f"已解析 {preview['file_name']}，共 {preview['total_rows']} 行数据。",
        data={
            "file_name": preview["file_name"],
            "file_key": arguments.file_key,
            "sheet_count": preview["sheet_count"],
            "total_rows": preview["total_rows"],
            "sheets": [
                {
                    "name": s["sheet_name"],
                    "headers": s["headers"],
                    "row_count": s["row_count"],
                }
                for s in preview["sheets"]
            ],
            "indicator_hints": hints,
            "comparison_with_system": comparison if comparison else None,
            "aggregate_comparison": aggregate_comparison,
            "comparisons_detail": comparisons_detail if comparisons_detail else None,
        },
        evidence=[ToolEvidence(
            source="uploaded_excel",
            source_id=arguments.file_key,
            fact_types=["file_analysis"],
        )],
    )


def build_upload_tools(services: UploadToolServices) -> list[AgentTool]:
    return [
        AgentTool(
            name="analyze_uploaded_indicators",
            description=(
                "分析已上传的医院指标 Excel 文件，提取表头、行数、数值列统计（最小/最大/均值/总和），"
                "自动检测指标相关列（分子/分母/指标率/统计周期），并与当前系统试运行结果对比，"
                "输出差异分析而不暴露患者明细。需要先通过上传接口提交文件获得 file_key。"
            ),
            input_model=AnalyzeUploadedIndicatorsInput,
            handler=partial(analyze_uploaded_indicators, services=services),
            risk_level=ToolRiskLevel.READ,
            timeout_seconds=30.0,
        ),
    ]
