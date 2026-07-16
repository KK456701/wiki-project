from app.agent_runtime.model_adapter import AgentModelError
from app.llm.model_registry import ModelRegistry


def test_registry_falls_back_to_legacy_ollama_config() -> None:
    registry = ModelRegistry({
        "agent_model": "qwen3:4B-instruct",
        "ollama_base_url": "http://ollama.local",
    })

    models = registry.list_models()

    assert [model.id for model in models] == ["qwen3:4B-instruct"]
    assert models[0].provider == "ollama"
    assert models[0].base_url == "http://ollama.local"


def test_registry_lists_models_and_resolves_env_api_key(monkeypatch) -> None:
    monkeypatch.setenv("DEEPSEEK_API_KEY", "secret-key")
    registry = ModelRegistry({
        "default_model": "deepseek-v4-pro",
        "models": [
            {
                "id": "ollama-qwen3",
                "name": "Qwen3 4B",
                "provider": "ollama",
                "model": "qwen3:4B-instruct",
                "base_url": "http://127.0.0.1:11434",
            },
            {
                "id": "deepseek-v4-pro",
                "name": "DeepSeek V4 Pro",
                "provider": "openai",
                "model": "deepseek-v4-pro",
                "base_url": "https://api.deepseek.com",
                "api_key": "${DEEPSEEK_API_KEY}",
            },
        ],
    })

    public = [model.model_dump_public() for model in registry.list_models()]

    assert registry.default_model_id == "deepseek-v4-pro"
    assert public == [
        {"id": "ollama-qwen3", "name": "Qwen3 4B", "provider": "ollama"},
        {"id": "deepseek-v4-pro", "name": "DeepSeek V4 Pro", "provider": "openai"},
    ]
    assert registry.get_model("deepseek-v4-pro").api_key == "secret-key"
    assert "secret-key" not in str(public)


def test_registry_rejects_missing_env_api_key(monkeypatch) -> None:
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    registry = ModelRegistry({
        "models": [{
            "id": "deepseek-v4-pro",
            "name": "DeepSeek V4 Pro",
            "provider": "openai",
            "model": "deepseek-v4-pro",
            "base_url": "https://api.deepseek.com",
            "api_key": "${DEEPSEEK_API_KEY}",
        }],
    })

    try:
        registry.build_adapter("deepseek-v4-pro")
    except AgentModelError as exc:
        assert "API Key" in str(exc)
        assert "DEEPSEEK_API_KEY" in str(exc)
    else:
        raise AssertionError("expected missing api key to fail")


def test_registry_rejects_unknown_model_id() -> None:
    registry = ModelRegistry({"agent_model": "qwen3:4B-instruct"})

    try:
        registry.get_model("missing")
    except AgentModelError as exc:
        assert "未知模型" in str(exc)
    else:
        raise AssertionError("expected unknown model to fail")
