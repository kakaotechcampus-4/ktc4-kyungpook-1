"""채팅에서 받은 답으로 자소서 문항 하나의 빈칸 자리만 다시 쓴다.

초안 쓰기의 고치기 경로(fix)에 '그 자리만 쓰고 나머지는 그대로' 한 줄을 넣어 쓰므로 근거 없는 수치·낱말 검사와 추론 표시가 초안과 같다.
다시 쓴 글이 앞 글에 없던 문제로 걸리면 그 문제만 한 번 더 고쳐 쓰게 하고, 그래도 남거나 다른 문장을 여럿 바꾸면 앞 글로 되돌린다
— 사용자가 고친 글을 지키는 쪽이 낫다.
"""

from __future__ import annotations

import asyncio
from dataclasses import replace
from typing import Optional

from schemas.cover_letter import (
    DraftQuestion,
    DraftsRequest,
    DraftStep,
    FillRequest,
    FillResult,
    KnownAnswer,
    MaterialItem,
)
from services.cover_letter_check import ITEM_LABELS, blank, split_sentences
from services.cover_letter_drafter import CoverLetterDrafter
from services.cover_letter_llm import CoverLetterLimits, CoverLetterLLMUnavailable, StructuredClient
from services.cover_letter_polish import as_draft_request, keep_status
from services.cover_letter_writer import SENTENCE_MAX_CHARS, QuestionWriter, ReviewFinding, WrittenDraft, mixes_fact


#: 새로 생긴 문제를 고쳐 쓸 때 함께 넣는 말. 기술 이름 상한처럼 글 전체를 보는 문제를 고치다 사용자가 둔 다른 문장까지 바꾸지 않게 한다
FIX_ONLY_NEW = "새로 쓴 문장 안에서만 고치고 나머지 문장은 그대로 두세요."


class CoverLetterFiller:
    def __init__(
        self, write_client: StructuredClient, judge_client: StructuredClient, limits: Optional[CoverLetterLimits] = None,
    ) -> None:
        self._limits = limits or CoverLetterLimits()
        self._writer = QuestionWriter(write_client, judge_client, self._limits)
        self._drafter = CoverLetterDrafter(write_client, judge_client, self._limits)

    async def fill(self, request: FillRequest) -> FillResult:
        """빈칸 자리 쓰기 → 검사(새 문제면 한 번 고쳐 쓰고, 남으면 되돌림) → 감독 검토 한 번 → 표시. 쓰기 답을 못 받으면 CoverLetterLLMUnavailable 을 올린다."""
        steps: list[DraftStep] = []

        def record(actor, kind, question_id: Optional[int] = None, *, code=None, value=None, sentence_seq=None,
                   action=None, reason=None, sentence=None):
            steps.append(DraftStep(seq=len(steps) + 1, actor=actor, kind=kind, question_id=question_id, code=code,
                                   value=value, sentence_seq=sentence_seq, action=action, reason=reason,
                                   sentence=None if sentence is None else sentence[:SENTENCE_MAX_CHARS]))

        deadline = asyncio.get_running_loop().time() + self._limits.fill_deadline_seconds
        draft_request, question = as_draft_request(request)
        qid = question.question_id
        target = request.target
        text = target.current_text.strip()
        sentences = split_sentences(text)
        spot = locate_blank(text, request.blank, request.filled_item)
        # 채울 자리가 항목 빈칸이면 다시 쓴 글에서 빠지는 게 맞고, 남은 다른 항목 빈칸은 그대로 두게 한다
        blanks: list[MaterialItem] = [item for item in ITEM_LABELS if blank(item) in text and blank(item) != spot]  # type: ignore[misc]
        inferred = [sentences[seq - 1] for seq in target.inferred_seqs if seq <= len(sentences)]
        settled = {_squash(s) for s in sentences} - {_squash(s) for s in inferred}
        before = WrittenDraft(qid, text, blanks, 0, "LLM", inferred=tuple(inferred))
        answer = [answer for answer in target.answers if answer.item == request.filled_item][-1]

        # 새 답에 든 기술 이름이 상한을 넘기는 일처럼 새 문제가 생기면 한 번은 그 문제만 고쳐 쓰게 하고, 그래도 남으면 되돌린다
        written = await self._writer.rewrite_with(draft_request, question, before, [fill_problem(spot, answer)],
                                                  deadline - self._limits.fill_review_reserve_seconds, record,
                                                  fix_rounds=self._limits.fill_fix_rounds, keep=(FIX_ONLY_NEW,))
        written = self._kept_or_reverted(before, written, spot, qid, record)
        found: list[ReviewFinding] = []
        if written.text != before.text:
            reviewed, found = await self._review(draft_request, question, written, sentences, settled, deadline, record)
            written = self._kept_or_reverted(written, reviewed, spot, qid, record, original=before)
        written = keep_status(written, inferred, settled)
        return FillResult(draft=self._writer.finish(draft_request, question, written, found, record), steps=steps)

    async def _review(
        self, request: DraftsRequest, question: DraftQuestion, written: WrittenDraft, old: list[str],
        settled: set[str], deadline: float, record,
    ) -> tuple[WrittenDraft, list[ReviewFinding]]:
        """감독이 새로 쓴 문장만 본다. 그대로 둔 문장은 사용자가 이미 본 글이라 다시 고치지 않는다."""
        qid = question.question_id
        try:
            found = (await self._drafter.review(request, [(question, written)], deadline)).get(qid, [])
        except CoverLetterLLMUnavailable:
            record("SUPERVISOR", "FAILED", qid)
            return written, []
        unchanged = {_squash(s) for s in old}
        found = [finding for finding in found if _squash(finding.sentence) not in unchanged
                 and (finding.code != "INFERRED" or _squash(finding.sentence) not in settled)]
        sent_back = [finding for finding in found if finding.code != "INFERRED" or mixes_fact(finding.sentence)]
        record("SUPERVISOR", "REVIEWED", qid, value=str(len(sent_back)))
        for finding in sent_back:
            record("SUPERVISOR", "SENT_BACK", qid, code=finding.code, sentence_seq=finding.sentence_seq,
                   reason=finding.reason, sentence=finding.sentence)
        return await self._writer.rewrite_for_review(request, question, written, sent_back, deadline, record), found

    def _kept_or_reverted(
        self, before: WrittenDraft, after: WrittenDraft, spot: Optional[str], qid: int, record,
        original: Optional[WrittenDraft] = None,
    ) -> WrittenDraft:
        """다시 쓴 글이 빈칸 자리 밖의 문장을 상한보다 많이 바꿨으면 바꾸기 전 글로 되돌린다."""
        base = original or before
        if after.text == before.text or changed_sentences(base.text, after.text, spot) <= self._limits.max_fill_changed_sentences:
            return after
        record("CODE", "REVERTED", qid)
        return replace(before, rewrites=after.rewrites)


def locate_blank(text: str, wanted: Optional[str], item: MaterialItem) -> Optional[str]:
    """채울 빈칸이 지금 글에 있는 모양 그대로. 빈칸 안의 글만 왔으면 '[더 쓸 거리: …]' 로 찾고, 없으면 그 항목의 빈칸을 찾는다."""
    if wanted is not None:
        wanted = wanted.strip()
        # 빈칸 안의 글은 빈칸 째로 찾아야 그 글자만 바꾸라는 말이 되지 않는다
        candidate = wanted if wanted.startswith("[") else f"[더 쓸 거리: {wanted}]"
        if candidate in text:
            return candidate
    item_blank = blank(item)
    return item_blank if item_blank in text else None


def fill_problem(spot: Optional[str], answer: KnownAnswer) -> str:
    """쓰기 AI 에 넣는 고칠 점 한 줄로, 빈칸 자리만 새 답으로 쓰게 한다."""
    said = f"새 답({ITEM_LABELS[answer.item]}: '{answer.text.strip()}')"
    if spot is not None:
        return f"'{spot}' 자리를 {said}으로 쓰고 나머지 문장은 그대로 두세요."
    return f"{said}을 그 내용이 들어갈 자리에 한 문장 이하로 더하고 나머지 문장은 그대로 두세요."


def changed_sentences(before: str, after: str, spot: Optional[str]) -> int:
    """앞 글의 문장 가운데 빈칸 자리가 든 문장을 빼고 다시 쓴 글에 그대로 없는 문장 수."""
    left = {_squash(s) for s in split_sentences(after)}
    return sum(1 for s in split_sentences(before) if (spot is None or spot not in s) and _squash(s) not in left)


def _squash(text: str) -> str:
    return " ".join(text.split())
