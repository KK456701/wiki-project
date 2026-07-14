from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class RunContext(BaseModel):
    """试运行时固化的非患者级口径上下文。"""

    model_config = ConfigDict(extra="forbid")

    schema_version: Literal[1] = 1
    rule_name: str
    effective_level: str
    national_version: str | None = None
    hospital_version: int | None = None
    calculation_definition: dict[str, Any]
    field_mapping: dict[str, Any]
    params: dict[str, Any] = Field(default_factory=dict)
    stat_start: str
    stat_end: str
    db_source: str
    main_table: str


class DetailColumn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    field: str
    label: str
    sensitivity: Literal["none", "patient_id", "name", "phone", "id_card"] = "none"


class DetailQuery(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sql: str
    params: dict[str, Any]
    columns: list[DetailColumn]


class DetailFieldLineage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    field: str
    label: str
    kind: Literal["column", "derived"]
    sources: list[str] = Field(default_factory=list)
    explanation: str


class DetailSnapshotSummary(BaseModel):
    model_config = ConfigDict(extra="forbid")

    snapshot_id: str
    run_id: str
    hospital_id: str
    rule_id: str
    rule_name: str
    effective_level: str
    national_version: str | None = None
    hospital_version: int | None = None
    stat_start: str
    stat_end: str
    denominator_count: int
    numerator_count: int
    unmatched_count: int
    columns: list[DetailColumn]
    created_at: datetime
    expires_at: datetime
    reused: bool = False
    source_database: str = ""
    source_tables: list[str] = Field(default_factory=list)
    field_lineage: list[DetailFieldLineage] = Field(default_factory=list)


class DetailPage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    snapshot_id: str
    run_id: str
    group: Literal["denominator", "numerator", "unmatched"]
    page: int
    page_size: int
    total: int
    items: list[dict[str, Any]]


class ExportSummary(BaseModel):
    model_config = ConfigDict(extra="forbid")

    export_id: str
    run_id: str
    hospital_id: str
    rule_id: str
    file_name: str
    row_count: int
    status: str
    created_at: datetime
    expires_at: datetime
    download_count: int = 0


class CleanupResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    expired_snapshots: int = 0
    expired_exports: int = 0
    failed_paths: int = 0
