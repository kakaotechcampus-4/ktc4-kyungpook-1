"""OpenAI 호환 API로 구조화된 출력을 받는 공통 LLM 호출기.

B-2 판정기 외의 LLM 작업(diff 분석, STAR 초안, 질문 생성)이 같은 방식으로
모델을 호출하도록 연결 설정·오류 처리·사용량 수집을 한곳에 모은다.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Any, Generic, Literal, Optional, TypeVar

from openai import (
    APIConnectionError,
    APIError,
    APITimeoutError,
    ContentFilterFinishReasonError,
    LengthFinishReasonError,
    OpenAI,
    RateLimitError,
)
from pydantic import BaseModel, ValidationError

ReasoningEffort = Literal["none", "low", "medium", "high"]
OutputT = TypeVar("OutputT", bound=BaseModel)


class LLMStructuredCallError(RuntimeError):
    """모델 응답을 구조화된 결과로 사용할 수 없을 때 발생하는 공통 오류."""


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
    ) -> StructuredCallResult[OutputT]:
        """입력은 지시가 아닌 데이터로 격리하기 위해 JSON 문자열로만 전달한다."""
        started_at = time.perf_counter()
        try:
            # GPT-5.6 계열은 temperature=0을 거절하므로 모든 모델에서 기본값을 쓴다.
            completion = self._client.chat.completions.parse(
                model=self._settings.model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
                ],
                response_format=output_model,
                max_completion_tokens=max_completion_tokens,
                reasoning_effort=self._settings.reasoning_effort,
            )
        except RateLimitError as exc:
            raise LLMStructuredCallError("LLM 호출 한도를 초과했습니다.") from exc
        except (APIConnectionError, APITimeoutError, APIError) as exc:
            raise LLMStructuredCallError("LLM 요청을 처리하지 못했습니다.") from exc
        except (ValidationError, LengthFinishReasonError, ContentFilterFinishReasonError) as exc:
            raise LLMStructuredCallError("LLM이 유효한 구조화 출력을 반환하지 않았습니다.") from exc

        output = getattr(completion.choices[0].message, "parsed", None)
        if not isinstance(output, output_model):
            raise LLMStructuredCallError("LLM이 유효한 구조화 출력을 반환하지 않았습니다.")

        usage = getattr(completion, "usage", None)
        return StructuredCallResult(
            output=output,
            prompt_tokens=getattr(usage, "prompt_tokens", None),
            completion_tokens=getattr(usage, "completion_tokens", None),
            latency_ms=round((time.perf_counter() - started_at) * 1000),
        )

