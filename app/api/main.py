from __future__ import annotations

import json
import logging
import re
import secrets
import time
import uuid
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from fastapi import Body, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from sqlalchemy import text

from app.agent.graph import langgraph_installed, run_chat, run_chat_stream, workflow_engine_name
from app.config import get
from app.db_access.business_db import BusinessDBClient
from app.db_access.dbhub_mcp import DBHubMCPClient, DBHubMCPError, dbhub_sources
from app.db_access.metadata_provider import DBHubMetadataProvider
from app.kb.tools import DEFAULT_KB_ROOT, KBToolError, KnowledgeBaseTools
from app.observability.trace import TraceRecorder
from app.monitoring.repository import MonitoringRepository
from app.monitoring.runtime import (
    get_monitoring_scheduler,
    monitoring_scheduler_status,
    set_monitoring_scheduler,
    set_monitoring_scheduler_disabled,
    set_monitoring_scheduler_error,
)
from app.monitoring.schema import ensure_monitoring_schema
from app.terminology.schema import ensure_terminology_schema
from app.rules.repository import RuleNotFoundError, create_rule_repository
from app.rules.importer import import_four_indicator_rules
from app.observability.workflow_nodes import (
    record_diagnose_trace_nodes,
    record_metadata_sync_trace_node,
    record_review_trace_node,
)
from app.tasks.scheduler import MonitoringScheduler


PROJECT_ROOT = Path(__file__).resolve().parents[2]
WEB_ROOT = PROJECT_ROOT / "web"
ADMIN_PASSWORD = get("admin_password", "admin123")
REQUEST_LOG_PATH = PROJECT_ROOT / "runtime" / "request_events.jsonl"
logger = logging.getLogger("wiki_agent.api")

# 内存管理员 token，服务重启后失效。
_admin_tokens: set[str] = set()


def _create_rule_repository():
    from app.db.engine import create_runtime_engine

    return create_rule_repository(create_runtime_engine(), DEFAULT_KB_ROOT)


def _create_company_repository():
    from app.db.engine import create_company_engine
    from app.kb.company_repository import CompanyKnowledgeRepository

    return CompanyKnowledgeRepository(create_company_engine())


def _create_agent_orchestrator(
    *,
    runtime_engine: Any | None = None,
    rule_repository: Any | None = None,
    business_db: Any | None = None,
    metadata_provider: Any | None = None,
):
    from app.agents.caliber_adaptation import CaliberAdaptationAgent
    from app.agents.human_interaction import HumanInteractionAgent
    from app.agents.indicator_generation import IndicatorGenerationAgent
    from app.agents.metadata_parsing import MetadataParsingAgent
    from app.agents.orchestrator import CoreIndicatorOrchestrator
    from app.agents.root_cause_diagnosis import RootCauseDiagnosisAgent
    from app.db.engine import create_runtime_engine
    from app.diagnose.agent import DiagnoseAgent
    from app.sqlgen.agent import SQLGenerationAgent
    from app.terminology.normalizer import TerminologyNormalizer
    from app.terminology.repository import TerminologyRepository

    engine = runtime_engine or create_runtime_engine()
    rules = rule_repository or create_rule_repository(engine, DEFAULT_KB_ROOT)
    db_client = business_db or create_business_db_client("hospital_demo_data")
    metadata = metadata_provider or create_dbhub_metadata_provider(
        "hospital_demo_data"
    )
    terminology_repository = TerminologyRepository(engine)
    return CoreIndicatorOrchestrator(
        interaction=HumanInteractionAgent(),
        caliber=CaliberAdaptationAgent(rules),
        indicator_generation=IndicatorGenerationAgent(
            SQLGenerationAgent(
                kb_root=DEFAULT_KB_ROOT,
                runtime_engine=engine,
                business_db=db_client,
                rule_repository=rules,
            )
        ),
        diagnosis=RootCauseDiagnosisAgent(
            DiagnoseAgent(
                kb_root=DEFAULT_KB_ROOT,
                runtime_engine=engine,
                business_db=db_client,
                metadata_provider=metadata,
            )
        ),
        metadata=MetadataParsingAgent(engine, DEFAULT_KB_ROOT),
        terminology_normalizer=TerminologyNormalizer(terminology_repository),
        terminology_repository=terminology_repository,
    )


class ChatRequest(BaseModel):
    query: str
    hospital_id: str | None = "hospital_001"
    use_llm: bool = True
    session_id: str | None = None


class SearchRequest(BaseModel):
    query: str
    limit: int = 5


class ChangeRequestCreate(BaseModel):
    rule_id: str
    indicator_name: str | None = None
    hospital_id: str
    target_level: str = "hospital"
    requested_definition: str | None = None
    requested_formula: str
    hospital_feedback: str | None = None
    original_user_message: str | None = None
    change_type: str = "本院口径反馈"
    submitter_id: str | None = None
    submitter_role: str | None = None


class LoginRequest(BaseModel):
    password: str


class ApproveRejectRequest(BaseModel):
    approver_id: str | None = None


class MergeItemDecisionRequest(BaseModel):
    decision: str = "adopt_as_company_candidate"
    approver_id: str | None = None
    reason: str | None = None


class CompanyReleaseCreateRequest(BaseModel):
    candidate_ids: list[str]
    created_by: str | None = None
    notes: str | None = None


class CompanyReleasePublishRequest(BaseModel):
    approver_id: str | None = None


class RecoveryRetryRequest(BaseModel):
    approver_id: str | None = None


def _require_admin(authorization: str | None = Header(None)) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="请先登录管理员账号")
    token = authorization.removeprefix("Bearer ").strip()
    if token not in _admin_tokens:
        raise HTTPException(status_code=403, detail="管理员 token 无效或已过期")
    return token


def _sse_event(event: str, payload: dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _config_suffix(value: str) -> str:
    return re.sub(r"[^0-9a-zA-Z]+", "_", value).strip("_").lower()


def _dbhub_api_url() -> str:
    return get("dbhub_api_url", get("dbhub_http_url", "http://127.0.0.1:8080"))


def _dbhub_mcp_url() -> str:
    return get("dbhub_mcp_url", f"{_dbhub_api_url().rstrip('/')}/mcp")


def _dbhub_execute_tool_for_db(db_name: str) -> str:
    suffix = _config_suffix(db_name)
    return get(f"dbhub_execute_tool_{suffix}", f"execute_sql_{suffix}")


def _dbhub_source_id_for_db(db_name: str) -> str:
    suffix = _config_suffix(db_name)
    return get(f"dbhub_source_id_{suffix}", get(f"dbhub_source_{suffix}", get("dbhub_source_id", db_name)))


def create_dbhub_client_for_db(db_name: str) -> DBHubMCPClient:
    return DBHubMCPClient(
        endpoint=_dbhub_mcp_url(),
        execute_tool=_dbhub_execute_tool_for_db(db_name),
        timeout_seconds=int(get("dbhub_timeout_seconds", "10")),
        source_id=_dbhub_source_id_for_db(db_name),
    )


def create_business_db_client(db_name: str = "hospital_demo_data") -> BusinessDBClient:
    client = create_dbhub_client_for_db(db_name)
    return BusinessDBClient(
        client.execute_sql,
        source_id=_dbhub_source_id_for_db(db_name),
        tool_name=_dbhub_execute_tool_for_db(db_name),
    )


def create_dbhub_metadata_provider(db_name: str = "hospital_demo_data") -> DBHubMetadataProvider:
    client = create_dbhub_client_for_db(db_name)
    return DBHubMetadataProvider(client.execute_sql)


app = FastAPI(title="Core Rules Wiki Agent", version="0.1.0")

from app.api.indicator_drafts import (
    published_router as hospital_defined_router,
    router as indicator_draft_router,
)
from app.api.monitoring import router as monitoring_router
from app.api.terminology import router as terminology_router

app.include_router(indicator_draft_router)
app.include_router(hospital_defined_router)
app.include_router(monitoring_router)
app.include_router(terminology_router)


def start_monitoring_scheduler() -> None:
    if get("monitoring_scheduler_enabled", "true").strip().lower() not in {
        "true", "1", "yes", "on"
    }:
        set_monitoring_scheduler_disabled()
        return
    try:
        from app.db.engine import create_runtime_engine
        from app.db.repositories import mark_running_recovery_tasks_interrupted
        from app.monitoring.factory import create_monitoring_service

        engine = create_runtime_engine()
        ensure_monitoring_schema(engine)
        mark_running_recovery_tasks_interrupted(engine)
        scheduler = MonitoringScheduler(
            MonitoringRepository(engine),
            service_factory=lambda: create_monitoring_service(engine),
            timezone_name=get(
                "monitoring_scheduler_timezone", "Asia/Shanghai"
            ),
        )
        scheduler.start()
        set_monitoring_scheduler(scheduler)
    except Exception as exc:
        logger.exception("monitoring scheduler startup failed")
        set_monitoring_scheduler_error(str(exc))


def initialize_terminology_runtime() -> None:
    try:
        from app.db.engine import create_runtime_engine
        from app.terminology.normalizer import TerminologyNormalizer
        from app.terminology.repository import TerminologyRepository

        engine = create_runtime_engine()
        ensure_terminology_schema(engine)
        TerminologyNormalizer(TerminologyRepository(engine)).warm()
    except Exception:
        logger.exception("terminology runtime initialization failed")


def stop_monitoring_scheduler() -> None:
    scheduler = get_monitoring_scheduler()
    if scheduler is not None:
        scheduler.shutdown()


app.add_event_handler("startup", initialize_terminology_runtime)
app.add_event_handler("startup", start_monitoring_scheduler)
app.add_event_handler("shutdown", stop_monitoring_scheduler)


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def _write_request_log(event: dict[str, Any]) -> None:
    try:
        REQUEST_LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
        with REQUEST_LOG_PATH.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")
    except Exception:
        logger.debug("request log write failed", exc_info=True)


@app.middleware("http")
async def request_observability_middleware(request: Request, call_next):
    request_id = request.headers.get("X-Request-ID") or f"REQ_{uuid.uuid4().hex[:12]}"
    request.state.request_id = request_id
    started = time.perf_counter()
    status_code = 500
    try:
        response = await call_next(request)
        status_code = response.status_code
    except Exception as exc:
        elapsed_ms = max(0, int((time.perf_counter() - started) * 1000))
        _write_request_log(
            {
                "event": "http_request",
                "request_id": request_id,
                "method": request.method,
                "path": request.url.path,
                "status_code": status_code,
                "duration_ms": elapsed_ms,
                "error": str(exc),
                "created_at": _utc_now_iso(),
            }
        )
        raise
    elapsed_ms = max(0, int((time.perf_counter() - started) * 1000))
    response.headers["X-Request-ID"] = request_id
    response.headers["X-Process-Time-Ms"] = str(elapsed_ms)
    _write_request_log(
        {
            "event": "http_request",
            "request_id": request_id,
            "method": request.method,
            "path": request.url.path,
            "status_code": status_code,
            "duration_ms": elapsed_ms,
            "created_at": _utc_now_iso(),
        }
    )
    return response


def _dependency_check(
    ok: bool,
    code: str = "OK",
    critical: bool = True,
    error: str = "",
    **extra: Any,
) -> dict[str, Any]:
    payload = {"ok": ok, "code": "OK" if ok else code, "critical": critical}
    if error:
        payload["error"] = error
    payload.update(extra)
    return payload


def _normalize_dependency_result(
    raw: dict[str, Any],
    unavailable_code: str,
    critical: bool = True,
) -> dict[str, Any]:
    ok = bool(raw.get("ok", False))
    result = dict(raw)
    result["ok"] = ok
    result["code"] = str(raw.get("code") or ("OK" if ok else unavailable_code))
    result["critical"] = bool(raw.get("critical", critical))
    return result


def _health_status(checks: dict[str, dict[str, Any]]) -> str:
    critical_failed = any(not check.get("ok", False) and check.get("critical", True) for check in checks.values())
    return "degraded" if critical_failed else "ok"


def _collect_dependency_checks() -> dict[str, dict[str, Any]]:
    from app.db.engine import create_runtime_engine

    checks: dict[str, dict[str, Any]] = {
        "fastapi": _dependency_check(True),
        "langgraph": _dependency_check(langgraph_installed(), "LANGGRAPH_NOT_INSTALLED", critical=False, engine=workflow_engine_name()),
    }
    try:
        with create_runtime_engine().connect() as conn:
            conn.execute(text("SELECT 1"))
        checks["runtime_db"] = _dependency_check(True)
    except Exception as exc:
        checks["runtime_db"] = _dependency_check(False, "RUNTIME_DB_UNAVAILABLE", error=str(exc))

    try:
        checks["business_db_mcp"] = _normalize_dependency_result(
            create_business_db_client("hospital_demo_data").check_available(),
            "BUSINESS_DB_MCP_UNAVAILABLE",
        )
    except Exception as exc:
        checks["business_db_mcp"] = _dependency_check(False, "BUSINESS_DB_MCP_UNAVAILABLE", error=str(exc))

    try:
        payload = dbhub_sources(_dbhub_api_url(), int(get("dbhub_timeout_seconds", "5")))
        source_items = payload if isinstance(payload, list) else payload.get("sources", payload.get("value", []))
        checks["dbhub_http"] = _dependency_check(True, source_count=len(source_items))
    except Exception as exc:
        checks["dbhub_http"] = _dependency_check(False, "DBHUB_HTTP_UNAVAILABLE", error=str(exc))

    checks["monitoring_scheduler"] = monitoring_scheduler_status()

    return checks


def _health_summary_item(key: str, check: dict[str, Any]) -> dict[str, Any]:
    names = {
        "fastapi": "后端服务",
        "runtime_db": "运行数据库",
        "business_db_mcp": "业务数据库",
        "dbhub_http": "DBHub 服务",
        "langgraph": "流程引擎",
        "monitoring_scheduler": "指标调度器",
    }
    descriptions = {
        "fastapi": "负责页面和接口请求。",
        "runtime_db": "保存会话、审批、执行记录等运行数据。",
        "business_db_mcp": "通过 DBHub 访问医院业务数据。",
        "dbhub_http": "提供数据库工具、元数据同步和 SQL 试运行能力。",
        "langgraph": "非流式接口可使用的流程编排引擎。",
        "monitoring_scheduler": "按运行计划定时计算指标，并触发波动预警。",
    }
    suggestions = {
        "runtime_db": "检查 runtime 数据库是否已初始化，以及 config.yaml 中 runtime_db_url 是否正确。",
        "business_db_mcp": "检查 DBHub 是否启动、业务库账号密码是否正确，以及只读 SQL 工具是否可用。",
        "dbhub_http": "检查 DBHub sidecar 是否运行在 127.0.0.1:8080，并确认 dbhub.local.toml 连接串正确。",
        "langgraph": "如果需要 LangGraph 流程能力，请安装 langgraph；主前端流式问答不依赖它。",
        "fastapi": "检查后端服务日志。",
        "monitoring_scheduler": "检查运行数据库迁移、APScheduler 依赖和调度器配置。",
    }
    ok = bool(check.get("ok", False))
    if key == "langgraph" and not ok:
        return {
            "key": key,
            "name": names.get(key, key),
            "status": "optional",
            "status_text": "未启用",
            "description": descriptions.get(key, ""),
            "suggestion": suggestions.get(key, ""),
            "problem_code": str(check.get("code") or ""),
            "detail": "",
        }
    item = {
        "key": key,
        "name": names.get(key, key),
        "status": "ok" if ok else "failed",
        "status_text": "正常" if ok else "异常",
        "description": descriptions.get(key, ""),
        "suggestion": "" if ok else suggestions.get(key, "查看后端日志并联系实施人员处理。"),
        "problem_code": "" if ok else str(check.get("code") or ""),
        "detail": "" if ok else str(check.get("error") or ""),
    }
    if key == "monitoring_scheduler":
        item["enabled_plan_count"] = int(check.get("enabled_plan_count") or 0)
        item["job_count"] = int(check.get("job_count") or 0)
        item["last_scan_at"] = check.get("last_scan_at")
    return item


def _build_health_summary(request_id: str) -> dict[str, Any]:
    checks = _collect_dependency_checks()
    status = _health_status(checks)
    order = [
        "fastapi", "runtime_db", "dbhub_http", "business_db_mcp",
        "monitoring_scheduler", "langgraph",
    ]
    return {
        "title": "系统自检",
        "status": status,
        "status_text": "全部正常" if status == "ok" else "部分异常",
        "request_id": request_id,
        "checked_at": _utc_now_iso(),
        "items": [_health_summary_item(key, checks[key]) for key in order if key in checks],
    }


def _start_api_trace(hospital_id: str | None, user_query: str) -> tuple[str, TraceRecorder | None]:
    from app.db.engine import create_runtime_engine

    trace_id = f"TRACE_{uuid.uuid4().hex[:12]}"
    try:
        recorder = TraceRecorder(create_runtime_engine())
        recorder.start_trace(trace_id, None, hospital_id, user_query)
        return trace_id, recorder
    except Exception:
        return trace_id, None


def _finish_api_trace(
    recorder: TraceRecorder | None,
    trace_id: str,
    final_status: str,
    summary: str,
    intent: str,
    error_count: int = 0,
) -> None:
    if recorder is None:
        return
    recorder.finish_trace(trace_id, final_status, summary, intent=intent, error_count=error_count)


def _request_id_from_state(request: Request | None) -> str:
    return str(getattr(getattr(request, "state", None), "request_id", "") or "")


def _recovery_status_text(status: str) -> str:
    return {
        "running": "执行中",
        "interrupted": "上次中断",
        "failed_retryable": "可重试",
        "completed": "已完成",
        "ignored": "已忽略",
    }.get(status, status or "未知")


def _recovery_action_text(action: str) -> str:
    return {
        "retry": "重试",
        "rebuild_index": "继续重建索引",
        "manual": "人工确认",
    }.get(action, "")


def _format_recovery_task(item: dict[str, Any]) -> dict[str, Any]:
    status = str(item.get("status") or "")
    action = str(item.get("recoverable_action") or "")
    suggestions = {
        "metadata_sync": "确认 DBHub 和数据库连接正常后重试。",
        "approval_apply_override": "先查看执行链路和待审批文件，再决定是否人工重试审批。",
        "index_rebuild": "可直接重试重建索引，不会修改口径正文。",
        "restore_override": "先确认目标历史版本，再决定是否重新恢复。",
        "indicator_recompute": "确认 DBHub 和业务库正常后，按原统计周期重新运算。",
    }
    formatted = dict(item)
    formatted["status_text"] = _recovery_status_text(status)
    formatted["action_text"] = _recovery_action_text(action)
    formatted["suggestion"] = suggestions.get(str(item.get("task_type") or ""), "查看执行链路后处理。")
    return formatted


def _retry_recovery_task(runtime_engine, task: dict[str, Any], approver_id: str | None = None) -> dict[str, Any]:
    from app.db.repositories import complete_recovery_task, fail_recovery_task, update_recovery_task

    task_type = str(task.get("task_type") or "")
    payload = dict(task.get("payload") or {})
    update_recovery_task(runtime_engine, str(task["task_id"]), status="running", error_message="", increment_retry=True)
    try:
        if task_type == "metadata_sync":
            from app.metadata.sync import sync_metadata_from_provider

            result = sync_metadata_from_provider(
                runtime_engine=runtime_engine,
                provider=create_dbhub_metadata_provider(str(payload.get("db_name") or "hospital_demo_data")),
                hospital_id=str(payload.get("hospital_id") or "hospital_001"),
                db_name=str(payload.get("db_name") or "hospital_demo_data"),
                kb_root=DEFAULT_KB_ROOT,
            )
        elif task_type == "index_rebuild":
            result = KnowledgeBaseTools(DEFAULT_KB_ROOT).rebuild_runtime_indexes()
        elif task_type == "indicator_recompute":
            from app.monitoring.factory import create_monitoring_service

            result = create_monitoring_service(runtime_engine).retry_result(
                result_id=int(payload["failed_result_id"]),
                request_id=str(task.get("request_id") or f"REQ_{uuid.uuid4().hex[:12]}"),
            )
        else:
            raise ValueError("该任务需要人工确认后重新发起原操作")
        complete_recovery_task(runtime_engine, str(task["task_id"]), result)
        return {**_format_recovery_task(task), "status": "completed", "status_text": "已完成", "result": result}
    except Exception as exc:
        fail_recovery_task(runtime_engine, str(task["task_id"]), str(exc))
        raise


@app.get("/")
def index() -> FileResponse:
    return FileResponse(WEB_ROOT / "index.html")


@app.post("/api/chat")
def chat(request: ChatRequest) -> dict[str, Any]:
    return run_chat(
        request.query,
        hospital_id=request.hospital_id,
        kb_root=DEFAULT_KB_ROOT,
        use_llm=request.use_llm,
        session_id=request.session_id,
        rule_repository=_create_rule_repository(),
    )


@app.post("/api/chat/stream")
def chat_stream(request: ChatRequest) -> StreamingResponse:
    """Ollama 逐 token 生成，FastAPI 通过 SSE 流式返回。"""

    def generate() -> Iterable[str]:
        try:
            for event, data in run_chat_stream(
                query=request.query,
                hospital_id=request.hospital_id,
                kb_root=DEFAULT_KB_ROOT,
                use_llm=request.use_llm,
                session_id=request.session_id,
                rule_repository=_create_rule_repository(),
            ):
                yield _sse_event(event, data)
        except Exception as exc:
            yield _sse_event("error", {"message": str(exc)})

    return StreamingResponse(generate(), media_type="text/event-stream")


@app.post("/api/review/change-requests")
def create_change_request(request: ChangeRequestCreate) -> dict[str, Any]:
    trace_id, recorder = _start_api_trace(request.hospital_id, f"submit_change_request:{request.rule_id}")
    try:
        result = _create_rule_repository().submit_change_request(request.model_dump())
        record_review_trace_node(
            recorder,
            trace_id,
            "change_request_submit",
            "success",
            request.model_dump(),
            result,
        )
        _finish_api_trace(recorder, trace_id, "success", str(result.get("change_id") or ""), "change_request_submit")
        result["trace_id"] = trace_id
        return result
    except (RuleNotFoundError, ValueError) as exc:
        record_review_trace_node(
            recorder,
            trace_id,
            "change_request_submit",
            "failed",
            request.model_dump(),
            {"status": "failed", "error": str(exc)},
        )
        _finish_api_trace(recorder, trace_id, "failed", str(exc), "change_request_submit", error_count=1)
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        _finish_api_trace(
            recorder,
            trace_id,
            "failed",
            str(exc),
            "change_request_submit",
            error_count=1,
        )
        raise HTTPException(status_code=503, detail=f"MySQL 规则库写入失败: {exc}") from exc


@app.post("/api/kb/search")
def kb_search(request: SearchRequest) -> dict[str, Any]:
    return _create_rule_repository().search(request.query, request.limit)


@app.get("/api/kb/rules/{rule_id}/effective")
def kb_effective_rule(rule_id: str, hospital_id: str | None = "hospital_001") -> dict[str, Any]:
    try:
        return _create_rule_repository().get_effective_rule(rule_id, hospital_id)
    except (RuleNotFoundError, ValueError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.get("/api/health")
def health() -> dict[str, str | bool]:
    return {
        "status": "ok",
        "workflow_engine": workflow_engine_name(),
        "langgraph_installed": langgraph_installed(),
    }


@app.get("/api/health/dependencies")
def health_dependencies(request: Request) -> dict[str, Any]:
    checks = _collect_dependency_checks()

    result: dict[str, Any] = {
        "status": _health_status(checks),
        "request_id": getattr(request.state, "request_id", ""),
        "checked_at": _utc_now_iso(),
        "checks": checks,
    }
    result.update(checks)
    return result


@app.get("/api/health/summary")
def health_summary(request: Request) -> dict[str, Any]:
    return _build_health_summary(getattr(request.state, "request_id", ""))


@app.post("/api/rules/import-four")
def import_four_rules(
    authorization: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(authorization)
    from app.db.engine import create_runtime_engine

    result = import_four_indicator_rules(
        create_runtime_engine(), DEFAULT_KB_ROOT, "hospital_001"
    )
    if result.get("failed"):
        raise HTTPException(status_code=500, detail=result)
    return result


@app.get("/api/traces/{trace_id}")
def get_trace(trace_id: str) -> dict[str, Any]:
    from app.db.engine import create_runtime_engine
    from app.observability.trace import TraceRecorder

    return TraceRecorder(create_runtime_engine()).get_trace(trace_id)


@app.get("/api/workflows/{workflow_id}")
def workflow_manifest(workflow_id: str) -> dict[str, Any]:
    from app.workflows.manifest import load_workflow_manifest

    return load_workflow_manifest(workflow_id)


@app.get("/api/workflows/{workflow_id}/validate")
def workflow_manifest_validate(workflow_id: str) -> dict[str, Any]:
    from app.workflows.manifest import validate_workflow_manifest

    return validate_workflow_manifest(workflow_id)


@app.get("/api/recovery/tasks")
def recovery_tasks(
    include_completed: bool = False,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    from app.db.engine import create_runtime_engine
    from app.db.repositories import list_recovery_tasks, mark_running_recovery_tasks_interrupted

    runtime_engine = create_runtime_engine()
    mark_running_recovery_tasks_interrupted(runtime_engine)
    items = [_format_recovery_task(item) for item in list_recovery_tasks(runtime_engine, include_completed=include_completed)]
    return {"title": "恢复中心", "items": items}


@app.post("/api/recovery/tasks/{task_id}/ignore")
def recovery_task_ignore(
    task_id: str,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    from app.db.engine import create_runtime_engine
    from app.db.repositories import ignore_recovery_task

    task = ignore_recovery_task(create_runtime_engine(), task_id)
    if not task:
        raise HTTPException(status_code=404, detail="恢复任务不存在")
    return _format_recovery_task(task)


@app.post("/api/recovery/tasks/{task_id}/retry")
def recovery_task_retry(
    task_id: str,
    body: RecoveryRetryRequest | None = None,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    from app.db.engine import create_runtime_engine
    from app.db.repositories import get_recovery_task

    runtime_engine = create_runtime_engine()
    task = get_recovery_task(runtime_engine, task_id)
    if not task:
        raise HTTPException(status_code=404, detail="恢复任务不存在")
    try:
        return _retry_recovery_task(runtime_engine, task, approver_id=(body.approver_id if body else None))
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/kb/export")
def kb_export(hospital_id: str = "hospital_001") -> Response:
    from app.db.engine import create_runtime_engine
    from app.kb.export import export_hospital_kb_zip

    try:
        data = export_hospital_kb_zip(create_runtime_engine(), hospital_id)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"医院知识包导出失败：{exc}") from exc
    filename = f"{hospital_id}_kb_export.zip"
    return Response(
        content=data,
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@app.post("/api/kb/merge/upload")
def kb_merge_upload(
    payload: bytes = Body(..., media_type="application/zip"),
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        return _create_company_repository().create_merge_report(payload, uploaded_by="admin")
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/kb/merge/reports")
def kb_merge_reports(_token: str | None = Header(None, alias="Authorization")) -> dict[str, Any]:
    _require_admin(_token)
    return {"items": _create_company_repository().list_merge_reports()}


@app.get("/api/kb/merge/report/{report_id}")
def kb_merge_report(report_id: str, _token: str | None = Header(None, alias="Authorization")) -> dict[str, Any]:
    _require_admin(_token)
    try:
        return _create_company_repository().read_merge_report(report_id)
    except Exception as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.post("/api/kb/merge/report/{report_id}/items/{item_id}/approve")
def kb_merge_item_approve(
    report_id: str,
    item_id: str,
    body: MergeItemDecisionRequest | None = None,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        decision = (body.decision if body else None) or "adopt_as_company_candidate"
        approver_id = (body.approver_id if body else None) or "admin"
        return _create_company_repository().approve_merge_item(
            report_id, item_id, decision, approver_id
        )
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/kb/merge/report/{report_id}/items/{item_id}/reject")
def kb_merge_item_reject(
    report_id: str,
    item_id: str,
    body: MergeItemDecisionRequest | None = None,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        reason = (body.reason if body else None) or ""
        approver_id = (body.approver_id if body else None) or "admin"
        return _create_company_repository().reject_merge_item(
            report_id, item_id, reason, approver_id
        )
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/kb/company/releases")
def kb_company_releases(
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    return {"items": _create_company_repository().list_releases()}


@app.get("/api/kb/company/candidates")
def kb_company_candidates(
    status: str | None = "approved",
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    return {"items": _create_company_repository().list_candidates(status)}


@app.post("/api/kb/company/releases")
def kb_company_release_create(
    body: CompanyReleaseCreateRequest,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        return _create_company_repository().create_release(
            body.candidate_ids,
            body.created_by or "admin",
            body.notes or "",
        )
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/kb/company/releases/{release_id}")
def kb_company_release_detail(
    release_id: str,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        return _create_company_repository().read_release(release_id)
    except Exception as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.post("/api/kb/company/releases/{release_id}/publish")
def kb_company_release_publish(
    release_id: str,
    body: CompanyReleasePublishRequest | None = None,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        return _create_company_repository().publish_release(
            release_id, (body.approver_id if body else None) or "admin"
        )
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/kb/company/releases/{release_id}/export")
def kb_company_release_export(
    release_id: str,
    _token: str | None = Header(None, alias="Authorization"),
) -> Response:
    _require_admin(_token)
    try:
        data = _create_company_repository().export_release_zip(release_id)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return Response(
        content=data,
        media_type="application/zip",
        headers={
            "Content-Disposition": f'attachment; filename="{release_id}_company_release.zip"'
        },
    )


@app.post("/api/admin/login")
def admin_login(request: LoginRequest) -> dict[str, Any]:
    if request.password != ADMIN_PASSWORD:
        raise HTTPException(status_code=401, detail="管理员密码错误")
    token = secrets.token_hex(16)
    _admin_tokens.add(token)
    return {"token": token, "message": "登录成功"}


@app.post("/api/admin/logout")
def admin_logout(token: str = Header(..., alias="Authorization")) -> dict[str, str]:
    clean = token.removeprefix("Bearer ").strip()
    _admin_tokens.discard(clean)
    return {"message": "已登出"}


@app.get("/api/review/pending")
def list_pending_change_requests(_token: str | None = Header(None, alias="Authorization")) -> dict[str, Any]:
    _require_admin(_token)
    return {"items": _create_rule_repository().list_pending_changes()}


@app.post("/api/review/change-requests/{change_id}/approve")
def approve_change_request(
    change_id: str,
    request: Request,
    body: ApproveRejectRequest | None = None,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    from app.db.engine import create_runtime_engine
    from app.db.repositories import complete_recovery_task, create_recovery_task, fail_recovery_task, update_recovery_task

    trace_id, recorder = _start_api_trace(None, f"approve_change_request:{change_id}")
    runtime_engine = create_runtime_engine()
    recovery_task_id = create_recovery_task(
        runtime_engine,
        task_type="approval_apply_override",
        task_name="审批并应用医院口径",
        current_step="approval_apply_override",
        payload={"change_id": change_id, "approver_id": (body.approver_id if body else None) or "admin"},
        trace_id=trace_id,
        request_id=_request_id_from_state(request),
        recoverable_action="manual",
    )
    try:
        approver_id = (body.approver_id if body else None) or "admin"
        result = _create_rule_repository().approve_change_request(change_id, approver_id)
        update_recovery_task(runtime_engine, recovery_task_id, current_step="index_rebuild")
        record_review_trace_node(
            recorder,
            trace_id,
            "approval_apply_override",
            "success",
            {"change_id": change_id, "approver_id": approver_id},
            result,
        )
        record_review_trace_node(
            recorder,
            trace_id,
            "index_rebuild",
            "success",
            {"change_id": change_id, "rule_id": result.get("rule_id"), "hospital_id": result.get("hospital_id")},
            {"status": "mysql_projection_updated", "active_version_id": result.get("active_version_id")},
        )
        _finish_api_trace(recorder, trace_id, "success", str(result.get("active_version_id") or ""), "approval_apply_override")
        complete_recovery_task(runtime_engine, recovery_task_id, result)
        result["trace_id"] = trace_id
        result["recovery_task_id"] = recovery_task_id
        return result
    except (RuleNotFoundError, ValueError) as exc:
        fail_recovery_task(runtime_engine, recovery_task_id, str(exc))
        record_review_trace_node(
            recorder,
            trace_id,
            "approval_apply_override",
            "failed",
            {"change_id": change_id},
            {"status": "failed", "error": str(exc)},
        )
        _finish_api_trace(recorder, trace_id, "failed", str(exc), "approval_apply_override", error_count=1)
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/review/hospital-overrides/{hospital_id}/{rule_id}/versions")
def list_hospital_override_versions(
    hospital_id: str,
    rule_id: str,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        return _create_rule_repository().list_versions(rule_id, hospital_id)
    except (RuleNotFoundError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/review/hospital-overrides/{hospital_id}/{rule_id}/versions/{version_id}/restore")
def restore_hospital_override_version(
    hospital_id: str,
    rule_id: str,
    version_id: str,
    request: Request,
    body: ApproveRejectRequest | None = None,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    from app.db.engine import create_runtime_engine
    from app.db.repositories import complete_recovery_task, create_recovery_task, fail_recovery_task, update_recovery_task

    trace_id, recorder = _start_api_trace(hospital_id, f"restore_override:{rule_id}:{version_id}")
    runtime_engine = create_runtime_engine()
    recovery_task_id = create_recovery_task(
        runtime_engine,
        task_type="restore_override",
        task_name="恢复医院历史口径",
        current_step="approval_apply_override",
        payload={"rule_id": rule_id, "hospital_id": hospital_id, "version_id": version_id, "approver_id": (body.approver_id if body else None) or "admin"},
        trace_id=trace_id,
        request_id=_request_id_from_state(request),
        hospital_id=hospital_id,
        rule_id=rule_id,
        recoverable_action="manual",
    )
    try:
        approver_id = (body.approver_id if body else None) or "admin"
        result = _create_rule_repository().restore_version(
            rule_id, hospital_id, int(version_id), approver_id
        )
        update_recovery_task(runtime_engine, recovery_task_id, current_step="index_rebuild")
        record_review_trace_node(
            recorder,
            trace_id,
            "approval_apply_override",
            "success",
            {"rule_id": rule_id, "hospital_id": hospital_id, "version_id": version_id, "approver_id": approver_id},
            result,
        )
        record_review_trace_node(
            recorder,
            trace_id,
            "index_rebuild",
            "success",
            {"rule_id": rule_id, "hospital_id": hospital_id, "version_id": version_id},
            {"status": "mysql_projection_updated", "active_version_id": result.get("active_version_id")},
        )
        _finish_api_trace(recorder, trace_id, "success", str(result.get("active_version_id") or ""), "approval_apply_override")
        complete_recovery_task(runtime_engine, recovery_task_id, result)
        result["trace_id"] = trace_id
        result["recovery_task_id"] = recovery_task_id
        return result
    except (RuleNotFoundError, ValueError) as exc:
        fail_recovery_task(runtime_engine, recovery_task_id, str(exc))
        record_review_trace_node(
            recorder,
            trace_id,
            "approval_apply_override",
            "failed",
            {"rule_id": rule_id, "hospital_id": hospital_id, "version_id": version_id},
            {"status": "failed", "error": str(exc)},
        )
        _finish_api_trace(recorder, trace_id, "failed", str(exc), "approval_apply_override", error_count=1)
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/review/change-requests/{change_id}/reject")
def reject_change_request(
    change_id: str,
    body: ApproveRejectRequest | None = None,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        approver_id = (body.approver_id if body else None) or "admin"
        return _create_rule_repository().reject_change_request(change_id, approver_id)
    except (RuleNotFoundError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


class MetadataSyncRequest(BaseModel):
    hospital_id: str
    db_name: str
    source: str = "dbhub"


class SqlGenerateRequest(BaseModel):
    query: str
    hospital_id: str
    rule_id: str
    stat_start_time: str
    stat_end_time: str
    trial_run: bool = False


class DiagnoseRequest(BaseModel):
    hospital_id: str
    rule_id: str
    trigger: str = "manual"
    related_sql_id: str | None = None
    stat_period: str | None = None


@app.get("/api/mcp/dbhub/sources")
def dbhub_sources_api() -> dict[str, Any]:
    try:
        payload = dbhub_sources(_dbhub_api_url(), int(get("dbhub_timeout_seconds", "10")))
    except (DBHubMCPError, urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail=f"DBHub sidecar 访问失败: {exc}") from exc
    items = payload if isinstance(payload, list) else payload.get("sources", payload.get("value", []))
    return {
        "status": "ok",
        "dbhub_http_url": _dbhub_api_url().rstrip("/"),
        "sources": items,
    }


@app.get("/api/metadata/overview")
def metadata_overview(
    hospital_id: str,
    db_name: str = "hospital_demo_data",
) -> dict[str, Any]:
    from app.db.engine import create_runtime_engine
    from app.metadata.overview import load_metadata_overview

    return load_metadata_overview(
        create_runtime_engine(),
        DEFAULT_KB_ROOT,
        hospital_id,
        db_name,
    )


@app.post("/api/metadata/sync")
def metadata_sync(request: Request, payload: MetadataSyncRequest) -> dict[str, Any]:
    from app.db.engine import create_runtime_engine
    from app.db.repositories import complete_recovery_task, create_recovery_task, fail_recovery_task

    runtime_engine = create_runtime_engine()
    trace_id = f"TRACE_{uuid.uuid4().hex[:12]}"
    recorder = TraceRecorder(runtime_engine)
    recorder.start_trace(trace_id, None, payload.hospital_id, f"metadata_sync:{payload.db_name}")
    source = (payload.source or "dbhub").lower()
    if source != "dbhub":
        raise HTTPException(status_code=400, detail="当前 FastAPI 主链路只允许通过 DBHub MCP 同步业务库元数据")
    recovery_task_id = create_recovery_task(
        runtime_engine,
        task_type="metadata_sync",
        task_name="同步数据库元数据",
        current_step="metadata_sync_mcp",
        payload=payload.model_dump(),
        trace_id=trace_id,
        request_id=_request_id_from_state(request),
        hospital_id=payload.hospital_id,
        recoverable_action="retry",
    )

    try:
        metadata_provider = create_dbhub_metadata_provider(payload.db_name)
        orchestrator = _create_agent_orchestrator(
            runtime_engine=runtime_engine,
            metadata_provider=metadata_provider,
        )
        result = orchestrator.sync_metadata(
            metadata_provider,
            payload.hospital_id,
            payload.db_name,
        )
        record_metadata_sync_trace_node(recorder, trace_id, result, payload.hospital_id, payload.db_name)
        recorder.finish_trace(trace_id, "success", str(result.get("batch_id") or ""), intent="metadata_sync")
        complete_recovery_task(runtime_engine, recovery_task_id, result)
        result["trace_id"] = trace_id
        result["recovery_task_id"] = recovery_task_id
        return result
    except DBHubMCPError as exc:
        fail_recovery_task(runtime_engine, recovery_task_id, str(exc))
        raise HTTPException(status_code=400, detail=f"DBHub MCP 调用失败: {exc}") from exc
    except Exception as exc:
        fail_recovery_task(runtime_engine, recovery_task_id, str(exc))
        raise


@app.post("/api/sql/generate")
def sql_generate(request: SqlGenerateRequest) -> dict[str, Any]:
    from app.db.engine import create_runtime_engine

    runtime_engine = create_runtime_engine()
    rules = create_rule_repository(runtime_engine, DEFAULT_KB_ROOT)
    orchestrator = _create_agent_orchestrator(
        runtime_engine=runtime_engine,
        business_db=create_business_db_client("hospital_demo_data"),
        rule_repository=rules,
    )
    prepared = orchestrator.prepare_rule_request(
        query=request.query,
        hospital_id=request.hospital_id,
        intent="trial_run" if request.trial_run else "generate_sql",
        rule_id=request.rule_id,
    )
    return orchestrator.generate_indicator(
        prepared,
        stat_start_time=request.stat_start_time,
        stat_end_time=request.stat_end_time,
        trial_run=request.trial_run,
    )


@app.post("/api/diagnose/run")
def diagnose_run(request: DiagnoseRequest) -> dict[str, Any]:
    from app.db.engine import create_runtime_engine

    runtime_engine = create_runtime_engine()
    trace_id = f"TRACE_{uuid.uuid4().hex[:12]}"
    recorder = TraceRecorder(runtime_engine)
    recorder.start_trace(trace_id, None, request.hospital_id, f"diagnose:{request.rule_id}")
    rules = create_rule_repository(runtime_engine, DEFAULT_KB_ROOT)
    orchestrator = _create_agent_orchestrator(
        runtime_engine=runtime_engine,
        business_db=create_business_db_client("hospital_demo_data"),
        rule_repository=rules,
        metadata_provider=create_dbhub_metadata_provider("hospital_demo_data"),
    )
    prepared = orchestrator.prepare_rule_request(
        query=f"diagnose:{request.rule_id}",
        hospital_id=request.hospital_id,
        intent="diagnose",
        rule_id=request.rule_id,
    )
    result = orchestrator.diagnose(
        prepared,
        trigger=request.trigger,
        related_sql_id=request.related_sql_id,
        stat_period=request.stat_period,
    )
    record_diagnose_trace_nodes(recorder, trace_id, result, request.rule_id, request.hospital_id)
    recorder.finish_trace(
        trace_id,
        "success" if result.get("diagnose_status") != "failed" else "failed",
        str(result.get("report_id") or ""),
        intent="diagnose",
        error_count=1 if result.get("diagnose_status") == "failed" else 0,
    )
    result["trace_id"] = trace_id
    return result


if WEB_ROOT.exists():
    app.mount("/static", StaticFiles(directory=WEB_ROOT), name="static")
