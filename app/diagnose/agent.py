"""Three-layer diagnose agent."""

from pathlib import Path
import time
from typing import Any

from sqlalchemy import Engine

from app.db_access.business_db import BusinessDBClient
from app.db_access.metadata_provider import MetadataProvider
from app.diagnose.structure_check import structure_check
from app.diagnose.rule_check import rule_check
from app.diagnose.data_check import data_check
from app.diagnose.caliber_compare import execute_caliber_comparison
from app.diagnose.evidence import extract_pasted_evidence
from app.diagnose.narrator import DiagnosisNarrator
from app.diagnose.pasted_diagnosis import PastedDiagnosisService
from app.diagnose.report import build_report, save_report


class DiagnoseAgent:
    def __init__(
        self,
        kb_root: str | Path,
        runtime_engine: Engine,
        business_db: BusinessDBClient,
        metadata_provider: MetadataProvider | None = None,
        llm_client: Any | None = None,
    ):
        self.kb_root = Path(kb_root)
        self.runtime_engine = runtime_engine
        self.business_db = business_db
        self.metadata_provider = metadata_provider
        self.llm_client = llm_client

    def run(
        self,
        hospital_id: str,
        rule_id: str,
        effective_rule: dict[str, Any],
        query_text: str = "",
        trigger: str = "manual",
        related_sql_id: str | None = None,
        stat_period: str | None = None,
        caliber_context: dict[str, Any] | None = None,
        field_mapping: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        layers: list[dict[str, Any]] = []
        pasted_result: dict[str, Any] | None = None
        evidence_started = time.perf_counter()
        evidence = extract_pasted_evidence(
            query_text,
            rule_id=rule_id,
            llm_client=self.llm_client if query_text else None,
        )
        evidence_duration_ms = max(1, int((time.perf_counter() - evidence_started) * 1000))

        structure_started = time.perf_counter()
        r1 = structure_check(self.kb_root, self.runtime_engine, hospital_id, rule_id, metadata_provider=self.metadata_provider)
        structure_duration_ms = max(1, int((time.perf_counter() - structure_started) * 1000))
        layers.append(r1)
        if not r1["ok"]:
            return self._finish(hospital_id, rule_id, layers, trigger, related_sql_id, stat_period, stopped_at_layer=1)

        if evidence.sql_text and caliber_context is not None and field_mapping is not None:
            pasted_result = PastedDiagnosisService(
                runtime_engine=self.runtime_engine,
                business_db=self.business_db,
            ).run(
                evidence=evidence,
                hospital_id=hospital_id,
                caliber_context=caliber_context,
                field_mapping=field_mapping,
                stat_period=stat_period,
            )
            pasted_result["evidence_duration_ms"] = evidence_duration_ms
            pasted_result["structure_duration_ms"] = structure_duration_ms
            comparison = pasted_result["caliber_comparison"]
        elif caliber_context is None or field_mapping is None:
            comparison = {
                "applicable": False,
                "reason": "comparison_context_not_provided",
                "conclusion_code": "caliber_compare_not_applicable",
                "blocking": False,
            }
        else:
            try:
                comparison = execute_caliber_comparison(
                    runtime_engine=self.runtime_engine,
                    business_db=self.business_db,
                    context=caliber_context,
                    field_mapping=field_mapping,
                    stat_period=stat_period,
                )
            except (TypeError, ValueError) as exc:
                comparison = {
                    "applicable": True,
                    "reason": str(exc),
                    "conclusion_code": "caliber_compare_invalid_request",
                    "blocking": True,
                }

        r2 = rule_check(
            effective_rule,
            comparison,
            sql_zero_guard=bool(
                pasted_result
                and pasted_result.get("user_zero_denominator_guard")
            ),
        )
        if pasted_result is not None:
            r2["pasted_findings"] = pasted_result["findings"]
            r2["user_sql_execution"] = pasted_result["execution_results"]["user"]
        layers.append(r2)
        if not r2["ok"]:
            response = self._finish(hospital_id, rule_id, layers, trigger, related_sql_id, stat_period, stopped_at_layer=2)
            return self._attach_pasted(response, pasted_result)

        data_started = time.perf_counter()
        r3 = data_check(self.kb_root, self.business_db, hospital_id, rule_id)
        data_duration_ms = max(1, int((time.perf_counter() - data_started) * 1000))
        if pasted_result is not None:
            pasted_result["data_duration_ms"] = data_duration_ms
        layers.append(r3)
        stopped_at_layer = 3 if not r3["ok"] or any(c.get("status") == "warn" for c in r3.get("checks", [])) else None
        response = self._finish(hospital_id, rule_id, layers, trigger, related_sql_id, stat_period, stopped_at_layer=stopped_at_layer)
        return self._attach_pasted(response, pasted_result)

    def _attach_pasted(
        self,
        response: dict[str, Any],
        pasted_result: dict[str, Any] | None,
    ) -> dict[str, Any]:
        if pasted_result is None:
            return response
        response.update({
            "primary_conclusion": pasted_result["primary_conclusion"],
            "findings": pasted_result["findings"],
            "execution_results": pasted_result["execution_results"],
            "evidence": {
                "raw_text": "",
                "sql_text": "",
                **pasted_result["evidence_summary"],
                "stat_period": {
                    "start": pasted_result["stat_period"].split("~", 1)[0],
                    "end": pasted_result["stat_period"].split("~", 1)[1],
                },
            },
        })
        compose_started = time.perf_counter()
        response["user_summary"] = DiagnosisNarrator(self.llm_client).compose(response)
        compose_duration_ms = max(1, int((time.perf_counter() - compose_started) * 1000))
        events_by_name = {
            str(event.get("node_name")): event
            for event in pasted_result.get("trace_events", [])
        }
        layers = response.get("layers") or []
        structure_layer = layers[0] if layers else {}
        data_layer = layers[2] if len(layers) > 2 else {}
        ordered_events = [
            {
                "node_name": "evidence_extract",
                "status": "success",
                "duration_ms": int(pasted_result.get("evidence_duration_ms") or 0),
                "output_summary": "已识别用户问题、SQL 和执行参数",
                "output_data": {
                    "parameter_names": sorted(
                        str(key)
                        for key in (pasted_result["evidence_summary"].get("declared_params") or {})
                    ),
                    "has_claimed_result": bool(
                        pasted_result["evidence_summary"].get("claimed_result")
                    ),
                    "warning_count": len(
                        pasted_result["evidence_summary"].get("parse_warnings") or []
                    ),
                },
            },
            events_by_name.get("user_sql_guard", {}),
            events_by_name.get("user_sql_trial", {}),
            {
                "node_name": "structure_compare",
                "status": "success" if structure_layer.get("ok") else "warning",
                "duration_ms": int(pasted_result.get("structure_duration_ms") or 0),
                "output_summary": (
                    "医院表字段校验通过"
                    if structure_layer.get("ok")
                    else "发现表字段适配问题"
                ),
                "output_data": {
                    "ok": bool(structure_layer.get("ok")),
                    "metadata_source": structure_layer.get("metadata_source"),
                    "issue_count": sum(
                        1
                        for check in structure_layer.get("checks", [])
                        if check.get("status") in {"warn", "fail"}
                    ),
                },
            },
            events_by_name.get("caliber_semantic_compare", {}),
            {
                "node_name": "data_quality_profile",
                "status": (
                    "warning"
                    if any(
                        check.get("status") in {"warn", "fail"}
                        for check in data_layer.get("checks", [])
                    )
                    else "success"
                ),
                "duration_ms": int(pasted_result.get("data_duration_ms") or 0),
                "output_summary": (
                    "发现数据质量风险"
                    if any(
                        check.get("status") in {"warn", "fail"}
                        for check in data_layer.get("checks", [])
                    )
                    else "数据质量检查通过"
                ),
                "output_data": {
                    "check_count": len(data_layer.get("checks", [])),
                    "risk_count": sum(
                        1
                        for check in data_layer.get("checks", [])
                        if check.get("status") in {"warn", "fail"}
                    ),
                },
            },
            {
                "node_name": "diagnosis_compose",
                "status": "success",
                "duration_ms": compose_duration_ms,
                "output_summary": "已生成面向医生和实施人员的诊断结论",
                "output_data": {"answer_ready": True},
            },
        ]
        response["trace_events"] = [event for event in ordered_events if event]
        return response

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
