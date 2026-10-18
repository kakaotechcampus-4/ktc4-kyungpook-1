"""공통 LLM 호출기가 재시도 가능한 오류를 구분하는지 검증한다."""

from __future__ import annotations

from types import SimpleNamespace

import httpx
import pytest
from openai import (
    APIConnectionError,
    APITimeoutError,
    AuthenticationError,
    BadRequestError,
    NotFoundError,
    PermissionDeniedError,
    RateLimitError,
    UnprocessableEntityError,
)
from pydantic import BaseModel

from services.llm_structured_client import (
    LLMInvalidOutputError,
    LLMNonRetryableError,
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


_REQUEST = httpx.Request("POST", "http://127.0.0.1:1/chat/completions")


def _status_error(error_type: type[Exception], status: int) -> Exception:
    return error_type("error", response=httpx.Response(status, request=_REQUEST), body=None)


def _client_raising(error: Exception) -> StructuredLLMClient:
    client = StructuredLLMClient(LLMSettings(base_url="http://127.0.0.1:1", api_key="test", model="m"))

    def parse(**_):
        raise error

    client._client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(parse=parse)))
    return client


@pytest.mark.parametrize(
    "error",
    [
        _status_error(AuthenticationError, 401),
        _status_error(PermissionDeniedError, 403),
        _status_error(NotFoundError, 404),
        _status_error(BadRequestError, 400),
        _status_error(UnprocessableEntityError, 422),
    ],
    ids=lambda error: type(error).__name__,
)
def test_key_permission_and_request_errors_are_non_retryable(error: Exception) -> None:
    with pytest.raises(LLMNonRetryableError):
        _client_raising(error).call("system", {}, _Output, 100)


@pytest.mark.parametrize(
    "error",
    [
        _status_error(RateLimitError, 429),
        APIConnectionError(request=_REQUEST),
        APITimeoutError(request=_REQUEST),
    ],
    ids=lambda error: type(error).__name__,
)
def test_limit_and_connection_errors_stay_retryable(error: Exception) -> None:
    with pytest.raises(LLMStructuredCallError) as raised:
        _client_raising(error).call("system", {}, _Output, 100)

    assert type(raised.value) is LLMStructuredCallError


def test_per_call_timeout_disables_sdk_retries() -> None:
    client = _client_returning(_Output(value="ok"))
    default_client = client._client
    options: list[dict] = []

    def with_options(**kwargs):
        options.append(kwargs)
        return default_client

    client._client = SimpleNamespace(with_options=with_options)

    client.call("system", {}, _Output, 100, timeout_seconds=12.5)

    assert options == [{"timeout": 12.5, "max_retries": 0}]
