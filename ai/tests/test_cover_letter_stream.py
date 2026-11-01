"""자소서 실시간: 카드 고르기·초안 쓰기의 단계는 생기는 대로, 초안 글은 문장이 끝날 때만 문항마다 0.5초에 한 번까지 보낸다."""

from __future__ import annotations

import asyncio
import json
import threading
from typing import Any, Optional

from fastapi.testclient import TestClient

import api.cover_letters as cover_letters_api
from main import create_app
from schemas.cover_letter import DraftsRequest
from services.cover_letter_assigner import (
    ConsideredOutput,
    CoverLetterAssigner,
    DecisionOutput,
    PickOutput,
    SupervisorOutput,
)
from services.cover_letter_drafter import CoverLetterDrafter, FindingOutput, ReviewOutput
from services.cover_letter_llm import CoverLetterLimits
from services.cover_letter_progress import DraftTextStreamer
from services.cover_letter_writer import DecideOutput, DecisionItem, WriteOutput
from services.llm_structured_client import StructuredCallResult
from services.progress import listening

CARD = {
    "card_id": 1, "title": "배포 자동화", "source_name": "gitory",
    "statements": [{"star_slot": "S", "text": "배포를 손으로 했다"}, {"star_slot": "A", "text": "배포를 자동화했다"}],
}
DRAFTS_REQUEST = {
    "cover_letter_id": 9, "job_role": "백엔드 개발",
    "questions": [{"question_id": 1, "text": "문제 해결 경험", "char_limit": 700, "question_theme": "PROBLEM_SOLVING",
                   "primary_card": CARD, "answers": [{"item": "RESULT", "text": "배포가 빨라졌다", "card_id": 1}]}],
}
ASSIGNMENT_REQUEST = {
    "cover_letter_id": 9, "job_role": "백엔드 개발",
    "questions": [{"question_id": 1, "text": "협업 경험", "char_limit": 700},
                  {"question_id": 2, "text": "문제 해결 경험", "char_limit": 700}],
    "cards": [{"card_id": 1, "title": "API 모양 조율", "summary": "의견을 조율했다"},
              {"card_id": 2, "title": "배포 자동화", "summary": "배포를 자동화했다"}],
}
NO_TARGET = CoverLetterLimits(target_ratio=0.0)


class FakeClient:
    """출력 종류마다 정해 둔 답을 차례로 돌려주고, 쓰기는 on_partial 을 받았으면 부분 출력을 먼저 흘려준다."""

    def __init__(self, answers: dict[type, list[Any]], partials: Optional[list[dict[str, Any]]] = None) -> None:
        self._answers = {kind: list(items) for kind, items in answers.items()}
        self._partials = partials or []
        self._lock = threading.Lock()
        self.partial_callbacks: list[Any] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None, on_partial=None):
        with self._lock:
            answer = self._answers[output_model].pop(0)
            if output_model is WriteOutput:
                self.partial_callbacks.append(on_partial)
        if output_model is WriteOutput and on_partial is not None:
            for partial in self._partials:
                on_partial(partial)
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _lines(response) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    lines = [json.loads(line) for line in response.text.splitlines()]
    assert all(line["step"] == "COVER_LETTER" for line in lines if line["type"] == "progress")
    return [line["item"] for line in lines if line["type"] == "progress"], lines[-1]


def _pick(card: int, second: Optional[int] = None) -> PickOutput:
    ids = [card] if second is None else [card, second]
    return PickOutput(question_theme="COLLABORATION",
                      considered=[ConsideredOutput(card_id=c, note="견줄 만함", fit="MEDIUM") for c in ids],
                      first_card_id=card, first_reason="가장 맞음",
                      second_card_id=second, second_reason=None if second is None else "다음으로 맞음")


def test_assignment_stream_sends_each_pick_and_the_supervisor_step_with_the_final_numbers(monkeypatch) -> None:
    """문항마다 고르는 즉시 다 끝난 뒤의 과정과 같은 번호로 단계를 보내고, 감독 단계도 정하는 즉시 보내며, 마지막 줄은 보통 API 응답과 같다."""
    def assigner() -> CoverLetterAssigner:
        client = FakeClient({PickOutput: [_pick(1, 2), _pick(2)],
                             SupervisorOutput: [SupervisorOutput(decisions=[
                                 DecisionOutput(question_id=1, choice="FIRST"), DecisionOutput(question_id=2, choice="FIRST")])]})
        return CoverLetterAssigner(client, client)

    # 두 문항은 동시에 돌아 부르는 순서가 정해지지 않으므로 문항 문장으로 답을 고르지 않고, 같은 답 둘을 쓴다
    monkeypatch.setattr(cover_letters_api, "_assigner", assigner())
    items, result = _lines(TestClient(create_app()).post("/internal/cover-letter-assignments/stream",
                                                         json=ASSIGNMENT_REQUEST))
    monkeypatch.setattr(cover_letters_api, "_assigner", assigner())
    plain = TestClient(create_app()).post("/internal/cover-letter-assignments", json=ASSIGNMENT_REQUEST).json()

    steps = sorted((item["step"] for item in items if item["kind"] == "STEP"), key=lambda step: step["seq"])
    assert result["type"] == "result" and result["status"] == 200
    assert steps == result["body"]["data"]["steps"]
    assert [(step["actor"], step["kind"]) for step in steps] == [
        ("SUB_AGENT", "PICKED"), ("SUB_AGENT", "PICKED"), ("SUPERVISOR", "CHECKED")]
    assert result["body"]["data"]["assignments"] == plain["data"]["assignments"]


def test_drafts_stream_sends_text_by_sentence_then_the_steps_as_they_happen(monkeypatch) -> None:
    """쓰는 도중의 글은 끝난 문장까지만 보내고 다 쓰면 완성된 글을 보내며, 단계는 생기는 대로 보내 마지막 줄의 과정과 같다."""
    text = "배포를 자동화했습니다. 배포가 빨라졌습니다."
    client = FakeClient({WriteOutput: [WriteOutput(text=text)], ReviewOutput: [ReviewOutput(findings=[])]},
                        partials=[{"text": "배포를 자동"}, {"text": "배포를 자동화했습니다. 배포가"}])
    monkeypatch.setattr(cover_letters_api, "_drafter", CoverLetterDrafter(client, client, NO_TARGET))

    items, result = _lines(TestClient(create_app()).post("/internal/cover-letter-drafts/stream", json=DRAFTS_REQUEST))

    texts = [(item["question_id"], item["round"], item["text"], item["done"])
             for item in items if item["kind"] == "DRAFT_TEXT"]
    steps = [item["step"] for item in items if item["kind"] == "STEP"]
    assert texts == [(1, 0, "배포를 자동화했습니다.", False), (1, 0, text, True)]
    assert steps == result["body"]["data"]["steps"]
    assert [step["kind"] for step in steps] == ["WROTE", "CHECK_PASSED", "REVIEWED"]


def test_rewritten_text_is_sent_as_the_next_round() -> None:
    """검사에 걸려 고쳐 쓰는 글은 round 1 로 보내, 화면이 첫 초안과 고쳐 쓰는 중을 나눠 보이게 한다."""
    client = FakeClient({
        WriteOutput: [WriteOutput(text="주문 처리량을 30% 늘렸습니다."), WriteOutput(text="주문 처리량을 늘렸습니다.")],
        DecideOutput: [DecideOutput(decisions=[DecisionItem(index=1, action="FIX")])],
        ReviewOutput: [ReviewOutput(findings=[])],
    })
    sent: list[tuple[int, bool]] = []

    with listening(lambda step, done, total, item: sent.append((item["round"], item["done"]))
                   if item["kind"] == "DRAFT_TEXT" else None):
        asyncio.run(CoverLetterDrafter(client, client, NO_TARGET).draft(DraftsRequest.model_validate(DRAFTS_REQUEST)))

    assert sent == [(0, True), (1, True)]


def test_reverted_rewrite_sends_the_kept_text_again() -> None:
    """검토 뒤 고쳐 쓴 글이 새 문제로 되돌려지면 남긴 앞 글을 그 바퀴의 글로 다시 보내, 화면에 버린 글이 남지 않게 한다."""
    kept = "배포를 자동화했습니다."
    client = FakeClient({
        WriteOutput: [WriteOutput(text=kept), WriteOutput(text="주문 처리량을 30% 늘렸습니다.")],
        ReviewOutput: [ReviewOutput(findings=[FindingOutput(question_id=1, sentence_seq=1, code="OFF_TOPIC",
                                                            reason="문항과 상관없음")])],
    })
    sent: list[tuple[int, str, bool]] = []

    with listening(lambda step, done, total, item: sent.append((item["round"], item["text"], item["done"]))
                   if item["kind"] == "DRAFT_TEXT" else None):
        result, _ = asyncio.run(CoverLetterDrafter(client, client, NO_TARGET).draft(
            DraftsRequest.model_validate(DRAFTS_REQUEST)))

    assert sent == [(0, kept, True), (1, "주문 처리량을 30% 늘렸습니다.", True), (1, kept, True)]
    assert result.drafts[0].text == kept
    assert "REVERTED" in [step.kind for step in result.steps]


def test_flagged_steps_carry_the_sentence_number_and_text() -> None:
    """검사·다음 행동·전체 검토·표시 단계는 걸린 문장의 번호와 글을 실어, 고치기 전 문장을 검토 기록으로 보이게 한다."""
    flagged = "주문 처리량을 30% 늘렸습니다."
    off = "회사 문화가 좋다고 들었습니다."
    text = f"{flagged} {off}"
    client = FakeClient({
        WriteOutput: [WriteOutput(text=text), WriteOutput(text=text)],
        DecideOutput: [DecideOutput(decisions=[DecisionItem(index=1, action="KEEP", reason="답의 표현과 같음")])],
        ReviewOutput: [ReviewOutput(findings=[FindingOutput(question_id=1, sentence_seq=2, code="OFF_TOPIC",
                                                            reason="문항과 상관없음")])],
    })

    result, _ = asyncio.run(CoverLetterDrafter(client, client, NO_TARGET).draft(DraftsRequest.model_validate(DRAFTS_REQUEST)))

    located = {(step.kind, step.code, step.sentence_seq, step.sentence) for step in result.steps
               if step.kind in ("CHECK_FAILED", "DECIDED", "SENT_BACK", "MARKED")}
    assert located == {
        ("CHECK_FAILED", "UNSOURCED_NUMBER", 1, flagged),
        ("DECIDED", "UNSOURCED_NUMBER", 1, flagged),
        ("SENT_BACK", "OFF_TOPIC", 2, off),
        ("MARKED", "UNSOURCED_NUMBER", 1, flagged),
        ("MARKED", "OFF_TOPIC", 2, off),
    }


def test_flagged_sentence_is_cut_to_two_hundred_chars() -> None:
    """걸린 문장이 길어도 단계에는 200자까지만 실어, 진행 줄이 자르는 길이와 같게 한다."""
    long_sentence = "주문 처리량을 30% 늘렸습니다" + "가" * 300 + "."
    client = FakeClient({
        WriteOutput: [WriteOutput(text=long_sentence)] * 3,
        DecideOutput: [DecideOutput(decisions=[DecisionItem(index=1, action="FIX")])] * 2,
        ReviewOutput: [ReviewOutput(findings=[])],
    })

    result, _ = asyncio.run(CoverLetterDrafter(client, client, NO_TARGET).draft(DraftsRequest.model_validate(DRAFTS_REQUEST)))

    sentences = [step.sentence for step in result.steps if step.kind == "CHECK_FAILED"]
    assert sentences and all(len(sentence) == 200 for sentence in sentences)


def test_draft_text_is_sent_at_most_every_half_second_per_question() -> None:
    """같은 문항에서 0.5초 안에 문장이 또 끝나면 그 사이 줄은 건너뛰고, 다 쓰면 기다리지 않고 완성된 글을 보낸다."""
    sent: list[tuple[str, bool]] = []
    now = [0.0]
    streamer = DraftTextStreamer(1, 0, clock=lambda: now[0])

    with listening(lambda step, done, total, item: sent.append((item["text"], item["done"]))):
        streamer.feed({"text": "첫 문장입니다. "})
        now[0] = 0.3
        streamer.feed({"text": "첫 문장입니다. 둘째 문장입니다. "})
        now[0] = 0.4
        streamer.finish("첫 문장입니다. 둘째 문장입니다. 셋째")

    assert sent == [("첫 문장입니다.", False), ("첫 문장입니다. 둘째 문장입니다. 셋째", True)]


def test_without_a_listener_the_writer_gets_no_partial_callback() -> None:
    """스트리밍 API 가 듣지 않는 보통 초안 API 는 지금처럼 부분 출력 없이 부른다."""
    client = FakeClient({WriteOutput: [WriteOutput(text="배포를 자동화했습니다.")], ReviewOutput: [ReviewOutput(findings=[])]})

    asyncio.run(CoverLetterDrafter(client, client, NO_TARGET).draft(DraftsRequest.model_validate(DRAFTS_REQUEST)))

    assert client.partial_callbacks == [None]
