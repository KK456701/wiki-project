"""编排用户 SQL、国标口径和本院口径的只读差异诊断。"""

from __future__ import annotations

import hashlib
import time
import uuid
from typing import Any

from sqlalchemy import Engine

from app.agents.contracts import (
    CaliberComparisonContext,
    FieldMapping,
    PastedDiagnosisEvidence,
)
from app.business_source import current_business_source
from app.db.repositories import insert_sql_run_log
from app.db_access.business_db import BusinessDBClient
from app.diagnose.caliber_compare import (
    execute_caliber_comparison,
    parse_diagnose_period,
)
from app.diagnose.sql_semantics import (
    DiagnosisFinding,
    compare_sql_profiles,
    profile_sql,
)
from app.diagnose.user_sql import prepare_pasted_sql
from app.sqlgen.template_renderer import render_sql


class PastedDiagnosisService:
    def __init__(
        self,
        *,
        runtime_engine: Engine,
        business_db: BusinessDBClient,
        allowed_database: str | None = None,
        allowed_schema: str | None = None,
    ) -> None:
        settings = current_business_source()
        self.runtime_engine = runtime_engine
        self.business_db = business_db
        self.allowed_database = allowed_database or settings.database_name
        self.allowed_schema = allowed_schema if allowed_schema is not None else settings.schema

    @staticmethod
    def _period(evidence: PastedDiagnosisEvidence, stat_period: str | None) -> str | None:
        if evidence.stat_period.start and evidence.stat_period.end:
            return f"{evidence.stat_period.start}~{evidence.stat_period.end}"
        return stat_period

    def _execute_user(
        self,
        query_sql: str,
        *,
        hospital_id: str,
        rule_id: str,
        stat_start: str,
        stat_end: str,
    ) -> dict[str, Any]:
        started = time.perf_counter()
        run_id = f"RUN_DIAG_USER_{uuid.uuid4().hex[:10]}"
        status = "failed"
        error_message = ""
        result_value = None
        numerator_count = None
        denominator_count = None
        sample_count = None
        try:
            query_result = self.business_db.execute_select(query_sql)
            first = query_result.rows[0] if query_result.rows else {}
            lowered = {str(key).lower(): value for key, value in first.items()}
            result_value = lowered.get("index_value")
            numerator_count = lowered.get("numerator_count")
            denominator_count = lowered.get("denominator_count")
            sample_count = lowered.get("sample_count", denominator_count)
            status = "success" if first else "empty"
        except Exception as exc:
            error_message = str(exc)
        duration_ms = max(1, int((time.perf_counter() - started) * 1000))
        insert_sql_run_log(
            self.runtime_engine,
            run_id,
            f"DIAG_USER_{rule_id}",
            hospital_id,
            rule_id,
            stat_start,
            stat_end,
            status,
            float(result_value) if result_value is not None else None,
            error_message,
            duration_ms,
            "diagnose_pasted_sql",
        )
        return {
            "status": status,
            "result_value": float(result_value) if result_value is not None else None,
            "numerator_count": int(numerator_count) if numerator_count is not None else None,
            "denominator_count": int(denominator_count) if denominator_count is not None else None,
            "sample_count": int(sample_count) if sample_count is not None else None,
            "error_message": error_message,
            "duration_ms": duration_ms,
            "run_id": run_id,
            "source": getattr(self.business_db, "source_id", ""),
            "tool_name": getattr(self.business_db, "tool_name", ""),
        }

    @staticmethod
    def _render_profile_sql(
        template: str,
        mapping: FieldMapping,
    ) -> str:
        if not template.strip():
            return ""
        return render_sql(
            template,
            mapping.fields,
            mapping.main_table,
            dict(mapping.get("custom_rules") or {}),
        )

    @staticmethod
    def _result_finding(
        user: dict[str, Any],
        hospital: dict[str, Any],
    ) -> DiagnosisFinding | None:
        if user.get("status") != "success" or hospital.get("status") != "success":
            return None
        user_value = user.get("result_value")
        hospital_value = hospital.get("result_value")
        if user_value is None or hospital_value is None or abs(float(user_value) - float(hospital_value)) <= 0.01:
            return None
        return DiagnosisFinding(
            code="execution_result_changed",
            category="caliber",
            severity="warning",
            title="实际计算结果不同",
            evidence=f"用户 SQL 为 {user_value}；本院生效口径为 {hospital_value}。",
            impact="当前差异已经影响最终指标值。",
            suggestion="结合其他口径差异项确认应采用的计算方式。",
        )

    def run(
        self,
        *,
        evidence: PastedDiagnosisEvidence,
        hospital_id: str,
        caliber_context: dict[str, Any],
        field_mapping: dict[str, Any],
        stat_period: str | None,
    ) -> dict[str, Any]:
        context = CaliberComparisonContext.model_validate(caliber_context)
        mapping = FieldMapping.model_validate(field_mapping)
        allowed_database = mapping.db_name or self.allowed_database
        prepared = prepare_pasted_sql(
            evidence.sql_text,
            allowed_database=allowed_database,
            allowed_schema=self.allowed_schema,
        )
        effective_period = self._period(evidence, stat_period)
        stat_start, stat_end, normalized_period = parse_diagnose_period(effective_period)

        if prepared.safe_to_execute:
            user_result = self._execute_user(
                prepared.query_sql,
                hospital_id=hospital_id,
                rule_id=str(evidence.rule_id or context.rule_id),
                stat_start=stat_start,
                stat_end=stat_end,
            )
        else:
            user_result = {
                "status": "blocked",
                "blocked_reasons": prepared.blocked_reasons,
                "duration_ms": 0,
            }

        comparison = execute_caliber_comparison(
            runtime_engine=self.runtime_engine,
            business_db=self.business_db,
            context=context,
            field_mapping=mapping,
            stat_period=normalized_period,
        )
        execution_results = {
            "user": user_result,
            "national": dict(comparison.get("national") or {}),
            "hospital": dict(comparison.get("hospital") or {}),
        }

        user_sql = prepared.query_sql or evidence.sql_text
        hospital_sql = self._render_profile_sql(context.effective_sql_template, mapping)
        user_profile = profile_sql(user_sql, mapping.dialect)
        findings = compare_sql_profiles(
            profile_sql(hospital_sql, mapping.dialect),
            user_profile,
        )
        result_finding = self._result_finding(user_result, execution_results["hospital"])
        if result_finding:
            findings.append(result_finding)

        if user_result["status"] == "blocked":
            conclusion = "user_sql_blocked"
        elif findings:
            conclusion = "caliber_difference"
        elif user_result["status"] == "failed":
            conclusion = "user_sql_execution_failed"
        else:
            conclusion = "no_material_difference"

        sql_hash = hashlib.sha256(evidence.sql_text.encode("utf-8")).hexdigest()
        return {
            "primary_conclusion": conclusion,
            "findings": [item.model_dump() for item in findings],
            "execution_results": execution_results,
            "caliber_comparison": comparison,
            "stat_period": normalized_period,
            "user_zero_denominator_guard": user_profile.zero_denominator_guard,
            "evidence_summary": {
                "question": evidence.question,
                "rule_id": evidence.rule_id,
                "declared_params": evidence.declared_params,
                "claimed_result": evidence.claimed_result,
                "parse_warnings": evidence.parse_warnings,
                "sql_sha256": sql_hash,
            },
        }
