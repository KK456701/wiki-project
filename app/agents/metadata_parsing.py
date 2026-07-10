"""Metadata synchronization and field-precheck boundary."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

from app.metadata.precheck import precheck_rule_fields
from app.metadata.sync import sync_metadata_from_provider


class MetadataParsingAgent:
    agent_id = "metadata_parsing"

    def __init__(
        self,
        runtime_engine: Any,
        kb_root: str | Path,
        sync_fn: Callable[..., dict[str, Any]] = sync_metadata_from_provider,
        precheck_fn: Callable[..., dict[str, Any]] = precheck_rule_fields,
    ):
        self.runtime_engine = runtime_engine
        self.kb_root = Path(kb_root)
        self._sync = sync_fn
        self._precheck = precheck_fn

    def sync(self, provider: Any, hospital_id: str, db_name: str) -> dict[str, Any]:
        return self._sync(
            runtime_engine=self.runtime_engine,
            provider=provider,
            hospital_id=hospital_id,
            db_name=db_name,
            kb_root=self.kb_root,
        )

    def precheck(self, hospital_id: str, rule_id: str) -> dict[str, Any]:
        return self._precheck(
            self.kb_root,
            self.runtime_engine,
            hospital_id,
            rule_id,
        )
