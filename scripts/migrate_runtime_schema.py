from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.db.engine import create_runtime_engine
from app.db.migrations import ensure_diagnose_report_schema, ensure_monitoring_schema


def main() -> int:
    engine = create_runtime_engine()
    added = ensure_diagnose_report_schema(engine)
    monitoring = ensure_monitoring_schema(engine)
    print(
        json.dumps(
            {
                "status": "success",
                "migration": "diagnose_report_schema",
                "added_columns": added,
                "monitoring": monitoring,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
