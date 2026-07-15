from __future__ import annotations

from datetime import timedelta
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from fastapi.responses import FileResponse
from pydantic import BaseModel

from app.config import get, get_int
from app.hospital_auth.dependencies import require_hospital_permission
from app.hospital_auth.models import (
    DETAIL_EXPORT_PERMISSION,
    DETAIL_VIEW_PERMISSION,
    HospitalPrincipal,
)
from app.indicator_details.models import (
    DetailPage,
    DetailSnapshotSummary,
    ExportSummary,
)
from app.indicator_details.service import IndicatorDetailError, IndicatorDetailService


router = APIRouter(tags=["indicator-details"])
require_detail_view = require_hospital_permission(DETAIL_VIEW_PERMISSION)
require_detail_export = require_hospital_permission(DETAIL_EXPORT_PERMISSION)
_configured_page_size = get_int("indicator_detail_default_page_size", 50)
DEFAULT_DETAIL_PAGE_SIZE = (
    _configured_page_size if _configured_page_size in {20, 50, 100} else 50
)


class ExportCreateRequest(BaseModel):
    confirmed: bool = False


def get_indicator_detail_service() -> IndicatorDetailService:
    from app.api.main import create_business_db_client
    from app.db.engine import create_runtime_engine
    from app.hospital_auth.repository import HospitalAuthRepository
    from app.indicator_details.repository import IndicatorDetailRepository
    from app.indicator_details.snapshot import DetailSnapshotStore

    engine = create_runtime_engine()
    repository = IndicatorDetailRepository(engine)
    export_root = Path(get("indicator_detail_export_root", "runtime/exports"))
    expire_hours = max(1, get_int("indicator_detail_expire_hours", 24))
    max_rows = max(1, get_int("indicator_detail_max_rows", 20_000))
    snapshot_store = DetailSnapshotStore(
        repository,
        create_business_db_client(),
        export_root=export_root,
        max_detail_rows=max_rows,
        snapshot_ttl=timedelta(hours=expire_hours),
    )
    return IndicatorDetailService(
        repository,
        snapshot_store,
        HospitalAuthRepository(engine),
        export_root=export_root,
        export_ttl=timedelta(hours=expire_hours),
    )


def _raise_detail_error(exc: Exception) -> None:
    if isinstance(exc, IndicatorDetailError):
        raise HTTPException(
            status_code=exc.status_code,
            detail={"code": exc.code, "message": str(exc)},
        ) from exc
    if isinstance(exc, LookupError):
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    if isinstance(exc, ValueError):
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    raise exc


@router.post(
    "/api/sql-runs/{run_id}/details",
    response_model=DetailSnapshotSummary,
    status_code=201,
)
def ensure_details(
    run_id: str,
    response: Response,
    principal: HospitalPrincipal = Depends(require_detail_view),
    service: IndicatorDetailService = Depends(get_indicator_detail_service),
) -> DetailSnapshotSummary:
    try:
        result = service.ensure_snapshot(principal, run_id)
    except Exception as exc:
        _raise_detail_error(exc)
    response.status_code = 200 if result.reused else 201
    return result


@router.get(
    "/api/sql-runs/{run_id}/details/{group}",
    response_model=DetailPage,
)
def get_detail_page(
    run_id: str,
    group: Literal["denominator", "numerator", "unmatched"],
    page: int = Query(1, ge=1),
    page_size: int = Query(DEFAULT_DETAIL_PAGE_SIZE, ge=1),
    principal: HospitalPrincipal = Depends(require_detail_view),
    service: IndicatorDetailService = Depends(get_indicator_detail_service),
) -> DetailPage:
    if page_size not in {20, 50, 100}:
        raise HTTPException(status_code=422, detail="每页条数只能选择20、50或100")
    try:
        return service.get_page(principal, run_id, group, page, page_size)
    except Exception as exc:
        _raise_detail_error(exc)


@router.post(
    "/api/sql-runs/{run_id}/exports",
    response_model=ExportSummary,
    status_code=201,
)
def create_export(
    run_id: str,
    body: ExportCreateRequest,
    principal: HospitalPrincipal = Depends(require_detail_export),
    service: IndicatorDetailService = Depends(get_indicator_detail_service),
) -> ExportSummary:
    try:
        return service.create_export(principal, run_id, body.confirmed)
    except Exception as exc:
        _raise_detail_error(exc)


@router.get("/api/indicator-exports", response_model=list[ExportSummary])
def list_exports(
    principal: HospitalPrincipal = Depends(require_detail_export),
    service: IndicatorDetailService = Depends(get_indicator_detail_service),
) -> list[ExportSummary]:
    try:
        return service.list_exports(principal)
    except Exception as exc:
        _raise_detail_error(exc)


@router.get("/api/indicator-exports/{export_id}/download")
def download_export(
    export_id: str,
    request: Request,
    principal: HospitalPrincipal = Depends(require_detail_export),
    service: IndicatorDetailService = Depends(get_indicator_detail_service),
) -> FileResponse:
    try:
        path, file_name = service.resolve_download(
            principal,
            export_id,
            request_id=getattr(request.state, "request_id", None),
        )
    except Exception as exc:
        _raise_detail_error(exc)
    return FileResponse(
        path,
        filename=file_name,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Cache-Control": "no-store"},
    )
