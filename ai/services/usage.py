"""요청 하나가 LLM 을 몇 번 부르고 토큰을 얼마나 썼는지 모은다.

요청마다 미들웨어가 빈 기록을 열고(``metering``), 공통 LLM 호출기가 그 기록에 더하며, 각 API 는 응답을
만들 때 ``usage_meta`` 로 읽는다. LLM 호출은 실행기 스레드에서 돌지만 ``run_llm_call`` 이 요청의 문맥을
넘기므로 같은 기록에 더해진다.
"""

from __future__ import annotations

import threading
import time
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Iterator, Optional

from schemas.common import EndedBy, Meta, ModelUsage


class CallUsage:
    """한 요청의 LLM 호출 기록으로, 동시에 부른 호출(diff 근거 3개 등)이 함께 더하므로 잠그고 더한다.

    쓰기와 검토에 다른 모델을 쓰면 합계만으로는 모델별 비용을 알 수 없어서 모델마다 따로도 센다.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.model: Optional[str] = None
        self.llm_calls = 0
        self.input_tokens = 0
        self.output_tokens = 0
        self.by_model: dict[str, ModelUsage] = {}

    def add_llm_call(self, model: str, input_tokens: Optional[int], output_tokens: Optional[int]) -> None:
        with self._lock:
            self.model = model
            self.llm_calls += 1
            self.input_tokens += input_tokens or 0
            self.output_tokens += output_tokens or 0
            entry = self.by_model.setdefault(model, ModelUsage(model=model))
            entry.llm_calls += 1
            entry.input_tokens += input_tokens or 0
            entry.output_tokens += output_tokens or 0


_current: ContextVar[Optional[CallUsage]] = ContextVar("call_usage", default=None)


@contextmanager
def metering() -> Iterator[CallUsage]:
    """이 안에서 부른 LLM 호출을 새 기록 하나에 모은다."""
    usage = CallUsage()
    token = _current.set(usage)
    try:
        yield usage
    finally:
        _current.reset(token)


def record_llm_call(model: str, input_tokens: Optional[int], output_tokens: Optional[int]) -> None:
    """열린 기록이 없으면(시험·스크립트가 서비스를 바로 부를 때) 아무것도 하지 않는다."""
    usage = _current.get()
    if usage is not None:
        usage.add_llm_call(model, input_tokens, output_tokens)


def usage_meta(
    started: Optional[float],
    ended_by: EndedBy,
    *,
    github_calls: int = 0,
    model: Optional[str] = None,
) -> Meta:
    """지금 요청의 기록으로 응답 meta 를 만들고, model 은 LLM 을 쓰지 않은 응답에 대신 적을 이름(임베딩 모델 등)이다."""
    usage = _current.get() or CallUsage()
    # 마감으로 버려진 호출이 늦게 끝나 더하는 사이에 읽으면 합계와 모델별 목록이 어긋나서 잠근 채 한 번에 읽는다
    with usage._lock:
        return Meta(
            model=usage.model or model,
            model_usage=[entry.model_copy() for entry in usage.by_model.values()],
            llm_calls=usage.llm_calls,
            input_tokens=usage.input_tokens,
            output_tokens=usage.output_tokens,
            github_calls=github_calls,
            ended_by=ended_by,
            processing_ms=0 if started is None else int((time.perf_counter() - started) * 1000),
        )
