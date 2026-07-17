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
    assert 'id="agentRuntimeMode"' not in html
    assert 'id="modelSelector"' in html
    assert (
        '/static/agent-runtime.js?v=20260717-upload-file-session'
        in html
    )


def test_runtime_exports_only_the_agent_chat_mode() -> None:
    expression = """[
      typeof runtime.streamAgent,
      typeof runtime.isAvailable,
      typeof runtime.selectMode,
      typeof runtime.canFallbackToLegacy
    ]"""

    assert _run_node(expression) == ["function", "function", "undefined", "undefined"]


def test_model_selector_payload_uses_selected_model() -> None:
    expression = """[
      runtime.modelSelectorOptions({
        models: [
          {id:'ollama-qwen3', name:'Qwen3 4B', provider:'ollama'},
          {id:'deepseek-v4-pro', name:'DeepSeek V4 Pro', provider:'openai'}
        ],
        model: 'deepseek-v4-pro'
      }),
      runtime.buildChatPayload('查询指标', 's1', 'deepseek-v4-pro')
    ]"""

    assert _run_node(expression) == [
        [
            {"value": "ollama-qwen3", "label": "Qwen3 4B", "selected": False},
            {"value": "deepseek-v4-pro", "label": "DeepSeek V4 Pro", "selected": True},
        ],
        {"query": "查询指标", "session_id": "s1", "model_id": "deepseek-v4-pro"},
    ]


def test_chat_payload_carries_latest_uploaded_file_key() -> None:
    expression = """[
      runtime.buildChatPayload(
        '分析刚上传的文件', 's1', 'ollama-qwen3',
        'hospital_001_85a68d23d925_无标题.xlsx'
      ),
      runtime.buildChatPayload('查询指标', 's1', 'ollama-qwen3', '')
    ]"""

    assert _run_node(expression) == [
        {
            "query": "分析刚上传的文件",
            "session_id": "s1",
            "model_id": "ollama-qwen3",
            "file_key": "hospital_001_85a68d23d925_无标题.xlsx",
        },
        {
            "query": "查询指标",
            "session_id": "s1",
            "model_id": "ollama-qwen3",
        },
    ]


def test_page_wires_latest_upload_to_chat_and_clears_it_for_new_session() -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

    assert 'var latestUploadedFileKey = "";' in html
    assert "latestUploadedFileKey = fileKey;" in html
    assert "fileKey: latestUploadedFileKey," in html
    new_session_handler = html[html.index(
        'newSessionButton.addEventListener("click", function() {'
    ):]
    assert 'latestUploadedFileKey = "";' in new_session_handler


def test_expired_login_has_a_specific_agent_error_message() -> None:
    assert _run_node("runtime.agentRequestErrorMessage(401)") == "登录状态已失效，请重新登录。"


def test_public_event_projection_never_renders_arguments_or_result_data() -> None:
    event = _run_node("""runtime.projectEvent({
      event:'tool_result', trace_id:'TRACE_1', tool_name:'trial_run_indicator_sql',
      status:'success', code:'TRIAL_RUN_COMPLETED', message:'试运行完成',
      reused:true,
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
        "reused": True,
    }
    assert "SELECT" not in json.dumps(event, ensure_ascii=False)


def test_public_event_projection_keeps_fallback_classification() -> None:
    event = _run_node("""runtime.projectEvent({
      event:'agent_done', trace_id:'TRACE_2', stop_reason:'need_clarification',
      fallback_category:'USER_CLARIFICATION', failure_code:'INDICATOR_AMBIGUOUS'
    })""")

    assert event["fallback_category"] == "USER_CLARIFICATION"
    assert event["failure_code"] == "INDICATOR_AMBIGUOUS"


def test_evidence_track_css_supports_mobile_focus_and_reduced_motion() -> None:
    css = (ROOT / "web" / "agent-runtime.css").read_text(encoding="utf-8")

    assert ".agent-evidence-track" in css
    assert ".agent-runtime-mode" not in css
    assert ":focus-visible" in css
    assert "@media (max-width: 720px)" in css
    assert "prefers-reduced-motion: reduce" in css


def test_agent_trace_uses_only_the_authenticated_run_endpoint() -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

    assert "attachTraceButton(ass, ass.traceId || latestTraceId)" in html
    assert '"/api/agent/runs/" + encodeURIComponent(traceId)' in html
    assert 'Authorization: "Bearer " + hospitalAuthToken' in html
    assert '"/api/traces/" + encodeURIComponent(traceId)' not in html


def test_agent_trace_button_is_attached_for_success_and_failure() -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

    done_block = html[html.index('if (event.event === "agent_done")'):]
    error_start = html.index('if (event.event === "agent_error")')
    error_end = html.index('if (event.event === "agent_done")', error_start)
    error_block = html[error_start:error_end]

    assert "attachTraceButton" in done_block
    assert "attachTraceButton" in error_block


def test_agent_trace_ui_distinguishes_node_types_and_full_detail_sections() -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
    css = (ROOT / "web" / "agent-runtime.css").read_text(encoding="utf-8")

    for node_type in ("llm", "code", "tool", "storage"):
        assert f"trace-type-{node_type}" in css
        assert node_type in html
    for title in ("输入参数", "输出参数", "数据处理", "节点配置"):
        assert title in html
    assert "node.node_name" in html
    assert "processing_data" in html
    assert "开发与排障" not in html
