"""STAR 초안을 쓰는 동안 칸 글과 거친 단계를 스트리밍 API 로 흘려보낸다. 듣는 쪽이 없으면 아무것도 하지 않는다.

칸 글은 모델이 쓰는 도중의 글이라, 문장 중간에서 멈춘 모습이 화면에 남지 않도록 문장이 끝났을 때만 보낸다.
화면은 서버를 1~2초마다 다시 읽으므로 칸마다 0.5초에 한 번까지만 보내고, 칸이 끝나면 완성된 글을 한 번 더 보낸다.
"""

from __future__ import annotations

import math
import re
import time
from collections.abc import Callable, Iterable
from typing import Any

from schemas.analysis import StarStep
from services.progress import STAR, report_progress, slot_item, step_item

SLOTS = ("S", "T", "A", "R")
MIN_INTERVAL_SECONDS = 0.5

# 마침표·물음표·느낌표 뒤가 띄어쓰기이거나 글 끝이면 문장 끝으로 본다
_SENTENCE_END = re.compile(r"[.!?](?=\s|$)")


class SlotStreamer:
    """모델 호출 하나가 쓰는 칸들의 글을 부분 출력에서 읽어 보낸다. 호출을 다시 하면 새로 만든다."""

    def __init__(self, round_no: int, slots: Iterable[str], clock: Callable[[], float] = time.monotonic) -> None:
        self._round = round_no
        self._slots = [slot for slot in SLOTS if slot in set(slots)]
        self._clock = clock
        self._sent: dict[str, str] = {}
        self._sent_at: dict[str, float] = {}
        self._done: set[str] = set()

    def feed(self, partial: dict[str, Any]) -> None:
        """지금까지 받은 출력을 부분 파싱한 dict 로, 모델은 칸을 S·T·A·R 순서로 쓴다."""
        present = [slot for slot in self._slots if isinstance(partial.get(slot), dict)]
        for index, slot in enumerate(present):
            text = partial[slot].get("text")
            if not isinstance(text, str):
                continue
            # 다음 칸을 쓰기 시작했거나 이 칸의 근거 목록을 쓰기 시작했으면 이 칸의 글은 끝났다
            if index < len(present) - 1 or "evidence_shas" in partial[slot]:
                self._finish(slot, text)
            else:
                self._progress(slot, text)

    def finish(self, output: Any) -> None:
        """호출이 끝나 받은 출력으로, 아직 끝났다고 보내지 않은 칸의 완성된 글을 보낸다."""
        for slot in self._slots:
            self._finish(slot, getattr(output, slot).text or "")

    def _progress(self, slot: str, text: str) -> None:
        if slot in self._done:
            return
        sentences = complete_sentences(text)
        if not sentences or sentences == self._sent.get(slot):
            return
        now = self._clock()
        if now - self._sent_at.get(slot, -math.inf) < MIN_INTERVAL_SECONDS:
            return
        self._send(slot, sentences, False, now)

    def _finish(self, slot: str, text: str) -> None:
        if slot in self._done:
            return
        self._done.add(slot)
        self._send(slot, text, True, self._clock())

    def _send(self, slot: str, text: str, done: bool, now: float) -> None:
        self._sent[slot] = text
        self._sent_at[slot] = now
        report_progress(STAR, 0, None, slot_item(self._round, slot, text, done))


class ReportedSteps(list[StarStep]):
    """초안이 거친 단계를 더할 때마다, 다 끝나기를 기다리지 않고 곧바로 보낸다."""

    def append(self, step: StarStep) -> None:
        super().append(step)
        report_progress(STAR, 0, None, step_item(step.model_dump(mode="json")))

    def extend(self, steps: Iterable[StarStep]) -> None:
        for step in steps:
            self.append(step)


def complete_sentences(text: str) -> str:
    ends = list(_SENTENCE_END.finditer(text))
    return text[: ends[-1].end()] if ends else ""
