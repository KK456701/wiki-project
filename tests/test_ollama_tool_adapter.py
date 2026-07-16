import unittest


class FakeOllamaClient:
    def __init__(self, response=None, error=None):
        self.response = response
        self.error = error
        self.calls = []

    def chat(self, **kwargs):
        self.calls.append(kwargs)
        if self.error is not None:
            raise self.error
        return self.response


class OllamaToolCallingAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_adapter_parses_multiple_tool_calls_and_usage(self) -> None:
        from app.llm.ollama_tools import OllamaToolCallingAdapter

        client = FakeOllamaClient({
            "model": "qwen3:4b-instruct",
            "prompt_eval_count": 20,
            "eval_count": 8,
            "message": {
                "role": "assistant",
                "content": "",
                "tool_calls": [
                    {"function": {
                        "name": "search_indicator_rules",
                        "arguments": {"query": "急会诊"},
                    }},
                    {"function": {
                        "name": "get_effective_rule",
                        "arguments": "{\"rule_id\":\"MQSI2025_005\"}",
                    }},
                ],
            },
        })

        response = await OllamaToolCallingAdapter(client).chat(
            messages=[{"role": "user", "content": "急会诊怎么算"}],
            tools=[],
        )

        self.assertEqual(
            [call.name for call in response.tool_calls],
            ["search_indicator_rules", "get_effective_rule"],
        )
        self.assertEqual(response.tool_calls[1].arguments["rule_id"], "MQSI2025_005")
        self.assertEqual(response.usage, {"prompt_tokens": 20, "completion_tokens": 8})

    async def test_adapter_serializes_standard_assistant_and_tool_messages(self) -> None:
        from app.llm.ollama_tools import OllamaToolCallingAdapter

        client = FakeOllamaClient({
            "model": "qwen3:4b-instruct",
            "message": {"role": "assistant", "content": "结论", "tool_calls": []},
        })
        await OllamaToolCallingAdapter(client).chat(
            messages=[
                {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [{
                        "id": "call_1",
                        "name": "search_indicator_rules",
                        "arguments": {"query": "急会诊"},
                    }],
                },
                {
                    "role": "tool",
                    "tool_name": "search_indicator_rules",
                    "content": "{\"ok\":true}",
                },
            ],
            tools=[],
        )

        sent = client.calls[0]["messages"]
        self.assertEqual(sent[0]["tool_calls"][0]["function"]["name"], "search_indicator_rules")
        self.assertEqual(sent[1]["tool_name"], "search_indicator_rules")

    async def test_adapter_rejects_invalid_tool_arguments(self) -> None:
        from app.agent_runtime.model_adapter import AgentModelError
        from app.llm.ollama_tools import OllamaToolCallingAdapter

        client = FakeOllamaClient({
            "message": {
                "role": "assistant",
                "content": "",
                "tool_calls": [{"function": {
                    "name": "search_indicator_rules",
                    "arguments": "not-json",
                }}],
            },
        })
        with self.assertRaises(AgentModelError):
            await OllamaToolCallingAdapter(client).chat(messages=[], tools=[])

    async def test_adapter_rejects_malformed_tool_call_shape(self) -> None:
        from app.agent_runtime.model_adapter import AgentModelError
        from app.llm.ollama_tools import OllamaToolCallingAdapter

        client = FakeOllamaClient({
            "message": {"role": "assistant", "content": "", "tool_calls": ["bad"]},
        })
        with self.assertRaises(AgentModelError):
            await OllamaToolCallingAdapter(client).chat(messages=[], tools=[])

    async def test_adapter_hides_ollama_internal_error(self) -> None:
        from app.agent_runtime.model_adapter import AgentModelError
        from app.llm.ollama import OllamaError
        from app.llm.ollama_tools import OllamaToolCallingAdapter

        adapter = OllamaToolCallingAdapter(
            FakeOllamaClient(error=OllamaError("token=secret"))
        )
        with self.assertRaisesRegex(AgentModelError, "模型服务暂时不可用") as raised:
            await adapter.chat(messages=[], tools=[])
        self.assertNotIn("secret", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
