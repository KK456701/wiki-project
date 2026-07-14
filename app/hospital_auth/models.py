from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


DETAIL_VIEW_PERMISSION = "indicator_detail_view"
DETAIL_EXPORT_PERMISSION = "indicator_detail_export"


@dataclass(frozen=True)
class HospitalPrincipal:
    user_id: str
    account_id: str
    hospital_id: str
    permissions: frozenset[str]
    must_change_password: bool
    session_id: str

    def can_access_hospital(self, hospital_id: str) -> bool:
        return bool(hospital_id) and self.hospital_id == hospital_id


@dataclass(frozen=True)
class LoginResult:
    token: str
    expires_at: datetime
    user_id: str
    account_id: str
    hospital_id: str
    permissions: frozenset[str]
    must_change_password: bool
