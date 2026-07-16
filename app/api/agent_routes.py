"""工具调用型 Agent 的独立登录态 API。"""

from __future__ import annotations

import uuid
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime.service import AgentRunAccessError, AgentRuntimeService, AgentRuntimeUnavailable
from app.hospital_auth.dependencies import require_hospital_session
from app.hospital_auth.models import HospitalPrincipal


router = APIRouter(prefix="/api/agent", tags=["agent"])


class AgentChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    query: str = Field(min_length=1, max_length=5000)
    session_id: str | None = Field(default=None, min_length=1, max_length=128)


class AgentChatResponse(BaseModel):
    answer: str
    stop_reason: str
    trace_id: str
    session_id: str
    step_count: int


def get_agent_runtime_service() -> AgentRuntimeService:
    return AgentRuntimeService.from_config()


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
