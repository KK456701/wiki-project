from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.db.engine import create_runtime_engine
from app.db.migrations import (
    ensure_diagnose_report_schema,
    ensure_hospital_auth_schema,
    ensure_indicator_detail_schema,
    ensure_kb_exchange_schema,
    ensure_monitoring_schema,
    ensure_rule_lineage_schema,
    ensure_terminology_schema,
)


def main() -> int:
    engine = create_runtime_engine()
    added = ensure_diagnose_report_schema(engine)
    monitoring = ensure_monitoring_schema(engine)
    terminology = ensure_terminology_schema(engine)
    rule_lineage = ensure_rule_lineage_schema(engine)
    hospital_auth = ensure_hospital_auth_schema(engine)
    indicator_details = ensure_indicator_detail_schema(engine)
    kb_exchange = ensure_kb_exchange_schema(engine)
    print(
        json.dumps(
            {
                "status": "success",
                "migration": "diagnose_report_schema",
                "added_columns": added,
                "monitoring": monitoring,
                "terminology": terminology,
                "rule_lineage": rule_lineage,
                "hospital_auth": hospital_auth,
                "indicator_details": indicator_details,
                "kb_exchange": kb_exchange,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
