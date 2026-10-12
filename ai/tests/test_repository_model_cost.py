import json

import httpx
from pydantic import BaseModel, ConfigDict

from scripts.evaluate_answer_sufficiency_models import ModelConfig
from scripts.measure_repository_model_cost import NativeClaudeStructuredClient


class SampleOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    value: str


def test_native_claude_cost_runner_uses_messages_tool_call_without_leaking_key() -> None:
    captured: dict = {}

    def handle(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["authorization"] = request.headers["authorization"]
        captured["x_api_key"] = request.headers["x-api-key"]
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "content": [
                    {
                        "type": "tool_use",
                        "name": "emit_structured_output",
                        "input": {"value": "ok"},
                    }
                ],
                "usage": {
                    "input_tokens": 10,
                    "cache_creation_input_tokens": 20,
                    "cache_read_input_tokens": 30,
                    "output_tokens": 4,
                },
            },
        )

    client = NativeClaudeStructuredClient(
        base_url="https://example.test/deployment/v1",
        api_key="secret-key",
        config=ModelConfig(
            name="Claude Sonnet 5",
            model="claude-sonnet-5",
            base_url_env="BASE_URL",
            api_key_env="API_KEY",
        ),
        http_client=httpx.Client(transport=httpx.MockTransport(handle)),
    )

    result = client.call("system", {"input": "data"}, SampleOutput, 2000)

    assert result.output == SampleOutput(value="ok")
    assert result.prompt_tokens == 60
    assert result.completion_tokens == 4
    assert captured["url"] == "https://example.test/deployment/v1/messages"
    assert captured["authorization"] == "Bearer secret-key"
    assert captured["x_api_key"] == "secret-key"
    assert captured["body"]["tool_choice"] == {
        "type": "tool",
        "name": "emit_structured_output",
    }
    # Elice Claude 네이티브 Messages API는 reasoning_effort를 거절한다.
    assert "reasoning_effort" not in captured["body"]


def test_native_claude_cost_runner_accepts_opus_arguments_wrapper() -> None:
    def handle(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "content": [
                    {
                        "type": "tool_use",
                        "name": "emit_structured_output",
                        "input": {"arguments": {"value": "ok"}},
                    }
                ],
                "usage": {"input_tokens": 10, "output_tokens": 4},
            },
        )

    client = NativeClaudeStructuredClient(
        base_url="https://example.test/deployment/v1",
        api_key="secret-key",
        config=ModelConfig(
            name="Claude Opus 5",
            model="claude-opus-5",
            base_url_env="BASE_URL",
            api_key_env="API_KEY",
        ),
        http_client=httpx.Client(transport=httpx.MockTransport(handle)),
    )

    result = client.call("system", {"input": "data"}, SampleOutput, 2000)

    assert result.output == SampleOutput(value="ok")
