from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.db.engine import create_runtime_engine
from app.hospital_auth.models import DETAIL_EXPORT_PERMISSION, DETAIL_VIEW_PERMISSION
from app.hospital_auth.repository import HospitalAuthRepository
from app.hospital_auth.schema import ensure_hospital_auth_schema
from app.hospital_auth.service import HospitalAuthService


def main() -> int:
    engine = create_runtime_engine()
    ensure_hospital_auth_schema(engine)
    service = HospitalAuthService(HospitalAuthRepository(engine))
    user = service.create_or_reset_local_user(
        account_id="user_001",
        hospital_id="hospital_001",
        password="123456",
        permissions={DETAIL_VIEW_PERMISSION, DETAIL_EXPORT_PERMISSION},
        must_change_password=True,
    )
    print(
        json.dumps(
            {
                "status": "success",
                "account_id": user["account_id"],
                "hospital_id": user["hospital_id"],
                "must_change_password": True,
                "permissions": [DETAIL_VIEW_PERMISSION, DETAIL_EXPORT_PERMISSION],
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
