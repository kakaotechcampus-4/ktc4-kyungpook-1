"""STAR 초안 검사기가 칸마다 무엇이 왜 걸렸는지 정해 둔 이유로 돌려주는지 검증한다."""

from __future__ import annotations

from schemas.analysis import DiffEvidence
from services.star_check import (
    NO_EVIDENCE_REASON,
    DraftProblem,
    DraftSlot,
    check_draft,
)

SHA_A = "a" * 40
SHA_B = "b" * 40


def _evidence(sha: str, message: str) -> DiffEvidence:
    return DiffEvidence(sha=sha, message=message, churn="+10/-2", summary="요약", summary_source="DIFF")


EVIDENCE = {
    SHA_A: _evidence(SHA_A, "feat: 목록 조회에 캐시 추가"),
    SHA_B: _evidence(SHA_B, "perf: 조회 시간 30% 단축"),
}


def test_sentence_without_commit_or_answer_is_no_evidence() -> None:
    checks = check_draft({"A": DraftSlot("캐시를 도입했다.", [])}, EVIDENCE, [], None)

    assert checks["A"].cited == []
    assert checks["A"].problems == [DraftProblem("A", "NO_EVIDENCE", "캐시를 도입했다.", None, NO_EVIDENCE_REASON)]


def test_unsourced_numbers_are_listed_with_the_reason() -> None:
    text = "조회 시간을 50% 줄이고 2배 빨라졌다."

    checks = check_draft({"R": DraftSlot(text, [SHA_A])}, EVIDENCE, [], None)

    assert checks["R"].problems == [DraftProblem(
        "R", "UNSOURCED_NUMBER", text, "2배, 50%", "근거에서 확인되지 않는 수치(2배, 50%)가 있어 사용자 확인이 필요합니다."
    )]


def test_number_found_in_cited_commit_answer_or_pull_request_body_is_not_a_problem() -> None:
    cited = check_draft({"R": DraftSlot("조회 시간을 30% 줄였다.", [SHA_B])}, EVIDENCE, [], None)
    answered = check_draft({"R": DraftSlot("배포를 3배 빠르게 했다.", [SHA_A])}, EVIDENCE, ["배포가 3배 빨라졌어요"], None)
    body = check_draft({"R": DraftSlot("응답을 200ms 줄였다.", [SHA_A])}, EVIDENCE, [], "응답이 200ms 줄었습니다")

    assert [cited["R"].problems, answered["R"].problems, body["R"].problems] == [[], [], []]


def test_unknown_sha_is_listed_and_other_evidence_keeps_the_sentence() -> None:
    checks = check_draft({"A": DraftSlot("캐시를 도입했다.", [SHA_A, "deadbeef00"])}, EVIDENCE, [], None)

    assert checks["A"].cited == [EVIDENCE[SHA_A]]
    assert checks["A"].problems == [DraftProblem(
        "A", "UNKNOWN_SHA", "캐시를 도입했다.", "deadbeef00", "입력에 없는 커밋 번호(deadbeef00)를 근거로 적어 그 번호는 뺐습니다."
    )]


def test_only_unknown_shas_without_answers_is_also_no_evidence() -> None:
    checks = check_draft({"A": DraftSlot("캐시를 도입했다.", ["deadbeef00"])}, EVIDENCE, [], None)

    assert [problem.code for problem in checks["A"].problems] == ["UNKNOWN_SHA", "NO_EVIDENCE"]


def test_answer_only_sentence_is_not_a_problem() -> None:
    checks = check_draft({"T": DraftSlot("팀이 정한 일정에 맞춰야 했다.", [])}, EVIDENCE, ["일정이 빠듯했어요"], None)

    assert (checks["T"].cited, checks["T"].problems) == ([], [])
