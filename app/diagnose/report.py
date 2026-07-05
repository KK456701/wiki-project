"""诊断报告汇总。"""

from typing import Any
from app.db.repositories import insert_diagnose_report
from sqlalchemy import Engine
import uuid


def build_report(layer_results: list[dict[str, Any]]) -> dict[str, Any]:
    failed = [r for r in layer_results if not r.get("ok")]
    all_ok = len(failed) == 0
    return {
        "ok": all_ok,
        "layers": layer_results,
        "summary": "全部通过" if all_ok else f"{len(failed)} 层未通过: " + ", ".join(r["layer_name"] for r in failed),
    }


def save_report(runtime_engine: Engine, hospital_id: str, rule_id: str,
                report: dict[str, Any], trigger: str = "manual") -> str:
    report_id = f"DR_{uuid.uuid4().hex[:12]}"
    failed = [r for r in report.get("layers", []) if not r.get("ok")]
    if failed:
        f0 = failed[0]
        insert_diagnose_report(runtime_engine, report_id, hospital_id, rule_id,
                               f0.get("diagnose_type", "unknown"),
                               f0.get("problem_detail", ""),
                               f0.get("repair_suggest", ""),
                               f0.get("repair_sql", ""))
    return report_id
