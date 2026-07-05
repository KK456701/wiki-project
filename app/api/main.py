from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Iterable

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from app.agent.graph import run_chat
from app.kb.tools import DEFAULT_KB_ROOT, KBToolError, KnowledgeBaseTools


PROJECT_ROOT = Path(__file__).resolve().parents[2]
WEB_ROOT = PROJECT_ROOT / "web"


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
    def generate() -> Iterable[str]:
        try:
            result = run_chat(
                request.query,
                hospital_id=request.hospital_id,
                kb_root=DEFAULT_KB_ROOT,
                use_llm=request.use_llm,
                session_id=request.session_id,
            )
            yield _sse_event(
                "meta",
                {
                    "session_id": result.get("session_id"),
                    "intent": result.get("intent"),
                    "rule_id": result.get("rule_id"),
                    "generation_method": result.get("generation_method"),
                },
            )
            for chunk in _chunk_text(str(result.get("answer", ""))):
                yield _sse_event("token", {"text": chunk})
            if result.get("feedback_preview"):
                yield _sse_event("feedback_preview", result["feedback_preview"])
            yield _sse_event("done", result)
        except Exception as exc:
            yield _sse_event("error", {"message": str(exc)})

    return StreamingResponse(generate(), media_type="text/event-stream")


@app.post("/api/review/change-requests")
def create_change_request(request: ChangeRequestCreate) -> dict[str, Any]:
    try:
        return KnowledgeBaseTools(DEFAULT_KB_ROOT).submit_change_request(request.model_dump())
    except KBToolError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/review/pending")
def list_pending_change_requests() -> dict[str, Any]:
    return {"items": KnowledgeBaseTools(DEFAULT_KB_ROOT).list_pending_change_requests()}


@app.post("/api/review/change-requests/{change_id}/approve")
def approve_change_request(change_id: str) -> dict[str, Any]:
    try:
        return KnowledgeBaseTools(DEFAULT_KB_ROOT).approve_change_request(change_id)
    except KBToolError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/review/change-requests/{change_id}/reject")
def reject_change_request(change_id: str) -> dict[str, Any]:
    try:
        return KnowledgeBaseTools(DEFAULT_KB_ROOT).reject_change_request(change_id)
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


if WEB_ROOT.exists():
    app.mount("/static", StaticFiles(directory=WEB_ROOT), name="static")

