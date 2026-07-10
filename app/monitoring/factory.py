from __future__ import annotations

from typing import Any


def create_monitoring_service(runtime_engine: Any | None = None):
    from app.api.main import (
        _create_agent_orchestrator,
        create_business_db_client,
        create_dbhub_metadata_provider,
    )
    from app.db.engine import create_runtime_engine
    from app.config import get_int
    from app.kb.tools import DEFAULT_KB_ROOT
    from app.monitoring.repository import MonitoringRepository
    from app.monitoring.service import IndicatorRunService
    from app.observability.trace import TraceRecorder
    from app.rules.repository import create_rule_repository

    engine = runtime_engine or create_runtime_engine()
    rules = create_rule_repository(engine, DEFAULT_KB_ROOT)
    orchestrator = _create_agent_orchestrator(
        runtime_engine=engine,
        rule_repository=rules,
        business_db=create_business_db_client("hospital_demo_data"),
        metadata_provider=create_dbhub_metadata_provider("hospital_demo_data"),
    )
    return IndicatorRunService(
        runtime_engine=engine,
        repository=MonitoringRepository(engine),
        orchestrator=orchestrator,
        trace_recorder=TraceRecorder(engine),
        lease_seconds=get_int("monitoring_scheduler_lease_seconds", 600),
    )
