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
#: STAR 초안 진행은 Spring 카드 초안 Job 이 카드마다 받는다.
STAR = "STAR"
#: 자소서 카드 고르기·초안 쓰기 진행은 Spring 이 자소서마다 받는다.
COVER_LETTER = "COVER_LETTER"

# 화면이 한 줄로 보여 주는 값이라 길이를 자른다.
TITLE_MAX_CHARS = 100
REASON_MAX_CHARS = 200
SLOT_TEXT_MAX_CHARS = 400
# 자소서 문항 글자 수 상한(5,000자)과 같아, 쓰는 도중의 글을 자르지 않는다
DRAFT_TEXT_MAX_CHARS = 5_000

ProgressItem = dict[str, Any]
ProgressListener = Callable[[str, int, "int | None", "ProgressItem | None"], None]

# 서비스 함수의 인자를 늘리지 않고, 스트리밍 API 가 만든 작업 안에서만 진행이 흐르게 한다.
_listener: ContextVar[ProgressListener | None] = ContextVar("progress_listener", default=None)


def report_progress(step: str, done: int, total: int | None, item: ProgressItem | None = None) -> None:
    """지금 작업을 스트리밍 API 가 듣고 있으면 단계·처리한 수·전체 수와, 있으면 방금 읽거나 판단한 것 하나를 알린다."""
    listener = _listener.get()
    if listener is not None:
        listener(step, done, total, item)


def is_listening() -> bool:
    """듣는 쪽이 있을 때만 진행을 만들도록, 만드는 데 드는 일을 아낄 때 쓴다."""
    return _listener.get() is not None


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


def slot_item(round_no: int, slot: str, text: str, done: bool) -> ProgressItem:
    return {"kind": "SLOT", "round": round_no, "slot": slot, "text": _text(text, SLOT_TEXT_MAX_CHARS), "done": done}


def draft_text_item(question_id: int, round_no: int, text: str, done: bool) -> ProgressItem:
    return {"kind": "DRAFT_TEXT", "question_id": question_id, "round": round_no,
            "text": _text(text, DRAFT_TEXT_MAX_CHARS), "done": done}


def step_item(step: dict[str, Any]) -> ProgressItem:
    return {
        "kind": "STEP",
        "step": {key: _text(value, REASON_MAX_CHARS) if isinstance(value, str) else value for key, value in step.items()},
    }


def _text(text: str, limit: int) -> str:
    # 자른 뒤에 가리면 잘린 비밀값이 모양을 잃어 가려지지 않으므로 먼저 가린다
    return redact_secrets(text)[:limit]
