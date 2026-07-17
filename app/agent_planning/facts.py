"""业务事实名称的统一与向后兼容。"""

from __future__ import annotations


_FACT_TYPE_ALIASES = {
    "upload_analysis": "file_analysis",
}


def canonical_fact_type(value: object) -> str:
    """返回计划控制器使用的规范事实名称。"""
    fact_type = str(value)
    return _FACT_TYPE_ALIASES.get(fact_type, fact_type)
