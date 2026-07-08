"""Three-layer diagnose agent."""

from pathlib import Path
from typing import Any

from sqlalchemy import Engine

from app.db_access.business_db import BusinessDBClient
from app.db_access.metadata_provider import MetadataProvider
from app.diagnose.structure_check import structure_check
from app.diagnose.rule_check import rule_check
from app.diagnose.data_check import data_check
from app.diagnose.report import build_report, save_report


class DiagnoseAgent:
    def __init__(
        self,
        kb_root: str | Path,
        runtime_engine: Engine,
        business_db: BusinessDBClient,
        metadata_provider: MetadataProvider | None = None,
    ):
        self.kb_root = Path(kb_root)
        self.runtime_engine = runtime_engine
        self.business_db = business_db
        self.metadata_provider = metadata_provider

    def run(
        self,
        hospital_id: str,
        rule_id: str,
        effective_rule: dict[str, Any],
        trigger: str = "manual",
        related_sql_id: str | None = None,
        stat_period: str | None = None,
    ) -> dict[str, Any]:
        layers: list[dict[str, Any]] = []

        r1 = structure_check(self.kb_root, self.runtime_engine, hospital_id, rule_id, metadata_provider=self.metadata_provider)
        layers.append(r1)
        if not r1["ok"]:
            return self._finish(hospital_id, rule_id, layers, trigger, related_sql_id, stat_period, stopped_at_layer=1)

        r2 = rule_check(effective_rule)
        layers.append(r2)
        if not r2["ok"]:
            return self._finish(hospital_id, rule_id, layers, trigger, related_sql_id, stat_period, stopped_at_layer=2)

        r3 = data_check(self.kb_root, self.business_db, hospital_id, rule_id)
        layers.append(r3)
        stopped_at_layer = 3 if not r3["ok"] or any(c.get("status") == "warn" for c in r3.get("checks", [])) else None
        return self._finish(hospital_id, rule_id, layers, trigger, related_sql_id, stat_period, stopped_at_layer=stopped_at_layer)

    def _finish(
        self,
        hospital_id: str,
        rule_id: str,
        layers: list[dict[str, Any]],
        trigger: str,
        related_sql_id: str | None,
        stat_period: str | None,
        stopped_at_layer: int | None,
    ) -> dict[str, Any]:
        report = build_report(layers, trigger_type=trigger, related_sql_id=related_sql_id, stat_period=stat_period)
        report_id = save_report(
            self.runtime_engine,
            hospital_id,
            rule_id,
            report,
            trigger=trigger,
            related_sql_id=related_sql_id,
            stat_period=stat_period,
        )
        response = {**report, "report_id": report_id}
        if stopped_at_layer is not None:
            response["stopped_at_layer"] = stopped_at_layer
        return response
