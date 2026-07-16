"""生成 Agent 固定业务评测门槛报告。"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.agent_evaluation.contracts import EvaluationObservation
from app.agent_evaluation.runner import (
    build_reference_observations,
    evaluate_observations,
    load_evaluation_cases,
)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="运行 Agent 固定业务评测")
    parser.add_argument(
        "--dataset",
        default="evaluations/agent_business_cases.yaml",
    )
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument(
        "--reference",
        action="store_true",
        help="使用确定性契约替身，不访问 Ollama 或业务库",
    )
    source.add_argument(
        "--observations",
        help="读取真实探针或离线运行产生的 JSON 观测数组",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    cases = load_evaluation_cases(args.dataset)
    if args.reference:
        observations = build_reference_observations(cases)
    else:
        raw = json.loads(Path(args.observations).read_text(encoding="utf-8"))
        observations = [EvaluationObservation.model_validate(item) for item in raw]
    report = evaluate_observations(cases, observations)
    payload = report.model_dump(mode="json")
    payload.update({
        "dataset": str(args.dataset),
        "case_count": len(cases),
        "run_count": len(observations),
    })
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if report.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
