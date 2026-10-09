"""공통 LLM 호출기가 재시도 가능한 오류를 구분하는지 검증한다."""

from __future__ import annotations

from types import SimpleNamespace

import pytest
from pydantic import BaseModel

from services.llm_structured_client import (
    LLMInvalidOutputError,
    LLMSettings,
    LLMStructuredCallError,
    StructuredLLMClient,
)


class _Output(BaseModel):
    value: str


def _client_returning(parsed) -> StructuredLLMClient:
    client = StructuredLLMClient(LLMSettings(base_url="http://127.0.0.1:1", api_key="test", model="m"))
    completion = SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(parsed=parsed))],
        usage=SimpleNamespace(prompt_tokens=3, completion_tokens=2),
    )
    client._client = SimpleNamespace(
        chat=SimpleNamespace(completions=SimpleNamespace(parse=lambda **_: completion))
    )
    return client


def test_unparsed_output_is_retryable_invalid_output() -> None:
    with pytest.raises(LLMInvalidOutputError):
        _client_returning(None).call("system", {}, _Output, 100)


def test_invalid_output_is_still_a_structured_call_error() -> None:
    assert issubclass(LLMInvalidOutputError, LLMStructuredCallError)


def test_parsed_output_is_returned_with_usage() -> None:
    result = _client_returning(_Output(value="ok")).call("system", {}, _Output, 100)

    assert result.output.value == "ok"
    assert (result.prompt_tokens, result.completion_tokens) == (3, 2)
