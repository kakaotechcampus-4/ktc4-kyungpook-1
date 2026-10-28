"""STAR 초안 검토·고쳐 쓰기 루프가 합의한 규칙대로 걸린 칸만 고치는지 LLM 없이 검증한다."""

from __future__ import annotations

import asyncio
import json
import time
from typing import Any

from schemas.analysis import StarAnalysisRequest
from services.analysis_pipeline import AnalysisPipeline
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult
from services.star_generator import (
    REWRITE_SYSTEM_PROMPT,
    StarDraftOutput,
    StarField,
    StarGenerationLimits,
    StarGenerator,
)
from services.star_review import SlotReview, StarReviewer, StarReviewOutput, reviewer_from_env
from services.usage import metering

SHA_A = "a" * 40
PR_BODY = "예전에는 목록을 열 때마다 DB 를 10번 읽었다. 한 번에 읽도록 IN 조회로 바꿨다."
QUOTE = "예전에는 목록을 열 때마다 DB 를 10번 읽었다."
MESSAGE = "perf: 목록 조회를 IN 조회 한 번으로 바꿈"
REVERSED_S = "목록을 한 번에 읽고 있어 느렸다."
FIXED_S = "목록을 열 때마다 DB 를 10번 읽어 느렸다."
ACTION = "IN 조회로 목록을 한 번에 읽게 바꿨다."


class _FakeClient:
    """입력을 기록하고 미리 정한 응답(또는 오류)을 순서대로 돌려준다."""

    def __init__(self, *responses: Any, delay_seconds: float = 0) -> None:
        self._responses = list(responses)
        self._delay_seconds = delay_seconds
        self.calls: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.calls.append({"system_prompt": system_prompt, "payload": payload})
        time.sleep(self._delay_seconds)
        response = self._responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return StructuredCallResult(output=response, prompt_tokens=10, completion_tokens=5, latency_ms=1)


def _field(text: str, shas: list[str] | None = None) -> StarField:
    return StarField(status="FILLED", text=text, evidence_shas=[SHA_A] if shas is None else shas,
                     insufficient_reason=None)


def _draft(**fields: StarField) -> StarDraftOutput:
    empty = StarField(status="EMPTY", text=None, evidence_shas=[], insufficient_reason="근거 없음")
    return StarDraftOutput(**{slot: fields.get(slot, empty) for slot in ("S", "T", "A", "R")})


def _flags(*flags: tuple[str, str]) -> StarReviewOutput:
    return StarReviewOutput(slots=[
        SlotReview(slot=slot, is_reversed=True, source_quote=quote, reason="고치기 전과 뒤를 반대로 옮겼다.")
        for slot, quote in flags
    ])


NO_FLAGS = StarReviewOutput(slots=[])


def _request(answers: list[str] | None = None) -> StarAnalysisRequest:
    return StarAnalysisRequest.model_validate({
        "candidate_id": "c1",
        "target_login": "octo",
        "title": "목록 조회 줄이기",
        "source_type": "PR",
        "source_ref": "PR #12",
        "pull_request_body": PR_BODY,
        "evidence": [{
            "sha": SHA_A,
            "message": MESSAGE,
            "author_login": "octo",
            "churn": "+10/-2",
            "summary": "SUMMARY-ONLY 요약 문장",
            "summary_source": "DIFF",
            "technical_points": ["TECH-ONLY 포인트"],
        }],
        "confirmed_answers": answers or [],
    })


def _run(writer: _FakeClient, reviewer: _FakeClient, request: StarAnalysisRequest | None = None,
         limits: StarGenerationLimits = StarGenerationLimits()):
    generator = StarGenerator(writer, limits, reviewer=StarReviewer(reviewer))
    return asyncio.run(generator.generate(request or _request()))


def test_reversed_slot_is_rewritten_and_slots_without_problems_stay() -> None:
    writer = _FakeClient(
        _draft(S=_field(REVERSED_S), A=_field(ACTION)),
        _draft(S=_field(FIXED_S), A=_field("다시 쓴 A 문장")),
    )
    reviewer = _FakeClient(_flags(("S", QUOTE)), NO_FLAGS)

    result = _run(writer, reviewer)

    assert result.star["S"].text == FIXED_S
    assert (result.star["S"].status, result.star["S"].review_reason) == ("FILLED", None)
    assert result.star["A"].text == ACTION
    rewrite = writer.calls[1]
    assert rewrite["system_prompt"] == REWRITE_SYSTEM_PROMPT
    assert rewrite["payload"]["previous_draft"]["S"]["text"] == REVERSED_S
    assert rewrite["payload"]["problems"] == [
        {"slot": "S", "code": "REVERSED_MEANING", "value": QUOTE, "reason": "고치기 전과 뒤를 반대로 옮겼다."}
    ]
    assert [slot["slot"] for slot in reviewer.calls[1]["payload"]["slots"]] == ["S"]


def test_review_compares_only_human_written_sources() -> None:
    writer = _FakeClient(_draft(S=_field(REVERSED_S)))
    reviewer = _FakeClient(NO_FLAGS)

    _run(writer, reviewer, _request(answers=["처음엔 목록이 느렸어요"]))

    payload = reviewer.calls[0]["payload"]
    assert payload["sources"] == {
        "pull_request_body": PR_BODY,
        "commit_messages": [MESSAGE],
        "confirmed_answers": ["처음엔 목록이 느렸어요"],
    }
    assert "SUMMARY-ONLY" not in json.dumps(payload, ensure_ascii=False)
    assert "TECH-ONLY" not in json.dumps(payload, ensure_ascii=False)


def test_flag_whose_quote_is_not_in_sources_is_dropped() -> None:
    writer = _FakeClient(_draft(S=_field(REVERSED_S), A=_field(ACTION)))
    reviewer = _FakeClient(_flags(("S", "목록은 처음부터 한 번에 읽고 있었다."), ("A", "읽었다.")))

    result = _run(writer, reviewer)

    assert len(writer.calls) == 1
    assert [(result.star[slot].text, result.star[slot].status) for slot in ("S", "A")] == [
        (REVERSED_S, "FILLED"), (ACTION, "FILLED")
    ]


def test_code_problems_are_rewritten_but_unknown_sha_alone_is_not() -> None:
    number_writer = _FakeClient(_draft(R=_field("조회 시간을 50% 줄였다.")), _draft(R=_field("목록 조회를 한 번으로 줄였다.")))
    sha_writer = _FakeClient(_draft(A=_field(ACTION, [SHA_A, "deadbeef00"])))

    number = _run(number_writer, _FakeClient(NO_FLAGS, NO_FLAGS))
    _run(sha_writer, _FakeClient(NO_FLAGS))

    assert [problem["code"] for problem in number_writer.calls[1]["payload"]["problems"]] == ["UNSOURCED_NUMBER"]
    assert (number.star["R"].text, number.star["R"].status) == ("목록 조회를 한 번으로 줄였다.", "FILLED")
    assert len(sha_writer.calls) == 1


def test_slot_still_reversed_after_two_rewrites_keeps_text_and_needs_review() -> None:
    writer = _FakeClient(
        _draft(S=_field(REVERSED_S)),
        _draft(S=_field("한 번에 읽던 목록이 느렸다.")),
        _draft(S=_field("목록을 한 번에 읽어 느렸다.")),
    )
    reviewer = _FakeClient(_flags(("S", QUOTE)), _flags(("S", QUOTE)), _flags(("S", QUOTE)))

    result = _run(writer, reviewer)

    assert (len(writer.calls), len(reviewer.calls)) == (3, 3)
    assert result.star["S"].text == "목록을 한 번에 읽어 느렸다."
    assert result.star["S"].status == "NEEDS_REVIEW"
    assert result.star["S"].confidence == "LOW"
    assert result.star["S"].review_reason == "원문과 뜻이 다를 수 있어 사용자 확인이 필요합니다."


def test_failed_review_or_rewrite_builds_the_card_from_the_current_draft() -> None:
    review_failed = _run(_FakeClient(_draft(S=_field(REVERSED_S))), _FakeClient(LLMStructuredCallError("검토 실패")))
    rewrite_failed = _run(
        _FakeClient(_draft(S=_field(REVERSED_S)), LLMStructuredCallError("다시 쓰기 실패")),
        _FakeClient(_flags(("S", QUOTE))),
    )

    assert (review_failed.star["S"].text, review_failed.star["S"].status) == (REVERSED_S, "FILLED")
    assert (rewrite_failed.star["S"].text, rewrite_failed.star["S"].status) == (REVERSED_S, "NEEDS_REVIEW")


def test_only_received_rewrites_are_counted_as_repair_rounds() -> None:
    with metering() as rewritten_twice:
        _run(
            _FakeClient(_draft(S=_field(REVERSED_S)), _draft(S=_field("한 번에 읽던 목록이 느렸다.")),
                        _draft(S=_field("목록을 한 번에 읽어 느렸다."))),
            _FakeClient(_flags(("S", QUOTE)), _flags(("S", QUOTE)), _flags(("S", QUOTE))),
        )
    with metering() as rewrite_failed:
        _run(_FakeClient(_draft(S=_field(REVERSED_S)), LLMStructuredCallError("다시 쓰기 실패")),
             _FakeClient(_flags(("S", QUOTE))))

    assert (rewritten_twice.repair_rounds, rewrite_failed.repair_rounds) == (2, 0)


def test_repair_stops_when_the_deadline_is_near() -> None:
    writer = _FakeClient(_draft(S=_field(REVERSED_S)), delay_seconds=0.5)
    reviewer = _FakeClient(_flags(("S", QUOTE)))

    result = _run(writer, reviewer, limits=StarGenerationLimits(deadline_seconds=1.0, min_call_seconds=0.6))

    assert reviewer.calls == []
    assert (result.star["S"].text, result.star["S"].status) == (REVERSED_S, "FILLED")


def test_repair_runs_only_when_switched_on(monkeypatch) -> None:
    monkeypatch.delenv("GITORY_STAR_REPAIR_ENABLED", raising=False)
    assert reviewer_from_env() is None
    assert AnalysisPipeline().star_generator._reviewer is None

    monkeypatch.setenv("GITORY_STAR_REPAIR_ENABLED", "true")
    assert isinstance(reviewer_from_env(), StarReviewer)
