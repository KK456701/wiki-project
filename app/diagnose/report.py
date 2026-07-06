"""Diagnose report aggregation and persistence."""

from typing import Any
import uuid

from sqlalchemy import Engine

from app.db.repositories import insert_diagnose_report

TITLE_FAILED = "\u8bca\u65ad\u672a\u901a\u8fc7"
TITLE_WARNING = "\u8bca\u65ad\u901a\u8fc7\u4f46\u5b58\u5728\u98ce\u9669"
TITLE_HEALTHY = "\u8bca\u65ad\u901a\u8fc7"
SUMMARY_OK = "\u5168\u90e8\u901a\u8fc7"
SUMMARY_RISK = "\u5b58\u5728\u98ce\u9669: "
ICON_PASS = "\u901a\u8fc7"
ICON_FAIL = "\u5931\u8d25"


def _has_warning(layer_results: list[dict[str, Any]]) -> bool:
    for layer in layer_results:
        for check in layer.get("checks", []):
            if check.get("status") == "warn":
                return True
    return False


def _status(layer_results: list[dict[str, Any]]) -> str:
    if any(not layer.get("ok") for layer in layer_results):
        return "failed"
    if _has_warning(layer_results):
        return "warning"
    return "healthy"


def _summary_markdown(layer_results: list[dict[str, Any]], diagnose_status: str) -> str:
    title = {"failed": TITLE_FAILED, "warning": TITLE_WARNING, "healthy": TITLE_HEALTHY}.get(diagnose_status, TITLE_HEALTHY)
    lines = [f"### {title}"]
    for layer in layer_results:
        icon = ICON_PASS if layer.get("ok") else ICON_FAIL
        lines.append(f"- L{layer.get('layer')} {layer.get('layer_name')}: {icon}")
        for check in layer.get("checks", []):
            if check.get("status") != "pass":
                lines.append(f"  - {check.get('status')}: {check.get('message')}")
    return "\\n".join(lines)


def build_report(layer_results: list[dict[str, Any]], trigger_type: str = "manual", related_sql_id: str | None = None, stat_period: str | None = None) -> dict[str, Any]:
    failed = [r for r in layer_results if not r.get("ok")]
    diagnose_status = _status(layer_results)
    risky_layers = [r.get("layer_name", "unknown") for r in layer_results if not r.get("ok") or any(c.get("status") == "warn" for c in r.get("checks", []))]
    summary = SUMMARY_OK if diagnose_status == "healthy" else SUMMARY_RISK + ", ".join(risky_layers)
    return {
        "ok": len(failed) == 0,
        "layers": layer_results,
        "summary": summary,
        "summary_markdown": _summary_markdown(layer_results, diagnose_status),
        "diagnose_status": diagnose_status,
        "trigger_type": trigger_type,
        "related_sql_id": related_sql_id,
        "stat_period": stat_period,
    }


def save_report(runtime_engine: Engine, hospital_id: str, rule_id: str, report: dict[str, Any], trigger: str = "manual", related_sql_id: str | None = None, stat_period: str | None = None) -> str:
    report_id = f"DR_{uuid.uuid4().hex[:12]}"
    issue_layers = [r for r in report.get("layers", []) if not r.get("ok") or any(c.get("status") == "warn" for c in r.get("checks", []))]
    first = issue_layers[0] if issue_layers else (report.get("layers") or [{}])[0]
    insert_diagnose_report(
        runtime_engine,
        report_id,
        hospital_id,
        rule_id,
        first.get("diagnose_type", "diagnose"),
        first.get("problem_detail", ""),
        first.get("repair_suggest", ""),
        first.get("repair_sql", ""),
        trigger_type=report.get("trigger_type") or trigger,
        related_sql_id=report.get("related_sql_id") or related_sql_id,
        layer_results=report.get("layers", []),
        diagnose_status=report.get("diagnose_status", "healthy"),
        stat_period=report.get("stat_period") or stat_period,
    )
    return report_id

