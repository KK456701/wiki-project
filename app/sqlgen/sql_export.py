"""Render database-client-friendly SQL without changing stored templates."""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal
from typing import Any


_PARAMETER_PATTERN = re.compile(r":([A-Za-z_][A-Za-z0-9_]*)")
_DATETIME_PARAMETER_PATTERN = re.compile(
    r"(?:^|_)(?:start|end|from|to)?_?time$|(?:^|_)(?:date|datetime)$",
    re.IGNORECASE,
)


def render_sqlserver_navicat_script(
    sql_text: str,
    params: dict[str, Any],
    *,
    database: str | None = None,
) -> str:
    """Return a SQL Server script that Navicat can execute directly."""

    names = list(dict.fromkeys(_PARAMETER_PATTERN.findall(sql_text)))
    missing = [name for name in names if name not in params]
    if missing:
        raise ValueError("Missing SQL parameters: " + ", ".join(missing))

    lines = ["-- Navicat / SQL Server 可直接执行版本"]
    if database:
        escaped_database = str(database).replace("]", "]]")
        lines.extend([f"USE [{escaped_database}];", ""])
    for name in names:
        value = params[name]
        lines.append(
            f"DECLARE @{name} {_sqlserver_type(name, value)} = "
            f"{_sqlserver_literal(name, value)};"
        )

    executable_sql = _PARAMETER_PATTERN.sub(
        lambda match: f"@{match.group(1)}", sql_text
    )
    if names:
        lines.append("")
    lines.append(executable_sql.strip())
    return "\n".join(lines)


def _sqlserver_type(name: str, value: Any) -> str:
    if isinstance(value, (datetime, date)) or _looks_like_datetime(name, value):
        return "DATETIME2"
    if isinstance(value, bool):
        return "BIT"
    if isinstance(value, int):
        return "BIGINT"
    if isinstance(value, (float, Decimal)):
        return "DECIMAL(38, 10)"
    return "NVARCHAR(MAX)"


def _sqlserver_literal(name: str, value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, datetime):
        return f"'{value.isoformat(sep=' ')}'"
    if isinstance(value, date):
        return f"'{value.isoformat()} 00:00:00'"
    if isinstance(value, (int, float, Decimal)):
        return str(value)
    escaped = str(value).replace("'", "''")
    prefix = "" if _looks_like_datetime(name, value) else "N"
    return f"{prefix}'{escaped}'"


def _looks_like_datetime(name: str, value: Any) -> bool:
    if not isinstance(value, str) or not _DATETIME_PARAMETER_PATTERN.search(name):
        return False
    return bool(
        re.fullmatch(
            r"\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?)?",
            value.strip(),
        )
    )
