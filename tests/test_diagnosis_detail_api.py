from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.hospital_auth.models import HospitalPrincipal


class _Store:
    def read_summary(self, hospital_id, comparison_id):
        assert hospital_id == "hospital_001"
        return {
            "comparison_id": comparison_id,
            "rule_id": "MQSI2025_001",
            "source_database": "WIN60_QA_991827",
            "created_at": "2026-07-15 12:00:00",
            "expires_at": "2026-07-16 12:00:00",
            "user_result": {"numerator_count": 3, "denominator_count": 4, "result_value": 75},
            "current_result": {"numerator_count": 2, "denominator_count": 4, "result_value": 50},
            "counts": {
                "all_differences": 1,
                "only_user_scope": 0,
                "only_current_scope": 0,
                "user_only_numerator": 1,
                "current_only_numerator": 0,
            },
        }

    def read_page(self, hospital_id, comparison_id, group, *, page, page_size):
        assert hospital_id == "hospital_001"
        return {
            "comparison_id": comparison_id,
            "group": group,
            "page": page,
            "page_size": page_size,
            "total": 1,
            "items": [{
                "record_key": "E0***01",
                "difference_group": group,
                "difference_reason": "用户 SQL 计入分子，当前生效 SQL 未计入分子。",
                "user_in_scope": True,
                "current_in_scope": True,
                "user_meets_numerator": True,
                "current_meets_numerator": False,
                "current_details": {},
            }],
        }


def _principal():
    return HospitalPrincipal(
        user_id="USER_001",
        account_id="user_001",
        hospital_id="hospital_001",
        permissions=frozenset({"indicator_detail_view"}),
        must_change_password=False,
        session_id="SESSION_001",
    )


def _client():
    from app.api import diagnosis_details as api

    app = FastAPI()
    app.include_router(api.router)
    app.dependency_overrides[api.get_diagnosis_comparison_store] = _Store
    app.dependency_overrides[api.require_detail_view] = _principal
    return TestClient(app)


def test_reads_comparison_summary_and_masked_difference_page():
    client = _client()

    summary = client.get("/api/diagnosis-comparisons/CMP_001")
    page = client.get(
        "/api/diagnosis-comparisons/CMP_001/details/user_only_numerator?page=1&page_size=20"
    )

    assert summary.status_code == 200
    assert summary.json()["counts"]["all_differences"] == 1
    assert page.status_code == 200
    assert page.json()["items"][0]["record_key"] == "E0***01"


def test_rejects_invalid_group_and_page_size():
    client = _client()

    assert client.get(
        "/api/diagnosis-comparisons/CMP_001/details/unknown?page_size=20"
    ).status_code == 422
    assert client.get(
        "/api/diagnosis-comparisons/CMP_001/details/all_differences?page_size=500"
    ).status_code == 422
