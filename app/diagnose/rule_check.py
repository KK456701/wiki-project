"""Layer 2: static rule checks and national/hospital execution comparison."""

from typing import Any

LAYER_NAME = "\u53e3\u5f84\u89c4\u5219\u6821\u9a8c"
TYPE_MISSING = "\u53e3\u5f84\u7f3a\u5931"
TYPE_RISK = "\u53e3\u5f84\u98ce\u9669"
TYPE_OK = "\u53e3\u5f84\u89c4\u5219\u6b63\u5e38"
TYPE_EXECUTION_ERROR = "口径执行异常"


def _check(name: str, status: str, message: str, repair_suggest: str = "") -> dict[str, str]:
    return {"name": name, "status": status, "message": message, "repair_suggest": repair_suggest}


def _comparison_check(comparison: dict[str, Any]) -> dict[str, str]:
    code = str(comparison.get("conclusion_code") or "")
    national = comparison.get("national") or {}
    hospital = comparison.get("hospital") or {}
    versions = (
        f"国标版本 {national.get('version') or '未知'}，"
        f"本院版本 {hospital.get('version') or '未知'}"
    )
    if code == "caliber_result_diff":
        return _check(
            "caliber_comparison",
            "warn",
            (
                "国标口径结果与本院生效口径结果存在差异："
                f"{national.get('result_value')} vs {hospital.get('result_value')}；"
                f"{versions}。"
            ),
            "结合本院定制字段和版本记录确认差异是否符合审批预期。",
        )
    if code == "caliber_result_same":
        return _check(
            "caliber_comparison", "pass", f"双口径执行结果一致；{versions}。"
        )
    if code == "caliber_no_sample":
        return _check(
            "caliber_comparison", "pass", "当前统计周期内两种口径均无样本。"
        )
    if code == "caliber_compare_not_applicable":
        reason = str(comparison.get("reason") or "未提供可比较的医院定制口径")
        return _check(
            "caliber_comparison", "pass", f"本次不执行双口径对比：{reason}。"
        )

    failure_messages = {
        "hospital_caliber_execution_failed": (
            "本院生效口径执行失败，但国标口径执行成功。",
            "检查本院定制参数、定制 SQL 和字段映射。",
        ),
        "national_caliber_execution_failed": (
            "国标口径执行失败，但本院生效口径执行成功。",
            "检查国标 SQL 模板及其参数、字段映射。",
        ),
        "shared_caliber_execution_failed": (
            "国标口径与本院生效口径均执行失败。",
            "优先检查共享字段映射、统计周期和业务数据库连接。",
        ),
        "caliber_compare_invalid_request": (
            "双口径执行请求无效。",
            "检查统计周期和双口径输入配置。",
        ),
    }
    if code in failure_messages or comparison.get("blocking"):
        message, suggestion = failure_messages.get(
            code,
            ("双口径执行失败。", "检查双口径执行配置与业务数据库状态。"),
        )
        return _check("caliber_comparison", "fail", message, suggestion)
    return _check(
        "caliber_comparison", "pass", "本次未发现双口径执行异常。"
    )


def rule_check(
    effective_rule: dict[str, Any],
    caliber_comparison: dict[str, Any] | None = None,
) -> dict[str, Any]:
    formula = str(effective_rule.get("formula") or "").strip()
    definition = str(effective_rule.get("definition") or "").strip()
    checks: list[dict[str, str]] = []

    if definition:
        checks.append(_check("definition", "pass", "Definition is configured."))
    else:
        checks.append(_check("definition", "fail", "Rule definition is missing.", "Confirm the indicator definition first."))

    if formula:
        checks.append(_check("formula", "pass", "Formula is configured."))
    else:
        checks.append(_check("formula", "fail", "Rule formula is missing.", "Confirm the indicator formula first."))

    zero_guard_tokens = ["NULLIF", "CASE", "NONZERO", "NON-ZERO"]
    if formula and "/" in formula and not any(token in formula.upper() for token in zero_guard_tokens):
        checks.append(_check("zero_guard", "warn", "Formula contains division but no explicit zero-denominator guard.", "Generated SQL should use NULLIF or CASE for denominator protection."))

    if formula and "100" not in formula and "%" not in formula:
        checks.append(_check("percent_unit", "warn", "Formula does not clearly state percentage conversion.", "Confirm whether the result unit is percentage."))

    comparison = caliber_comparison or {
        "applicable": False,
        "reason": "comparison_context_not_provided",
        "conclusion_code": "caliber_compare_not_applicable",
        "blocking": False,
    }
    checks.append(_comparison_check(comparison))

    failed = [c for c in checks if c["status"] == "fail"]
    warnings = [c for c in checks if c["status"] == "warn"]
    comparison_failed = any(
        c["name"] == "caliber_comparison" and c["status"] == "fail"
        for c in checks
    )
    return {
        "ok": not failed,
        "layer": 2,
        "layer_name": LAYER_NAME,
        "checks": checks,
        "diagnose_type": (
            TYPE_EXECUTION_ERROR
            if comparison_failed
            else (TYPE_MISSING if failed else (TYPE_RISK if warnings else TYPE_OK))
        ),
        "problem_detail": "; ".join(c["message"] for c in failed + warnings),
        "repair_suggest": "; ".join(c["repair_suggest"] for c in failed + warnings if c.get("repair_suggest")),
        "repair_sql": "",
        "caliber_comparison": comparison,
        "conclusion_code": comparison.get("conclusion_code"),
    }
