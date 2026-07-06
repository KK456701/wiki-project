"""Layer 2: static rule checks without three-caliber comparison."""

from typing import Any

LAYER_NAME = "\u53e3\u5f84\u89c4\u5219\u6821\u9a8c"
TYPE_MISSING = "\u53e3\u5f84\u7f3a\u5931"
TYPE_RISK = "\u53e3\u5f84\u98ce\u9669"
TYPE_OK = "\u53e3\u5f84\u89c4\u5219\u6b63\u5e38"


def _check(name: str, status: str, message: str, repair_suggest: str = "") -> dict[str, str]:
    return {"name": name, "status": status, "message": message, "repair_suggest": repair_suggest}


def rule_check(effective_rule: dict[str, Any]) -> dict[str, Any]:
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

    failed = [c for c in checks if c["status"] == "fail"]
    warnings = [c for c in checks if c["status"] == "warn"]
    return {
        "ok": not failed,
        "layer": 2,
        "layer_name": LAYER_NAME,
        "checks": checks,
        "diagnose_type": TYPE_MISSING if failed else (TYPE_RISK if warnings else TYPE_OK),
        "problem_detail": "; ".join(c["message"] for c in failed + warnings),
        "repair_suggest": "; ".join(c["repair_suggest"] for c in failed + warnings if c.get("repair_suggest")),
        "repair_sql": "",
    }
