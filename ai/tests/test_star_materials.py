"""STAR 초안이 걸리거나 빈 칸에서 연결 이슈·리뷰를 골라 열 때 코드가 합의한 범위만 열게 하는지 LLM 없이 검증한다."""

from __future__ import annotations

import asyncio
import json
from typing import Any

from schemas.analysis import StarAnalysisRequest
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult
from services.star_generator import (
    CHOOSE_SYSTEM_PROMPT,
    MATERIAL_REWRITE_SYSTEM_PROMPT,
    REWRITE_SYSTEM_PROMPT,
    SlotChoice,
    SlotChoiceOutput,
    StarDraftOutput,
    StarField,
    StarGenerationLimits,
    StarGenerator,
)
from services.star_review import (
    REVIEW_WITH_MATERIALS_SYSTEM_PROMPT,
    SlotReview,
    StarReviewer,
    StarReviewOutput,
)
from services.usage import metering, usage_meta

SHA_A = "a" * 40
MESSAGE = "fix: 세션을 캐시에 두고 만료 전에 늘리기"
ISSUE_BODY = "세션이 30분 뒤 풀려 작성 중인 글이 사라진다. 목표: 다시 로그인 없이 이어 쓰기"
REVIEW_NOTE = "여기 세션을 매번 DB 에서 읽어서 느려요"
OWNER_REPLY = "세션을 캐시에 두게 바꿨습니다. 로컬에서 300ms 에서 40ms 로 줄었습니다"
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


def _choose(*choices: tuple[str, str, str | None]) -> SlotChoiceOutput:
    return SlotChoiceOutput(slots=[
        SlotChoice(slot=slot, action=action, material_id=material, reason="이유")
        for slot, action, material in choices
    ])


def _request(materials: dict[str, Any] | None) -> StarAnalysisRequest:
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
    if materials is not None:
        body["materials"] = materials
    return StarAnalysisRequest.model_validate(body)


ALL_MATERIALS = {
    "issues": [{"number": 98, "title": "세션 만료 시 작성 글 사라짐", "body": ISSUE_BODY}],
    "reviews": [REVIEW_NOTE],
    "owner_replies": [OWNER_REPLY],
}


def _run(writer: _FakeClient, reviewer: _FakeClient, materials: dict[str, Any] | None = None,
         limits: StarGenerationLimits = StarGenerationLimits()):
    generator = StarGenerator(writer, limits, reviewer=StarReviewer(reviewer))
    with metering():
        result = asyncio.run(generator.generate(_request(ALL_MATERIALS if materials is None else materials)))
        return result, usage_meta(None, "COMPLETED")


def test_first_write_does_not_see_materials() -> None:
    writer = _FakeClient(_draft(A=_field(ACTION)), _choose(("S", "KEEP", None), ("T", "KEEP", None)))

    _run(writer, _FakeClient(NO_FLAGS), {"issues": [], "reviews": [REVIEW_NOTE], "owner_replies": []})

    first = json.dumps(writer.calls[0]["payload"], ensure_ascii=False)
    assert "materials" not in writer.calls[0]["payload"]
    assert REVIEW_NOTE not in first


def test_empty_slot_opens_an_issue_and_is_written_from_it() -> None:
    written_s = "세션이 30분 뒤 풀려 작성 중이던 글이 사라졌다."
    writer = _FakeClient(
        _draft(A=_field(ACTION)),
        _choose(("S", "OPEN", "issue-98"), ("T", "KEEP", None)),
        _draft(S=_field(written_s), A=_field("바뀌면 안 되는 A")),
    )
    materials = {"issues": ALL_MATERIALS["issues"], "reviews": [], "owner_replies": []}

    result, meta = _run(writer, _FakeClient(NO_FLAGS, NO_FLAGS), materials)

    choose, rewrite = writer.calls[1], writer.calls[2]
    assert choose["system_prompt"] == CHOOSE_SYSTEM_PROMPT
    assert [slot["slot"] for slot in choose["payload"]["slots"]] == ["S", "T"]
    assert ISSUE_BODY not in json.dumps(choose["payload"], ensure_ascii=False)
    assert rewrite["system_prompt"] == MATERIAL_REWRITE_SYSTEM_PROMPT
    assert rewrite["payload"]["problems"] == [
        {"slot": "S", "code": "EMPTY", "value": None, "reason": "이 칸이 비어 있습니다."}
    ]
    assert [material["id"] for material in rewrite["payload"]["opened_materials"]] == ["issue-98"]
    assert (result.star["S"].text, result.star["S"].status) == (written_s, "FILLED")
    assert result.star["A"].text == ACTION
    assert result.star["T"].status == "EMPTY"
    assert (meta.tool_calls_made, meta.repair_rounds) == (1, 1)



def test_issue_and_review_are_not_opened_for_action_or_result() -> None:
    writer = _FakeClient(
        _draft(A=_field("세션 조회를 50% 줄였다.")),
        _choose(("S", "KEEP", None), ("A", "OPEN", "reviews"), ("R", "OPEN", "issue-98")),
        _draft(A=_field(ACTION)),
    )

    result, meta = _run(writer, _FakeClient(NO_FLAGS, NO_FLAGS))

    rewrite = writer.calls[2]
    assert rewrite["system_prompt"] == REWRITE_SYSTEM_PROMPT
    assert "opened_materials" not in rewrite["payload"]
    assert [problem["slot"] for problem in rewrite["payload"]["problems"]] == ["A"]
    assert result.star["A"].text == ACTION
    assert result.star["R"].status == "EMPTY"
    assert meta.tool_calls_made == 0


def test_choice_is_skipped_when_no_material_fits_the_slots() -> None:
    writer = _FakeClient(_draft(S=_field("세션 문제가 있었다."), T=_field("세션 처리를 맡았다."),
                                A=_field("세션 조회를 50% 줄였다.")), _draft(A=_field(ACTION)))
    materials = {"issues": ALL_MATERIALS["issues"], "reviews": [], "owner_replies": []}

    _run(writer, _FakeClient(NO_FLAGS, NO_FLAGS), materials)

    assert [call["system_prompt"] for call in writer.calls[1:]] == [REWRITE_SYSTEM_PROMPT]


def test_owner_reply_backs_the_result_and_its_numbers() -> None:
    written_r = "세션 조회 시간을 300ms 에서 40ms 로 줄였다."
    writer = _FakeClient(
        _draft(A=_field(ACTION)),
        _choose(("R", "OPEN", "owner-replies")),
        _draft(R=_field(written_r)),
    )
    materials = {"issues": [], "reviews": [], "owner_replies": [OWNER_REPLY]}

    result, _ = _run(writer, _FakeClient(NO_FLAGS, NO_FLAGS), materials)

    assert [slot["slot"] for slot in writer.calls[1]["payload"]["slots"]] == ["S", "T", "R"]
    assert (result.star["R"].text, result.star["R"].status) == (written_r, "FILLED")


def test_numbers_in_an_opened_material_count_only_in_its_slots() -> None:
    writer = _FakeClient(
        _draft(A=_field("세션 조회를 50% 줄였다.")),
        _choose(("S", "OPEN", "issue-98"), ("A", "REWRITE", None)),
        _draft(S=_field("세션이 30분 뒤 풀려 글이 사라졌다."), A=_field("세션이 30분 넘게 유지되게 바꿨다.")),
        _draft(A=_field("세션이 30분 넘게 유지되게 바꿨다.")),
    )
    materials = {"issues": ALL_MATERIALS["issues"], "reviews": [], "owner_replies": []}

    result, _ = _run(writer, _FakeClient(NO_FLAGS, NO_FLAGS, NO_FLAGS), materials,
                     StarGenerationLimits(max_repair_rounds=2))

    assert result.star["S"].status == "FILLED"
    assert result.star["A"].status == "NEEDS_REVIEW"
    assert "30분" in result.star["A"].review_reason


def test_unknown_material_and_opens_over_the_limit_are_ignored() -> None:
    writer = _FakeClient(
        _draft(A=_field(ACTION)),
        _choose(("S", "OPEN", "issue-1"), ("T", "OPEN", "issue-2"), ("R", "OPEN", "issue-404")),
        _draft(S=_field("세션이 풀렸다.")),
    )
    materials = {
        "issues": [{"number": 1, "title": "이슈 하나"}, {"number": 2, "title": "이슈 둘"}],
        "reviews": [],
        "owner_replies": [OWNER_REPLY],
    }

    _, meta = _run(writer, _FakeClient(NO_FLAGS, NO_FLAGS), materials, StarGenerationLimits(max_material_opens=1))

    rewrite = writer.calls[2]
    assert [material["id"] for material in rewrite["payload"]["opened_materials"]] == ["issue-1"]
    assert [problem["slot"] for problem in rewrite["payload"]["problems"]] == ["S"]
    assert meta.tool_calls_made == 1


def test_failed_choice_rewrites_flagged_slots_without_materials() -> None:
    writer = _FakeClient(
        _draft(A=_field("세션 조회를 50% 줄였다.")),
        LLMStructuredCallError("고르기 실패"),
        _draft(A=_field(ACTION)),
    )

    result, meta = _run(writer, _FakeClient(NO_FLAGS, NO_FLAGS))

    rewrite = writer.calls[2]
    assert rewrite["system_prompt"] == REWRITE_SYSTEM_PROMPT
    assert "opened_materials" not in rewrite["payload"]
    assert result.star["A"].text == ACTION
    assert result.star["S"].status == "EMPTY"
    assert (meta.tool_calls_made, meta.repair_rounds) == (0, 1)


def test_review_compares_opened_materials_with_who_wrote_them() -> None:
    claimed = "세션을 매번 DB 에서 읽어 느린 문제를 찾았다."
    writer = _FakeClient(
        _draft(A=_field(ACTION)),
        _choose(("S", "OPEN", "reviews")),
        _draft(S=_field(claimed)),
    )
    reviewer = _FakeClient(NO_FLAGS, StarReviewOutput(slots=[SlotReview(
        slot="S", is_reversed=True, source_quote=REVIEW_NOTE, reason="리뷰어가 찾은 문제를 이 개발자가 찾았다고 썼다.",
    )]))
    materials = {"issues": [], "reviews": [REVIEW_NOTE], "owner_replies": []}

    result, _ = _run(writer, reviewer, materials, StarGenerationLimits(max_repair_rounds=1))

    second = reviewer.calls[1]
    assert second["system_prompt"] == REVIEW_WITH_MATERIALS_SYSTEM_PROMPT
    assert second["payload"]["sources"]["opened_materials"] == [f"[다른 사람의 리뷰] - {REVIEW_NOTE}"]
    assert (result.star["S"].text, result.star["S"].status) == (claimed, "NEEDS_REVIEW")


def test_materials_are_masked_clipped_and_capped() -> None:
    token = "ghp_" + "x" * 36
    writer = _FakeClient(
        _draft(A=_field(ACTION)),
        _choose(("S", "OPEN", "issue-1"), ("T", "OPEN", "reviews")),
        _draft(),
        _choose(),
    )
    materials = {
        "issues": [{"number": n, "title": f"이슈 {n}", "body": f"작성자 kim@example.com 토큰 {token}"} for n in range(1, 5)],
        "reviews": [f"리뷰 {n} " + "가" * 400 for n in range(1, 7)],
        "owner_replies": [],
    }

    _run(writer, _FakeClient(NO_FLAGS), materials)

    offered = [material["id"] for material in writer.calls[1]["payload"]["materials"]]
    opened = {material["id"]: material["body"] for material in writer.calls[2]["payload"]["opened_materials"]}
    assert offered == ["issue-1", "issue-2", "issue-3", "reviews"]
    assert "kim@example.com" not in opened["issue-1"] and token not in opened["issue-1"]
    notes = opened["reviews"].split("\n")
    assert len(notes) == 5
    assert all(len(note) <= len("- ") + 300 for note in notes)


def test_empty_slot_is_not_rewritten_without_a_material() -> None:
    writer = _FakeClient(_draft(A=_field(ACTION)), _choose(("S", "REWRITE", None), ("T", "KEEP", None)))

    _, meta = _run(writer, _FakeClient(NO_FLAGS), {"issues": [], "reviews": [REVIEW_NOTE], "owner_replies": []})

    assert len(writer.calls) == 2
    assert meta.repair_rounds == 0


def test_reversed_slot_kept_by_the_choice_still_needs_review() -> None:
    quote = "세션을 캐시에 두고 만료 전에 늘리기"
    writer = _FakeClient(
        _draft(S=_field("세션을 캐시에 두고 있어 만료가 늦었다."), A=_field("세션 조회를 50% 줄였다.")),
        _choose(("S", "KEEP", None), ("A", "REWRITE", None)),
        _draft(A=_field(ACTION)),
    )
    reviewer = _FakeClient(
        StarReviewOutput(slots=[SlotReview(slot="S", is_reversed=True, source_quote=quote, reason="전후를 뒤집었다.")]),
        NO_FLAGS,
    )
    materials = {"issues": [], "reviews": [], "owner_replies": [OWNER_REPLY]}

    result, _ = _run(writer, reviewer, materials)

    assert (result.star["S"].status, result.star["S"].review_reason) == (
        "NEEDS_REVIEW", "원문과 뜻이 다를 수 있어 사용자 확인이 필요합니다."
    )
