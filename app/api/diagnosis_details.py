from __future__ import annotations

from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query

from app.config import get
from app.diagnose.detail_compare import DiagnosisComparisonStore
from app.hospital_auth.dependencies import require_hospital_permission
from app.hospital_auth.models import (
    DETAIL_VIEW_PERMISSION,
    HospitalPrincipal,
)


router = APIRouter(tags=["diagnosis-details"])
require_detail_view = require_hospital_permission(DETAIL_VIEW_PERMISSION)


def get_diagnosis_comparison_store() -> DiagnosisComparisonStore:
    return DiagnosisComparisonStore(
        Path(get("diagnosis_detail_root", "runtime/diagnosis-details"))
    )


def _raise_detail_error(exc: Exception) -> None:
    if isinstance(exc, LookupError):
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    if isinstance(exc, ValueError):
        status = 410 if "过期" in str(exc) else 400
        raise HTTPException(status_code=status, detail=str(exc)) from exc
    raise exc


@router.get("/api/diagnosis-comparisons/{comparison_id}")
def get_comparison_summary(
    comparison_id: str,
    principal: HospitalPrincipal = Depends(require_detail_view),
    store: DiagnosisComparisonStore = Depends(get_diagnosis_comparison_store),
) -> dict:
    try:
        return store.read_summary(principal.hospital_id, comparison_id)
    except Exception as exc:
        _raise_detail_error(exc)


@router.get("/api/diagnosis-comparisons/{comparison_id}/details/{group}")
def get_comparison_page(
    comparison_id: str,
    group: Literal[
        "all_differences",
        "only_user_scope",
        "only_current_scope",
        "user_only_numerator",
        "current_only_numerator",
    ],
    page: int = Query(1, ge=1),
    page_size: int = Query(50, ge=1),
    principal: HospitalPrincipal = Depends(require_detail_view),
    store: DiagnosisComparisonStore = Depends(get_diagnosis_comparison_store),
) -> dict:
    if page_size not in {20, 50, 100}:
        raise HTTPException(status_code=422, detail="每页条数只能选择20、50或100")
    try:
        return store.read_page(
            principal.hospital_id,
            comparison_id,
            group,
            page=page,
            page_size=page_size,
        )
    except Exception as exc:
        _raise_detail_error(exc)
