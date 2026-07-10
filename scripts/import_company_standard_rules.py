from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.db.engine import create_company_engine
from app.kb.company_importer import import_company_standard_rules
from app.kb.tools import DEFAULT_KB_ROOT


def main() -> int:
    result = import_company_standard_rules(create_company_engine(), DEFAULT_KB_ROOT)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if result["failed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
