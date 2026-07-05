from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.kb.tools import DEFAULT_KB_ROOT, KnowledgeBaseTools


def main() -> None:
    result = KnowledgeBaseTools(DEFAULT_KB_ROOT).rebuild_runtime_indexes()
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
