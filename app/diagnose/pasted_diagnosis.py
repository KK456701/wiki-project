"""编排用户 SQL、国标口径和本院口径的只读差异诊断。"""

from __future__ import annotations

import hashlib
import time
import uuid
from pathlib import Path
from typing import Any

from sqlalchemy import Engine

from app.agents.contracts import (
    CaliberComparisonContext,
    FieldMapping,
    PastedDiagnosisEvidence,
)
from app.business_source import current_business_source
from app.config import get
from app.db.repositories import insert_sql_run_log
from app.db_access.business_db import BusinessDBClient
from app.diagnose.caliber_compare import (
    execute_caliber_comparison,
    parse_diagnose_period,
)
from app.diagnose.sql_semantics import (
    DiagnosisFinding,
    SqlSemanticProfile,
    compare_sql_profiles,
    profile_sql,
)
from app.diagnose.user_sql import prepare_pasted_sql
from app.diagnose.detail_compare import (
    DiagnosisComparisonStore,
    build_current_detail_query,
    build_user_detail_query,
    create_detail_comparison,
)
from app.sqlgen.template_renderer import render_sql


class PastedDiagnosisService:
    def __init__(
        self,
        *,
        runtime_engine: Engine,
        business_db: BusinessDBClient,
        allowed_database: str | None = None,
        allowed_schema: str | None = None,
        comparison_store: DiagnosisComparisonStore | None = None,
    ) -> None:
        settings = current_business_source()
        self.runtime_engine = runtime_engine
        self.business_db = business_db
        self.allowed_database = allowed_database or settings.database_name
        self.allowed_schema = allowed_schema if allowed_schema is not None else settings.schema
        self.comparison_store = comparison_store or DiagnosisComparisonStore(
            Path(get("diagnosis_detail_root", "runtime/diagnosis-details"))
        )

    @staticmethod
    def _period(evidence: PastedDiagnosisEvidence, stat_period: str | None) -> str | None:
        if evidence.stat_period.start and evidence.stat_period.end:
            return f"{evidence.stat_period.start}~{evidence.stat_period.end}"
        return stat_period

    @staticmethod
    def _aggregate_value(
        row: dict[str, Any],
        *,
        aliases: tuple[str, ...],
        chinese_keyword: str,
    ) -> Any:
        normalized = {
            str(key).strip().lower(): value
            for key, value in row.items()
        }
        for alias in aliases:
            if alias in normalized:
                return normalized[alias]
        for key, value in normalized.items():
            if chinese_keyword in key:
                return value
        return None

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
            result_value = self._aggregate_value(
                first,
                aliases=("index_value", "indicator_value", "result_value"),
                chinese_keyword="比例",
            )
            numerator_count = self._aggregate_value(
                first,
                aliases=("numerator_count", "numerator"),
                chinese_keyword="分子",
            )
            denominator_count = self._aggregate_value(
                first,
                aliases=("denominator_count", "denominator"),
                chinese_keyword="分母",
            )
            sample_count = self._aggregate_value(
                first,
                aliases=("sample_count", "sample_size"),
                chinese_keyword="样本",
            )
            if sample_count is None:
                sample_count = denominator_count
            if (
                result_value is None
                and numerator_count is not None
                and denominator_count not in {None, 0}
            ):
                result_value = round(
                    float(numerator_count) / float(denominator_count) * 100,
                    2,
                )
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

    @staticmethod
    def _comparison_rows(
        findings: list[DiagnosisFinding],
        current: SqlSemanticProfile,
        user: SqlSemanticProfile,
        execution_results: dict[str, dict[str, Any]],
    ) -> list[dict[str, str]]:
        def joined(values: list[str]) -> str:
            return "、".join(values) if values else "未明确识别"

        def elapsed_starts(profile: SqlSemanticProfile) -> str:
            return joined(list(dict.fromkeys(
                item.get("start", "")
                for item in profile.elapsed_pairs
                if item.get("start")
            )))

        boundary_labels = {
            "inclusive": "包含正好 48 小时",
            "exclusive": "不包含正好 48 小时",
            "unknown": "未明确识别",
        }
        icu_labels = {
            "configured_id_list": "使用系统配置的 ICU 组织 ID 清单",
            "organization_code_lookup": "从医院组织字典按组织编码查询 ICU",
            "unknown": "未明确识别",
        }
        event_labels = {
            "earliest_matching_event": "每次入院取最早一条符合条件的转科记录",
            "any_matching_event": "每次入院只要存在任意一条符合条件的转科记录",
            "unknown": "未明确识别",
        }
        values: dict[str, tuple[str, str]] = {
            "period_field_changed": (
                joined(user.period_fields),
                joined(current.period_fields),
            ),
            "elapsed_start_field_changed": (
                elapsed_starts(user),
                elapsed_starts(current),
            ),
            "upper_boundary_inclusive_changed": (
                boundary_labels[user.upper_boundary_mode],
                boundary_labels[current.upper_boundary_mode],
            ),
            "icu_scope_strategy_changed": (
                icu_labels[user.icu_scope_strategy],
                icu_labels[current.icu_scope_strategy],
            ),
            "event_selection_changed": (
                event_labels[user.event_selection],
                event_labels[current.event_selection],
            ),
            "null_handling_changed": (
                joined(user.null_handling),
                joined(current.null_handling),
            ),
        }
        user_result = execution_results.get("user") or {}
        current_result = execution_results.get("hospital") or {}
        values["execution_result_changed"] = (
            str(user_result.get("result_value") or "--"),
            str(current_result.get("result_value") or "--"),
        )

        return [
            {
                "item": finding.title,
                "user_sql": values.get(finding.code, (finding.evidence, "--"))[0],
                "current_sql": values.get(finding.code, ("--", finding.evidence))[1],
                "impact": finding.impact,
                "suggestion": finding.suggestion,
            }
            for finding in findings
        ]

    @staticmethod
    def _effective_source(context: CaliberComparisonContext) -> dict[str, Any]:
        if context.overridden_fields:
            version = f" v{context.hospital_version}" if context.hospital_version is not None else ""
            label = f"本院生效口径{version}"
        else:
            version = f" v{context.national_version}" if context.national_version is not None else ""
            label = f"国标口径{version}"
        return {
            "label": label,
            "national_version": context.national_version,
            "hospital_version": context.hospital_version,
            "overridden_fields": list(context.overridden_fields),
        }

    def run(
        self,
        *,
        evidence: PastedDiagnosisEvidence,
        hospital_id: str,
        caliber_context: dict[str, Any],
        field_mapping: dict[str, Any],
        stat_period: str | None,
        effective_rule: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        context = CaliberComparisonContext.model_validate(caliber_context)
        mapping = FieldMapping.model_validate(field_mapping)
        allowed_database = mapping.db_name or self.allowed_database
        guard_started = time.perf_counter()
        prepared = prepare_pasted_sql(
            evidence.sql_text,
            allowed_database=allowed_database,
            allowed_schema=self.allowed_schema,
        )
        guard_duration_ms = max(1, int((time.perf_counter() - guard_started) * 1000))
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

        caliber_started = time.perf_counter()
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
        current_profile = profile_sql(hospital_sql, mapping.dialect)
        findings = compare_sql_profiles(current_profile, user_profile)
        result_finding = self._result_finding(user_result, execution_results["hospital"])
        if result_finding:
            findings.append(result_finding)
        comparison_rows = self._comparison_rows(
            findings,
            current_profile,
            user_profile,
            execution_results,
        )

        detail_comparison: dict[str, Any] | None = None
        if (
            effective_rule
            and prepared.safe_to_execute
            and user_result.get("status") == "success"
            and execution_results["hospital"].get("status") == "success"
        ):
            try:
                user_detail_sql = build_user_detail_query(
                    prepared.query_sql,
                    str(evidence.rule_id or context.rule_id),
                )
                current_detail_query = build_current_detail_query(
                    effective_rule=dict(effective_rule),
                    caliber_context=context.model_dump(),
                    field_mapping=mapping.model_dump(by_alias=True),
                    stat_start=stat_start,
                    stat_end=stat_end,
                )
                detail_comparison = create_detail_comparison(
                    business_db=self.business_db,
                    store=self.comparison_store,
                    hospital_id=hospital_id,
                    rule_id=str(evidence.rule_id or context.rule_id),
                    source_database=mapping.db_name or self.allowed_database,
                    user_detail_sql=user_detail_sql,
                    current_detail_query=current_detail_query,
                    user_result=user_result,
                    current_result=execution_results["hospital"],
                )
            except Exception as exc:
                detail_comparison = {"status": "unavailable", "reason": str(exc)}

        if user_result["status"] == "blocked":
            conclusion = "user_sql_blocked"
        elif findings:
            conclusion = "caliber_difference"
        elif user_result["status"] == "failed":
            conclusion = "user_sql_execution_failed"
        else:
            conclusion = "no_material_difference"
        caliber_duration_ms = max(1, int((time.perf_counter() - caliber_started) * 1000))

        guard_status = "success" if prepared.safe_to_execute else "warning"
        trial_status = (
            "warning"
            if user_result["status"] in {"blocked", "failed"}
            else "success"
        )
        trial_summary = {
            "blocked": "未执行，已完成静态分析",
            "failed": "试运行失败，已保留静态分析结果",
            "empty": "试运行完成，未返回聚合结果",
            "success": "只读试运行成功",
        }.get(str(user_result["status"]), "试运行已处理")
        trace_events = [
            {
                "node_name": "user_sql_guard",
                "status": guard_status,
                "duration_ms": guard_duration_ms,
                "output_summary": (
                    "SQL 通过只读安全检查"
                    if prepared.safe_to_execute
                    else "未执行，已完成静态分析"
                ),
                "output_data": {
                    "safe_to_execute": prepared.safe_to_execute,
                    "blocked_reasons": list(prepared.blocked_reasons),
                    "parameter_count": len(evidence.declared_params),
                },
                "config_data": {"readonly": True, "single_statement": True},
            },
            {
                "node_name": "user_sql_trial",
                "status": trial_status,
                "duration_ms": int(user_result.get("duration_ms") or 0),
                "output_summary": trial_summary,
                "output_data": {
                    "execution_status": user_result.get("status"),
                    "result_value": user_result.get("result_value"),
                    "numerator_count": user_result.get("numerator_count"),
                    "denominator_count": user_result.get("denominator_count"),
                    "sample_count": user_result.get("sample_count"),
                    "run_id": user_result.get("run_id"),
                    "data_source": user_result.get("source"),
                },
                "config_data": {"readonly": True},
            },
            {
                "node_name": "caliber_semantic_compare",
                "status": "warning" if findings else "success",
                "duration_ms": caliber_duration_ms,
                "output_summary": (
                    "发现口径差异" if findings else "未发现明显口径差异"
                ),
                "output_data": {
                    "conclusion_code": conclusion,
                    "finding_count": len(findings),
                    "finding_codes": [item.code for item in findings],
                    "national_result": execution_results["national"].get("result_value"),
                    "hospital_result": execution_results["hospital"].get("result_value"),
                    "user_result": user_result.get("result_value"),
                },
            },
        ]

        sql_hash = hashlib.sha256(evidence.sql_text.encode("utf-8")).hexdigest()
        return {
            "primary_conclusion": conclusion,
            "findings": [item.model_dump() for item in findings],
            "comparison_rows": comparison_rows,
            "effective_source": self._effective_source(context),
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
            "trace_events": trace_events,
            "detail_comparison": detail_comparison,
        }
