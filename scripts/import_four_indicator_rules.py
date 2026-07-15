from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.db.engine import create_runtime_engine
from app.business_source import current_business_source
from app.config import get
from app.kb.tools import DEFAULT_KB_ROOT
from app.rules.importer import import_four_indicator_rules


def main() -> int:
    source = current_business_source()
    options = {
        "business_source_id": source.source_id,
        "business_dialect": source.dialect,
    }
    if source.dialect == "sqlserver":
        options.update(
            {
                "hospital_scope_value": int(
                    get("business_db_hospital_scope_value", "0")
                ),
                "urgent_level_code": int(
                    get("business_db_urgent_consult_level_code", "0")
                ),
                "transfer_department_code": int(
                    get("business_db_transfer_department_code", "0")
                ),
                "transfer_ward_code": int(
                    get("business_db_transfer_ward_code", "0")
                ),
                "icu_org_ids_csv": str(get("business_db_icu_org_ids", "")),
            }
        )
    result = import_four_indicator_rules(
        create_runtime_engine(), DEFAULT_KB_ROOT, **options
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if result["failed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
