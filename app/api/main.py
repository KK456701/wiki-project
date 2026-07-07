from __future__ import annotations

import json
import importlib.util
import os
import secrets
import uuid
from pathlib import Path
from typing import Any, Iterable

from fastapi import Body, FastAPI, Header, HTTPException
from fastapi.responses import FileResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from sqlalchemy import text

from app.agent.graph import run_chat, run_chat_stream
from app.config import get
from app.db_access.business_db import BusinessDBClient
from app.db_access.dbhub_mcp import DBHubMCPClient, dbhub_sources
from app.kb.tools import DEFAULT_KB_ROOT, KBToolError, KnowledgeBaseTools
from app.metadata.sync import DBHubMetadataProvider


PROJECT_ROOT = Path(__file__).resolve().parents[2]
WEB_ROOT = PROJECT_ROOT / "web"
ADMIN_PASSWORD = get("admin_password", "admin123")

# 内存中存 admin token（重启失效）
_admin_tokens: set[str] = set()


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


def _require_admin(authorization: str | None = Header(None)) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="请先登录管理员账号")
    token = authorization.removeprefix("Bearer ").strip()
    if token not in _admin_tokens:
        raise HTTPException(status_code=403, detail="管理员 token 无效或已过期")
    return token




def _sse_event(event: str, payload: dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _chunk_text(text: str, size: int = 16) -> Iterable[str]:
    value = text or ""
    for index in range(0, len(value), size):
        yield value[index : index + size]


def _dbhub_api_url() -> str:
    return get("dbhub_api_url", "http://127.0.0.1:8080")


def _dbhub_mcp_url() -> str:
    return get("dbhub_mcp_url", f"{_dbhub_api_url().rstrip('/')}/mcp")


def _dbhub_source_id_for_db(db_name: str) -> str:
    return get(f"dbhub_source_{db_name}", db_name)


def _dbhub_execute_tool_for_db(db_name: str) -> str:
    return get(f"dbhub_execute_tool_{db_name}", f"execute_sql_{db_name}")


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


def _langgraph_available() -> bool:
    return importlib.util.find_spec("langgraph") is not None

app = FastAPI(title="Core Rules Wiki Agent", version="0.1.0")


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
    )


@app.post("/api/chat/stream")
def chat_stream(request: ChatRequest) -> StreamingResponse:
    """真正的流式对话：LLM 逐 token 产出 → SSE 逐 token 推送."""

    def generate() -> Iterable[str]:
        try:
            for event, data in run_chat_stream(
                query=request.query,
                hospital_id=request.hospital_id,
                kb_root=DEFAULT_KB_ROOT,
                use_llm=request.use_llm,
                session_id=request.session_id,
            ):
                yield _sse_event(event, data)
        except Exception as exc:
            yield _sse_event("error", {"message": str(exc)})

    return StreamingResponse(generate(), media_type="text/event-stream")


@app.post("/api/review/change-requests")
def create_change_request(request: ChangeRequestCreate) -> dict[str, Any]:
    try:
        return KnowledgeBaseTools(DEFAULT_KB_ROOT).submit_change_request(request.model_dump())
    except KBToolError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/kb/search")
def kb_search(request: SearchRequest) -> dict[str, Any]:
    return KnowledgeBaseTools(DEFAULT_KB_ROOT).search(request.query, request.limit)


@app.get("/api/kb/rules/{rule_id}/effective")
def kb_effective_rule(rule_id: str, hospital_id: str | None = "hospital_001") -> dict[str, Any]:
    return KnowledgeBaseTools(DEFAULT_KB_ROOT).get_effective_rule(rule_id, hospital_id)


@app.get("/api/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/health/dependencies")
def health_dependencies() -> dict[str, Any]:
    from app.db.engine import create_runtime_engine

    result: dict[str, Any] = {
        "fastapi": {"ok": True},
        "langgraph": {"ok": _langgraph_available(), "engine": "langgraph" if _langgraph_available() else "fallback"},
    }
    try:
        with create_runtime_engine().connect() as conn:
            conn.execute(text("SELECT 1"))
        result["runtime_db"] = {"ok": True}
    except Exception as exc:
        result["runtime_db"] = {"ok": False, "error": str(exc)}
    try:
        result["business_db_mcp"] = create_business_db_client("hospital_demo_data").check_available()
    except Exception as exc:
        result["business_db_mcp"] = {"ok": False, "error": str(exc)}
    try:
        sources = dbhub_sources(_dbhub_api_url(), int(get("dbhub_timeout_seconds", "5")))
        if isinstance(sources, dict):
            source_items = sources.get("sources", [])
        elif isinstance(sources, list):
            source_items = sources
        else:
            source_items = []
        result["dbhub_http"] = {"ok": True, "source_count": len(source_items)}
    except Exception as exc:
        result["dbhub_http"] = {"ok": False, "error": str(exc)}
    return result


@app.get("/api/traces/{trace_id}")
def get_trace(trace_id: str) -> dict[str, Any]:
    from app.db.engine import create_runtime_engine
    from app.observability.trace import TraceRecorder

    return TraceRecorder(create_runtime_engine()).get_trace(trace_id)


# ---- 管理员认证 ----



# ---- KB export and merge ----

@app.get("/api/kb/export")
def kb_export(hospital_id: str = "hospital_001") -> Response:
    from app.kb.export import export_hospital_kb_zip
    data = export_hospital_kb_zip(DEFAULT_KB_ROOT, hospital_id)
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
        from app.kb.merge import create_merge_report
        return create_merge_report(DEFAULT_KB_ROOT, payload, uploaded_by="admin")
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/kb/merge/reports")
def kb_merge_reports(_token: str | None = Header(None, alias="Authorization")) -> dict[str, Any]:
    _require_admin(_token)
    from app.kb.merge import list_merge_reports
    return {"items": list_merge_reports(DEFAULT_KB_ROOT)}


@app.get("/api/kb/merge/report/{report_id}")
def kb_merge_report(report_id: str, _token: str | None = Header(None, alias="Authorization")) -> dict[str, Any]:
    _require_admin(_token)
    try:
        from app.kb.merge import read_merge_report
        return read_merge_report(DEFAULT_KB_ROOT, report_id)
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
        from app.kb.merge import approve_merge_item
        decision = (body.decision if body else None) or "adopt_as_company_candidate"
        approver_id = (body.approver_id if body else None) or "admin"
        return approve_merge_item(DEFAULT_KB_ROOT, report_id, item_id, decision, approver_id)
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
        from app.kb.merge import reject_merge_item
        reason = (body.reason if body else None) or ""
        approver_id = (body.approver_id if body else None) or "admin"
        return reject_merge_item(DEFAULT_KB_ROOT, report_id, item_id, reason, approver_id)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

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


# ---- 审批接口（需管理员登录） ----

@app.get("/api/review/pending")
def list_pending_change_requests(_token: str | None = Header(None, alias="Authorization")) -> dict[str, Any]:
    _require_admin(_token)
    return {"items": KnowledgeBaseTools(DEFAULT_KB_ROOT).list_pending_change_requests()}


@app.post("/api/review/change-requests/{change_id}/approve")
def approve_change_request(
    change_id: str,
    body: ApproveRejectRequest | None = None,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        approver_id = (body.approver_id if body else None) or "admin"
        return KnowledgeBaseTools(DEFAULT_KB_ROOT).approve_change_request(change_id, approver_id)
    except KBToolError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc



@app.get("/api/review/hospital-overrides/{hospital_id}/{rule_id}/versions")
def list_hospital_override_versions(
    hospital_id: str,
    rule_id: str,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        return KnowledgeBaseTools(DEFAULT_KB_ROOT).list_hospital_override_versions(rule_id, hospital_id)
    except KBToolError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/review/hospital-overrides/{hospital_id}/{rule_id}/versions/{version_id}/restore")
def restore_hospital_override_version(
    hospital_id: str,
    rule_id: str,
    version_id: str,
    body: ApproveRejectRequest | None = None,
    _token: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        approver_id = (body.approver_id if body else None) or "admin"
        return KnowledgeBaseTools(DEFAULT_KB_ROOT).restore_hospital_override_version(rule_id, hospital_id, version_id, approver_id)
    except KBToolError as exc:
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
        return KnowledgeBaseTools(DEFAULT_KB_ROOT).reject_change_request(change_id, approver_id)
    except KBToolError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


# ---- SQL 生成与元数据同步 ----

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


@app.post("/api/metadata/sync")
def metadata_sync(request: MetadataSyncRequest) -> dict[str, Any]:
    from app.db.engine import create_runtime_engine
    from app.metadata.sync import sync_mysql_metadata
    runtime_engine = create_runtime_engine()
    return sync_mysql_metadata(
        runtime_engine=runtime_engine,
        metadata_provider=create_dbhub_metadata_provider(request.db_name),
        hospital_id=request.hospital_id,
        db_name=request.db_name,
    )


@app.post("/api/sql/generate")
def sql_generate(request: SqlGenerateRequest) -> dict[str, Any]:
    from app.db.engine import create_runtime_engine
    from app.sqlgen.agent import SQLGenerationAgent
    tools = KnowledgeBaseTools(DEFAULT_KB_ROOT)
    effective = tools.get_effective_rule(request.rule_id, request.hospital_id)
    agent = SQLGenerationAgent(
        kb_root=DEFAULT_KB_ROOT,
        runtime_engine=create_runtime_engine(),
        business_db=create_business_db_client("hospital_demo_data"),
    )
    return agent.generate(
        query=request.query,
        hospital_id=request.hospital_id,
        rule_id=request.rule_id,
        effective_rule=effective,
        stat_start_time=request.stat_start_time,
        stat_end_time=request.stat_end_time,
        trial_run=request.trial_run,
    )


@app.post("/api/diagnose/run")
def diagnose_run(request: DiagnoseRequest) -> dict[str, Any]:
    from app.db.engine import create_runtime_engine
    from app.diagnose.agent import DiagnoseAgent
    tools = KnowledgeBaseTools(DEFAULT_KB_ROOT)
    effective = tools.get_effective_rule(request.rule_id, request.hospital_id)
    agent = DiagnoseAgent(
        kb_root=DEFAULT_KB_ROOT,
        runtime_engine=create_runtime_engine(),
        business_db=create_business_db_client("hospital_demo_data"),
    )
    return agent.run(
        hospital_id=request.hospital_id,
        rule_id=request.rule_id,
        effective_rule=effective,
        trigger=request.trigger,
        related_sql_id=request.related_sql_id,
        stat_period=request.stat_period,
    )


if WEB_ROOT.exists():
    app.mount("/static", StaticFiles(directory=WEB_ROOT), name="static")

