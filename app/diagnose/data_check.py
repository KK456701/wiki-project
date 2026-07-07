"""第三层：原始数据质量校验。"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

from app.db_access.business_db import BusinessDBClient
from app.sqlgen.spec_loader import load_field_contract, load_hospital_mapping

LAYER_NAME = "数据质量校验"
TYPE_ACCESS_FAIL = "数据访问失败"
TYPE_RISK = "数据质量风险"
TYPE_OK = "数据质量正常"


def load_yaml(path: Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def _check(name: str, status: str, message: str, repair_suggest: str = "") -> dict[str, str]:
    return {"name": name, "status": status, "message": message, "repair_suggest": repair_suggest}


def _quote_ident(name: str, dialect: str = "") -> str:
    raw = str(name or "")
    if str(dialect or "").lower() in {"mysql", "mariadb"}:
        safe = raw.replace("`", "``")
        return f"`{safe}`"
    safe = raw.replace('"', '""')
    return f'"{safe}"'


def _normalize_col_ref(col_ref: str, fallback_table: str) -> tuple[str, str]:
    parts = [p for p in str(col_ref or "").split(".") if p]
    if len(parts) >= 2:
        return parts[-2], parts[-1]
    return fallback_table, parts[0] if parts else ""


def _first_value(row: dict[str, Any], default: Any = 0) -> Any:
    if not row:
        return default
    return next(iter(row.values()), default)


def data_check(kb_root: Path, business_db: BusinessDBClient, hospital_id: str, rule_id: str) -> dict[str, Any]:
    checks: list[dict[str, str]] = []
    try:
        mapping = load_hospital_mapping(Path(kb_root), hospital_id, rule_id)
        contract = load_field_contract(Path(kb_root), rule_id)
    except Exception as exc:
        checks.append(_check("data_prerequisite", "warn", f"无法加载数据质量校验配置: {exc}", "先补齐字段契约和医院字段映射。"))
        return _result(True, checks)

    main_table = str(mapping.get("main_table") or "")
    dialect = str(mapping.get("dialect") or "mysql")
    fields = mapping.get("fields") or {}
    business_fields = contract.get("business_fields") or {}
    if not main_table:
        checks.append(_check("main_table", "fail", "医院字段映射未配置主表 main_table。", "补充 main_table 后重新诊断。"))
        return _result(False, checks)

    try:
        total_result = business_db.execute_select(f"SELECT COUNT(*) AS total FROM {_quote_ident(main_table, dialect)}")
        total_row = total_result.rows[0] if total_result.rows else {}
        total = int(total_row.get("total", _first_value(total_row, 0)) or 0)
    except Exception as exc:
        checks.append(
            _check(
                "main_table_access",
                "fail",
                f"无法通过业务库 MCP 访问主表 {main_table}: {exc}",
                "检查 DBHub 连接、表名、账号权限和数据库来源配置。",
            )
        )
        return _result(False, checks)

    if total == 0:
        checks.append(_check("table_rows", "warn", f"业务主表 {main_table} 当前没有数据。", "检查统计周期或业务数据同步状态。"))
        return _result(True, checks)
    if total < 10:
        checks.append(_check("table_rows", "warn", f"业务主表样本量较小：{total} 行。", "确认当前是否为测试库，或统计周期是否覆盖足够数据。"))
    else:
        checks.append(_check("table_rows", "pass", f"业务主表可访问，共 {total} 行。"))

    for field_name, spec in business_fields.items():
        col_ref = fields.get(field_name)
        if not col_ref:
            continue
        table_name, column_name = _normalize_col_ref(str(col_ref), main_table)
        if table_name != main_table:
            checks.append(
                _check(
                    f"field.{field_name}",
                    "warn",
                    f"字段 {field_name} 映射到非主表 {table_name}.{column_name}，MVP 阶段仅检查主表字段。",
                    "后续可扩展关联表数据质量检查。",
                )
            )
            continue

        required = bool((spec or {}).get("required", False))
        field_type = str((spec or {}).get("type") or "").lower()
        null_sql = (
            f"SELECT COUNT(*) AS total, "
            f"SUM(CASE WHEN {_quote_ident(column_name, dialect)} IS NULL THEN 1 ELSE 0 END) AS nulls "
            f"FROM {_quote_ident(main_table, dialect)}"
        )
        try:
            null_result = business_db.execute_select(null_sql)
            row = null_result.rows[0] if null_result.rows else {}
        except Exception as exc:
            checks.append(
                _check(
                    f"field.{field_name}",
                    "fail" if required else "warn",
                    f"检查字段 {column_name} 失败: {exc}",
                    "确认业务表字段是否存在，或更新医院字段映射。",
                )
            )
            continue

        nulls = int(row.get("nulls") or 0)
        row_total = int(row.get("total") or total or 0)
        null_rate = nulls / max(row_total, 1)
        if required and null_rate >= 0.5:
            checks.append(
                _check(
                    f"null_rate.{field_name}",
                    "warn",
                    f"必填字段 {column_name} 空值率较高：{nulls}/{row_total}（{null_rate:.0%}）。",
                    "检查源数据质量，或确认字段映射是否正确。",
                )
            )
        elif null_rate > 0.3:
            checks.append(
                _check(
                    f"null_rate.{field_name}",
                    "warn",
                    f"字段 {column_name} 空值率偏高：{nulls}/{row_total}（{null_rate:.0%}）。",
                    "确认该字段是否参与筛选、分组或分子分母计算。",
                )
            )
        else:
            checks.append(_check(f"null_rate.{field_name}", "pass", f"字段 {column_name} 空值率可接受：{nulls}/{row_total}（{null_rate:.0%}）。"))

        if field_type == "datetime" and nulls > 0:
            checks.append(
                _check(
                    f"datetime.{field_name}",
                    "warn",
                    f"时间字段 {column_name} 存在空值：{nulls}/{row_total}。",
                    "时间字段空值可能影响分子、分母或统计周期过滤。",
                )
            )

    return _result(not any(c["status"] == "fail" for c in checks), checks)


def _result(ok: bool, checks: list[dict[str, str]]) -> dict[str, Any]:
    failed = [c for c in checks if c["status"] == "fail"]
    warnings = [c for c in checks if c["status"] == "warn"]
    return {
        "ok": ok,
        "layer": 3,
        "layer_name": LAYER_NAME,
        "checks": checks,
        "diagnose_type": TYPE_ACCESS_FAIL if failed else (TYPE_RISK if warnings else TYPE_OK),
        "problem_detail": "；".join(c["message"] for c in failed + warnings),
        "repair_suggest": "；".join(c["repair_suggest"] for c in failed + warnings if c.get("repair_suggest")),
        "repair_sql": "",
    }
