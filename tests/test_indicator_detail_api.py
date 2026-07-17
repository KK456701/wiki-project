from __future__ import annotations

from datetime import datetime
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.hospital_auth.models import HospitalPrincipal
from app.indicator_details.models import (
    DetailColumn,
    DetailPage,
    DetailSnapshotSummary,
    ExportSummary,
)


class _FakeService:
    def __init__(self, download_path: Path) -> None:
        self.download_path = download_path
        self.export_confirmed: bool | None = None

    def ensure_snapshot(self, principal, run_id):
        return DetailSnapshotSummary(
            snapshot_id="SNAP_001",
            run_id=run_id,
            hospital_id=principal.hospital_id,
            rule_id="MQSI2025_005",
            rule_name="急会诊及时到位率",
            effective_level="hospital",
            hospital_version=1,
            stat_start="2026-07-01 00:00:00",
            stat_end="2026-08-01 00:00:00",
            denominator_count=3,
            numerator_count=2,
            unmatched_count=1,
            columns=[DetailColumn(field="patient_id", label="患者标识", sensitivity="patient_id")],
            created_at=datetime(2026, 7, 14, 9, 0, 0),
            expires_at=datetime(2026, 7, 15, 9, 0, 0),
        )

    def get_page(self, principal, run_id, group, page, page_size):
        return DetailPage(
            snapshot_id="SNAP_001",
            run_id=run_id,
            group=group,
            page=page,
            page_size=page_size,
            total=1,
            items=[{"患者标识": "PA******01", "是否达到要求": "是"}],
        )

    def create_export(self, principal, run_id, confirmed):
        self.export_confirmed = confirmed
        if not confirmed:
            raise ValueError("导出前必须确认患者明细使用范围")
        return ExportSummary(
            export_id="EXP_001",
            run_id=run_id,
            hospital_id=principal.hospital_id,
            rule_id="MQSI2025_005",
            file_name="急会诊及时到位率.xlsx",
            row_count=3,
            status="ready",
            created_at=datetime(2026, 7, 14, 9, 0, 0),
            expires_at=datetime(2026, 7, 15, 9, 0, 0),
            download_count=0,
        )

    def create_upload_comparison_export(
        self, principal, run_id, file_token, confirmed
    ):
        self.comparison_file_token = file_token
        return self.create_export(principal, run_id, confirmed)

    def list_exports(self, principal):
        return [self.create_export(principal, "RUN_001", True)]

    def resolve_download(self, principal, export_id, request_id=None):
        return self.download_path, "急会诊及时到位率.xlsx"


def _principal() -> HospitalPrincipal:
    return HospitalPrincipal(
        user_id="USER_001",
        account_id="user_001",
        hospital_id="hospital_001",
        permissions=frozenset({"indicator_detail_view", "indicator_detail_export"}),
        must_change_password=False,
        session_id="SESSION_001",
    )


def _client(tmp_path: Path):
    from app.api import indicator_details as api

    download = tmp_path / "detail.xlsx"
    download.write_bytes(b"xlsx")
    service = _FakeService(download)
    app = FastAPI()
    app.include_router(api.router)
    app.dependency_overrides[api.get_indicator_detail_service] = lambda: service
    app.dependency_overrides[api.require_detail_view] = _principal
    app.dependency_overrides[api.require_detail_export] = _principal
    return TestClient(app), service


def test_detail_api_creates_snapshot_and_returns_masked_page(tmp_path: Path) -> None:
    client, _ = _client(tmp_path)

    created = client.post("/api/sql-runs/RUN_001/details")
    page = client.get("/api/sql-runs/RUN_001/details/numerator?page=1&page_size=50")

    assert created.status_code == 201
    assert created.json()["denominator_count"] == 3
    assert page.status_code == 200
    assert page.json()["items"][0]["患者标识"] == "PA******01"


def test_export_requires_confirmation_and_download_is_not_cached(tmp_path: Path) -> None:
    client, service = _client(tmp_path)

    denied = client.post("/api/sql-runs/RUN_001/exports", json={"confirmed": False})
    created = client.post("/api/sql-runs/RUN_001/exports", json={"confirmed": True})
    downloaded = client.get("/api/indicator-exports/EXP_001/download")

    assert denied.status_code == 400
    assert created.status_code == 201
    assert service.export_confirmed is True
    assert downloaded.status_code == 200
    assert downloaded.headers["cache-control"] == "no-store"
    assert "attachment" in downloaded.headers["content-disposition"]


def test_upload_comparison_export_passes_bound_file_token(tmp_path: Path) -> None:
    client, service = _client(tmp_path)

    created = client.post(
        "/api/sql-runs/RUN_001/upload-comparison-exports",
        json={"confirmed": True, "file_token": "aG9zcGl0YWxfMDAxX3JlcG9ydC54bHN4"},
    )

    assert created.status_code == 201
    assert service.comparison_file_token == "aG9zcGl0YWxfMDAxX3JlcG9ydC54bHN4"


def test_invalid_group_and_page_size_are_rejected_before_service(tmp_path: Path) -> None:
    client, _ = _client(tmp_path)

    assert client.get("/api/sql-runs/RUN_001/details/all?page_size=50").status_code == 422
    assert client.get("/api/sql-runs/RUN_001/details/numerator?page_size=500").status_code == 422
