from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.db.engine import create_runtime_engine
from app.kb.tools import DEFAULT_KB_ROOT
from app.rules.importer import import_four_indicator_rules


def main() -> int:
    result = import_four_indicator_rules(create_runtime_engine(), DEFAULT_KB_ROOT)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if result["failed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
