from __future__ import annotations

import uuid
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import Engine, and_, insert, select, text, update
from sqlalchemy.exc import IntegrityError

from app.monitoring.contracts import IndicatorAlert, RunPlan, RunResult
from app.monitoring.schema import alert_table, ensure_monitoring_schema, run_plan_table


def _now() -> datetime:
    return datetime.now().replace(microsecond=0)


def _bool_fields(item: dict[str, Any], fields: tuple[str, ...]) -> dict[str, Any]:
    result = dict(item)
    for field in fields:
        if field in result and result[field] is not None:
            result[field] = bool(result[field])
    return result


class MonitoringRepository:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine
        ensure_monitoring_schema(engine)

    def _db_value(self, value: Any) -> Any:
        if self.engine.dialect.name == "sqlite" and isinstance(value, datetime):
            return value.isoformat(sep=" ", timespec="seconds")
        return value

    @staticmethod
    def _plan(row: Any) -> dict[str, Any]:
        payload = _bool_fields(dict(row), ("mom_enabled", "yoy_enabled"))
        return RunPlan.model_validate(payload).model_dump()

    def create_plan(self, payload: dict[str, Any]) -> dict[str, Any]:
        now = _now()
        item = {
            "plan_id": str(payload.get("plan_id") or f"PLAN_{uuid.uuid4().hex[:12]}"),
            "hospital_id": str(payload["hospital_id"]),
            "rule_id": str(payload["rule_id"]),
            "plan_name": str(payload["plan_name"]),
            "frequency": str(payload["frequency"]),
            "run_time": str(payload.get("run_time") or "02:00"),
            "day_of_month": int(payload.get("day_of_month") or 1),
            "timezone": str(payload.get("timezone") or "Asia/Shanghai"),
            "mom_enabled": bool(payload.get("mom_enabled", True)),
            "mom_threshold_pct": float(payload.get("mom_threshold_pct", 20.0)),
            "yoy_enabled": bool(payload.get("yoy_enabled", True)),
            "yoy_threshold_pct": float(payload.get("yoy_threshold_pct", 30.0)),
            "status": str(payload.get("status") or "enabled"),
            "next_run_at": payload.get("next_run_at"),
            "last_run_at": payload.get("last_run_at"),
            "locked_until": None,
            "locked_by": "",
            "created_by": str(payload.get("created_by") or "admin"),
            "created_at": now,
            "updated_at": now,
        }
        RunPlan.model_validate(item)
        with self.engine.begin() as conn:
            conn.execute(insert(run_plan_table).values(**item))
        return self.get_plan(item["plan_id"]) or item

    def update_plan(self, plan_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        allowed = {
            "plan_name", "frequency", "run_time", "day_of_month", "timezone",
            "mom_enabled", "mom_threshold_pct", "yoy_enabled", "yoy_threshold_pct",
            "next_run_at",
        }
        values = {key: value for key, value in payload.items() if key in allowed}
        values["updated_at"] = _now()
        with self.engine.begin() as conn:
            conn.execute(
                update(run_plan_table)
                .where(run_plan_table.c.plan_id == plan_id)
                .values(**values)
            )
        result = self.get_plan(plan_id)
        if result is None:
            raise LookupError(f"运行计划不存在: {plan_id}")
        return result

    def get_plan(self, plan_id: str) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(
                select(run_plan_table).where(run_plan_table.c.plan_id == plan_id)
            ).mappings().first()
        return self._plan(row) if row else None

    def list_plans(self, hospital_id: str) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                select(run_plan_table)
                .where(run_plan_table.c.hospital_id == hospital_id)
                .order_by(run_plan_table.c.id)
            ).mappings().all()
        return [self._plan(row) for row in rows]

    def list_enabled_plans(self) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                select(run_plan_table)
                .where(run_plan_table.c.status == "enabled")
                .order_by(run_plan_table.c.id)
            ).mappings().all()
        return [self._plan(row) for row in rows]

    def list_due_plans(self, now: datetime) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                select(run_plan_table).where(
                    and_(
                        run_plan_table.c.status == "enabled",
                        run_plan_table.c.next_run_at.is_not(None),
                        run_plan_table.c.next_run_at <= now,
                    )
                ).order_by(run_plan_table.c.id)
            ).mappings().all()
        return [self._plan(row) for row in rows]

    def set_plan_status(self, plan_id: str, status: str) -> dict[str, Any]:
        if status not in {"enabled", "disabled"}:
            raise ValueError("计划状态必须是 enabled 或 disabled")
        with self.engine.begin() as conn:
            conn.execute(
                update(run_plan_table)
                .where(run_plan_table.c.plan_id == plan_id)
                .values(status=status, updated_at=_now())
            )
        result = self.get_plan(plan_id)
        if result is None:
            raise LookupError(f"运行计划不存在: {plan_id}")
        return result

    def set_plan_next_run(self, plan_id: str, next_run_at: datetime | None) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                update(run_plan_table)
                .where(run_plan_table.c.plan_id == plan_id)
                .values(next_run_at=next_run_at, updated_at=_now())
            )

    def try_acquire_lease(
        self,
        plan_id: str,
        worker_id: str,
        now: datetime,
        lease_seconds: int = 600,
    ) -> bool:
        with self.engine.begin() as conn:
            result = conn.execute(
                update(run_plan_table)
                .where(
                    and_(
                        run_plan_table.c.plan_id == plan_id,
                        run_plan_table.c.status == "enabled",
                        (run_plan_table.c.locked_until.is_(None))
                        | (run_plan_table.c.locked_until <= now),
                    )
                )
                .values(
                    locked_until=now + timedelta(seconds=lease_seconds),
                    locked_by=worker_id,
                    updated_at=now,
                )
            )
        return int(result.rowcount or 0) == 1

    def release_lease(
        self,
        plan_id: str,
        worker_id: str,
        last_run_at: datetime,
        next_run_at: datetime | None,
    ) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                update(run_plan_table)
                .where(
                    and_(
                        run_plan_table.c.plan_id == plan_id,
                        run_plan_table.c.locked_by == worker_id,
                    )
                )
                .values(
                    locked_until=None,
                    locked_by="",
                    last_run_at=last_run_at,
                    next_run_at=next_run_at,
                    updated_at=_now(),
                )
            )

    def create_run_result(self, payload: dict[str, Any]) -> dict[str, Any]:
        validated = RunResult.model_validate(payload)
        values = validated.model_dump()
        values.pop("id", None)
        values.update(
            {
                "previous_value": payload.get("previous_value"),
                "change_rate": payload.get("change_rate"),
                "run_id": payload.get("run_id"),
                "created_at": payload.get("created_at") or _now(),
                "no_sample": bool(payload.get("no_sample", False)),
            }
        )
        columns = {
            "hospital_id", "rule_id", "stat_period", "result_value", "previous_value",
            "change_rate", "is_abnormal", "run_id", "created_at", "plan_id", "run_key",
            "retry_of_result_id", "trigger_type", "stat_start_time", "stat_end_time",
            "run_status", "no_sample", "effective_level", "national_version",
            "hospital_version", "data_source", "duration_ms", "error_code",
            "error_message", "mom_baseline_result_id", "mom_change_rate",
            "yoy_baseline_result_id", "yoy_change_rate", "wave_status",
        }
        values = {key: value for key, value in values.items() if key in columns}
        values = {key: self._db_value(value) for key, value in values.items()}
        with self.engine.begin() as conn:
            result = conn.execute(
                text(
                    "INSERT INTO med_index_run_result "
                    f"({', '.join(values)}) VALUES "
                    f"({', '.join(':' + key for key in values)})"
                ),
                values,
            )
            result_id = int(result.lastrowid)
        return self.get_result_for_retry(result_id) or {**values, "id": result_id}

    def _result_query(self, where_sql: str, params: dict[str, Any]) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(
                text(f"SELECT * FROM med_index_run_result WHERE {where_sql} LIMIT 1"),
                params,
            ).mappings().first()
        return _bool_fields(dict(row), ("is_abnormal", "no_sample")) if row else None

    def get_result_by_run_key(self, run_key: str) -> dict[str, Any] | None:
        return self._result_query("run_key=:run_key", {"run_key": run_key})

    def get_result(self, result_id: int, hospital_id: str) -> dict[str, Any] | None:
        return self._result_query(
            "id=:result_id AND hospital_id=:hospital_id",
            {"result_id": result_id, "hospital_id": hospital_id},
        )

    def get_result_for_retry(self, result_id: int) -> dict[str, Any] | None:
        return self._result_query("id=:result_id", {"result_id": result_id})

    def list_results(
        self, hospital_id: str, rule_id: str | None = None, limit: int = 100
    ) -> list[dict[str, Any]]:
        where = "hospital_id=:hospital_id"
        params: dict[str, Any] = {"hospital_id": hospital_id, "limit": int(limit)}
        if rule_id:
            where += " AND rule_id=:rule_id"
            params["rule_id"] = rule_id
        with self.engine.connect() as conn:
            rows = conn.execute(
                text(
                    f"SELECT * FROM med_index_run_result WHERE {where} "
                    "ORDER BY id DESC LIMIT :limit"
                ),
                params,
            ).mappings().all()
        return [_bool_fields(dict(row), ("is_abnormal", "no_sample")) for row in rows]

    def find_success_result(
        self,
        hospital_id: str,
        rule_id: str,
        stat_start: datetime,
        stat_end: datetime,
    ) -> dict[str, Any] | None:
        return self._result_query(
            "hospital_id=:hospital_id AND rule_id=:rule_id "
            "AND stat_start_time=:stat_start AND stat_end_time=:stat_end "
            "AND run_status='success' ORDER BY id DESC",
            {
                "hospital_id": hospital_id,
                "rule_id": rule_id,
                "stat_start": self._db_value(stat_start),
                "stat_end": self._db_value(stat_end),
            },
        )

    def update_wave_result(self, result_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        allowed = {
            "previous_value", "change_rate", "mom_baseline_result_id",
            "mom_change_rate", "yoy_baseline_result_id", "yoy_change_rate",
            "wave_status", "is_abnormal",
        }
        values = {key: value for key, value in payload.items() if key in allowed}
        if not values:
            result = self.get_result_for_retry(result_id)
            if result is None:
                raise LookupError(f"运行结果不存在: {result_id}")
            return result
        assignments = ", ".join(f"{key}=:{key}" for key in values)
        with self.engine.begin() as conn:
            conn.execute(
                text(f"UPDATE med_index_run_result SET {assignments} WHERE id=:result_id"),
                {**values, "result_id": result_id},
            )
        result = self.get_result_for_retry(result_id)
        if result is None:
            raise LookupError(f"运行结果不存在: {result_id}")
        return result

    @staticmethod
    def _alert(row: Any) -> dict[str, Any]:
        return IndicatorAlert.model_validate(dict(row)).model_dump()

    def create_alert(self, payload: dict[str, Any]) -> dict[str, Any]:
        existing = None
        with self.engine.connect() as conn:
            existing = conn.execute(
                select(alert_table).where(
                    and_(
                        alert_table.c.result_id == int(payload["result_id"]),
                        alert_table.c.alert_type == str(payload["alert_type"]),
                        alert_table.c.conclusion_code == str(payload["conclusion_code"]),
                    )
                )
            ).mappings().first()
        if existing:
            return self._alert(existing)

        now = _now()
        item = {
            "alert_id": str(payload.get("alert_id") or f"ALERT_{uuid.uuid4().hex[:12]}"),
            "hospital_id": str(payload["hospital_id"]),
            "rule_id": str(payload["rule_id"]),
            "plan_id": payload.get("plan_id"),
            "result_id": int(payload["result_id"]),
            "alert_type": str(payload["alert_type"]),
            "alert_level": str(payload.get("alert_level") or "warning"),
            "conclusion_code": str(payload["conclusion_code"]),
            "current_value": payload.get("current_value"),
            "mom_value": payload.get("mom_value"),
            "mom_change_rate": payload.get("mom_change_rate"),
            "yoy_value": payload.get("yoy_value"),
            "yoy_change_rate": payload.get("yoy_change_rate"),
            "diagnose_status": str(payload.get("diagnose_status") or "pending"),
            "diagnose_report_id": payload.get("diagnose_report_id"),
            "status": str(payload.get("status") or "open"),
            "acknowledged_by": None,
            "acknowledged_at": None,
            "closed_at": None,
            "created_at": now,
            "updated_at": now,
        }
        with self.engine.begin() as conn:
            try:
                conn.execute(insert(alert_table).values(**item))
            except IntegrityError:
                pass
        return self.get_alert(item["alert_id"], item["hospital_id"]) or item

    def get_alert(self, alert_id: str, hospital_id: str) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(
                select(alert_table).where(
                    and_(
                        alert_table.c.alert_id == alert_id,
                        alert_table.c.hospital_id == hospital_id,
                    )
                )
            ).mappings().first()
        return self._alert(row) if row else None

    def list_alerts(
        self, hospital_id: str, status: str | None = None, limit: int = 100
    ) -> list[dict[str, Any]]:
        query = select(alert_table).where(alert_table.c.hospital_id == hospital_id)
        if status:
            query = query.where(alert_table.c.status == status)
        with self.engine.connect() as conn:
            rows = conn.execute(
                query.order_by(alert_table.c.id.desc()).limit(int(limit))
            ).mappings().all()
        return [self._alert(row) for row in rows]

    def update_alert(
        self, alert_id: str, hospital_id: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        allowed = {
            "diagnose_status", "diagnose_report_id", "status",
            "acknowledged_by", "acknowledged_at", "closed_at",
        }
        values = {key: value for key, value in payload.items() if key in allowed}
        values["updated_at"] = _now()
        with self.engine.begin() as conn:
            conn.execute(
                update(alert_table)
                .where(
                    and_(
                        alert_table.c.alert_id == alert_id,
                        alert_table.c.hospital_id == hospital_id,
                    )
                )
                .values(**values)
            )
        result = self.get_alert(alert_id, hospital_id)
        if result is None:
            raise LookupError(f"预警不存在: {alert_id}")
        return result
