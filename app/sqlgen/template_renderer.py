"""Jinja2 模板渲染器。"""

from typing import Any
from jinja2 import Template


def render_sql(template_str: str, fields: dict[str, str], main_table: str,
               custom_rules: dict[str, Any] | None = None) -> str:
    rules = custom_rules or {}
    rules.setdefault("exclude_depts", [])
    rules.setdefault("count_multiple_transfers", False)
    tpl = Template(template_str)
    return tpl.render(fields=fields, main_table=main_table, custom_rules=rules).strip()
