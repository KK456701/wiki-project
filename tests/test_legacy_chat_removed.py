from pathlib import Path

from app.api.main import app


ROOT = Path(__file__).resolve().parents[1]


def test_legacy_chat_routes_are_not_registered() -> None:
    paths = {route.path for route in app.routes}

    assert "/api/chat" not in paths
    assert "/api/chat/stream" not in paths


def test_frontend_contains_only_the_agent_chat_path() -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
    runtime = (ROOT / "web" / "agent-runtime.js").read_text(encoding="utf-8")

    assert "streamLegacyChat" not in html
    assert 'fetch("/api/chat/stream"' not in html
    assert "稳定流程" not in html
    assert "canFallbackToLegacy" not in runtime
    assert 'mode: "legacy"' not in runtime


def test_obsolete_legacy_runtime_files_are_deleted() -> None:
    assert not (ROOT / "app" / "agent" / "graph.py").exists()
    assert not (ROOT / "app" / "agent_runtime" / "shadow.py").exists()
