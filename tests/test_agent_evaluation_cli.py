import json
import subprocess
import sys

from app.agent_evaluation.runner import (
    build_evaluation_run_plan,
    build_reference_observations,
    evaluate_observations,
    load_evaluation_cases,
)
from scripts.run_agent_evaluation import main


def test_reference_runner_covers_every_planned_run_and_passes(capsys) -> None:
    cases = load_evaluation_cases("evaluations/agent_business_cases.yaml")
    observations = build_reference_observations(cases)

    assert len(observations) == len(build_evaluation_run_plan(cases))
    assert evaluate_observations(cases, observations).passed

    exit_code = main(["--reference"])
    report = json.loads(capsys.readouterr().out)
    assert exit_code == 0
    assert report["passed"] is True
    assert report["run_count"] == len(observations)


def test_script_can_run_directly_from_repository_root() -> None:
    completed = subprocess.run(
        [sys.executable, "scripts/run_agent_evaluation.py", "--reference"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
    )

    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout)["passed"] is True
