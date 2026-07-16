"""工具调用型 Agent 的独立登录态 API。"""

from __future__ import annotations

import json
import uuid
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime.service import AgentRunAccessError, AgentRuntimeService, AgentRuntimeUnavailable
from app.hospital_auth.dependencies import require_hospital_session
from app.hospital_auth.models import HospitalPrincipal


router = APIRouter(prefix="/api/agent", tags=["agent"])


class AgentChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    query: str = Field(min_length=1, max_length=5000)
    session_id: str | None = Field(default=None, min_length=1, max_length=128)
    model_id: str | None = Field(default=None, min_length=1, max_length=128)


class AgentChatResponse(BaseModel):
    answer: str
    stop_reason: str
    trace_id: str
    session_id: str
    step_count: int


def get_agent_runtime_service() -> AgentRuntimeService:
    return AgentRuntimeService.from_config()


def _sse_event(event: dict[str, Any]) -> str:
    event_name = str(event.get("event") or "agent_error")
    return f"event: {event_name}\ndata: {json.dumps(event, ensure_ascii=False)}\n\n"


@router.post("/chat", response_model=AgentChatResponse)
async def agent_chat(
    body: AgentChatRequest,
    principal: HospitalPrincipal = Depends(require_hospital_session),
    service: AgentRuntimeService = Depends(get_agent_runtime_service),
    request_id: Annotated[str | None, Header(alias="X-Request-ID")] = None,
) -> dict[str, Any]:
    try:
        return await service.chat(
            query=body.query,
            principal=principal,
            request_id=request_id or f"REQ_{uuid.uuid4().hex[:12]}",
            session_id=body.session_id,
            model_id=body.model_id,
        )
    except AgentRuntimeUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.get("/capabilities")
def agent_capabilities(
    principal: HospitalPrincipal = Depends(require_hospital_session),
    service: AgentRuntimeService = Depends(get_agent_runtime_service),
) -> dict[str, Any]:
    del principal
    return service.capabilities()


@router.post("/chat/stream")
def agent_chat_stream(
    body: AgentChatRequest,
    principal: HospitalPrincipal = Depends(require_hospital_session),
    service: AgentRuntimeService = Depends(get_agent_runtime_service),
    request_id: Annotated[str | None, Header(alias="X-Request-ID")] = None,
) -> StreamingResponse:
    try:
        service.ensure_available()
    except AgentRuntimeUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc

    async def generate():
        try:
            async for event in service.stream(
                query=body.query,
                principal=principal,
                request_id=request_id or f"REQ_{uuid.uuid4().hex[:12]}",
                session_id=body.session_id,
                model_id=body.model_id,
            ):
                yield _sse_event(event)
        except AgentRuntimeUnavailable as exc:
            yield _sse_event({
                "event": "agent_error",
                "stop_reason": "tool_error",
                "message": str(exc),
            })

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@router.get("/runs/{trace_id}")
def agent_run(
    trace_id: str,
    principal: HospitalPrincipal = Depends(require_hospital_session),
    service: AgentRuntimeService = Depends(get_agent_runtime_service),
) -> dict[str, Any]:
    try:
        return service.get_run(trace_id, principal)
    except AgentRunAccessError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc
