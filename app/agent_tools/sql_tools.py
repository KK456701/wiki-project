"""确定性 SQL 准备和仅凭 sql_id 的只读试运行工具。"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from functools import partial
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator
from sqlalchemy.engine import Engine

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import (
    AgentTool,
    ToolEvidence,
    ToolResult,
    ToolRiskLevel,
)
from app.agent_tools.sql_objects import (
    AgentSqlObjectStore,
    PreparedSqlObject,
    SqlObjectAccessError,
)
from app.agent_tools.state_facts import has_active_sql, has_verified_rule
from app.sqlgen.context_overrides import apply_execution_field_roles
from app.sqlgen.runner import run_sql_trial
from app.sqlgen.validator import validate_select_sql


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class PrepareIndicatorSqlInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    rule_id: str = Field(min_length=1, max_length=128)
    stat_start_time: datetime
    stat_end_time: datetime

    @model_validator(mode="after")
    def validate_period(self):
        if self.stat_start_time >= self.stat_end_time:
            raise ValueError("统计开始时间必须早于结束时间")
        return self


class TrialRunIndicatorSqlInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sql_id: str = Field(pattern=r"^SQL_[A-Za-z0-9_-]{1,64}$")


@dataclass(frozen=True, slots=True)
class SqlToolServices:
    orchestrator: Any
    store: AgentSqlObjectStore
    runtime_engine: Engine
    business_db: Any
    ttl: timedelta = timedelta(minutes=30)
    now_provider: Callable[[], datetime] = _utcnow
    trial_executor: Callable[..., dict[str, Any]] = run_sql_trial
    sql_validator: Callable[..., dict[str, Any]] = validate_select_sql


def _period_text(value: datetime) -> str:
    return value.strftime("%Y-%m-%d %H:%M:%S")


def _model_payload(value: Any) -> dict[str, Any]:
    if value is None:
        return {}
    if hasattr(value, "model_dump"):
        return value.model_dump(mode="json", by_alias=True)
    return dict(value)


def _rule_snapshot(value: Any) -> dict[str, Any]:
    payload = _model_payload(value)
    standard_sql = str(payload.pop("standard_sql", "") or "")
    if standard_sql:
        payload["standard_sql_sha256"] = hashlib.sha256(
            standard_sql.encode("utf-8")
        ).hexdigest()
    return payload


def _context_snapshot(
    *,
    effective_rule: Any,
    field_mapping: dict[str, Any],
    execution_context: dict[str, Any],
    params: dict[str, Any],
    stat_start: str,
    stat_end: str,
    db_source_id: str | None,
) -> dict[str, Any]:
    return {
        "effective_rule": _rule_snapshot(effective_rule),
        "field_mapping": field_mapping,
        "execution_context": execution_context,
        "params": params,
        "stat_start": stat_start,
        "stat_end": stat_end,
        "db_source_id": db_source_id,
    }


def _context_digest(snapshot: dict[str, Any]) -> str:
    canonical = json.dumps(
        snapshot,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _prepare_failure(
    code: str,
    summary: str,
    *,
    data: dict[str, Any] | None = None,
) -> ToolResult:
    return ToolResult(
        ok=False,
        status="validation_failed",
        code=code,
        summary=summary,
        data=data or {},
    )


def prepare_indicator_sql(
    arguments: PrepareIndicatorSqlInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: SqlToolServices,
) -> ToolResult:
    if not has_verified_rule(state, arguments.rule_id):
        return _prepare_failure(
            "RULE_NOT_VERIFIED",
            "该指标尚未经过规则搜索或读取，不能准备 SQL。",
        )

    stat_start = _period_text(arguments.stat_start_time)
    stat_end = _period_text(arguments.stat_end_time)
    prepared = services.orchestrator.prepare_rule_request(
        query=f"为指标 {arguments.rule_id} 准备受控 SQL",
        hospital_id=context.hospital_id,
        intent="generate_sql",
        rule_id=arguments.rule_id,
    )
    result = services.orchestrator.generate_indicator(
        prepared,
        stat_start_time=stat_start,
        stat_end_time=stat_end,
        trial_run=False,
        generated_by=context.user_id,
        persist_run_result=False,
        execution_context={},
    )
    precheck = result.get("precheck") or {}
    if result.get("status") == "field_precheck_failed" or precheck.get("ok") is False:
        safe_precheck = {
            key: precheck.get(key) or []
            for key in (
                "missing_mappings",
                "unconfirmed_mappings",
                "missing_columns",
                "type_mismatches",
                "missing_relations",
            )
        }
        return _prepare_failure(
            "FIELD_PRECHECK_FAILED",
            "字段映射或元数据预检查未通过，暂不能准备 SQL。",
            data=safe_precheck,
        )

    validation = result.get("validation") or {}
    sql_id = str(result.get("sql_id") or "")
    sql_text = str(result.get("sql_text") or "")
    if (
        validation.get("ok") is not True
        or result.get("sql_status") != "validated"
        or not sql_id
        or not sql_text
    ):
        return _prepare_failure(
            "SQL_VALIDATION_FAILED",
            "生成的 SQL 未通过只读安全校验，不能进入试运行。",
        )

    execution_context = dict(result.get("execution_context") or {})
    field_mapping = dict(
        result.get("field_mapping") or _model_payload(prepared.field_mapping)
    )
    params = dict(result.get("params") or {})
    snapshot = _context_snapshot(
        effective_rule=prepared.effective_rule,
        field_mapping=field_mapping,
        execution_context=execution_context,
        params=params,
        stat_start=stat_start,
        stat_end=stat_end,
        db_source_id=context.db_source_id,
    )
    digest = _context_digest(snapshot)
    now = services.now_provider()
    sql_object = PreparedSqlObject(
        sql_id=sql_id,
        hospital_id=context.hospital_id,
        user_id=context.user_id,
        session_id=context.session_id,
        rule_id=arguments.rule_id,
        dialect=str(result.get("dialect") or field_mapping.get("dialect") or ""),
        sql_text=sql_text,
        params=params,
        stat_start=stat_start,
        stat_end=stat_end,
        context_snapshot=snapshot,
        context_digest=digest,
        validation_status="validated",
        validation_message=str(validation.get("message") or ""),
        created_at=now,
        expires_at=now + services.ttl,
        db_source_id=context.db_source_id,
    )
    try:
        services.store.save(sql_object)
    except SqlObjectAccessError as exc:
        return ToolResult(
            ok=False,
            status="error",
            code=exc.code,
            summary="SQL 对象保存失败，请重新准备。",
        )

    state.current_rule_id = arguments.rule_id
    if sql_id not in state.validated_sql_ids:
        state.validated_sql_ids.append(sql_id)
    return ToolResult(
        ok=True,
        status="success",
        code="SQL_OBJECT_PREPARED",
        summary="SQL 已完成确定性生成和只读安全校验，可进行受控试运行。",
        data={
            "sql_id": sql_id,
            "rule_id": arguments.rule_id,
            "dialect": sql_object.dialect,
            "validation_status": sql_object.validation_status,
            "stat_start": stat_start,
            "stat_end": stat_end,
            "expires_at": sql_object.expires_at.isoformat(),
        },
        evidence=[ToolEvidence(
            source="agent_sql_object",
            source_id=sql_id,
            version=digest,
            fact_types=["sql_object", "sql_validation"],
        )],
        warnings=list(result.get("warnings") or []),
    )


def _current_snapshot(
    sql_object: PreparedSqlObject,
    prepared: Any,
) -> dict[str, Any]:
    execution_context = dict(
        sql_object.context_snapshot.get("execution_context") or {}
    )
    field_mapping = apply_execution_field_roles(
        _model_payload(prepared.field_mapping),
        execution_context,
    )
    return _context_snapshot(
        effective_rule=prepared.effective_rule,
        field_mapping=field_mapping,
        execution_context=execution_context,
        params=dict(sql_object.params),
        stat_start=sql_object.stat_start,
        stat_end=sql_object.stat_end,
        db_source_id=sql_object.db_source_id,
    )


def _load_failure(exc: SqlObjectAccessError) -> ToolResult:
    forbidden_codes = {
        "SQL_OBJECT_TENANT_MISMATCH",
        "SQL_OBJECT_OWNER_MISMATCH",
        "SQL_OBJECT_SESSION_MISMATCH",
        "SQL_OBJECT_SOURCE_MISMATCH",
    }
    return ToolResult(
        ok=False,
        status="forbidden" if exc.code in forbidden_codes else "unavailable",
        code=exc.code,
        summary=str(exc),
    )


def _current_metadata_precheck(
    services: SqlToolServices,
    prepared: Any,
) -> dict[str, Any] | None:
    metadata = getattr(services.orchestrator, "metadata", None)
    if metadata is None:
        return None
    checker = getattr(metadata, "precheck_contract", None)
    if not callable(checker):
        checker = getattr(metadata, "precheck", None)
    if not callable(checker):
        return None
    effective_rule = prepared.effective_rule
    result = checker(
        str(prepared.hospital_id or ""),
        str(prepared.rule_id or ""),
        calculation_definition=(
            effective_rule.calculation_definition
            if effective_rule is not None
            else None
        ),
        field_mapping=_model_payload(prepared.field_mapping),
    )
    return _model_payload(result)


def trial_run_indicator_sql(
    arguments: TrialRunIndicatorSqlInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: SqlToolServices,
) -> ToolResult:
    if not has_active_sql(state, arguments.sql_id):
        return ToolResult(
            ok=False,
            status="unavailable",
            code="SQL_OBJECT_NOT_ACTIVE",
            summary="该 SQL 对象不在当前已验证状态中，请重新准备。",
        )
    try:
        sql_object = services.store.load_for_execution(arguments.sql_id, context)
    except SqlObjectAccessError as exc:
        if exc.code in {
            "SQL_OBJECT_NOT_FOUND",
            "SQL_OBJECT_EXPIRED",
            "SQL_OBJECT_NOT_VALIDATED",
            "SQL_OBJECT_CORRUPTED",
        }:
            state.validated_sql_ids = [
                sql_id
                for sql_id in state.validated_sql_ids
                if sql_id != arguments.sql_id
            ]
        return _load_failure(exc)

    prepared = services.orchestrator.prepare_rule_request(
        query=f"试运行指标 {sql_object.rule_id}",
        hospital_id=context.hospital_id,
        intent="trial_run",
        rule_id=sql_object.rule_id,
    )
    current_digest = _context_digest(_current_snapshot(sql_object, prepared))
    if current_digest != sql_object.context_digest:
        state.stop_reason = "context_conflict"
        return ToolResult(
            ok=False,
            status="validation_failed",
            code="SQL_CONTEXT_STALE",
            summary="指标规则或字段映射已变化，请重新准备 SQL 后再试运行。",
        )

    precheck = _current_metadata_precheck(services, prepared)
    if precheck is not None and precheck.get("ok") is not True:
        state.stop_reason = "context_conflict"
        safe_precheck = {
            key: precheck.get(key) or []
            for key in (
                "missing_mappings",
                "unconfirmed_mappings",
                "missing_columns",
                "type_mismatches",
                "missing_relations",
            )
            if precheck.get(key)
        }
        return ToolResult(
            ok=False,
            status="validation_failed",
            code="SQL_CONTEXT_STALE",
            summary="医院字段或元数据已变化，请重新准备 SQL 后再试运行。",
            data=safe_precheck,
        )

    mapping = sql_object.context_snapshot.get("field_mapping") or {}
    validation = services.sql_validator(
        sql_object.sql_text,
        context.hospital_id,
        str(mapping.get("main_table") or ""),
    )
    if validation.get("ok") is not True:
        return ToolResult(
            ok=False,
            status="validation_failed",
            code="SQL_REVALIDATION_FAILED",
            summary="SQL 在试运行前未通过二次只读安全校验。",
        )

    result = services.trial_executor(
        runtime_engine=services.runtime_engine,
        business_db=services.business_db,
        sql_id=sql_object.sql_id,
        sql_text=sql_object.sql_text,
        hospital_id=context.hospital_id,
        rule_id=sql_object.rule_id,
        stat_start=sql_object.stat_start,
        stat_end=sql_object.stat_end,
        params=sql_object.params,
        run_by=context.user_id,
        run_context=sql_object.context_snapshot,
    )
    result_source = str(result.get("source") or "")
    if (
        sql_object.db_source_id
        and result_source
        and result_source != sql_object.db_source_id
    ):
        return ToolResult(
            ok=False,
            status="error",
            code="TRIAL_SOURCE_MISMATCH",
            summary="试运行返回的数据源与 SQL 对象不一致，结果已拒绝。",
        )
    if result.get("status") not in {"success", "empty"}:
        return ToolResult(
            ok=False,
            status="error",
            code="TRIAL_RUN_FAILED",
            summary="只读试运行失败，未获得可用聚合结果。",
            retryable=True,
        )

    safe_data = {
        "sql_id": sql_object.sql_id,
        "run_id": str(result.get("run_id") or ""),
        "status": str(result.get("status") or ""),
        "result_value": result.get("result_value"),
        "numerator_count": result.get("numerator_count"),
        "denominator_count": result.get("denominator_count"),
        "no_sample": bool(result.get("no_sample")),
        "duration_ms": int(result.get("duration_ms") or 0),
        "source": result_source or sql_object.db_source_id,
        "stat_start": sql_object.stat_start,
        "stat_end": sql_object.stat_end,
    }
    run_id = safe_data["run_id"]
    state.last_run_id = run_id or None
    return ToolResult(
        ok=True,
        status="success",
        code="TRIAL_RUN_COMPLETED",
        summary=(
            "只读试运行完成，已获得聚合结果。"
            if result.get("status") == "success"
            else "只读试运行完成，当前统计区间没有可用样本。"
        ),
        data=safe_data,
        evidence=[ToolEvidence(
            source=result_source or sql_object.db_source_id or "hospital_business_db",
            source_id=run_id or None,
            version=sql_object.context_digest,
            fact_types=["trial_run", "aggregate_result"],
        )],
    )


def _state_has_verified_rule(
    context: AgentRuntimeContext,
    state: AgentRunState,
) -> bool:
    del context
    return has_verified_rule(state)


def _state_has_active_sql(
    context: AgentRuntimeContext,
    state: AgentRunState,
) -> bool:
    del context
    return has_active_sql(state)


def build_sql_tools(services: SqlToolServices) -> list[AgentTool]:
    permission = frozenset({"indicator_read"})
    return [
        AgentTool(
            name="prepare_indicator_sql",
            description=(
                "当用户询问某指标在某统计周期的结果、多少、从某日期到某日期怎么算时调用；"
                "调用前必须已确认指标规则。"
                "为已确认指标和统计区间执行字段预检、确定性 SQL 生成与只读安全校验；"
                "只返回服务端 sql_id，不返回 SQL 文本。"
            ),
            input_model=PrepareIndicatorSqlInput,
            handler=partial(prepare_indicator_sql, services=services),
            risk_level=ToolRiskLevel.CONTROLLED_EXECUTION,
            timeout_seconds=30.0,
            required_permissions=permission,
            availability=_state_has_verified_rule,
        ),
        AgentTool(
            name="trial_run_indicator_sql",
            description=(
                "使用当前会话中未过期且已校验的 sql_id 执行医院业务库只读试运行；"
                "只返回聚合结果，不返回患者明细。"
            ),
            input_model=TrialRunIndicatorSqlInput,
            handler=partial(trial_run_indicator_sql, services=services),
            risk_level=ToolRiskLevel.CONTROLLED_EXECUTION,
            timeout_seconds=60.0,
            required_permissions=permission,
            availability=_state_has_active_sql,
        ),
    ]
