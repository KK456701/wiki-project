from __future__ import annotations

import gzip
import hashlib
import json
import os
import re
import uuid
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable, Literal

from app.db_access.business_db import BusinessDBClient
from app.sqlgen.runner import _bind_sql_params

from .lineage import build_detail_lineage
from .models import (
    DetailColumn,
    DetailPage,
    DetailSnapshotSummary,
    RunContext,
)
from .repository import IndicatorDetailRepository
from .sql_builder import build_detail_query


MAX_DETAIL_ROWS = 20_000
SNAPSHOT_TTL = timedelta(hours=24)
GROUPS = {"denominator", "numerator", "unmatched"}


def _utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _json_default(value: Any) -> Any:
    if isinstance(value, (datetime, date)):
        return value.isoformat(sep=" ")
    if isinstance(value, Decimal):
        return float(value)
    raise TypeError(f"明细值无法序列化：{type(value).__name__}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def mask_value(value: Any, sensitivity: str) -> Any:
    if value is None or sensitivity == "none":
        return value
    text = str(value)
    if sensitivity == "name":
        return text[:1] + "*" * max(1, len(text) - 1)
    if sensitivity in {"phone", "id_card"}:
        return "*" * max(0, len(text) - 4) + text[-4:]
    if len(text) <= 4:
        return "*" * len(text)
    return text[:2] + "*" * max(3, len(text) - 4) + text[-2:]


class DetailSnapshotStore:
    def __init__(
        self,
        repository: IndicatorDetailRepository,
        business_db: BusinessDBClient,
        *,
        export_root: Path = Path("runtime/exports"),
        now_provider: Callable[[], datetime] = _utcnow,
        max_detail_rows: int = MAX_DETAIL_ROWS,
        snapshot_ttl: timedelta = SNAPSHOT_TTL,
    ) -> None:
        self.repository = repository
        self.business_db = business_db
        self.export_root = Path(export_root)
        self.now_provider = now_provider
        self.max_detail_rows = max_detail_rows
        self.snapshot_ttl = snapshot_ttl

    @staticmethod
    def _safe_segment(value: str) -> str:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", value or ""):
            raise ValueError("快照路径无效")
        return value

    def resolve_snapshot_path(self, snapshot: dict[str, Any]) -> Path:
        root = self.export_root.resolve()
        candidate = (root / str(snapshot.get("relative_path") or "")).resolve()
        try:
            candidate.relative_to(root)
        except ValueError as exc:
            raise ValueError("快照路径无效") from exc
        return candidate

    def _summary(
        self, snapshot: dict[str, Any], context: RunContext, *, reused: bool = False
    ) -> DetailSnapshotSummary:
        columns = [
            DetailColumn.model_validate(item)
            for item in (snapshot.get("column_schema_json") or [])
        ]
        source_database, source_tables, field_lineage = build_detail_lineage(
            context, columns
        )
        return DetailSnapshotSummary(
            snapshot_id=str(snapshot["snapshot_id"]),
            run_id=str(snapshot["run_id"]),
            hospital_id=str(snapshot["hospital_id"]),
            rule_id=str(snapshot["rule_id"]),
            rule_name=context.rule_name,
            effective_level=context.effective_level,
            national_version=context.national_version,
            hospital_version=context.hospital_version,
            stat_start=context.stat_start,
            stat_end=context.stat_end,
            denominator_count=int(snapshot.get("denominator_count") or 0),
            numerator_count=int(snapshot.get("numerator_count") or 0),
            unmatched_count=int(snapshot.get("unmatched_count") or 0),
            columns=columns,
            created_at=snapshot["created_at"],
            expires_at=snapshot["expires_at"],
            reused=reused,
            source_database=source_database,
            source_tables=source_tables,
            field_lineage=field_lineage,
        )

    def _validate_ready_snapshot(self, snapshot: dict[str, Any]) -> Path:
        if snapshot.get("status") != "ready":
            raise ValueError("明细尚未生成，请重新打开详情")
        if snapshot["expires_at"] <= self.now_provider():
            raise ValueError("明细已过期，请重新生成")
        path = self.resolve_snapshot_path(snapshot)
        if not path.is_file():
            raise ValueError("明细文件不存在，请重新生成")
        if _sha256(path) != str(snapshot.get("file_sha256") or ""):
            raise ValueError("明细文件校验失败，请重新生成")
        return path

    def create(
        self, run_id: str, hospital_id: str, actor_id: str
    ) -> DetailSnapshotSummary:
        run = self.repository.get_run(run_id)
        if run is None or str(run.get("hospital_id")) != hospital_id:
            raise LookupError("试运行记录不存在")
        if str(run.get("run_status")) != "success":
            raise ValueError("只有成功的试运行才能查看明细")
        if run.get("run_context_json") is None:
            raise ValueError("该试运行没有口径快照，请重新试运行")
        context = RunContext.model_validate(run["run_context_json"])
        existing = self.repository.get_snapshot_by_run(run_id)
        if existing and existing.get("status") == "ready":
            self._validate_ready_snapshot(existing)
            return self._summary(existing, context, reused=True)

        now = self.now_provider()
        snapshot_id = f"SNAP_{uuid.uuid4().hex[:16]}"
        safe_hospital = self._safe_segment(hospital_id)
        safe_run = self._safe_segment(run_id)
        relative_path = f"{safe_hospital}/{safe_run}/{snapshot_id}.jsonl.gz"
        snapshot = self.repository.begin_snapshot(
            snapshot_id=snapshot_id,
            run_id=run_id,
            hospital_id=hospital_id,
            rule_id=str(run["rule_id"]),
            relative_path=relative_path,
            created_by=actor_id,
            created_at=now,
            expires_at=now + self.snapshot_ttl,
        )
        final_path = self.resolve_snapshot_path(snapshot)
        temp_path = final_path.with_suffix(final_path.suffix + ".tmp")
        try:
            query = build_detail_query(context)
            executable_sql = _bind_sql_params(query.sql, query.params)
            rows = self.business_db.execute_select(executable_sql).rows
            if len(rows) > self.max_detail_rows:
                raise ValueError(
                    f"明细超过{self.max_detail_rows:,}条，请缩小统计区间后重新试运行"
                )
            denominator = len(rows)
            numerator = sum(
                1 for row in rows if int(row.get("__meets_numerator") or 0) == 1
            )
            expected = (
                int(run.get("numerator_count") or 0),
                int(run.get("denominator_count") or 0),
            )
            if (numerator, denominator) != expected:
                raise ValueError("业务数据已经变化，请重新试运行后查看明细")
            unmatched = denominator - numerator
            final_path.parent.mkdir(parents=True, exist_ok=True)
            meta = {
                "run_id": run_id,
                "hospital_id": hospital_id,
                "rule_id": str(run["rule_id"]),
                "rule_name": context.rule_name,
                "stat_start": context.stat_start,
                "stat_end": context.stat_end,
                "created_at": now.isoformat(sep=" "),
                "denominator_count": denominator,
                "numerator_count": numerator,
                "unmatched_count": unmatched,
                "columns": [item.model_dump(mode="json") for item in query.columns],
            }
            with gzip.open(temp_path, "wt", encoding="utf-8", newline="\n") as handle:
                handle.write(json.dumps({"__meta__": meta}, ensure_ascii=False))
                handle.write("\n")
                for row in rows:
                    handle.write(
                        json.dumps(
                            dict(row),
                            ensure_ascii=False,
                            default=_json_default,
                        )
                    )
                    handle.write("\n")
            os.replace(temp_path, final_path)
            self.repository.mark_snapshot_ready(
                run_id,
                file_sha256=_sha256(final_path),
                denominator_count=denominator,
                numerator_count=numerator,
                unmatched_count=unmatched,
                columns=[item.model_dump(mode="json") for item in query.columns],
            )
        except Exception as exc:
            if temp_path.exists():
                temp_path.unlink()
            self.repository.mark_snapshot_failed(run_id, str(exc))
            raise
        ready = self.repository.get_snapshot_by_run(run_id)
        if ready is None:
            raise RuntimeError("明细快照状态保存失败")
        return self._summary(ready, context)

    def _read_rows(self, snapshot: dict[str, Any]) -> list[dict[str, Any]]:
        path = self._validate_ready_snapshot(snapshot)
        rows: list[dict[str, Any]] = []
        with gzip.open(path, "rt", encoding="utf-8") as handle:
            for index, line in enumerate(handle):
                if index == 0:
                    continue
                if line.strip():
                    rows.append(json.loads(line))
        return rows

    def read_all_rows(
        self, run_id: str, hospital_id: str
    ) -> tuple[DetailSnapshotSummary, list[dict[str, Any]]]:
        snapshot = self.repository.get_snapshot_by_run(run_id)
        run = self.repository.get_run(run_id)
        if (
            snapshot is None
            or run is None
            or str(snapshot.get("hospital_id")) != hospital_id
            or str(run.get("hospital_id")) != hospital_id
        ):
            raise LookupError("明细快照不存在")
        context = RunContext.model_validate(run.get("run_context_json"))
        return self._summary(snapshot, context, reused=True), self._read_rows(snapshot)

    def read_page(
        self,
        run_id: str,
        hospital_id: str,
        group: Literal["denominator", "numerator", "unmatched"] | str,
        page: int,
        page_size: int,
    ) -> DetailPage:
        if group not in GROUPS:
            raise ValueError("明细分组无效")
        if page < 1 or page_size not in {20, 50, 100}:
            raise ValueError("分页参数无效")
        snapshot = self.repository.get_snapshot_by_run(run_id)
        if snapshot is None or str(snapshot.get("hospital_id")) != hospital_id:
            raise LookupError("明细快照不存在")
        rows = self._read_rows(snapshot)
        if group == "numerator":
            rows = [row for row in rows if int(row.get("__meets_numerator") or 0) == 1]
        elif group == "unmatched":
            rows = [row for row in rows if int(row.get("__meets_numerator") or 0) == 0]
        columns = [
            DetailColumn.model_validate(item)
            for item in (snapshot.get("column_schema_json") or [])
        ]
        start = (page - 1) * page_size
        items = []
        for row in rows[start : start + page_size]:
            item = {
                column.label: mask_value(row.get(column.field), column.sensitivity)
                for column in columns
            }
            item["是否达到要求"] = (
                "是" if int(row.get("__meets_numerator") or 0) == 1 else "否"
            )
            items.append(item)
        return DetailPage(
            snapshot_id=str(snapshot["snapshot_id"]),
            run_id=run_id,
            group=group,
            page=page,
            page_size=page_size,
            total=len(rows),
            items=items,
        )
