"""자소서 카드 고르기·초안 쓰기의 단계와 쓰는 도중의 초안 글을 스트리밍 API 로 흘려보낸다. 듣는 쪽이 없으면 아무것도 하지 않는다.

글은 STAR 실시간과 같이 문장이 끝났을 때만, 문항마다 0.5초에 한 번까지 보내고, 다 쓰면 완성된 글을 한 번 더 보낸다.
"""

from __future__ import annotations

import math
import time
from collections.abc import Callable
from typing import Any, Optional

from pydantic import BaseModel

from services.progress import COVER_LETTER, draft_text_item, is_listening, report_progress, step_item
from services.star_progress import MIN_INTERVAL_SECONDS, complete_sentences


def report_step(step: BaseModel) -> None:
    """단계가 생기자마자 보내, 화면이 단계가 다 끝나기를 기다리지 않고 과정을 그리게 한다."""
    report_progress(COVER_LETTER, 0, None, step_item(step.model_dump()))


class DraftTextStreamer:
    """문항 하나를 쓰는 모델 호출 한 번의 부분 출력에서 글을 읽어 보낸다. 다시 쓸 때마다 새로 만든다."""

    def __init__(self, question_id: int, round_no: int, clock: Callable[[], float] = time.monotonic) -> None:
        self._question_id = question_id
        self._round = round_no
        self._clock = clock
        self._sent: Optional[str] = None
        self._sent_at = -math.inf

    def feed(self, partial: dict[str, Any]) -> None:
        text = partial.get("text")
        if not isinstance(text, str):
            return
        sentences = complete_sentences(text)
        now = self._clock()
        if not sentences or sentences == self._sent or now - self._sent_at < MIN_INTERVAL_SECONDS:
            return
        self._send(sentences, False, now)

    def finish(self, text: str) -> None:
        self._send(text, True, self._clock())

    def _send(self, text: str, done: bool, now: float) -> None:
        self._sent = text
        self._sent_at = now
        report_progress(COVER_LETTER, 0, None, draft_text_item(self._question_id, self._round, text, done))


def report_text(question_id: int, round_no: int, text: str) -> None:
    """고쳐 쓴 글을 버리고 앞 글로 되돌릴 때 앞 글을 다시 보내, 화면에 버린 글이 남지 않게 한다."""
    report_progress(COVER_LETTER, 0, None, draft_text_item(question_id, round_no, text, True))


def text_streamer(question_id: int, round_no: int) -> Optional[DraftTextStreamer]:
    """스트리밍 API 가 듣고 있을 때만 만들어, 보통 API 의 모델 호출에는 부분 출력을 받지 않게 한다."""
    return DraftTextStreamer(question_id, round_no) if is_listening() else None
