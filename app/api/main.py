from __future__ import annotations

import json
import os
import secrets
import uuid
from pathlib import Path
from typing import Any, Iterable

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from app.agent.graph import run_chat, run_chat_stream
from app.kb.tools import DEFAULT_KB_ROOT, KBToolError, KnowledgeBaseTools


PROJECT_ROOT = Path(__file__).resolve().parents[2]
WEB_ROOT = PROJECT_ROOT / "web"
ADMIN_PASSWORD = os.getenv("ADMIN_PASSWORD", "admin123")

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
    change_type: str = "??????"
    submitter_id: str | None = None
    submitter_role: str | None = None


class LoginRequest(BaseModel):
    password: str


class ApproveRejectRequest(BaseModel):
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


def _chunk_text(text: str, size: int = 16) -> Iterable[str]:
    value = text or ""
    for index in range(0, len(value), size):
        yield value[index : index + size]

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


# ---- 管理员认证 ----

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
def list_pending_change_requests(_token: str = Header(..., alias="Authorization")) -> dict[str, Any]:
    _require_admin(_token)
    return {"items": KnowledgeBaseTools(DEFAULT_KB_ROOT).list_pending_change_requests()}


@app.post("/api/review/change-requests/{change_id}/approve")
def approve_change_request(
    change_id: str,
    body: ApproveRejectRequest | None = None,
    _token: str = Header(..., alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        approver_id = (body.approver_id if body else None) or "admin"
        return KnowledgeBaseTools(DEFAULT_KB_ROOT).approve_change_request(change_id, approver_id)
    except KBToolError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/review/change-requests/{change_id}/reject")
def reject_change_request(
    change_id: str,
    body: ApproveRejectRequest | None = None,
    _token: str = Header(..., alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(_token)
    try:
        approver_id = (body.approver_id if body else None) or "admin"
        return KnowledgeBaseTools(DEFAULT_KB_ROOT).reject_change_request(change_id, approver_id)
    except KBToolError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


if WEB_ROOT.exists():
    app.mount("/static", StaticFiles(directory=WEB_ROOT), name="static")

