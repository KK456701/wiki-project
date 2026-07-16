from unittest.mock import patch

from app.api.main import ChatRequest, _schedule_agent_shadow
from app.hospital_auth.models import HospitalPrincipal


class FakeBackgroundTasks:
    def __init__(self):
        self.tasks = []

    def add_task(self, func, *args, **kwargs):
        self.tasks.append((func, args, kwargs))


class FakeAuthService:
    def __init__(self, principal=None, error=None):
        self.principal = principal
        self.error = error

    def authenticate(self, token):
        if self.error:
            raise self.error
        assert token == "valid-token"
        return self.principal


def _principal(*, implementer=True):
    permissions = {"indicator_detail_view"}
    if implementer:
        permissions.add("indicator_detail_export")
    return HospitalPrincipal(
        user_id="u1",
        account_id="a1",
        hospital_id="h1",
        permissions=frozenset(permissions),
        must_change_password=False,
        session_id="s1",
    )


def _request():
    return ChatRequest(query="急会诊及时到位率怎么算？", hospital_id="h1")


def test_shadow_schedules_only_authenticated_implementation_account() -> None:
    background = FakeBackgroundTasks()
    with (
        patch("app.api.main.get_bool", return_value=True),
        patch("app.api.main.get", return_value="shadow"),
        patch(
            "app.api.main.get_hospital_auth_service",
            return_value=FakeAuthService(_principal()),
        ),
    ):
        _schedule_agent_shadow(
            background,
            _request(),
            "Bearer valid-token",
            {"rule_id": "MQSI2025_005"},
            "REQ_001",
        )

    assert len(background.tasks) == 1
    _, _, kwargs = background.tasks[0]
    assert kwargs["principal"].hospital_id == "h1"
    assert kwargs["legacy_result"] == {"rule_id": "MQSI2025_005"}


def test_shadow_is_not_scheduled_for_legacy_doctor_or_anonymous_requests() -> None:
    scenarios = [
        (False, "shadow", _principal(), "Bearer valid-token"),
        (True, "legacy", _principal(), "Bearer valid-token"),
        (True, "shadow", _principal(implementer=False), "Bearer valid-token"),
        (True, "shadow", _principal(), None),
    ]
    for enabled, mode, principal, authorization in scenarios:
        background = FakeBackgroundTasks()
        with (
            patch("app.api.main.get_bool", return_value=enabled),
            patch("app.api.main.get", return_value=mode),
            patch(
                "app.api.main.get_hospital_auth_service",
                return_value=FakeAuthService(principal),
            ),
        ):
            _schedule_agent_shadow(
                background,
                _request(),
                authorization,
                {},
                "REQ_001",
            )
        assert background.tasks == []
