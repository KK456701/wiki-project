"""Small idempotent migrations for installations created by older releases."""

from __future__ import annotations

from sqlalchemy import Engine, inspect, text


DIAGNOSE_REPORT_COLUMNS = {
    "trigger_type": "VARCHAR(64) NOT NULL DEFAULT 'manual'",
    "related_sql_id": "VARCHAR(64) NULL",
    "layer_results": "JSON NULL",
    "diagnose_status": "VARCHAR(32) NOT NULL DEFAULT 'healthy'",
    "stat_period": "VARCHAR(128) NULL",
}


def ensure_diagnose_report_schema(engine: Engine) -> list[str]:
    inspector = inspect(engine)
    if not inspector.has_table("med_index_diagnose_report"):
        return []
    existing = {
        str(column["name"])
        for column in inspector.get_columns("med_index_diagnose_report")
    }
    added: list[str] = []
    with engine.begin() as conn:
        for column_name, column_ddl in DIAGNOSE_REPORT_COLUMNS.items():
            if column_name in existing:
                continue
            conn.execute(
                text(
                    "ALTER TABLE med_index_diagnose_report "
                    f"ADD COLUMN {column_name} {column_ddl}"
                )
            )
            added.append(column_name)
    return added


def ensure_monitoring_schema(engine: Engine) -> dict[str, list[str]]:
    from app.monitoring.schema import ensure_monitoring_schema as ensure

    return ensure(engine)


def ensure_terminology_schema(engine: Engine) -> dict[str, list[str]]:
    from app.terminology.schema import ensure_terminology_schema as ensure

    return ensure(engine)


def ensure_rule_lineage_schema(engine: Engine) -> dict[str, list[str]]:
    from app.rules.schema import ensure_rule_lineage_schema as ensure

    return ensure(engine)


def ensure_hospital_auth_schema(engine: Engine) -> dict[str, list[str]]:
    from app.hospital_auth.schema import ensure_hospital_auth_schema as ensure

    return ensure(engine)


def ensure_indicator_detail_schema(engine: Engine) -> dict[str, list[str]]:
    from app.indicator_details.schema import ensure_indicator_detail_schema as ensure

    return ensure(engine)


def ensure_kb_exchange_schema(engine: Engine) -> dict[str, list[str]]:
    from app.kb.exchange_schema import ensure_kb_exchange_schema as ensure

    return ensure(engine)
