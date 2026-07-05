"""三层异常排查 Agent。"""

from pathlib import Path
from typing import Any

from sqlalchemy import Engine

from app.diagnose.structure_check import structure_check
from app.diagnose.rule_check import rule_check
from app.diagnose.data_check import data_check
from app.diagnose.report import build_report, save_report


class DiagnoseAgent:
    def __init__(self, kb_root: str | Path, runtime_engine: Engine, business_engine: Engine):
        self.kb_root = Path(kb_root)
        self.runtime_engine = runtime_engine
        self.business_engine = business_engine

    def run(self, hospital_id: str, rule_id: str,
            effective_rule: dict[str, Any], trigger: str = "manual") -> dict[str, Any]:
        layers = []

        # Layer 1
        r1 = structure_check(self.kb_root, self.runtime_engine, hospital_id, rule_id)
        layers.append(r1)
        if not r1["ok"]:
            report = build_report(layers)
            save_report(self.runtime_engine, hospital_id, rule_id, report, trigger)
            return {"ok": False, **report, "stopped_at_layer": 1}

        # Layer 2
        r2 = rule_check(effective_rule)
        layers.append(r2)
        if not r2["ok"]:
            report = build_report(layers)
            save_report(self.runtime_engine, hospital_id, rule_id, report, trigger)
            return {"ok": False, **report, "stopped_at_layer": 2}

        # Layer 3
        r3 = data_check(self.kb_root, self.business_engine, hospital_id, rule_id)
        layers.append(r3)

        report = build_report(layers)
        save_report(self.runtime_engine, hospital_id, rule_id, report, trigger)
        return {"ok": True, **report}
