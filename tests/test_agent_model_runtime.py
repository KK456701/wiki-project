from app.agent_runtime.service import model_request_timeout
from app.llm.model_registry import ModelInfo


def test_model_request_timeout_prefers_model_override() -> None:
    thinking_model = ModelInfo(
        id="qwen8b",
        name="Qwen3 8B",
        provider="ollama",
        model="qwen3:8b",
        base_url="http://127.0.0.1:11434",
        request_timeout_seconds=300,
    )
    default_model = ModelInfo(
        id="qwen4b",
        name="Qwen3 4B",
        provider="ollama",
        model="qwen3:4b-instruct",
        base_url="http://127.0.0.1:11434",
    )

    assert model_request_timeout(thinking_model, 120) == 300
    assert model_request_timeout(default_model, 120) == 120
