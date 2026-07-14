from __future__ import annotations

import json
from datetime import datetime
from typing import Any

from sqlalchemy import Engine, text


def _datetime(value: Any) -> datetime | None:
    if value is None or isinstance(value, datetime):
        return value
    return datetime.fromisoformat(str(value))


def _json(value: Any) -> Any:
    if value is None or isinstance(value, (dict, list)):
        return value
    return json.loads(str(value))


def _row(value: Any) -> dict[str, Any] | None:
    if value is None:
        return None
    item = dict(value._mapping)
    for key in ("created_at", "expires_at", "run_time"):
        if key in item:
            item[key] = _datetime(item.get(key))
    for key in ("run_context_json", "column_schema_json"):
        if key in item:
            item[key] = _json(item.get(key))
    return item


class IndicatorDetailRepository:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def get_run(self, run_id: str) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(
                text("SELECT * FROM med_sql_run_log WHERE run_id=:run_id"),
                {"run_id": run_id},
            ).first()
        return _row(row)

    def get_snapshot_by_run(self, run_id: str) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(
                text(
                    "SELECT * FROM med_indicator_detail_snapshot "
                    "WHERE run_id=:run_id"
                ),
                {"run_id": run_id},
            ).first()
        return _row(row)

    def begin_snapshot(
        self,
        *,
        snapshot_id: str,
        run_id: str,
        hospital_id: str,
        rule_id: str,
        relative_path: str,
        created_by: str,
        created_at: datetime,
        expires_at: datetime,
    ) -> dict[str, Any]:
        with self.engine.begin() as conn:
            existing = conn.execute(
                text(
                    "SELECT snapshot_id FROM med_indicator_detail_snapshot "
                    "WHERE run_id=:run_id"
                ),
                {"run_id": run_id},
            ).first()
            if existing:
                conn.execute(
                    text(
                        """
                        UPDATE med_indicator_detail_snapshot
                        SET snapshot_id=:snapshot_id,
                            hospital_id=:hospital_id, rule_id=:rule_id,
                            relative_path=:relative_path, file_sha256=NULL,
                            denominator_count=NULL, numerator_count=NULL,
                            unmatched_count=NULL, column_schema_json=NULL,
                            status='creating', created_by=:created_by,
                            created_at=:created_at, expires_at=:expires_at,
                            error_message=NULL
                        WHERE run_id=:run_id
                        """
                    ),
                    {
                        "snapshot_id": snapshot_id,
                        "run_id": run_id,
                        "hospital_id": hospital_id,
                        "rule_id": rule_id,
                        "relative_path": relative_path,
                        "created_by": created_by,
                        "created_at": created_at,
                        "expires_at": expires_at,
                    },
                )
            else:
                conn.execute(
                    text(
                        """
                        INSERT INTO med_indicator_detail_snapshot
                          (snapshot_id, run_id, hospital_id, rule_id, relative_path,
                           status, created_by, created_at, expires_at)
                        VALUES
                          (:snapshot_id, :run_id, :hospital_id, :rule_id, :relative_path,
                           'creating', :created_by, :created_at, :expires_at)
                        """
                    ),
                    {
                        "snapshot_id": snapshot_id,
                        "run_id": run_id,
                        "hospital_id": hospital_id,
                        "rule_id": rule_id,
                        "relative_path": relative_path,
                        "created_by": created_by,
                        "created_at": created_at,
                        "expires_at": expires_at,
                    },
                )
        snapshot = self.get_snapshot_by_run(run_id)
        if snapshot is None:
            raise RuntimeError("明细快照记录创建失败")
        return snapshot

    def mark_snapshot_ready(
        self,
        run_id: str,
        *,
        file_sha256: str,
        denominator_count: int,
        numerator_count: int,
        unmatched_count: int,
        columns: list[dict[str, Any]],
    ) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    """
                    UPDATE med_indicator_detail_snapshot
                    SET file_sha256=:file_sha256,
                        denominator_count=:denominator_count,
                        numerator_count=:numerator_count,
                        unmatched_count=:unmatched_count,
                        column_schema_json=:columns,
                        status='ready', error_message=NULL
                    WHERE run_id=:run_id
                    """
                ),
                {
                    "run_id": run_id,
                    "file_sha256": file_sha256,
                    "denominator_count": denominator_count,
                    "numerator_count": numerator_count,
                    "unmatched_count": unmatched_count,
                    "columns": json.dumps(columns, ensure_ascii=False),
                },
            )

    def mark_snapshot_failed(self, run_id: str, error_message: str) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    "UPDATE med_indicator_detail_snapshot "
                    "SET status='failed', error_message=:error WHERE run_id=:run_id"
                ),
                {"run_id": run_id, "error": error_message[:1000]},
            )
