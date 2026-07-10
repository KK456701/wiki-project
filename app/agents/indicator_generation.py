"""Indicator SQL generation boundary."""

from __future__ import annotations

from typing import Any

from app.agents.contracts import SQLGenerationResult


class IndicatorGenerationAgent:
    agent_id = "indicator_generation"

    def __init__(
        self,
        sql_executor: Any,
        draft_parser: Any | None = None,
        draft_repository: Any | None = None,
        draft_sql_renderer: Any | None = None,
    ):
        self.sql_executor = sql_executor
        self.draft_parser = draft_parser
        self.draft_repository = draft_repository
        if draft_sql_renderer is None:
            from app.indicators.sql_plan import render_indicator_sql

            draft_sql_renderer = render_indicator_sql
        self.draft_sql_renderer = draft_sql_renderer

    def generate(self, **kwargs: Any) -> dict[str, Any]:
        return self.sql_executor.generate(**kwargs)

    def generate_contract(self, **kwargs: Any) -> SQLGenerationResult:
        return SQLGenerationResult.model_validate(
            self.sql_executor.generate(**kwargs)
        )

    def create_draft(
        self, query: str, hospital_id: str, actor_id: str
    ) -> dict[str, Any]:
        if self.draft_parser is None or self.draft_repository is None:
            raise RuntimeError("指标设计稿能力尚未配置")
        spec = self.draft_parser.parse(query, hospital_id)
        result = self.draft_repository.create(spec, actor_id)
        return result if isinstance(result, dict) else result.model_dump(exclude_none=True)

    def render_draft_sql(
        self, plan: dict[str, Any], mappings: dict[str, dict[str, Any]]
    ) -> dict[str, Any]:
        return self.draft_sql_renderer(plan, mappings)
