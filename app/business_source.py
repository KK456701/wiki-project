"""Formal hospital business database settings.

The runtime/rule database is intentionally separate.  This module only
describes the read-only clinical business source exposed by DBHub.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.config import get


@dataclass(frozen=True)
class BusinessSourceSettings:
    source_id: str
    dialect: str
    schema: str
    database_name: str


def current_business_source() -> BusinessSourceSettings:
    source_id = get("business_db_source_id", "win60_qa_991827").strip()
    dialect = get("business_db_dialect", "sqlserver").strip().lower()
    schema = get("business_db_schema", "WINDBA").strip()
    database_name = get("business_db_database", "WIN60_QA_991827").strip()
    return BusinessSourceSettings(
        source_id=source_id or "win60_qa_991827",
        dialect=dialect or "sqlserver",
        schema=schema if (dialect or "sqlserver") == "sqlserver" else "",
        database_name=database_name or "WIN60_QA_991827",
    )
