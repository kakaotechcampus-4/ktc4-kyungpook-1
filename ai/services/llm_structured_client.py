"""OpenAI 호환 API로 구조화된 출력을 받는 공통 LLM 호출기.

B-2 판정기 외의 LLM 작업(diff 분석, STAR 초안, 질문 생성)이 같은 방식으로
모델을 호출하도록 연결 설정·오류 처리·사용량 수집을 한곳에 모은다.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Any, Generic, Literal, Optional, TypeVar

import httpx
from openai import (
    APIConnectionError,
    APIError,
    APITimeoutError,
    AuthenticationError,
    BadRequestError,
    ContentFilterFinishReasonError,
    LengthFinishReasonError,
    NotFoundError,
    OpenAI,
    PermissionDeniedError,
    RateLimitError,
    UnprocessableEntityError,
)
from pydantic import BaseModel, ValidationError

from services.usage import record_llm_call

ReasoningEffort = Literal["none", "low", "medium", "high"]
OutputT = TypeVar("OutputT", bound=BaseModel)

# 운영 게이트웨이는 스트리밍이 아닌 답을 이 토큰 수까지만 기다리고, 더 큰 상한은 400 으로 거절한다.
# 그보다 긴 답이 필요한 호출(공고 조건 뽑기)만 스트리밍으로 받는다.
NON_STREAMING_MAX_COMPLETION_TOKENS = 2_000


class LLMStructuredCallError(RuntimeError):
    """모델 응답을 구조화된 결과로 사용할 수 없을 때 발생하는 공통 오류."""


class LLMInvalidOutputError(LLMStructuredCallError):
    """응답이 스키마에 맞지 않았다. 같은 요청을 다시 보내면 나아질 수 있는 경우다.

    호출 한도·연결 오류는 SDK가 이미 재시도했고, 콘텐츠 필터는 다시 보내도 같은
    결과이므로 기본 오류로 남긴다.
    """


class LLMStructuredOutputTooLongError(LLMStructuredCallError):
    """응답 토큰 상한 때문에 구조화 출력이 끝까지 생성되지 못한 경우."""


class LLMNonRetryableError(LLMStructuredCallError):
    """다시 보내도 같은 결과인 실패. 키·권한·모델 이름·요청 형식 오류와 콘텐츠 필터."""


class LLMContentFilterError(LLMNonRetryableError):
    """모델 출력이 콘텐츠 제한으로 중단됐다. 같은 입력이면 다시 걸린다."""


@dataclass(frozen=True)
class LLMSettings:
    """한 모델을 호출하기 위한 연결 설정."""

    base_url: str
    api_key: str
    model: str
    reasoning_effort: ReasoningEffort = "medium"
    timeout_seconds: float = 120.0


@dataclass(frozen=True)
class StructuredCallResult(Generic[OutputT]):
    """파싱된 결과와 비용·지연 계산에 필요한 메타데이터."""

    output: OutputT
    prompt_tokens: Optional[int]
    completion_tokens: Optional[int]
    latency_ms: int


class StructuredLLMClient:
    """시스템 프롬프트와 JSON 입력을 보내 스키마에 맞는 결과를 받는다."""

    def __init__(self, settings: LLMSettings) -> None:
        self._settings = settings
        self._client = OpenAI(
            base_url=settings.base_url.rstrip("/"),
            api_key=settings.api_key,
            timeout=settings.timeout_seconds,
            max_retries=1,
        )

    def call(
        self,
        system_prompt: str,
        payload: dict[str, Any],
        output_model: type[OutputT],
        max_completion_tokens: int,
        timeout_seconds: Optional[float] = None,
    ) -> StructuredCallResult[OutputT]:
        """입력은 지시가 아닌 데이터로 격리하기 위해 JSON 문자열로만 전달한다.

        ``timeout_seconds``를 주면 요청 전체 마감에 맞춰 이 호출만 그 시간 안에 끝내고,
        SDK 재시도도 하지 않는다. 재시도까지 하면 마감을 넘기기 때문이다.
        실패한 호출도 요청의 LLM 호출 수에 세고, SDK 가 안에서 다시 보낸 요청은 따로 세지 않는다.
        """
        try:
            result = self._call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)
        except Exception:
            record_llm_call(self._settings.model, None, None)
            raise
        record_llm_call(self._settings.model, result.prompt_tokens, result.completion_tokens)
        return result

    def _call(
        self,
        system_prompt: str,
        payload: dict[str, Any],
        output_model: type[OutputT],
        max_completion_tokens: int,
        timeout_seconds: Optional[float],
    ) -> StructuredCallResult[OutputT]:
        started_at = time.perf_counter()
        client = (
            self._client
            if timeout_seconds is None
            else self._client.with_options(timeout=timeout_seconds, max_retries=0)
        )
        try:
            # GPT-5.6 계열은 temperature=0을 거절하므로 모든 모델에서 기본값을 쓴다.
            request = dict(
                model=self._settings.model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
                ],
                response_format=output_model,
                max_completion_tokens=max_completion_tokens,
                reasoning_effort=self._settings.reasoning_effort,
            )
            if max_completion_tokens <= NON_STREAMING_MAX_COMPLETION_TOKENS:
                completion = client.chat.completions.parse(**request)
            else:
                with client.chat.completions.stream(**request, stream_options={"include_usage": True}) as stream:
                    completion = stream.get_final_completion()
        except RateLimitError as exc:
            raise LLMStructuredCallError("LLM 호출 한도를 초과했습니다.") from exc
        except (
            AuthenticationError,
            PermissionDeniedError,
            NotFoundError,
            BadRequestError,
            UnprocessableEntityError,
        ) as exc:
            raise LLMNonRetryableError("LLM 키·권한·모델 이름 또는 요청 형식이 잘못됐습니다.") from exc
        except (APIConnectionError, APITimeoutError, APIError) as exc:
            raise LLMStructuredCallError("LLM 요청을 처리하지 못했습니다.") from exc
        except httpx.TransportError as exc:
            # SDK 는 스트림을 읽는 도중 끊긴 연결을 자기 오류로 감싸지 않아 여기서 다시 시도할 수 있는 실패로 바꾼다
            raise LLMStructuredCallError("LLM 응답을 받는 중 연결이 끊겼습니다.") from exc
        except ValidationError as exc:
            raise LLMInvalidOutputError("LLM이 유효한 구조화 출력을 반환하지 않았습니다.") from exc
        except LengthFinishReasonError as exc:
            raise LLMStructuredOutputTooLongError(
                "LLM 구조화 출력이 응답 토큰 상한을 초과했습니다."
            ) from exc
        except ContentFilterFinishReasonError as exc:
            raise LLMContentFilterError("LLM 출력이 콘텐츠 제한으로 중단됐습니다.") from exc

        output = getattr(completion.choices[0].message, "parsed", None)
        if not isinstance(output, output_model):
            raise LLMInvalidOutputError("LLM이 유효한 구조화 출력을 반환하지 않았습니다.")

        usage = getattr(completion, "usage", None)
        return StructuredCallResult(
            output=output,
            prompt_tokens=getattr(usage, "prompt_tokens", None),
            completion_tokens=getattr(usage, "completion_tokens", None),
            latency_ms=round((time.perf_counter() - started_at) * 1000),
        )
