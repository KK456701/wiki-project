from __future__ import annotations

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
