from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, Field

from app.hospital_auth.dependencies import (
    get_hospital_auth_service,
    require_hospital_session,
)
from app.hospital_auth.models import HospitalPrincipal, LoginResult
from app.hospital_auth.service import HospitalAuthError, HospitalAuthService


router = APIRouter(prefix="/api/auth/hospital", tags=["hospital-auth"])


class HospitalLoginRequest(BaseModel):
    account_id: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=256)


class ChangePasswordRequest(BaseModel):
    current_password: str = Field(min_length=1, max_length=256)
    new_password: str = Field(min_length=8, max_length=256)


class LoginResponse(BaseModel):
    token: str
    token_type: str = "bearer"
    expires_at: datetime
    user_id: str
    account_id: str
    hospital_id: str
    permissions: list[str]
    must_change_password: bool


def _login_response(result: LoginResult) -> LoginResponse:
    return LoginResponse(
        token=result.token,
        expires_at=result.expires_at,
        user_id=result.user_id,
        account_id=result.account_id,
        hospital_id=result.hospital_id,
        permissions=sorted(result.permissions),
        must_change_password=result.must_change_password,
    )


def _raise_auth_error(exc: HospitalAuthError) -> None:
    headers = {"WWW-Authenticate": "Bearer"} if exc.status_code == 401 else None
    raise HTTPException(status_code=exc.status_code, detail=str(exc), headers=headers) from exc


@router.post("/login", response_model=LoginResponse)
def login(
    body: HospitalLoginRequest,
    service: HospitalAuthService = Depends(get_hospital_auth_service),
) -> LoginResponse:
    try:
        return _login_response(service.login(body.account_id, body.password))
    except HospitalAuthError as exc:
        _raise_auth_error(exc)


@router.post("/change-password", response_model=LoginResponse)
def change_password(
    body: ChangePasswordRequest,
    principal: HospitalPrincipal = Depends(require_hospital_session),
    service: HospitalAuthService = Depends(get_hospital_auth_service),
) -> LoginResponse:
    try:
        return _login_response(
            service.change_password(
                principal, body.current_password, body.new_password
            )
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except HospitalAuthError as exc:
        _raise_auth_error(exc)


@router.post("/logout", status_code=204)
def logout(
    principal: HospitalPrincipal = Depends(require_hospital_session),
    service: HospitalAuthService = Depends(get_hospital_auth_service),
) -> Response:
    service.logout(principal)
    return Response(status_code=204)
