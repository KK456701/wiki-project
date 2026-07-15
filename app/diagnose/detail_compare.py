"""生成并保存用户 SQL 与当前生效口径的记录级差异。"""

from __future__ import annotations

import gzip
import json
import re
import uuid
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable

from app.db_access.business_db import BusinessDBClient, assert_readonly_query
from app.indicator_details.models import DetailQuery, RunContext
from app.indicator_details.snapshot import mask_value
from app.indicator_details.sql_builder import build_detail_query
from app.sqlgen.runner import _bind_sql_params


COMPARISON_GROUPS = {
    "all_differences",
    "only_user_scope",
    "only_current_scope",
    "user_only_numerator",
    "current_only_numerator",
}


def _utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _json_default(value: Any) -> Any:
    if isinstance(value, (datetime, date)):
        return value.isoformat(sep=" ")
    if isinstance(value, Decimal):
        return float(value)
    raise TypeError(f"对账明细值无法序列化：{type(value).__name__}")


def _final_top_level_select(sql: str) -> int:
    positions: list[int] = []
    depth = 0
    index = 0
    state = "normal"
    while index < len(sql):
        char = sql[index]
        pair = sql[index:index + 2]
        if state == "normal":
            if pair == "--":
                state = "line_comment"
                index += 2
                continue
            if pair == "/*":
                state = "block_comment"
                index += 2
                continue
            if char == "'":
                state = "string"
                index += 1
                continue
            if char == "[":
                state = "bracket"
                index += 1
                continue
            if char == "(":
                depth += 1
            elif char == ")":
                depth = max(0, depth - 1)
            elif depth == 0 and sql[index:index + 6].upper() == "SELECT":
                before = sql[index - 1] if index else " "
                after = sql[index + 6] if index + 6 < len(sql) else " "
                if not (before.isalnum() or before == "_") and not (
                    after.isalnum() or after == "_"
                ):
                    positions.append(index)
                    index += 6
                    continue
        elif state == "string":
            if pair == "''":
                index += 2
                continue
            if char == "'":
                state = "normal"
        elif state == "line_comment" and char in "\r\n":
            state = "normal"
        elif state == "block_comment" and pair == "*/":
            state = "normal"
            index += 2
            continue
        elif state == "bracket" and char == "]":
            state = "normal"
        index += 1
    if not positions:
        raise ValueError("用户 SQL 中没有可识别的最终 SELECT。")
    return positions[-1]


def _numerator_flag_identifier(prefix: str, aggregate: str) -> str:
    if re.search(r"(?i)\bTRANSFER_WITHIN_48H\b", prefix):
        return "TRANSFER_WITHIN_48H"
    match = re.search(
        r"(?is)\bSUM\s*\(\s*(?:CAST\s*\(\s*)?"
        r"(?P<identifier>\[[^\]]+\]|[A-Za-z_\u4e00-\u9fff][A-Za-z0-9_\u4e00-\u9fff]*)",
        aggregate,
    )
    if not match:
        raise ValueError("用户 SQL 没有保留分子判定结果，无法逐条对账。")
    identifier = match.group("identifier").strip("[]")
    alias_pattern = (
        r"(?is)\bAS\s+(?:\["
        + re.escape(identifier)
        + r"\]|"
        + re.escape(identifier)
        + r")(?=\s|,|\)|$)"
    )
    if not re.search(alias_pattern, prefix):
        raise ValueError("用户 SQL 没有保留分子判定结果，无法逐条对账。")
    return identifier


def _qualified_identifier(source: str, identifier: str) -> str:
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", identifier):
        return f"{source}.{identifier}"
    return f"{source}.[{identifier}]"


def build_user_detail_query(query_sql: str, rule_id: str) -> str:
    """将受支持的聚合 SQL 改写为业务主键与分子判定明细。"""

    if rule_id != "MQSI2025_001":
        raise ValueError("当前指标暂未配置用户 SQL 的逐条对账规则。")
    select_start = _final_top_level_select(query_sql)
    prefix = query_sql[:select_start].rstrip().rstrip(";")
    aggregate = query_sql[select_start:]
    if not re.search(r"(?i)\bENCOUNTER_ID\b", prefix):
        raise ValueError("用户 SQL 没有保留入院流水号业务主键，无法逐条对账。")
    numerator_flag = _numerator_flag_identifier(prefix, aggregate)
    source_match = re.search(
        r"(?is)\bFROM\s+(\[[^\]]+\]|[A-Za-z_][A-Za-z0-9_$#]*)\s*;?\s*$",
        aggregate,
    )
    if not source_match:
        raise ValueError("用户 SQL 的最终聚合来源不明确，无法安全生成逐条明细。")
    source = source_match.group(1).strip("[]")
    if not re.search(rf"(?is)\b{re.escape(source)}\s+AS\s*\(", prefix):
        raise ValueError("用户 SQL 的最终聚合来源不是可识别的明细集合。")
    detail_sql = (
        f"{prefix}\n"
        "SELECT\n"
        f"  {source}.ENCOUNTER_ID AS [record_key],\n"
        f"  {_qualified_identifier(source, numerator_flag)} "
        "AS [user_meets_numerator]\n"
        f"FROM {source}"
    )
    assert_readonly_query(detail_sql)
    return detail_sql


def build_current_detail_query(
    *,
    effective_rule: dict[str, Any],
    caliber_context: dict[str, Any],
    field_mapping: dict[str, Any],
    stat_start: str,
    stat_end: str,
) -> DetailQuery:
    calculation = dict(effective_rule.get("calculation_definition") or {})
    if not calculation:
        raise ValueError("当前生效口径缺少结构化计算定义，无法生成逐条对账明细。")
    params = dict(caliber_context.get("effective_params") or {})
    params.update({"start_time": stat_start, "end_time": stat_end})
    context = RunContext(
        rule_id=str(effective_rule.get("rule_id") or field_mapping.get("rule_id") or ""),
        rule_name=str(effective_rule.get("rule_name") or "当前指标"),
        effective_level=str(effective_rule.get("effective_level") or "hospital"),
        national_version=(
            str(effective_rule["national_version"])
            if effective_rule.get("national_version") is not None
            else None
        ),
        hospital_version=(
            int(effective_rule["hospital_version"])
            if effective_rule.get("hospital_version") is not None
            else None
        ),
        calculation_definition=calculation,
        field_mapping=dict(field_mapping),
        params=params,
        stat_start=stat_start,
        stat_end=stat_end,
        db_source=str(field_mapping.get("db_name") or ""),
        main_table=str(field_mapping.get("main_table") or ""),
        dialect=str(field_mapping.get("dialect") or "mysql"),
        query_profile=str(field_mapping.get("query_profile") or ""),
        execution_context=dict(effective_rule.get("execution_context") or {}),
    )
    return build_detail_query(context)


def _records(rows: list[dict[str, Any]], key: str, flag: str) -> dict[str, dict[str, Any]]:
    records: dict[str, dict[str, Any]] = {}
    for row in rows:
        raw_key = row.get(key)
        if raw_key is None:
            continue
        record_key = str(raw_key)
        item = records.setdefault(record_key, {"row": dict(row), "flag": False})
        item["flag"] = bool(item["flag"] or int(row.get(flag) or 0) == 1)
    return records


def compare_detail_rows(
    user_rows: list[dict[str, Any]],
    current_rows: list[dict[str, Any]],
) -> dict[str, Any]:
    user = _records(user_rows, "record_key", "user_meets_numerator")
    current = _records(current_rows, "admission_id", "__meets_numerator")
    rows: list[dict[str, Any]] = []
    counts = {group: 0 for group in COMPARISON_GROUPS}
    for record_key in sorted(set(user) | set(current)):
        user_item = user.get(record_key)
        current_item = current.get(record_key)
        user_flag = bool(user_item and user_item["flag"])
        current_flag = bool(current_item and current_item["flag"])
        if user_item is None:
            group = "only_current_scope"
            reason = "当前生效 SQL 纳入统计，用户 SQL 未纳入统计。"
        elif current_item is None:
            group = "only_user_scope"
            reason = "用户 SQL 纳入统计，当前生效 SQL 未纳入统计。"
        elif user_flag and not current_flag:
            group = "user_only_numerator"
            reason = "用户 SQL 计入分子，当前生效 SQL 未计入分子。"
        elif current_flag and not user_flag:
            group = "current_only_numerator"
            reason = "当前生效 SQL 计入分子，用户 SQL 未计入分子。"
        else:
            continue
        details = dict((current_item or {}).get("row") or {})
        details.pop("__meets_numerator", None)
        details.pop("__evidence_row_count", None)
        details.pop("admission_id", None)
        rows.append({
            "record_key": record_key,
            "difference_group": group,
            "difference_reason": reason,
            "user_in_scope": user_item is not None,
            "current_in_scope": current_item is not None,
            "user_meets_numerator": user_flag,
            "current_meets_numerator": current_flag,
            "current_details": details,
        })
        counts[group] += 1
        counts["all_differences"] += 1
    return {"counts": counts, "rows": rows}


def _aggregate_counts(
    rows: list[dict[str, Any]], key: str, flag: str
) -> tuple[int, int]:
    records = _records(rows, key, flag)
    return sum(1 for item in records.values() if item["flag"]), len(records)


def _matches_aggregate(
    rows: list[dict[str, Any]],
    *,
    key: str,
    flag: str,
    aggregate: dict[str, Any],
) -> bool:
    numerator, denominator = _aggregate_counts(rows, key, flag)
    expected_numerator = aggregate.get("numerator_count")
    expected_denominator = aggregate.get("denominator_count")
    return (
        expected_numerator is not None
        and expected_denominator is not None
        and numerator == int(expected_numerator)
        and denominator == int(expected_denominator)
    )


def create_detail_comparison(
    *,
    business_db: BusinessDBClient,
    store: "DiagnosisComparisonStore",
    hospital_id: str,
    rule_id: str,
    source_database: str,
    user_detail_sql: str,
    current_detail_query: DetailQuery,
    user_result: dict[str, Any],
    current_result: dict[str, Any],
    max_rows: int = 20_000,
) -> dict[str, Any]:
    try:
        user_rows = business_db.execute_select(user_detail_sql).rows
        current_sql = _bind_sql_params(
            current_detail_query.sql,
            current_detail_query.params,
        )
        current_rows = business_db.execute_select(current_sql).rows
        if len(user_rows) > max_rows or len(current_rows) > max_rows:
            raise ValueError(
                f"逐条对账任一侧超过{max_rows:,}条，请缩小统计时间后重试。"
            )
        if not _matches_aggregate(
            user_rows,
            key="record_key",
            flag="user_meets_numerator",
            aggregate=user_result,
        ):
            raise ValueError("用户 SQL 明细与汇总结果不一致，暂不展示逐条差异。")
        if not _matches_aggregate(
            current_rows,
            key="admission_id",
            flag="__meets_numerator",
            aggregate=current_result,
        ):
            raise ValueError("当前生效 SQL 明细与汇总结果不一致，暂不展示逐条差异。")
        comparison = compare_detail_rows(user_rows, current_rows)
        return {
            "status": "ready",
            **store.save(
                hospital_id=hospital_id,
                rule_id=rule_id,
                source_database=source_database,
                user_result=user_result,
                current_result=current_result,
                comparison=comparison,
            ),
        }
    except Exception as exc:
        return {"status": "unavailable", "reason": str(exc)}


class DiagnosisComparisonStore:
    def __init__(
        self,
        root: str | Path,
        *,
        now_provider: Callable[[], datetime] = _utcnow,
        ttl: timedelta = timedelta(hours=24),
    ) -> None:
        self.root = Path(root)
        self.now_provider = now_provider
        self.ttl = ttl

    @staticmethod
    def _safe(value: str) -> str:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", value or ""):
            raise ValueError("对账明细标识无效。")
        return value

    def _path(self, hospital_id: str, comparison_id: str) -> Path:
        hospital = self._safe(hospital_id)
        comparison = self._safe(comparison_id)
        root = self.root.resolve()
        path = (root / hospital / f"{comparison}.json.gz").resolve()
        path.relative_to(root)
        return path

    def save(
        self,
        *,
        hospital_id: str,
        rule_id: str,
        source_database: str,
        user_result: dict[str, Any],
        current_result: dict[str, Any],
        comparison: dict[str, Any],
    ) -> dict[str, Any]:
        self.cleanup_expired()
        comparison_id = f"CMP_{uuid.uuid4().hex[:16]}"
        now = self.now_provider()
        payload = {
            "comparison_id": comparison_id,
            "hospital_id": hospital_id,
            "rule_id": rule_id,
            "source_database": source_database,
            "created_at": now.isoformat(sep=" "),
            "expires_at": (now + self.ttl).isoformat(sep=" "),
            "user_result": dict(user_result),
            "current_result": dict(current_result),
            "counts": dict(comparison.get("counts") or {}),
            "rows": list(comparison.get("rows") or []),
        }
        path = self._path(hospital_id, comparison_id)
        path.parent.mkdir(parents=True, exist_ok=True)
        with gzip.open(path, "wt", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, default=_json_default)
        return self._summary(payload)

    def _load(self, hospital_id: str, comparison_id: str) -> dict[str, Any]:
        path = self._path(hospital_id, comparison_id)
        if not path.is_file():
            raise LookupError("差异明细不存在。")
        with gzip.open(path, "rt", encoding="utf-8") as handle:
            payload = json.load(handle)
        if str(payload.get("hospital_id")) != hospital_id:
            raise LookupError("差异明细不存在。")
        if datetime.fromisoformat(str(payload["expires_at"])) <= self.now_provider():
            raise ValueError("差异明细已过期，请重新发起诊断。")
        return payload

    @staticmethod
    def _summary(payload: dict[str, Any]) -> dict[str, Any]:
        return {
            key: payload[key]
            for key in (
                "comparison_id",
                "rule_id",
                "source_database",
                "created_at",
                "expires_at",
                "user_result",
                "current_result",
                "counts",
            )
        }

    def read_summary(self, hospital_id: str, comparison_id: str) -> dict[str, Any]:
        return self._summary(self._load(hospital_id, comparison_id))

    def read_page(
        self,
        hospital_id: str,
        comparison_id: str,
        group: str,
        *,
        page: int,
        page_size: int,
    ) -> dict[str, Any]:
        if group not in COMPARISON_GROUPS:
            raise ValueError("差异明细分组无效。")
        if page < 1 or page_size not in {20, 50, 100}:
            raise ValueError("分页参数无效。")
        payload = self._load(hospital_id, comparison_id)
        rows = list(payload.get("rows") or [])
        if group != "all_differences":
            rows = [item for item in rows if item.get("difference_group") == group]
        start = (page - 1) * page_size
        items = []
        for row in rows[start:start + page_size]:
            item = dict(row)
            item["record_key"] = mask_value(item.get("record_key"), "patient_id")
            items.append(item)
        return {
            "comparison_id": comparison_id,
            "group": group,
            "page": page,
            "page_size": page_size,
            "total": len(rows),
            "items": items,
        }

    def cleanup_expired(self) -> int:
        removed = 0
        if not self.root.exists():
            return removed
        now = self.now_provider()
        for path in self.root.rglob("*.json.gz"):
            try:
                with gzip.open(path, "rt", encoding="utf-8") as handle:
                    payload = json.load(handle)
                expires_at = datetime.fromisoformat(str(payload["expires_at"]))
                if expires_at > now:
                    continue
                path.unlink()
                removed += 1
            except (OSError, ValueError, KeyError, json.JSONDecodeError):
                continue
        return removed
