from __future__ import annotations

from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    DateTime,
    Engine,
    Float,
    Index,
    Integer,
    MetaData,
    String,
    Table,
    Text,
    UniqueConstraint,
    inspect,
    text,
)


metadata = MetaData()
_id_type = BigInteger().with_variant(Integer, "sqlite")

run_plan_table = Table(
    "med_indicator_run_plan",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("plan_id", String(64), nullable=False, unique=True),
    Column("hospital_id", String(64), nullable=False),
    Column("rule_id", String(64), nullable=False),
    Column("plan_name", String(128), nullable=False),
    Column("frequency", String(32), nullable=False),
    Column("run_time", String(8), nullable=False, default="02:00"),
    Column("day_of_month", Integer, nullable=False, default=1),
    Column("timezone", String(64), nullable=False, default="Asia/Shanghai"),
    Column("mom_enabled", Boolean, nullable=False, default=True),
    Column("mom_threshold_pct", Float, nullable=False, default=20.0),
    Column("yoy_enabled", Boolean, nullable=False, default=True),
    Column("yoy_threshold_pct", Float, nullable=False, default=30.0),
    Column("status", String(32), nullable=False, default="enabled"),
    Column("next_run_at", DateTime),
    Column("last_run_at", DateTime),
    Column("locked_until", DateTime),
    Column("locked_by", String(128), nullable=False, default=""),
    Column("created_by", String(64), nullable=False, default="admin"),
    Column("created_at", DateTime, nullable=False),
    Column("updated_at", DateTime, nullable=False),
    UniqueConstraint("hospital_id", "rule_id", "plan_name", name="uq_monitor_plan"),
)
Index("idx_monitor_plan_status", run_plan_table.c.status)
Index("idx_monitor_plan_next", run_plan_table.c.next_run_at)

alert_table = Table(
    "med_indicator_alert",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("alert_id", String(64), nullable=False, unique=True),
    Column("hospital_id", String(64), nullable=False),
    Column("rule_id", String(64), nullable=False),
    Column("plan_id", String(64)),
    Column("result_id", BigInteger, nullable=False),
    Column("alert_type", String(32), nullable=False),
    Column("alert_level", String(16), nullable=False),
    Column("conclusion_code", String(64), nullable=False),
    Column("current_value", Float),
    Column("mom_value", Float),
    Column("mom_change_rate", Float),
    Column("yoy_value", Float),
    Column("yoy_change_rate", Float),
    Column("diagnose_status", String(32), nullable=False, default="pending"),
    Column("diagnose_report_id", String(64)),
    Column("status", String(32), nullable=False, default="open"),
    Column("acknowledged_by", String(64)),
    Column("acknowledged_at", DateTime),
    Column("closed_at", DateTime),
    Column("created_at", DateTime, nullable=False),
    Column("updated_at", DateTime, nullable=False),
    UniqueConstraint(
        "result_id", "alert_type", "conclusion_code", name="uq_monitor_alert"
    ),
)
Index("idx_monitor_alert_hospital", alert_table.c.hospital_id)
Index("idx_monitor_alert_status", alert_table.c.status)

run_result_table = Table(
    "med_index_run_result",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("hospital_id", String(64), nullable=False),
    Column("rule_id", String(64), nullable=False),
    Column("stat_period", String(128), nullable=False),
    Column("result_value", Float),
    Column("previous_value", Float),
    Column("change_rate", Float),
    Column("is_abnormal", Boolean, nullable=False, default=False),
    Column("run_id", String(64)),
    Column("created_at", DateTime, nullable=False),
    Column("plan_id", String(64)),
    Column("run_key", String(255)),
    Column("retry_of_result_id", BigInteger),
    Column("trigger_type", String(32)),
    Column("stat_start_time", DateTime),
    Column("stat_end_time", DateTime),
    Column("run_status", String(32)),
    Column("no_sample", Boolean, nullable=False, default=False),
    Column("effective_level", String(32)),
    Column("national_version", String(64)),
    Column("hospital_version", Integer),
    Column("data_source", String(128)),
    Column("duration_ms", Integer),
    Column("error_code", String(128)),
    Column("error_message", Text),
    Column("mom_baseline_result_id", BigInteger),
    Column("mom_change_rate", Float),
    Column("yoy_baseline_result_id", BigInteger),
    Column("yoy_change_rate", Float),
    Column("wave_status", String(64)),
)

RESULT_AUDIT_COLUMNS = {
    "plan_id": "VARCHAR(64) NULL",
    "run_key": "VARCHAR(255) NULL",
    "retry_of_result_id": "BIGINT NULL",
    "trigger_type": "VARCHAR(32) NULL",
    "stat_start_time": "DATETIME NULL",
    "stat_end_time": "DATETIME NULL",
    "run_status": "VARCHAR(32) NULL",
    "no_sample": "TINYINT NOT NULL DEFAULT 0",
    "effective_level": "VARCHAR(32) NULL",
    "national_version": "VARCHAR(64) NULL",
    "hospital_version": "INT NULL",
    "data_source": "VARCHAR(128) NULL",
    "duration_ms": "INT NULL",
    "error_code": "VARCHAR(128) NULL",
    "error_message": "TEXT NULL",
    "mom_baseline_result_id": "BIGINT NULL",
    "mom_change_rate": "DECIMAL(18,4) NULL",
    "yoy_baseline_result_id": "BIGINT NULL",
    "yoy_change_rate": "DECIMAL(18,4) NULL",
    "wave_status": "VARCHAR(64) NULL",
}


def ensure_monitoring_schema(engine: Engine) -> dict[str, list[str]]:
    before = set(inspect(engine).get_table_names())
    metadata.create_all(engine, checkfirst=True)
    after = set(inspect(engine).get_table_names())
    created = [
        name
        for name in ("med_indicator_run_plan", "med_indicator_alert", "med_index_run_result")
        if name in after and name not in before
    ]

    existing = {
        str(column["name"])
        for column in inspect(engine).get_columns("med_index_run_result")
    }
    added: list[str] = []
    with engine.begin() as conn:
        for column_name, column_ddl in RESULT_AUDIT_COLUMNS.items():
            if column_name in existing:
                continue
            conn.execute(
                text(
                    "ALTER TABLE med_index_run_result "
                    f"ADD COLUMN {column_name} {column_ddl}"
                )
            )
            added.append(column_name)

    indexes = {
        str(item.get("name") or "")
        for item in inspect(engine).get_indexes("med_index_run_result")
    }
    if "uq_med_index_run_result_run_key" not in indexes:
        with engine.begin() as conn:
            conn.execute(
                text(
                    "CREATE UNIQUE INDEX uq_med_index_run_result_run_key "
                    "ON med_index_run_result (run_key)"
                )
            )
    return {"created_tables": created, "added_result_columns": added}
