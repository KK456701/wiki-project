from __future__ import annotations

import argparse
import asyncio
from datetime import datetime
import json
from pathlib import Path
import sys
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.agent_planning import ModelRequestPlanner  # noqa: E402
from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext  # noqa: E402
from app.agent_runtime.runner import _split_compound_indicator_query  # noqa: E402
from app.llm.model_registry import get_model_registry  # noqa: E402


def _load_cases() -> list[dict[str, Any]]:
    payload = yaml.safe_load((ROOT / "evals" / "cases.yaml").read_text(encoding="utf-8"))
    return list(payload.get("cases") or [])


def _score(
    plan: dict[str, Any],
    expected: dict[str, Any],
    state: AgentRunState,
) -> dict[str, Any]:
    checks: dict[str, bool] = {}
    if expected.get("intent"):
        checks["intent"] = plan.get("intent") == expected["intent"]
    outputs = set(plan.get("requested_outputs") or [])
    if expected.get("outputs"):
        checks["outputs"] = set(expected["outputs"]).issubset(outputs)
    if expected.get("forbidden_outputs"):
        checks["forbidden_outputs"] = not bool(set(expected["forbidden_outputs"]) & outputs)
    if expected.get("indicator_contains"):
        raw_name = str((plan.get("target_indicator") or {}).get("raw_name") or "")
        checks["indicator_contains"] = expected["indicator_contains"] in raw_name.replace(" ", "")
    if expected.get("time_required"):
        checks["time_required"] = bool((plan.get("time_expression") or {}).get("raw_text"))
    if expected.get("constraint"):
        checks["constraint"] = expected["constraint"] in set(plan.get("constraints") or [])
    if expected.get("reuse_rule_id"):
        checks["reuse_rule_id"] = (
            (plan.get("target_indicator") or {}).get("rule_id")
            == state.current_rule_id
        )
    if expected.get("security_safe"):
        checks["security_safe"] = not bool(
            {"prepared_sql_handle", "trial_result"} & outputs
        )
    return {"passed": all(checks.values()) if checks else True, "checks": checks}


async def _run(models: list[str]) -> dict[str, Any]:
    registry = get_model_registry()
    cases = _load_cases()
    matrix: dict[str, Any] = {"generated_at": datetime.now().isoformat(), "models": {}}
    for model_id in models:
        planner = ModelRequestPlanner(registry.build_adapter(model_id, role="planner"))
        results = []
        for case in cases:
            if case.get("expected", {}).get("compound"):
                subqueries = _split_compound_indicator_query(case["query"])
                expected_count = int(case["expected"].get("indicator_count") or 0)
                results.append({
                    "id": case["id"],
                    "passed": len(subqueries) == expected_count,
                    "checks": {"indicator_count": len(subqueries) == expected_count},
                    "subqueries": subqueries,
                    "execution": "deterministic_server_splitter",
                })
                continue
            state = AgentRunState.model_validate(case.get("state") or {})
            context = AgentRuntimeContext(
                user_id="eval_user",
                hospital_id="hospital_001",
                session_id=f"eval_{case['id']}",
                user_role="hospital_user",
                permissions=frozenset(),
                request_id=f"eval_{case['id']}",
                trace_id=f"eval_{case['id']}",
            )
            try:
                plan = await planner.plan(
                    query=case["query"],
                    context=context,
                    state=state,
                    now=datetime.now(),
                )
                dumped = plan.model_dump(mode="json")
                results.append({
                    "id": case["id"],
                    "plan": dumped,
                    **_score(dumped, case.get("expected") or {}, state),
                })
            except Exception as exc:
                results.append({"id": case["id"], "passed": False, "error": str(exc)})
        scored = [item for item in results if "passed" in item]
        matrix["models"][model_id] = {
            "passed": sum(1 for item in scored if item["passed"]),
            "total": len(scored),
            "cases": results,
        }
    return matrix


def main() -> int:
    parser = argparse.ArgumentParser(description="按需运行 Agent Planner 模型矩阵")
    parser.add_argument("--models", nargs="+", required=True, help="config.yaml 中的模型 ID")
    parser.add_argument("--output", help="结果 JSON 路径")
    args = parser.parse_args()
    result = asyncio.run(_run(args.models))
    output = Path(args.output) if args.output else ROOT / "evals" / "results" / f"matrix-{datetime.now():%Y%m%d-%H%M%S}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
