"""Jinja2 template renderer."""

from typing import Any
from jinja2 import Template


def _normalize_custom_rules(custom_rules: dict[str, Any] | None) -> dict[str, Any]:
    rules = dict(custom_rules or {})
    raw_exclude_depts = list(rules.get("exclude_depts") or [])
    rules["exclude_depts"] = [str(value) for value in raw_exclude_depts]
    rules["exclude_dept_filters"] = [
        {"value": str(value), "param": f"exclude_dept_{index}"}
        for index, value in enumerate(raw_exclude_depts)
    ]
    rules.setdefault("count_multiple_transfers", False)
    return rules


def render_sql(template_str: str, fields: dict[str, str], main_table: str,
               custom_rules: dict[str, Any] | None = None) -> str:
    rules = _normalize_custom_rules(custom_rules)
    tpl = Template(template_str)
    return tpl.render(fields=fields, main_table=main_table, custom_rules=rules).strip()
