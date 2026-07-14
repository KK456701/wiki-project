"""SQL 生成 Agent。"""

import uuid
import time
from datetime import datetime
from pathlib import Path
from typing import Any, TYPE_CHECKING

from sqlalchemy import Engine

from app.db.repositories import insert_generated_sql, insert_run_result
from app.db_access.business_db import BusinessDBClient
from app.sqlgen.spec_loader import (
    load_hospital_mapping, load_rule_sql_spec, load_template,
)
from app.sqlgen.template_renderer import render_sql
from app.sqlgen.validator import validate_select_sql
from app.sqlgen.runner import run_sql_trial
from app.rules.calculation import parse_calculation_definition
from app.rules.lineage import build_indicator_lineage

if TYPE_CHECKING:
    from app.rules.repository import RuleRepository


def _elapsed_ms(start: float) -> int:
    return max(1, int((time.perf_counter() - start) * 1000))


def _extract_params(effective_rule: dict[str, Any], spec: dict[str, Any]) -> dict[str, Any]:
    """从 effective_rule 中提取口径参数。MVP：从公式文本中查找数字+单位。"""
    import re
    params: dict[str, Any] = {}
    formula = effective_rule.get("formula", "")
    definition = effective_rule.get("definition", "")
    text = f"{formula} {definition}"

    default_params = spec.get("default_params", {})
    for key, default_val in default_params.items():
        if "minute" in key or "分钟" in key:
            m = re.search(r"(\d+)\s*分钟", text)
            params[key] = int(m.group(1)) if m else default_val
        elif "hour" in key or "小时" in key:
            m = re.search(r"(\d+)\s*小时", text)
            params[key] = int(m.group(1)) if m else default_val
        else:
            params[key] = default_val

    return params


class SQLGenerationAgent:
    def __init__(
        self,
        kb_root: str | Path,
        runtime_engine: Engine,
        business_db: BusinessDBClient,
        rule_repository: "RuleRepository | None" = None,
    ):
        self.kb_root = Path(kb_root)
        self.runtime_engine = runtime_engine
        self.business_db = business_db
        self.rule_repository = rule_repository

    def generate(self, query: str, hospital_id: str, rule_id: str,
                 effective_rule: dict[str, Any], stat_start_time: str,
                 stat_end_time: str, precheck: dict[str, Any],
                 trial_run: bool = False,
                 generated_by: str = "agent",
                 custom_filters: list[dict[str, str]] | None = None,
                 term_bindings: list[dict[str, Any]] | None = None,
                 persist_run_result: bool = True,
                 field_mapping: dict[str, Any] | None = None) -> dict[str, Any]:
        node_timings: dict[str, int] = {}
        if not precheck.get("ok"):
            return {
                "status": "field_precheck_failed",
                "precheck": precheck,
                "_node_timings": node_timings,
                "message": str(precheck.get("error") or "字段预校验未通过。"),
            }

        generate_start = time.perf_counter()
        if field_mapping is not None:
            mapping = dict(field_mapping)
        elif self.rule_repository is not None:
            mapping = self.rule_repository.get_field_mapping(rule_id, hospital_id)
        else:
            mapping = load_hospital_mapping(self.kb_root, hospital_id, rule_id)

        if self.rule_repository is not None or effective_rule.get("standard_sql"):
            spec = None
            template_str = str(effective_rule.get("standard_sql") or "")
            params = dict(effective_rule.get("effective_params") or {})
        else:
            spec = load_rule_sql_spec(self.kb_root, rule_id)
            template_str = load_template(
                self.kb_root, rule_id, mapping.get("dialect", "mysql")
            )
            params = _extract_params(effective_rule, spec)
        dialect = mapping.get("dialect", "mysql")

        # 合并 YAML 配置的 custom_rules + LLM 提取的 custom_filters
        custom_rules: dict[str, Any] = dict(mapping.get("custom_rules") or {})
        custom_rules.setdefault("exclude_depts", [])
        custom_rules.setdefault("count_multiple_transfers", False)

        llm_filters = custom_filters or []
        # 校验 LLM 过滤条件：字段必须存在于映射中
        valid_filters = []
        mapped_fields = mapping.get("fields", {})
        for f in llm_filters:
            field = str(f.get("field", ""))
            if field in mapped_fields:
                valid_filters.append(f)
                # 排除类过滤自动加入 exclude_depts
                if f.get("operator") in ("!=", "not in", "NOT IN"):
                    custom_rules.setdefault("exclude_depts", [])
                    if f.get("value") not in custom_rules["exclude_depts"]:
                        custom_rules["exclude_depts"].append(str(f.get("value", "")))
            if str(f.get("count_multiple", "")).lower() in ("true", "1", "yes"):
                custom_rules["count_multiple_transfers"] = True

        sql_text = render_sql(template_str, mapped_fields, mapping.get("main_table", ""), custom_rules)

        validation_start = time.perf_counter()
        validation = validate_select_sql(sql_text, hospital_id, mapping.get("main_table", ""))
        node_timings["sql_validate"] = _elapsed_ms(validation_start)
        sql_id = f"SQL_{uuid.uuid4().hex[:12]}"
        sql_status = "validated" if validation["ok"] else "invalid"

        if mapping.get("filters", {}).get("consult_type_value"):
            params["consult_type_value"] = mapping["filters"]["consult_type_value"]
        for binding in term_bindings or []:
            parameter_name = str(binding.get("parameter_name") or "")
            values = list(binding.get("values") or [])
            if parameter_name and values:
                params[parameter_name] = values[0] if len(values) == 1 else values
        for index, value in enumerate(custom_rules.get("exclude_depts") or []):
            params[f"exclude_dept_{index}"] = str(value)

        insert_generated_sql(self.runtime_engine, sql_id, hospital_id, rule_id, dialect, sql_text,
                             sql_status, validation.get("message", validation.get("error", "")), generated_by)
        node_timings["sql_generate"] = _elapsed_ms(generate_start)

        result: dict[str, Any] = {
            "sql_id": sql_id, "sql_text": sql_text, "sql_status": sql_status,
            "validation": validation, "dialect": dialect, "params": params,
            "precheck": precheck,
            "calculation_definition": dict(
                effective_rule.get("calculation_definition") or {}
            ),
            "field_mapping": mapping,
            "_node_timings": node_timings,
        }
        calculation_payload = effective_rule.get("calculation_definition")
        if calculation_payload:
            definition = parse_calculation_definition(calculation_payload)
            result["lineage"] = build_indicator_lineage(
                definition,
                mapping,
                params,
                {**effective_rule, "hospital_id": hospital_id},
                stat_start_time,
                stat_end_time,
            )
        else:
            result["lineage"] = {}

        if trial_run and validation["ok"]:
            trial = run_sql_trial(self.runtime_engine, self.business_db, sql_id, sql_text,
                                   hospital_id, rule_id, stat_start_time, stat_end_time, params, generated_by)
            result["trial_run"] = trial
            if persist_run_result and trial.get("result_value") is not None:
                insert_run_result(self.runtime_engine, hospital_id, rule_id,
                                  f"{stat_start_time}~{stat_end_time}", trial["result_value"], trial["run_id"])

        return result
