"""Three-layer root-cause diagnosis boundary."""

from __future__ import annotations

from typing import Any


class RootCauseDiagnosisAgent:
    agent_id = "root_cause_diagnosis"

    def __init__(self, diagnose_executor: Any):
        self.diagnose_executor = diagnose_executor

    def run(self, **kwargs: Any) -> dict[str, Any]:
        return self.diagnose_executor.run(**kwargs)
