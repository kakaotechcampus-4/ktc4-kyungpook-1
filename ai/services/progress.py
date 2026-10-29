"""오래 걸리는 작업이 단계별 진행을 알리는 곳. 듣는 쪽이 없으면 아무것도 하지 않는다."""

from __future__ import annotations

from collections.abc import Callable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any

from schemas.collection import CollectedCommit
from services.secret_redaction import redact_secrets

#: 단계 이름은 Spring 분석 Job 의 단계 키(JobStepKey)와 같다.
PULL_REQUESTS = "PR_REVIEW"
COMMITS = "COMMITS"
COMPRESS = "COMPRESS"
REASON = "REASON"

# 화면이 한 줄로 보여 주는 값이라 길이를 자른다.
TITLE_MAX_CHARS = 100
REASON_MAX_CHARS = 200

ProgressItem = dict[str, Any]
ProgressListener = Callable[[str, int, "int | None", "ProgressItem | None"], None]

# 서비스 함수의 인자를 늘리지 않고, 스트리밍 API 가 만든 작업 안에서만 진행이 흐르게 한다.
_listener: ContextVar[ProgressListener | None] = ContextVar("progress_listener", default=None)


def report_progress(step: str, done: int, total: int | None, item: ProgressItem | None = None) -> None:
    """지금 작업을 스트리밍 API 가 듣고 있으면 단계·처리한 수·전체 수와, 있으면 방금 읽거나 판단한 것 하나를 알린다."""
    listener = _listener.get()
    if listener is not None:
        listener(step, done, total, item)


@contextmanager
def listening(listener: ProgressListener) -> Iterator[None]:
    """이 안에서 만든 작업(asyncio task)이 알리는 진행을 listener 로 받는다."""
    token = _listener.set(listener)
    try:
        yield
    finally:
        _listener.reset(token)


def pull_request_item(number: int, title: str, reviews: int) -> ProgressItem:
    return {"kind": "PULL_REQUEST", "number": number, "title": _text(title, TITLE_MAX_CHARS), "reviews": reviews}


def commit_item(commit: CollectedCommit) -> ProgressItem:
    lines = commit.message.strip().splitlines()
    return {
        "kind": "COMMIT",
        "sha": commit.sha[:7],
        "title": _text(lines[0] if lines else "", TITLE_MAX_CHARS),
        "files": commit.changed_files,
        "excluded": None if commit.exclusion_reason is None else commit.exclusion_reason.value,
    }


def grouped_item(units: int, experiences: int) -> ProgressItem:
    return {"kind": "GROUPED", "units": units, "experiences": experiences}


def described_item(title: str, reason: str) -> ProgressItem:
    return {"kind": "DESCRIBED", "title": _text(title, TITLE_MAX_CHARS), "reason": _text(reason, REASON_MAX_CHARS)}


def _text(text: str, limit: int) -> str:
    # 자른 뒤에 가리면 잘린 비밀값이 모양을 잃어 가려지지 않으므로 먼저 가린다
    return redact_secrets(text)[:limit]
