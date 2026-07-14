from __future__ import annotations

from typing import Annotated, Callable

from fastapi import Depends, Header, HTTPException

from .models import HospitalPrincipal
from .repository import HospitalAuthRepository
from .service import HospitalAuthError, HospitalAuthService


def get_hospital_auth_service() -> HospitalAuthService:
    from app.db.engine import create_runtime_engine

    return HospitalAuthService(HospitalAuthRepository(create_runtime_engine()))


def _token_from_header(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=401,
            detail="请先登录后继续操作",
            headers={"WWW-Authenticate": "Bearer"},
        )
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(status_code=401, detail="请先登录后继续操作")
    return token


def _to_http_error(exc: HospitalAuthError) -> HTTPException:
    headers = {"WWW-Authenticate": "Bearer"} if exc.status_code == 401 else None
    return HTTPException(status_code=exc.status_code, detail=str(exc), headers=headers)


def require_hospital_session(
    authorization: Annotated[str | None, Header(alias="Authorization")] = None,
    service: HospitalAuthService = Depends(get_hospital_auth_service),
) -> HospitalPrincipal:
    try:
        return service.authenticate(_token_from_header(authorization))
    except HospitalAuthError as exc:
        raise _to_http_error(exc) from exc


def require_hospital_permission(permission: str) -> Callable[..., HospitalPrincipal]:
    def dependency(
        authorization: Annotated[str | None, Header(alias="Authorization")] = None,
        service: HospitalAuthService = Depends(get_hospital_auth_service),
    ) -> HospitalPrincipal:
        try:
            return service.authenticate(
                _token_from_header(authorization), required_permission=permission
            )
        except HospitalAuthError as exc:
            raise _to_http_error(exc) from exc

    return dependency
