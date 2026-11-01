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


class _FakeStream:
    def __init__(self, completion=None, error: Exception | None = None, events: list | None = None) -> None:
        self._completion = completion
        self._error = error
        self._events = events or []

    def __enter__(self):
        return self

    def __iter__(self):
        return iter(self._events)

    def __exit__(self, *_):
        return False

    def get_final_completion(self):
        if self._error is not None:
            raise self._error
        return self._completion


def _client_streaming(stream: _FakeStream, calls: dict) -> StructuredLLMClient:
    client = StructuredLLMClient(LLMSettings(base_url="http://127.0.0.1:1", api_key="test", model="m"))

    def parse(**kwargs):
        calls["parse"] = kwargs
        raise AssertionError("상한이 게이트웨이 한도를 넘으면 스트리밍으로 보내야 한다")

    def open_stream(**kwargs):
        calls["stream"] = kwargs
        return stream

    client._client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(parse=parse, stream=open_stream)))
    return client


def test_budget_above_gateway_ceiling_is_streamed_with_usage() -> None:
    completion = SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(parsed=_Output(value="ok")))],
        usage=SimpleNamespace(prompt_tokens=7, completion_tokens=5),
    )
    calls: dict = {}

    result = _client_streaming(_FakeStream(completion), calls).call("system", {}, _Output, 6000)

    assert result.output.value == "ok"
    assert (result.prompt_tokens, result.completion_tokens) == (7, 5)
    assert calls["stream"]["max_completion_tokens"] == 6000
    assert calls["stream"]["stream_options"] == {"include_usage": True}
    assert "parse" not in calls


def test_streamed_partial_outputs_reach_on_partial_and_a_broken_listener_does_not_stop_the_call() -> None:
    completion = SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(parsed=_Output(value="ok")))],
        usage=SimpleNamespace(prompt_tokens=7, completion_tokens=5),
    )
    events = [
        SimpleNamespace(type="chunk", parsed=None),
        SimpleNamespace(type="content.delta", parsed={"value": "o"}),
        SimpleNamespace(type="content.delta", parsed=None),
        SimpleNamespace(type="content.delta", parsed={"value": "ok"}),
    ]
    seen: list[dict] = []

    def on_partial(parsed: dict) -> None:
        seen.append(parsed)
        raise RuntimeError("화면 쪽 고장")

    result = _client_streaming(_FakeStream(completion, events=events), {}).call(
        "system", {}, _Output, 6000, on_partial=on_partial
    )

    assert seen == [{"value": "o"}, {"value": "ok"}]
    assert result.output.value == "ok"


def test_budget_within_gateway_ceiling_is_not_streamed() -> None:
    result = _client_returning(_Output(value="ok")).call("system", {}, _Output, 2000)

    assert result.output.value == "ok"


def test_images_follow_the_json_input_as_image_parts_and_text_only_calls_stay_a_string() -> None:
    completion = SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(parsed=_Output(value="ok")))],
        usage=SimpleNamespace(prompt_tokens=7, completion_tokens=5),
    )
    calls: dict = {}
    client = _client_streaming(_FakeStream(completion), calls)

    client.call("system", {"n": 1}, _Output, 6000, images=["data:image/png;base64,AAAA"])

    user = calls["stream"]["messages"][1]["content"]
    assert user == [
        {"type": "text", "text": '{"n": 1}'},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,AAAA", "detail": "high"}},
    ]
    client.call("system", {"n": 1}, _Output, 6000)
    assert calls["stream"]["messages"][1]["content"] == '{"n": 1}'


def test_streamed_reply_cut_at_budget_is_too_long_error() -> None:
    from openai import LengthFinishReasonError

    from services.llm_structured_client import LLMStructuredOutputTooLongError

    error = LengthFinishReasonError(completion=SimpleNamespace(usage=None))

    with pytest.raises(LLMStructuredOutputTooLongError):
        _client_streaming(_FakeStream(error=error), {}).call("system", {}, _Output, 6000)


@pytest.mark.parametrize(
    "error",
    [
        httpx.RemoteProtocolError("peer closed connection without sending complete message body"),
        httpx.ReadTimeout("read timed out"),
    ],
    ids=lambda error: type(error).__name__,
)
def test_connection_cut_while_reading_stream_is_retryable(error: Exception) -> None:
    with pytest.raises(LLMStructuredCallError) as raised:
        _client_streaming(_FakeStream(error=error), {}).call("system", {}, _Output, 6000)

    assert type(raised.value) is LLMStructuredCallError

