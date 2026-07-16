from pathlib import Path

import app.prompts as prompts


ROOT = Path(__file__).resolve().parents[1]


def test_all_production_llm_prompts_are_loadable_from_one_directory() -> None:
    names = {
        "agent_planner",
        "agent_planner_context",
        "agent_planner_repair",
        "agent_replanner",
        "agent_executor",
        "agent_executor_context",
        "agent_executor_step",
        "agent_executor_corrections",
        "indicator_draft_parser",
        "indicator_draft_repair",
        "diagnosis_evidence",
        "diagnosis_compose",
    }

    assert callable(getattr(prompts, "load_prompt", None))
    assert callable(getattr(prompts, "prompt_version", None))
    for name in names:
        path = ROOT / "app" / "prompts" / f"{name}.txt"
        assert path.is_file(), name
        assert prompts.load_prompt(name).strip(), name
        assert len(prompts.prompt_version(name)) == 12

    prompt_dir = ROOT / "app" / "prompts"
    catalog = (prompt_dir / "README.md").read_text(encoding="utf-8")
    assert "Planner" in catalog
    assert "Executor" in catalog
    assert "## 旧聊天流程" not in catalog
    assert not (prompt_dir / "intent.txt").exists()
    assert not (prompt_dir / "answer.txt").exists()
    assert not (prompt_dir / "legacy_chat_intent.txt").exists()
    assert not (prompt_dir / "legacy_chat_answer.txt").exists()
    assert not hasattr(prompts, "intent_prompt_system")
    assert not hasattr(prompts, "answer_prompt_template")


def test_prompt_formatter_preserves_json_braces_and_replaces_named_values() -> None:
    assert callable(getattr(prompts, "format_prompt", None))
    rendered = prompts.format_prompt(
        "agent_planner_repair",
        validation_error="字段错误",
    )

    assert "字段错误" in rendered
    assert "field" in rendered
