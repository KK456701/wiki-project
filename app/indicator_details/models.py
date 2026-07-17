from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class RunContext(BaseModel):
    """试运行时固化的非患者级口径上下文。"""

    model_config = ConfigDict(extra="forbid")

    schema_version: Literal[1] = 1
    rule_id: str = ""
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
    dialect: str = "mysql"
    query_profile: str = ""
    execution_context: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="before")
    @classmethod
    def normalize_agent_sql_snapshot(cls, value: Any) -> Any:
        """兼容 Agent SQL 对象保存的嵌套上下文快照。"""
        if not isinstance(value, dict) or "effective_rule" not in value:
            return value
        effective_rule = dict(value.get("effective_rule") or {})
        field_mapping = dict(value.get("field_mapping") or {})
        stat_start = str(value.get("stat_start") or "")
        stat_end = str(value.get("stat_end") or "")
        params = dict(value.get("params") or {})
        params.setdefault("start_time", stat_start)
        params.setdefault("end_time", stat_end)
        hospital_id = str(field_mapping.get("hospital_id") or "")
        if hospital_id:
            params.setdefault("hospital_id", hospital_id)
        return {
            "schema_version": 1,
            "rule_id": str(effective_rule.get("rule_id") or ""),
            "rule_name": str(effective_rule.get("rule_name") or ""),
            "effective_level": str(
                effective_rule.get("effective_level") or "national"
            ),
            "national_version": effective_rule.get("national_version"),
            "hospital_version": effective_rule.get("hospital_version"),
            "calculation_definition": dict(
                effective_rule.get("calculation_definition")
                or effective_rule.get("national_calculation_definition")
                or {}
            ),
            "field_mapping": field_mapping,
            "params": params,
            "stat_start": stat_start,
            "stat_end": stat_end,
            "db_source": str(
                field_mapping.get("db_name")
                or value.get("db_source_id")
                or ""
            ),
            "main_table": str(field_mapping.get("main_table") or ""),
            "dialect": str(field_mapping.get("dialect") or "mysql"),
            "query_profile": str(field_mapping.get("query_profile") or ""),
            "execution_context": dict(value.get("execution_context") or {}),
        }


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
