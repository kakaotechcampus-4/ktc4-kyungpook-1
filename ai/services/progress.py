"""오래 걸리는 작업이 단계별 진행을 알리는 곳. 듣는 쪽이 없으면 아무것도 하지 않는다."""

from __future__ import annotations

from collections.abc import Callable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar

#: 단계 이름은 Spring 분석 Job 의 단계 키(JobStepKey)와 같다.
PULL_REQUESTS = "PR_REVIEW"
COMMITS = "COMMITS"
COMPRESS = "COMPRESS"
REASON = "REASON"

ProgressListener = Callable[[str, int, "int | None"], None]

# 서비스 함수의 인자를 늘리지 않고, 스트리밍 API 가 만든 작업 안에서만 진행이 흐르게 한다.
_listener: ContextVar[ProgressListener | None] = ContextVar("progress_listener", default=None)


def report_progress(step: str, done: int, total: int | None) -> None:
    """지금 작업을 스트리밍 API 가 듣고 있으면 단계·처리한 수·전체 수를 알린다."""
    listener = _listener.get()
    if listener is not None:
        listener(step, done, total)


@contextmanager
def listening(listener: ProgressListener) -> Iterator[None]:
    """이 안에서 만든 작업(asyncio task)이 알리는 진행을 listener 로 받는다."""
    token = _listener.set(listener)
    try:
        yield
    finally:
        _listener.reset(token)
