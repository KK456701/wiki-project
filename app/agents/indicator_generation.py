"""Indicator SQL generation boundary."""

from __future__ import annotations

from typing import Any

from app.agents.contracts import SQLGenerationResult


class IndicatorGenerationAgent:
    agent_id = "indicator_generation"

    def __init__(self, sql_executor: Any):
        self.sql_executor = sql_executor

    def generate(self, **kwargs: Any) -> dict[str, Any]:
        return self.sql_executor.generate(**kwargs)

    def generate_contract(self, **kwargs: Any) -> SQLGenerationResult:
        return SQLGenerationResult.model_validate(
            self.sql_executor.generate(**kwargs)
        )
