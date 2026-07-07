from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(slots=True)
class QueryResult:
    rows: list[dict[str, Any]]
    row_count: int
    source: str
    tool_name: str
    duration_ms: int
