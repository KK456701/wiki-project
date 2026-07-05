"""第二层：口径规则校验。"""

from typing import Any


def rule_check(effective_rule: dict[str, Any]) -> dict[str, Any]:
    formula = effective_rule.get("formula", "")
    definition = effective_rule.get("definition", "")

    if not formula and not definition:
        return {
            "ok": False, "layer": 2, "layer_name": "口径规则校验",
            "diagnose_type": "口径缺失",
            "problem_detail": "当前口径无公式和定义",
            "repair_suggest": "请先确认指标口径是否已配置",
            "repair_sql": "",
        }

    if "0" in formula.split("分母")[-1][:10] if "分母" in formula else False:
        return {
            "ok": False, "layer": 2, "layer_name": "口径规则校验",
            "diagnose_type": "口径风险",
            "problem_detail": "公式分母可能存在除零风险",
            "repair_suggest": "请检查分母定义是否有数据保障",
            "repair_sql": "",
        }

    return {"ok": True, "layer": 2, "layer_name": "口径规则校验", "message": "口径规则正常"}
