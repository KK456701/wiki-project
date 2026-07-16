import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def _run_node(expression):
    script = f"""
const runtime = require('./web/agent-runtime.js');
const value = {expression};
process.stdout.write(JSON.stringify(value));
"""
    completed = subprocess.run(
        ["node", "-e", script],
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
    )
    return json.loads(completed.stdout)


def test_page_loads_runtime_assets_before_inline_chat_code() -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

    css = '<link rel="stylesheet" href="/static/agent-runtime.css'
    script = '<script src="/static/agent-runtime.js'
    assert css in html
    assert script in html
    assert html.index(script) < html.index("<script>")
    assert 'id="agentRuntimeMode"' in html


def test_mode_selection_requires_implementation_permission_and_capability() -> None:
    expression = """[
      runtime.selectMode(
        {enabled:true, mode:'tool_calling'},
        {role:'hospital', permissions:['indicator_detail_view','indicator_detail_export']}
      ),
      runtime.selectMode(
        {enabled:true, mode:'tool_calling'},
        {role:'hospital', permissions:['indicator_detail_view']}
      ),
      runtime.selectMode(
        {enabled:false, mode:'legacy'},
        {role:'hospital', permissions:['indicator_detail_export']}
      )
    ]"""

    assert _run_node(expression) == ["tool_calling", "legacy", "legacy"]


def test_public_event_projection_never_renders_arguments_or_result_data() -> None:
    event = _run_node("""runtime.projectEvent({
      event:'tool_result', trace_id:'TRACE_1', tool_name:'trial_run_indicator_sql',
      status:'success', code:'TRIAL_RUN_COMPLETED', message:'试运行完成',
      arguments:{sql_text:'SELECT patient_name'},
      result:{data:{patient_name:'不应返回'}}
    })""")

    assert event == {
        "event": "tool_result",
        "trace_id": "TRACE_1",
        "tool_name": "trial_run_indicator_sql",
        "status": "success",
        "code": "TRIAL_RUN_COMPLETED",
        "message": "试运行完成",
    }
    assert "SELECT" not in json.dumps(event, ensure_ascii=False)


def test_legacy_fallback_is_allowed_only_before_agent_start() -> None:
    assert _run_node("runtime.canFallbackToLegacy(503, false)") is True
    assert _run_node("runtime.canFallbackToLegacy(503, true)") is False
    assert _run_node("runtime.canFallbackToLegacy(500, false)") is False


def test_evidence_track_css_supports_mobile_focus_and_reduced_motion() -> None:
    css = (ROOT / "web" / "agent-runtime.css").read_text(encoding="utf-8")

    assert ".agent-evidence-track" in css
    assert ".agent-runtime-mode" in css
    assert ":focus-visible" in css
    assert "@media (max-width: 720px)" in css
    assert "prefers-reduced-motion: reduce" in css
