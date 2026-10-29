"""STAR 초안이 거친 단계를 누가·무엇을·어느 바퀴에 했는지 합의한 모양대로 남기는지 LLM 없이 검증한다."""

from __future__ import annotations

import asyncio
from typing import Any

from schemas.analysis import StarAnalysisRequest, StarAnalysisResponse, StarStep
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult
from services.star_generator import (
    SlotChoice,
    SlotChoiceOutput,
    StarDraftOutput,
    StarField,
    StarGenerationLimits,
    StarGenerator,
)
from services.star_review import SlotReview, StarReviewer, StarReviewOutput

SHA_A = "a" * 40
MESSAGE = "fix: 세션을 캐시에 두고 만료 전에 늘리기"
ISSUE = {"number": 98, "title": "세션 만료 시 작성 글 사라짐", "body": "세션이 30분 뒤 풀려 작성 중인 글이 사라진다."}
ISSUE_TITLE = "이슈 #98 세션 만료 시 작성 글 사라짐"
UNSOURCED_A = "세션 조회를 50% 줄였다."
ACTION = "세션을 캐시에 두고 만료 전에 늘리게 바꿨다."
NO_FLAGS = StarReviewOutput(slots=[])


class _FakeClient:
    """입력을 기록하고 미리 정한 응답(또는 오류)을 순서대로 돌려준다."""

    def __init__(self, *responses: Any) -> None:
        self._responses = list(responses)
        self.calls: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.calls.append({"system_prompt": system_prompt, "payload": payload})
        response = self._responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return StructuredCallResult(output=response, prompt_tokens=10, completion_tokens=5, latency_ms=1)


def _field(text: str) -> StarField:
    return StarField(status="FILLED", text=text, evidence_shas=[SHA_A], insufficient_reason=None)


def _draft(**fields: StarField) -> StarDraftOutput:
    empty = StarField(status="EMPTY", text=None, evidence_shas=[], insufficient_reason="근거 없음")
    return StarDraftOutput(**{slot: fields.get(slot, empty) for slot in ("S", "T", "A", "R")})


def _choose(*choices: tuple[str, str, str | None], reason: str = "이유") -> SlotChoiceOutput:
    return SlotChoiceOutput(slots=[
        SlotChoice(slot=slot, action=action, material_id=material, reason=reason)
        for slot, action, material in choices
    ])


def _run(writer: _FakeClient, reviewer: _FakeClient | None, *, with_issue: bool = True,
         limits: StarGenerationLimits = StarGenerationLimits(), repair: bool = False) -> StarAnalysisResponse:
    body: dict[str, Any] = {
        "candidate_id": "c1",
        "target_login": "octo",
        "title": "세션 만료 처리",
        "source_type": "PR",
        "source_ref": "PR #120",
        "evidence": [{
            "sha": SHA_A,
            "message": MESSAGE,
            "author_login": "octo",
            "churn": "+40/-3",
            "summary": "세션 캐시 추가",
            "summary_source": "DIFF",
        }],
    }
    if with_issue:
        body["materials"] = {"issues": [ISSUE], "reviews": [], "owner_replies": []}
    generator = StarGenerator(writer, limits, reviewer=StarReviewer(reviewer) if reviewer is not None else None,
                              repair=repair)
    return asyncio.run(generator.generate(StarAnalysisRequest.model_validate(body)))


def _flag(round_no: int, slot: str, code: str, value: str | None = None, actor: str = "CODE") -> StarStep:
    return StarStep(round=round_no, actor=actor, kind="FLAG", slot=slot, code=code, value=value)


DRAFT = StarStep(round=0, actor="MODEL", kind="DRAFT")


def test_without_review_only_the_draft_and_first_check_are_recorded() -> None:
    result = _run(_FakeClient(_draft(A=_field(UNSOURCED_A))), None)

    assert result.steps == [
        DRAFT,
        _flag(0, "S", "EMPTY"),
        _flag(0, "T", "EMPTY"),
        _flag(0, "A", "UNSOURCED_NUMBER", "50%"),
        _flag(0, "R", "EMPTY"),
    ]


def test_choices_rewrites_and_rechecks_are_recorded_by_round() -> None:
    writer = _FakeClient(
        _draft(A=_field(UNSOURCED_A)),
        _choose(("S", "OPEN", "issue-98"), ("T", "KEEP", None), ("A", "REWRITE", None)),
        _draft(S=_field("세션이 30분 뒤 풀려 작성 중이던 글이 사라졌다."), A=_field("세션 조회를 30% 줄였다.")),
    )

    result = _run(writer, _FakeClient(NO_FLAGS, NO_FLAGS), limits=StarGenerationLimits(max_repair_rounds=1))

    assert result.steps == [
        DRAFT,
        _flag(0, "S", "EMPTY"),
        _flag(0, "T", "EMPTY"),
        _flag(0, "A", "UNSOURCED_NUMBER", "50%"),
        _flag(0, "R", "EMPTY"),
        StarStep(round=1, actor="MODEL", kind="OPEN", slot="S", value=ISSUE_TITLE, material="ISSUE", reason="이유"),
        StarStep(round=1, actor="MODEL", kind="KEEP", slot="T", reason="이유"),
        StarStep(round=1, actor="MODEL", kind="RETRY", slot="A", reason="이유"),
        StarStep(round=1, actor="MODEL", kind="REWRITE", slot="S"),
        StarStep(round=1, actor="MODEL", kind="REWRITE", slot="A"),
        StarStep(round=1, actor="CODE", kind="PASS", slot="S"),
        _flag(1, "A", "UNSOURCED_NUMBER", "30%"),
    ]



def test_rewrite_that_got_worse_is_recorded_as_revert_with_its_problem() -> None:
    no_sha = StarField(status="FILLED", text=ACTION, evidence_shas=[], insufficient_reason=None)
    writer = _FakeClient(_draft(A=_field(UNSOURCED_A)), _draft(A=no_sha), _draft(A=_field(ACTION)))

    result = _run(writer, None, with_issue=False, limits=StarGenerationLimits(max_repair_rounds=2), repair=True)

    assert [step for step in result.steps if step.slot == "A"] == [
        _flag(0, "A", "UNSOURCED_NUMBER", "50%"),
        StarStep(round=1, actor="MODEL", kind="REWRITE", slot="A"),
        StarStep(round=1, actor="CODE", kind="REVERT", slot="A", code="NO_EVIDENCE"),
        _flag(1, "A", "UNSOURCED_NUMBER", "50%"),
        StarStep(round=2, actor="MODEL", kind="REWRITE", slot="A"),
        StarStep(round=2, actor="CODE", kind="PASS", slot="A"),
    ]
    assert result.star["A"].text == ACTION

def test_actions_changed_by_code_are_recorded_without_the_model_reason() -> None:
    writer = _FakeClient(
        _draft(A=_field(UNSOURCED_A)),
        _choose(("S", "OPEN", "issue-404"), ("T", "REWRITE", None), ("A", "KEEP", None)),
    )

    result = _run(writer, _FakeClient(NO_FLAGS))

    assert [step for step in result.steps if step.round == 1] == [
        StarStep(round=1, actor="CODE", kind="KEEP", slot="S"),
        StarStep(round=1, actor="CODE", kind="KEEP", slot="T"),
        StarStep(round=1, actor="MODEL", kind="KEEP", slot="A", reason="이유"),
    ]


def test_model_reason_is_masked_and_clipped() -> None:
    token = "ghp_" + "x" * 36
    writer = _FakeClient(
        _draft(A=_field(UNSOURCED_A)),
        _choose(("A", "KEEP", None), reason=f"토큰 {token} 과 kim@example.com 이 적힌 글이라 " + "가" * 80),
    )

    result = _run(writer, _FakeClient(NO_FLAGS))

    reason = next(step.reason for step in result.steps if step.kind == "KEEP" and step.slot == "A")
    assert len(reason) <= 60
    assert token not in reason and "kim@example.com" not in reason


def test_reversed_meaning_is_flagged_by_the_review_model_without_the_quote() -> None:
    writer = _FakeClient(_draft(S=_field("세션을 캐시에 두고 있어 만료가 늦었다."), A=_field(ACTION)))
    reviewer = _FakeClient(StarReviewOutput(slots=[SlotReview(
        slot="S", is_reversed=True, source_quote=MESSAGE, reason="전후를 뒤집었다.",
    )]))

    result = _run(writer, reviewer, with_issue=False, limits=StarGenerationLimits(max_repair_rounds=0))

    assert result.steps == [
        DRAFT,
        _flag(0, "S", "REVERSED_MEANING", actor="MODEL"),
        _flag(0, "T", "EMPTY"),
        _flag(0, "R", "EMPTY"),
    ]


def test_failed_review_still_records_code_flags() -> None:
    reviewer = _FakeClient(LLMStructuredCallError("검토 실패"))

    result = _run(_FakeClient(_draft(A=_field(UNSOURCED_A))), reviewer)

    assert result.steps == [
        DRAFT,
        _flag(0, "S", "EMPTY"),
        _flag(0, "T", "EMPTY"),
        _flag(0, "A", "UNSOURCED_NUMBER", "50%"),
        _flag(0, "R", "EMPTY"),
    ]
