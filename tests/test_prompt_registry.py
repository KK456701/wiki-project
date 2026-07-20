from pathlib import Path

import app.prompts as prompts


ROOT = Path(__file__).resolve().parents[1]


def test_all_production_llm_prompts_are_loadable_from_one_directory() -> None:
    names = {
        "agent_planner",
        "agent_planner_context",
        "agent_planner_repair",
        "agent_replanner",
        "agent_final_answer",
        "agent_final_answer_context",
        "agent_final_answer_step",
        "agent_final_answer_corrections",
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
    assert "Final Answer" in catalog
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


def test_final_answer_prompt_has_no_tool_authority() -> None:
    final_answer = prompts.load_prompt("agent_final_answer")
    step = prompts.load_prompt("agent_final_answer_step")

    assert "自主选择必要工具" not in final_answer
    assert "服务端已经完成工具调用" in final_answer
    assert "不要调用工具" in final_answer
    assert "当前阶段只负责生成最终回答" in step
